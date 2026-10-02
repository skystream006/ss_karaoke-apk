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

> **Note:** Version numbers are derived from Git history, so the build needs a full
> (non-shallow) clone. If you cloned with `--depth`, run `git fetch --unshallow` first.

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
app/build/outputs/apk/release/app-release.apk
```

## Signing and in-app updates

Android only installs an update when it is signed with the same certificate as the
installed app. To keep every build updatable, the repository includes a shared debug
keystore at `app/debug.keystore` (store/key password `android`, alias `androiddebugkey`).
Debug builds, CI artifacts, and manual releases all use this key unless a distribution
key is supplied, so they can update one another.

> **Note:** This key is public. Anyone can sign an APK with it, so treat it as suitable
> for personal/sideloaded distribution only.

To sign releases with a private distribution key instead, set these environment
variables (or repository secrets for the release workflow):

| Workflow secret | Gradle environment variable |
| --- | --- |
| `APK_SIGNING_KEYSTORE_BASE64` (base64 keystore) | `APK_SIGNING_STORE_FILE` (path to keystore) |
| `APK_SIGNING_STORE_PASSWORD` | `APK_SIGNING_STORE_PASSWORD` |
| `APK_SIGNING_KEY_ALIAS` | `APK_SIGNING_KEY_ALIAS` |
| `APK_SIGNING_KEY_PASSWORD` | `APK_SIGNING_KEY_PASSWORD` |

Switching keys changes the signing certificate, so existing installs must be uninstalled once.

## Versioning

Versions are computed at build time from the number of first-parent commits on the
current branch (`git rev-list --first-parent --count HEAD`):

- `versionName` = `1.1.<count>`
- `versionCode` = `<count> + 1`

Every pull request merged into `main` adds a first-parent commit, so the version bumps
automatically without any commit being pushed back to the repository. After a merge, the
**PR Release Reminder** workflow comments on the pull request with a link to run the
manual release.

## Publishing a release

Run **Actions → Manual Android Release → Run workflow**. It builds and verifies a signed
release APK from `main` and publishes it as the latest GitHub release tagged `v<versionName>`
with a single `ss-karaoke-v<versionName>.apk` asset. The app checks this release at startup
and from **Settings → Check for updates** (the semi-transparent button in the bottom-right
corner, or the remote's Menu key), then downloads and installs it.

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
