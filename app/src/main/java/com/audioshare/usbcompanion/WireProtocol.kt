package com.audioshare.usbcompanion

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

object WireProtocol {
    const val MAGIC = 0x41535542
    const val VERSION = 1
    const val MAX_CONTROL_PAYLOAD = 64 * 1024
    const val MAX_PCM_PAYLOAD = 8 * 1024
    const val HELLO_PAYLOAD_SIZE = 40
    const val LEGACY_STATS_PAYLOAD_SIZE = 24
    const val ENHANCED_STATS_PAYLOAD_SIZE = 60
    const val PLAYBACK_PROGRESS_STATS_PAYLOAD_SIZE = 92

    enum class Type(val id: Int) {
        HELLO(1),
        READY(2),
        START_STREAM(3),
        PCM(4),
        STATS(5),
        PING(6),
        PONG(7),
        ERROR(8),
        STOP(9);

        companion object {
            fun fromId(id: Int): Type = entries.firstOrNull { it.id == id }
                ?: throw ProtocolException("Unknown message type: $id")
        }
    }

    data class Frame(val type: Type, val sequence: Int, val payload: ByteArray)

    data class Hello(
        val token: ByteArray,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
    )

    data class PlaybackStats(
        val receivedFrames: Long,
        val droppedFrames: Long,
        val queueDepth: Int,
        val bufferFrames: Int,
        val queueFrames: Int,
        val bufferCapacityFrames: Int,
        val startThresholdFrames: Int,
        val underrunCount: Int,
        val routedDeviceType: Int,
        val focusState: Int,
        val mediaVolume: Int,
        val mediaVolumeMax: Int,
        val queueHighWaterFrames: Int,
        val writtenFrames: Long,
        val playbackHeadFrames: Long,
        val lastWriteProgressAgeMillis: Int,
        val lastPlaybackAdvanceAgeMillis: Int,
        val playState: Int,
        val performanceMode: Int,
    )

    fun encodePlaybackStats(stats: PlaybackStats): ByteArray =
        ByteBuffer.allocate(PLAYBACK_PROGRESS_STATS_PAYLOAD_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
            .putLong(stats.receivedFrames)
            .putLong(stats.droppedFrames)
            .putInt(stats.queueDepth)
            .putInt(stats.bufferFrames)
            .putInt(stats.queueFrames)
            .putInt(stats.bufferCapacityFrames)
            .putInt(stats.startThresholdFrames)
            .putInt(stats.underrunCount)
            .putInt(stats.routedDeviceType)
            .putInt(stats.focusState)
            .putInt(stats.mediaVolume)
            .putInt(stats.mediaVolumeMax)
            .putInt(stats.queueHighWaterFrames)
            .putLong(stats.writtenFrames)
            .putLong(stats.playbackHeadFrames)
            .putInt(stats.lastWriteProgressAgeMillis)
            .putInt(stats.lastPlaybackAdvanceAgeMillis)
            .putInt(stats.playState)
            .putInt(stats.performanceMode)
            .array()

    fun requireStrictlyIncreasingSequence(previous: Int, next: Int) {
        if (next <= previous) {
            throw ProtocolException("Sequence did not increase")
        }
    }

    fun readFrame(input: InputStream): Frame? {
        val stream = DataInputStream(input)
        val magic = try {
            stream.readInt()
        } catch (_: EOFException) {
            return null
        }
        if (magic != MAGIC) throw ProtocolException("Invalid protocol magic")

        val version = stream.readUnsignedShort()
        if (version != VERSION) throw ProtocolException("Unsupported protocol version: $version")
        val type = Type.fromId(stream.readUnsignedShort())
        val payloadLength = stream.readInt()
        val sequence = stream.readInt()
        val maximum = if (type == Type.PCM) MAX_PCM_PAYLOAD else MAX_CONTROL_PAYLOAD
        if (payloadLength < 0 || payloadLength > maximum) {
            throw ProtocolException("Payload length $payloadLength exceeds $maximum")
        }
        val payload = ByteArray(payloadLength)
        stream.readFully(payload)
        return Frame(type, sequence, payload)
    }

    fun writeFrame(output: OutputStream, frame: Frame) {
        val maximum = if (frame.type == Type.PCM) MAX_PCM_PAYLOAD else MAX_CONTROL_PAYLOAD
        if (frame.payload.size > maximum) {
            throw ProtocolException("Payload length ${frame.payload.size} exceeds $maximum")
        }
        DataOutputStream(output).apply {
            writeInt(MAGIC)
            writeShort(VERSION)
            writeShort(frame.type.id)
            writeInt(frame.payload.size)
            writeInt(frame.sequence)
            write(frame.payload)
            flush()
        }
    }

    fun parseHello(frame: Frame, expectedToken: ByteArray): Hello {
        if (frame.type != Type.HELLO) throw ProtocolException("HELLO must be first")
        if (frame.payload.size != HELLO_PAYLOAD_SIZE) {
            throw ProtocolException("Invalid HELLO length")
        }
        val input = DataInputStream(frame.payload.inputStream())
        val token = ByteArray(32)
        input.readFully(token)
        if (!MessageDigest.isEqual(token, expectedToken)) {
            throw ProtocolException("Session authentication failed")
        }
        val sampleRate = input.readInt()
        val channels = input.readUnsignedByte()
        val bitsPerSample = input.readUnsignedByte()
        input.readUnsignedShort()
        if (sampleRate !in 8_000..192_000) throw ProtocolException("Invalid sample rate")
        if (channels !in 1..2) throw ProtocolException("Invalid channel count")
        if (bitsPerSample != 16) throw ProtocolException("Only PCM16 is supported")
        return Hello(token, sampleRate, channels, bitsPerSample)
    }
}

class ProtocolException(message: String) : Exception(message)
