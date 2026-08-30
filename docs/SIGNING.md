# Android Release Signing

The repository never contains a private release key, key password, or production
keystore. A stable signing identity must be created and backed up by the project
owner outside Git before distributing an installable production APK.

Supply all four variables to Gradle:

```powershell
$env:AUDIOSHARE_KEYSTORE_PATH = 'C:\secure\audioshare-release.jks'
$env:AUDIOSHARE_KEYSTORE_PASSWORD = '<secret>'
$env:AUDIOSHARE_KEY_ALIAS = 'audioshare-companion'
$env:AUDIOSHARE_KEY_PASSWORD = '<secret>'
```

Supplying only some variables is a configuration error and fails immediately;
the build never silently falls back to unsigned output from a partial signing
configuration. The keystore path must identify an existing file.

Then build with the pinned wrapper:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat clean testDebugUnitTest lintRelease assembleRelease `
  --no-daemon --max-workers=1
```

When all variables are present, Gradle uses that external identity. When they
are absent, `assembleRelease` intentionally produces an unsigned POC artifact;
it must not be presented as the distributable APK.

After signing, record the certificate and artifact fingerprints without exposing
the private key:

```powershell
keytool -list -v -keystore $env:AUDIOSHARE_KEYSTORE_PATH -alias $env:AUDIOSHARE_KEY_ALIAS
Get-FileHash .\app\build\outputs\apk\release\app-release.apk -Algorithm SHA256
```

Before supplying the result to the Windows release build, run the host
repository's `tools/verify_companion_apk.ps1`. Packaging requires a valid APK
signature, application ID `com.audioshare.usbcompanion`, and version code 2 or
newer; a debug package or unsigned release is rejected.
At runtime the Windows host also compares the installed base APK SHA-256 with
this exact bundled artifact, so rebuilding or replacing the APK requires a
matching Windows package.

Keep the same release identity for every version so Android permits compatible
`adb install -r` updates. Losing the key requires uninstalling the old app and
performing a new one-time installation.
