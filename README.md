# Sill for Android

An Android client for [Sill](https://github.com/Saffsanity/sill): any window from your Mac,
or the whole desktop, live on your Android phone, tablet or foldable, with touch, a trackpad,
a keyboard, a mouse or the S Pen.

Sill is Noah Saffer's open-source app for showing a Mac window on an iPhone or iPad. The Android
client here was written by **[Omar Marei](https://github.com/omarei-omoto)** and proposed to the
Sill project as [Saffsanity/sill#89](https://github.com/Saffsanity/sill/pull/89) (idea in
[#90](https://github.com/Saffsanity/sill/issues/90)). Sill is Swift and Apple frameworks only, so
upstream has not adopted a Kotlin app. This repository exists so the client can be built, released
and installed in the meantime, with the original code and its git history intact. If upstream
adopts an Android client, use that.

It speaks Sill's wire protocol unchanged, so **Sill for Mac needs no change**: any Sill for Mac
0.4.0 or later serves it. There is no account and no server in between.

[![Release](https://github.com/aminehmida/sill-android/actions/workflows/release.yml/badge.svg)](https://github.com/aminehmida/sill-android/actions/workflows/release.yml)
[![CI](https://github.com/aminehmida/sill-android/actions/workflows/ci.yml/badge.svg)](https://github.com/aminehmida/sill-android/actions/workflows/ci.yml)

## Install

### With Obtainium (updates included)

[Obtainium](https://github.com/ImranR98/Obtainium) installs apps straight from their GitHub
releases and keeps them up to date.

[<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" alt="Get it on Obtainium" height="60">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/aminehmida/sill-android)

Or, in Obtainium, tap **Add App** and paste:

```
https://github.com/aminehmida/sill-android
```

### APK by hand

Download `sill-android-<version>.apk` from the
[latest release](https://github.com/aminehmida/sill-android/releases/latest) and open it on the
device. Every release is built by GitHub Actions from its tag and signed with this repository's
release key, so later releases install over earlier ones.

## Requirements

- A Mac with Apple silicon running [Sill for Mac](https://getsill.app/download) 0.4.0 or later
  (`brew install --cask saffsanity/sill/sill`).
- Android 10 or later. The device's HEVC decoder sets the largest picture it can show: many phones
  stop below a Retina capture, and the app then asks the Mac for Standard resolution by itself.
- Both on the same Wi-Fi, or your own VPN such as Tailscale with Sill for Mac's **Remote Access**
  on.

## Using it

1. Open Sill on the Mac and on the device.
2. **At home:** the Mac is listed under **On this network**. Tap it, and the Mac shows a 12-digit
   code: scan it or type it. From then on the Mac's row reads **Wi-Fi** and a tap connects.
3. **Away, over Tailscale:** turn on Remote Access in Sill for Mac's settings, choose
   **Pair iPhone or iPad…** in its menu, and in the app tap **Add a Mac** and scan the code or type
   the address and code. See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for the details.

Compared with the iPhone and iPad app there is no USB-cable pairing, no Direct Wireless Connection
and no first-run tour; `docs/DEVELOPMENT.md` lists the rest, and maps each Kotlin file to its Swift
counterpart.

## Building

A JDK 17 or later and the Android SDK with platform 37:

```
./gradlew assembleDebug           # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`./gradlew assembleRelease` signs with the debug key unless `SILL_KEYSTORE_FILE`,
`SILL_KEYSTORE_PASSWORD`, `SILL_KEY_ALIAS` and `SILL_KEY_PASSWORD` are set, which is what the
[release workflow](.github/workflows/release.yml) does from the repository's secrets. Pushing a tag
`vX.Y.Z` that matches `versionName` in `app/build.gradle.kts` publishes a release.

## Credits and license

- **Omar Marei** ([@omarei-omoto](https://github.com/omarei-omoto)) wrote the Android client.
  The code here is their work, from [Saffsanity/sill#89](https://github.com/Saffsanity/sill/pull/89).
- **Noah Saffer** ([@Saffsanity](https://github.com/Saffsanity)) made Sill, the protocol and
  Sill for Mac: [getsill.app](https://getsill.app).
- This repository is maintained by [Amine Hmida](https://github.com/aminehmida): the packaging,
  CI and releases. Bugs in the app itself are best reported here, since upstream does not
  maintain it.

Licensed under the [Apache License 2.0](LICENSE), as Sill is. See [NOTICE](NOTICE).
