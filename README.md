# Noki

VPN client for Android.

[Website](https://noki.ykino.tech) · [Support](https://t.me/NokiSupport)

## Installation

Download the APK from the [Noki website](https://noki.ykino.tech) and install it.

## Building from source

Install Git, JDK 21 and Android Studio with Android SDK Platform 37 and Android SDK Build-Tools. Set `JAVA_HOME` to your JDK and add its `bin` directory to `PATH`.

```bash
git clone https://github.com/Noki-Vpn/Noki-Android.git
cd Noki-Android
```

Open the project in Android Studio, select JDK 21 for Gradle and let it locate your Android SDK. For a command-line setup, set `ANDROID_HOME` to the SDK directory instead.

Gradle downloads the dependencies automatically. The repository includes the VPN core, its source archive and the UI libraries.

### Firebase

Register an Android app with package name `com.noki.vpn` in your [Firebase project](https://firebase.google.com/docs/android/setup). Download its configuration file and save it as:

```text
app/google-services.json
```

This file is required for the build and is excluded from Git. Google sign-in and push notifications need Firebase settings that match the backend you use. For Google sign-in, set `noki.googleWebClientId` in your local Gradle properties to the Web client ID.

### Signing key

Create a directory named `sha-256 key` at the project root. Generate your signing key:

```bash
keytool -genkeypair -keystore "sha-256 key/noki-release.jks" -alias noki -keyalg RSA -keysize 4096 -validity 10000
```

Create `sha-256 key/release-signing.key` with these settings, replacing `YOUR_PASSWORD` with the password you entered:

```properties
storeFile=sha-256 key/noki-release.jks
storePassword=YOUR_PASSWORD
keyAlias=noki
keyPassword=YOUR_PASSWORD
```

Keep the same key for future updates of your build. APKs signed with your own key cannot update the official Noki app. See [Android app signing](https://developer.android.com/studio/publish/app-signing).

### Build the APK

Windows, from PowerShell:

```powershell
.\gradlew.bat :app:assembleRelease
```

Linux or macOS:

```bash
chmod +x gradlew
./gradlew :app:assembleRelease
```

Signed APKs are saved to:

```text
app/build-compose-2026/outputs/apk/release-named/off/
```

There are separate APKs for `arm64-v8a`, `armeabi-v7a` and `x86_64`, plus a universal APK.

### Rebuilding the VPN core

The bundled `app/libs/libbox.aar` is ready to use. To reproduce it from `app/libs/libbox.SOURCE.zip`, use Windows x64, PowerShell 7, Python 3, JDK 21 and Android NDK `29.0.14206865`. Set `JAVA_HOME` and `ANDROID_HOME` for those tools. The exact tool hashes are recorded in `build-inputs.json` inside the source archive.

```powershell
pwsh -NoProfile -File scripts/build-libbox.ps1 -OutputDirectory build/native-core
Copy-Item build/native-core/libbox.aar app/libs/libbox.aar
```

The script downloads its pinned Go toolchain and verifies the source, tools and resulting AAR. Use a new output directory for each run.

## License

GPL-3.0-or-later. See `LICENSE` and `COPYING`. Third-party licenses are included in `app/src/main/assets/licenses/` and `third_party/`.
