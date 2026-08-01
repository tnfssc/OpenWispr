# OpenWispr Keyboard

> **Modified FUTO Keyboard fork:** This non-commercial fork is modified by OpenWispr.
> Voice recordings are sent to the provider selected by the user instead of
> using FUTO's offline speech model. Keyboard typing, suggestions, and swipe
> processing remain local. This project is not an official FUTO release.

OpenWispr Keyboard is based on [FUTO Keyboard](https://github.com/futo-org/android-keyboard), itself a fork of [LatinIME, the Android Open-Source Keyboard](https://android.googlesource.com/platform/packages/inputmethods/LatinIME). Its OpenWispr voice-input code sends recordings only to the provider selected by the user.

The canonical source, releases, issue tracking, and contribution point for this modified fork is the [OpenWispr monorepo](https://github.com/tnfssc/OpenWispr).

The code is licensed under the [FUTO Source First License 1.1](LICENSE.md).

## Issue tracking and contributing

Report issues and open pull requests in the [OpenWispr monorepo](https://github.com/tnfssc/OpenWispr). Issues specific to OpenWispr voice input, branding, releases, or this fork belong here.

For a problem in unmodified upstream FUTO Keyboard behavior, consult [FUTO Keyboard's upstream repository](https://github.com/futo-org/android-keyboard). This repository is not an official FUTO support channel.

Contributions remain subject to the [FUTO Source First License 1.1-kb](LICENSE.md) and all applicable upstream notices.

## Layouts

The keyboard retains FUTO's layout submodule. See [the upstream layouts repository](https://github.com/futo-org/futo-keyboard-layouts) for its contribution policy.

## Building

When cloning the repository, you must perform a recursive clone to fetch all dependencies:
```
git clone --recurse-submodules https://github.com/tnfssc/OpenWispr.git
cd OpenWispr/apps/android-keyboard
```

If you forgot to specify recursive clone, use this to fetch submodules:
```
git submodule update --init --recursive
```

You can then open the project in Android Studio and build it that way, or use gradle commands:
```
./gradlew assembleUnstableDebug
./gradlew assembleStableRelease
```

## APK signing

For official FUTO Keyboard versions, you can verify the APK's signing key fingerprint for integrity.

```
Signing key fingerprint for all versions except Google Play:

MD5: 3A:BB:71:C6:BB:E4:92:27:B1:E3:5D:81:01:48:6A:B0
SHA1: 5D:15:B3:6E:C9:6A:96:28:41:09:DD:62:93:0D:9C:39:9F:5F:06:43
SHA-256: 74:3F:AD:58:64:AB:C4:26:50:0B:2D:C2:C4:7C:8A:D3:24:CB:CD:16:03:3F:80:16:99:48:41:35:63:74:F9:95

```
