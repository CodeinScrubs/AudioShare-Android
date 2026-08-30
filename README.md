# AudioShare USB Companion

Native Android receiver for the customized AudioShare Windows host.

The companion is intentionally not a network application. The Windows host
launches it over an already-authorized USB ADB connection, creates an ADB
forward from a randomized Windows loopback port to a randomized Android
abstract Unix-domain socket, and streams framed PCM to a foreground media
playback service.

Current phase: version-code 2 release candidate pending physical-phone audible
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
- Disconnect actively stops any in-flight `AudioTrack` write before joining the
  playback worker, so teardown cannot retain the session wake lock behind a
  stalled route.
- A partial wake lock is held only while a USB session is waiting or streaming,
  then released, so playback can continue reliably with the screen off.
- Each session uses a 256-bit nonce and a unique abstract socket name.
- Every protocol field and payload length is bounded; the transient playback
  queue is capped at 32 chunks (256 KiB maximum) with live-edge oldest-drop
  behavior if Android remains unable to play.
- A launched service stops if the host does not connect or authenticate.

## Build

The project uses Android Gradle Plugin 9.3.2 and its built-in Kotlin support with
a checksum-pinned Gradle 9.5.1 wrapper. JDK 17 or newer is supported; CI uses
the AGP baseline of JDK 17:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat clean testDebugUnitTest lintRelease assembleDebug assembleRelease `
  --no-daemon --max-workers=1
```

The real app and ADB-forwarded protocol can be exercised on an authorized device
or emulator (the command sends a deliberately quiet test tone):

```powershell
python .\tools\device_protocol_smoke.py `
  --adb "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" `
  --serial '<adb-serial>' --duration 3
```

An Android 16 emulator passed cold install, speaker/playback-head readiness,
READY, 2,880,000 exact PCM frames over 60 seconds with the display off, STATS,
heartbeat, enforced built-in-speaker routing, zero drops, wake-lock visibility,
STOP, screen restoration, and exact forward/service cleanup. A physical Samsung
is still required to prove audible speaker output, the actual USB cable,
device-specific foreground policy, reconnect, measured latency, and endurance.

Release APKs require an external stable signing key; see
[`docs/SIGNING.md`](docs/SIGNING.md). This project is licensed under
LGPL-3.0-or-later; see `LICENSE`.
