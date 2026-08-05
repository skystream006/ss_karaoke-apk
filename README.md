# ss_karaoke-apk
Android app to access Karaoke Party

## Prerequisites

- [Android Studio](https://developer.android.com/studio) (Hedgehog or later) **or** JDK 17+ with the Android SDK installed
- Android SDK with **API level 34** (compileSdk) installed
- Minimum supported device/emulator: **API level 21** (Android 5.0)

## Building the APK

### 1. Clone the repository

```bash
git clone https://github.com/skystream006/ss_karaoke-apk.git
cd ss_karaoke-apk
```

### 2. Build a debug APK

```bash
./gradlew assembleDebug
```

The output APK will be located at:

```
app/build/outputs/apk/debug/app-debug.apk
```

### 3. Build a release APK

```bash
./gradlew assembleRelease
```

The output APK will be located at:

```
app/build/outputs/apk/release/app-release-unsigned.apk
```

> **Note:** The release build is unsigned by default. To distribute the app you will need to [sign the APK](https://developer.android.com/studio/publish/app-signing) with a keystore before installing it on a device.

### Building with Android Studio

1. Open Android Studio and select **File → Open**, then choose the cloned project directory.
2. Wait for Gradle to sync.
3. Select **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
4. Once the build finishes, click **locate** in the notification to find the APK.

## Installing on a device

Enable **USB debugging** on your Android device, connect it via USB, then run:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If installation still fails because an existing app was signed with a different key, uninstall first and then reinstall:

```bash
adb uninstall com.sskaraoke.app
adb install app/build/outputs/apk/debug/app-debug.apk
```
