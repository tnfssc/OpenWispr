# OpenWispr

OpenWispr is a family of privacy-conscious dictation clients for macOS, GNOME, and Android. This repository is the canonical source, documentation, release, and issue-tracking home for every supported client.

| Client | Source | License | Status |
| --- | --- | --- | --- |
| macOS menu-bar app | [apps/macos](apps/macos) | MIT | Active |
| GNOME Shell extension and Linux companion | [apps/gnome](apps/gnome) | MIT | Active |
| Android keyboard | [apps/android-keyboard](apps/android-keyboard) | FUTO Source First License 1.1-kb | Active, upstream-derived |

## Start here

- [Architecture](docs/architecture.md)
- [Provider and privacy behavior](docs/providers.md)
- [Development](docs/development.md)
- [Releases](docs/releases.md)
- [FUTO Keyboard upstream and licensing](docs/upstreams/futo-keyboard.md)

Each client is versioned, built, and released independently. A platform directory contains the platform-specific setup and build instructions.

## First dictation and recovery

- **GNOME:** open **Set up dictation…** from the top-bar menu. The wizard covers your speech provider, microphone checks, shortcuts, and a safe test. Keep the extension and companion engine updated together.
- **macOS:** setup opens on a fresh launch. Reopen it with **Set up OpenWispr…** in the menu or Settings. It checks permissions, captures shortcuts, and lets you try dictation without changing your clipboard.
- **Android:** keep the keyboard's enable/select setup. Open **Speech provider** for guided voice setup, microphone permission, and a test that does not type into another app.

Failed recordings stay on the device for **Retry / Discard**. Successful audio is deleted; this is not a recording archive. Desktop retries copy their result. Android shows recovered text with explicit **Insert here** or **Copy** actions. Android keeps one failed recording and asks you to resolve it before recording again. Remote retry sends the retained audio to your currently selected provider.

## Repository policy

The macOS and GNOME code is licensed under the root [MIT License](LICENSE). The Android keyboard remains a modified FUTO Keyboard fork and is governed by its own [FUTO Source First License](apps/android-keyboard/LICENSE.md); the root MIT license does not apply to that directory or its submodules.

The GNOME and Android repositories formerly hosted by this project are retained as archived, read-only migration references. New issues, releases, and contributions belong here.
