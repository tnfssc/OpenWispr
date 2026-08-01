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

## Repository policy

The macOS and GNOME code is licensed under the root [MIT License](LICENSE). The Android keyboard remains a modified FUTO Keyboard fork and is governed by its own [FUTO Source First License](apps/android-keyboard/LICENSE.md); the root MIT license does not apply to that directory or its submodules.

The GNOME and Android repositories formerly hosted by this project are retained as archived, read-only migration references. New issues, releases, and contributions belong here.
