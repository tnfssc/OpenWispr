# OpenWispr Keyboard

> **Modified FUTO Keyboard fork:** This non-commercial fork is modified by OpenWispr.
> Voice recordings are sent to the provider selected by the user instead of
> using FUTO's offline speech model. Keyboard typing, suggestions, and swipe
> processing remain local. This project is not an official FUTO release.

OpenWispr Keyboard is based on [FUTO Keyboard](https://github.com/futo-org/android-keyboard), itself a fork of [LatinIME, the Android Open-Source Keyboard](https://android.googlesource.com/platform/packages/inputmethods/LatinIME). Its OpenWispr voice-input code sends recordings only to the provider selected by the user.

The canonical source, releases, issue tracking, and contribution point for this modified fork is the [OpenWispr monorepo](https://github.com/tnfssc/OpenWispr).

The code is licensed under the [FUTO Source First License 1.1](LICENSE.md).

## Install

Download `android-keyboard-stable-release.apk` from the latest [OpenWispr Keyboard release](https://github.com/tnfssc/OpenWispr/releases?q=%22OpenWispr+Keyboard%22&expanded=true).

To get updates automatically, use [Obtainium](https://github.com/ImranR98/Obtainium): open [Add OpenWispr to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%20%22app.openwispr.keyboard%22%2C%20%22url%22%3A%20%22https%3A%2F%2Fgithub.com%2Ftnfssc%2FOpenWispr%22%2C%20%22author%22%3A%20%22tnfssc%22%2C%20%22name%22%3A%20%22OpenWispr%22%2C%20%22preferredApkIndex%22%3A%200%2C%20%22additionalSettings%22%3A%20%22%7B%5C%22includePrereleases%5C%22%3A%20false%2C%20%5C%22fallbackToOlderReleases%5C%22%3A%20true%2C%20%5C%22filterReleaseTitlesByRegEx%5C%22%3A%20%5C%22%5EOpenWispr%20Keyboard%20%5C%22%2C%20%5C%22versionExtractionRegEx%5C%22%3A%20%5C%22%5C%5C%5C%5Cd%2B%5C%5C%5C%5C.%5C%5C%5C%5Cd%2B%5C%5C%5C%5C.%5C%5C%5C%5Cd%2B%5C%22%2C%20%5C%22matchGroupToUse%5C%22%3A%20%5C%22%5C%22%2C%20%5C%22versionDetection%5C%22%3A%20true%7D%22%2C%20%22overrideSource%22%3A%20%22GitHub%22%7D) on your phone.

To add it by hand instead, add `https://github.com/tnfssc/OpenWispr` and set:

- **Filter release titles by regular expression:** `^OpenWispr Keyboard ` (the repository also publishes GNOME and macOS releases)
- **Fallback to older releases:** on
- **Version extraction regular expression:** `\d+\.\d+\.\d+` (tags are `keyboard-vX.Y.Z`, the app reports `vX.Y.Z`)

## Provider onboarding

Open **OpenWispr voice input** in keyboard settings and choose **Groq** for the recommended fast free setup. Create a key in the [Groq Console](https://console.groq.com/keys), paste it, and keep the defaults. OpenRouter is also supported with keys from [OpenRouter](https://openrouter.ai/keys); model defaults are documented in the shared [provider guide](../../docs/providers.md).

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
