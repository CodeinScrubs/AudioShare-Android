#!/usr/bin/env python3
"""Real-device ADB-forward, protocol, playback, and cleanup smoke test."""

from __future__ import annotations

import argparse
import math
import os
import secrets
import select
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path

MAGIC = 0x41535542
VERSION = 1
HELLO = 1
READY = 2
PCM = 4
STATS = 5
PING = 6
PONG = 7
ERROR = 8
STOP = 9
HEADER = struct.Struct(">IHHII")


class CompanionStartupError(RuntimeError):
    """Fatal receiver error that must not be hidden by transport retries."""


def adb(args: argparse.Namespace, *command: str, timeout: float = 15.0) -> str:
    completed = subprocess.run(
        [args.adb, "-s", args.serial, *command],
        check=False,
        capture_output=True,
        text=True,
        timeout=timeout,
        env={
            **os.environ,
            "ADB_MDNS": "0",
            "ADB_MDNS_AUTO_CONNECT": "0",
            "ADB_EMU": "0",
        },
    )
    if completed.returncode != 0:
        details = (completed.stderr or completed.stdout).strip()
        raise RuntimeError(f"adb {' '.join(command)} failed: {details}")
    return completed.stdout.strip()


def recv_exact(stream: socket.socket, length: int) -> bytes:
    result = bytearray()
    while len(result) < length:
        chunk = stream.recv(length - len(result))
        if not chunk:
            raise RuntimeError("companion closed the protocol stream")
        result.extend(chunk)
    return bytes(result)


def send_frame(stream: socket.socket, frame_type: int, sequence: int, payload: bytes = b"") -> None:
    stream.sendall(HEADER.pack(MAGIC, VERSION, frame_type, len(payload), sequence) + payload)


def read_frame(stream: socket.socket) -> tuple[int, int, bytes]:
    magic, version, frame_type, length, sequence = HEADER.unpack(recv_exact(stream, HEADER.size))
    if magic != MAGIC or version != VERSION or length > 64 * 1024:
        raise RuntimeError("invalid companion protocol header")
    return frame_type, sequence, recv_exact(stream, length)


def fail_on_async_error(stream: socket.socket) -> None:
    readable, _, _ = select.select([stream], [], [], 0)
    if not readable:
        return
    frame_type, _, payload = read_frame(stream)
    if frame_type == ERROR:
        raise RuntimeError(f"companion playback error: {payload.decode('utf-8', 'replace')}")
    raise RuntimeError(f"unexpected asynchronous frame type {frame_type}")


def pcm_chunk(phase: int, frames: int = 960) -> tuple[bytes, int]:
    samples = bytearray(frames * 4)
    # A deliberately quiet 440 Hz stereo tone: enough to prove non-zero
    # AudioTrack writes without surprising someone holding the test phone.
    for frame in range(frames):
        value = int(math.sin((phase + frame) * 2.0 * math.pi * 440.0 / 48_000.0) * 800)
        struct.pack_into("<hh", samples, frame * 4, value, value)
    return bytes(samples), phase + frames


def connect_and_handshake(port: int, token: bytes, timeout: float = 10.0) -> tuple[socket.socket, bytes]:
    deadline = time.monotonic() + timeout
    last_error: Exception | None = None
    hello = token + struct.pack(">IBBH", 48_000, 2, 16, 0)
    while time.monotonic() < deadline:
        stream: socket.socket | None = None
        try:
            stream = socket.create_connection(("127.0.0.1", port), timeout=2.0)
            stream.settimeout(10.0)
            send_frame(stream, HELLO, 1, hello)
            frame_type, ready_sequence, payload = read_frame(stream)
            if frame_type == ERROR:
                raise CompanionStartupError(
                    f"companion startup error: {payload.decode('utf-8', 'replace')}"
                )
            if frame_type != READY or ready_sequence != 1 or len(payload) != 16:
                raise RuntimeError("companion did not return a valid READY frame")
            return stream, payload
        except CompanionStartupError:
            if stream is not None:
                stream.close()
            raise
        except (OSError, RuntimeError) as error:
            last_error = error
            if stream is not None:
                stream.close()
            time.sleep(0.1)
    raise RuntimeError(f"companion handshake did not become ready: {last_error}")


def wait_for_service_stop(args: argparse.Namespace, timeout: float = 3.0) -> None:
    component = f"{args.package}/com.audioshare.usbcompanion.PlaybackService"
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        services = adb(args, "shell", "dumpsys", "activity", "services", args.package)
        if component not in services:
            return
        time.sleep(0.1)
    raise RuntimeError("companion playback service did not stop after STOP")


def remove_and_verify_forward(args: argparse.Namespace, port: int) -> None:
    adb(args, "forward", "--remove", f"tcp:{port}")
    for line in adb(args, "forward", "--list").splitlines():
        fields = line.split()
        if len(fields) >= 2 and fields[0] == args.serial and fields[1] == f"tcp:{port}":
            raise RuntimeError(f"owned ADB forward tcp:{port} remained after cleanup")


