# AudioShare USB Companion

Native Android receiver for the customized AudioShare Windows host.

The companion is intentionally not a network application. The Windows host
launches it over an already-authorized USB ADB connection, creates an ADB
forward from a randomized Windows loopback port to a randomized Android
abstract Unix-domain socket, and streams framed PCM to a foreground media
playback service.

Current phase: version-code 3 release candidate pending physical-phone audible
output, cable reconnect, latency, and long-run acceptance.

## Security and privacy contract

- No `INTERNET`, microphone, camera, location, contacts, Bluetooth, or storage
  permissions.
- PCM remains in memory and is never recorded, uploaded, or logged.
- Playback service is not exported.
- The exported ADB launch bridge requires Android's shell-only `DUMP`
  permission, so ordinary installed apps cannot start receiver sessions.
- On Android 13 and newer, opening the companion once requests notification
  permission so the foreground Disconnect action remains visible. The USB ADB
  bridge never blocks first connection with a permission dialog. If permission
  is declined, playback still works and can be stopped from Windows or
  Android's foreground-service Task Manager.
- Playback verifies the actual Android audio route after writing starts and
  fails visibly if the OS routes PC audio anywhere except the built-in speaker.
- Playback requests media audio focus. Transient focus loss flushes queued PCM
  and resumes only at the live edge; ducking lowers the track volume; permanent
  loss is reported to Windows as a playback error.
- Disconnect actively stops any in-flight `AudioTrack` write before joining the
  playback worker, so teardown cannot retain the session wake lock behind a
  stalled route.
- A partial wake lock is held only while a USB session is waiting or streaming,
  then released, so playback can continue reliably with the screen off.
- Each session uses a 256-bit nonce and a unique abstract socket name.
- Every protocol field and payload length is bounded. The transient playback
  queue keeps only the newest 40 ms of complete PCM chunks (or one indivisible
  chunk), with a separate 32-chunk hard memory bound, so a route or writer stall
  drops stale audio instead of becoming permanent playback lag.
- A launched service stops if the host does not connect or authenticate.

## Build

The project uses Android Gradle Plugin 9.3.2 and its built-in Kotlin support with
a checksum-pinned Gradle 9.7.1 wrapper. JDK 17 or newer is supported; CI uses
the AGP baseline of JDK 17:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat clean testDebugUnitTest lintDebug assembleDebug `
  --no-daemon --max-workers=1
```

`assembleRelease` and `lintRelease` require the external signing variables in
[`docs/SIGNING.md`](docs/SIGNING.md); an unsigned artifact is never produced by
the release task. The receiver uses a 40 ms target buffer without going below
Android's reported minimum and, on API 31+, a 20 ms playback start threshold.
The exact capacity, effective buffer, threshold, underruns, route, focus, media
volume, and queue high-water mark are returned in STATS diagnostics.

The real app and ADB-forwarded protocol can be exercised on an authorized device
or emulator (the command sends a deliberately quiet test tone):

```powershell
python .\tools\device_protocol_smoke.py `
  --adb "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" `
  --serial '<adb-serial>' --duration 3
```

For a strict 60-second screen-off continuity run, use
`--duration 60 --screen-off-seconds 60`. The harness records the initial display state, turns
the display off and waits for that power transition before it launches the
receiver, verifies that the display is still off with the session wake lock
present, then restores the original display state during cleanup. Separating
the transition from the measured stream keeps the zero-drop assertion about
steady screen-off playback rather than about emulator power-transition timing.

An Android 16 emulator can complete cold install, speaker/playback-head
readiness, READY, STATS, heartbeat, enforced built-in-speaker routing,
wake-lock visibility, STOP, and exact forward/service cleanup. Headless virtual
audio is not a zero-drop timing oracle: a fresh 10-second run completed the
protocol with route/focus state valid but reported virtual-audio underruns and
dropped frames. A physical Samsung is still required to prove audible speaker
output, the actual USB cable, device-specific foreground policy, reconnect,
measured latency, zero-drop behavior, and endurance.
Locking an already-streaming cold emulator can briefly stall its virtual
`AudioTrack`; the bounded live-edge queue intentionally counts and discards stale
frames in that case instead of preserving permanent playback delay.

Release APKs require an external stable signing key; see
[`docs/SIGNING.md`](docs/SIGNING.md). This project is licensed under
LGPL-3.0-or-later; see `LICENSE`.
