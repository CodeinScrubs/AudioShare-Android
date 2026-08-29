# AudioShare USB Companion

Native Android receiver for the customized AudioShare Windows host.

The companion is intentionally not a network application. The Windows host
launches it over an already-authorized USB ADB connection, creates an ADB
forward from a randomized Windows loopback port to a randomized Android
abstract Unix-domain socket, and streams framed PCM to a foreground media
playback service.

Current phase: hardware-independent companion/transport POC.

## Security and privacy contract

- No `INTERNET`, microphone, camera, location, contacts, Bluetooth, or storage
  permissions.
- PCM remains in memory and is never recorded, uploaded, or logged.
- Playback service is not exported.
- Each session uses a 256-bit nonce and a unique abstract socket name.
- Every protocol field and payload length is bounded.
- A launched service stops if the host does not connect or authenticate.

## Build

The project uses Android Gradle Plugin 9.1 and its built-in Kotlin support with
a pinned local Gradle 9.4.1 wrapper:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat clean testDebugUnitTest lintRelease assembleDebug assembleRelease
```

Physical USB, Android 14 foreground-launch, speaker routing, and screen-off
behavior still require the target Samsung device.

Release APKs require an external stable signing key; see
[`docs/SIGNING.md`](docs/SIGNING.md). This project is licensed under
LGPL-3.0-or-later; see `LICENSE`.