def run(args: argparse.Namespace) -> None:
    token = secrets.token_bytes(32)
    socket_name = f"as_1_smoke_{secrets.token_hex(6)}"
    component = f"{args.package}/com.audioshare.usbcompanion.BridgeActivity"
    port: int | None = None
    stream: socket.socket | None = None
    screen_was_awake = False
    sequence = 1
    try:
        port_text = adb(
            args,
            "forward",
            "--no-rebind",
            "tcp:0",
            f"localabstract:{socket_name}",
        )
        port = int(port_text.splitlines()[-1])
        launch = adb(
            args,
            "shell",
            "am",
            "start",
            "-W",
            "-n",
            component,
            "-a",
            "com.audioshare.usbcompanion.LAUNCH_SESSION",
            "--es",
            "socket_name",
            socket_name,
            "--es",
            "token_hex",
            token.hex(),
            "--el",
            "generation",
            "1",
        )
        if "Error:" in launch or "Exception" in launch:
            raise RuntimeError(f"bridge launch failed: {launch}")

        stream, payload = connect_and_handshake(port, token)
        sample_rate, channels, bits, buffer_frames = struct.unpack(">IIII", payload)
        if (sample_rate, channels, bits) != (48_000, 2, 16) or buffer_frames <= 0:
            raise RuntimeError("companion READY format does not match PCM contract")

        power_state = adb(args, "shell", "dumpsys", "power")
        screen_was_awake = "mWakefulness=Awake" in power_state
        if args.screen_off_seconds > 0 and screen_was_awake:
            adb(args, "shell", "input", "keyevent", "26")

        phase = 0
        total_frames = 0
        started = time.monotonic()
        duration = max(args.duration, args.screen_off_seconds)
        next_send = started
        while time.monotonic() - started < duration:
            sequence += 1
            chunk, phase = pcm_chunk(phase)
            send_frame(stream, PCM, sequence, chunk)
            total_frames += len(chunk) // 4
            fail_on_async_error(stream)
            next_send += len(chunk) / 4 / 48_000.0
            delay = next_send - time.monotonic()
            if delay > 0:
                time.sleep(delay)

        sequence += 1
        send_frame(stream, STATS, sequence)
        frame_type, stats_sequence, payload = read_frame(stream)
        if frame_type == ERROR:
            raise RuntimeError(f"companion playback error: {payload.decode('utf-8', 'replace')}")
        if frame_type != STATS or stats_sequence != sequence or len(payload) != 24:
            raise RuntimeError("companion did not return valid STATS")
        received, dropped, queue_depth, reported_buffer = struct.unpack(">QQII", payload)
        if received != total_frames or reported_buffer != buffer_frames:
            raise RuntimeError("companion playback counters are inconsistent")
        if dropped != 0:
            raise RuntimeError(f"companion dropped {dropped} PCM frames")

        sequence += 1
        send_frame(stream, PING, sequence)
        frame_type, pong_sequence, payload = read_frame(stream)
        if frame_type != PONG or pong_sequence != sequence or payload:
            raise RuntimeError("companion heartbeat response is invalid")

        wake_dump = adb(args, "shell", "dumpsys", "power")
        wake_lock_seen = "AudioShare:UsbPlayback" in wake_dump
        if not wake_lock_seen:
            raise RuntimeError("session-scoped playback wake lock was not visible")
        send_frame(stream, STOP, sequence + 1)
        stream.close()
        stream = None
        wait_for_service_stop(args)
        remove_and_verify_forward(args, port)
        port = None
        print(
            "DEVICE_PROTOCOL_SMOKE_OK "
            f"serial={args.serial} frames={received} dropped={dropped} "
            f"queue={queue_depth} buffer={buffer_frames} wake_lock={wake_lock_seen} "
            "service_stopped=True forward_removed=True"
        )
    finally:
        if stream is not None:
            stream.close()
        if args.screen_off_seconds > 0 and screen_was_awake:
            try:
                adb(args, "shell", "input", "keyevent", "26")
            except Exception as error:  # best-effort state restoration
                print(f"warning: could not restore screen state: {error}", file=sys.stderr)
        if port is not None:
            try:
                adb(args, "forward", "--remove", f"tcp:{port}")
            except Exception as error:  # exact owned-forward cleanup only
                print(f"warning: could not remove tcp:{port}: {error}", file=sys.stderr)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", type=str, default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", default="com.audioshare.usbcompanion.debug")
    parser.add_argument("--duration", type=float, default=3.0)
    parser.add_argument("--screen-off-seconds", type=float, default=0.0)
    args = parser.parse_args()
    args.adb = str(Path(args.adb).resolve()) if Path(args.adb).exists() else args.adb
    try:
        run(args)
        return 0
    except Exception as error:
        print(f"DEVICE_PROTOCOL_SMOKE_FAILED: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
