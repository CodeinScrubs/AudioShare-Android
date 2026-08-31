# Physical-device test results

## 2026-08-31: Samsung Galaxy A52s initial audible run

Evidence label: **HARDWARE TESTED** for the items explicitly listed below.

- Phone: Samsung Galaxy A52s (`SM-A528B`), Android 14 / API 34.
- Host: Windows 10 Pro for Workstations 22H2, build 19045.7663.
- Connection: authorized physical USB ADB device using a data cable.
- Installation: the Windows host detected the missing companion and completed
  the explicit one-time install action.
- End-to-end result: ordinary Windows audio was audibly reproduced by the phone
  speaker and was reported by the tester as working very well.
- Windows capture mode: `globalSystem`.
- Host queue at the captured diagnostic snapshot: 0 frames / 0.0 ms.
- Host queue high-water mark: 480 frames / 10.0 ms.
- Host dropped chunks: 0.
- USB heartbeat round-trip time: 10 ms.
- Android dropped frames: 0.

The diagnostic screenshot and tester report prove the initial real-phone
Windows capture, USB transport, companion installation, and audible playback
path on this device. They do not by themselves prove a latency percentile,
screen-off continuity, repeated reconnect recovery, memory stability, or
long-run endurance.

## Remaining promotion gates

- Ten-minute continuous playback with the phone screen off and final diagnostics.
- Wi-Fi-disabled playback using multiple simultaneous Windows applications.
- Automatic recovery after USB unplug/replug and repeated reconnect cycling.
- At least 20 visible/audio transient measurements with median and p95 latency.
- Two-hour stream with start/end CPU, memory, latency, underrun, and drop counts.

Until those gates are recorded, releases must be marked as prereleases rather
than stable production releases.
