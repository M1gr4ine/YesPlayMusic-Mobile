# Building

The Android client uses a small Python build script so a full Gradle installation is not required. It needs Python 3, a JDK, and an installed Android SDK with platform tools and build-tools. The script does not download tools or contain a signing key.

## Debug APK

Set `ANDROID_SDK_ROOT` (or `ANDROID_HOME`) and `JAVA_HOME`, then run:

```sh
python3 build-apk.py --debug
```

The script selects the newest installed Android platform and build-tools version. It creates a standard local debug keystore at the ignored `build/debug.keystore` path and writes `dist/YesPlayMusic-Mobile-debug.apk`.

You can point to specific SDK components when they are installed in custom locations:

```sh
python3 build-apk.py --debug --android-jar /path/to/android.jar --build-tools /path/to/build-tools
```

On Windows, use `py -3 build-apk.py` in place of `python3` if needed.

## Release APK

Release signing requires a keystore and alias that you control. Passwords are read from environment variables and passed to `apksigner` by variable reference; they are not command-line options:

```sh
export YPM_KS_PASS='keystore password'
export YPM_KEY_PASS='key password'
python3 build-apk.py --keystore /secure/path/release.jks --alias upload
```

PowerShell equivalent:

```powershell
$env:YPM_KS_PASS = 'keystore password'
$env:YPM_KEY_PASS = 'key password'
py -3 .\build-apk.py --keystore C:\secure\release.jks --alias upload
```

The release APK is written to `dist/YesPlayMusic-Mobile.apk`. The script does not automatically select a debug key for release builds; use your own release signing key. Keep release keystores and passwords outside this repository.

The script recreates only its checked `build/work` temporary directory. It leaves existing output APKs and keystores in place, replacing an APK only when signing to that same output filename.
