# Releases

Each platform has its own tag namespace and GitHub Actions workflow:

| Platform | Tag format | Assets |
| --- | --- | --- |
| macOS | macos-vX.Y.Z | DMG and SHA-256 checksum |
| GNOME | gnome-vX.Y.Z | extension ZIP, Linux companion archives, service files |
| Android keyboard | keyboard-vX.Y.Z | unstable release APK |

Create a tag only after the matching platform CI succeeds. Historical releases remain available from the archived legacy repositories; new releases are published from this repository.
