---
name: Keyboard releases are Obtainium-compatible
description: How Obtainium picks keyboard releases out of the shared release list and matches versions
---
The user installs and updates through Obtainium. Three things keep that working:

- **Release titles.** GNOME and macOS releases share this repository's release list and are often
  marked Latest. Obtainium filters on the release title (falling back to the tag only when the title
  is blank), so every keyboard release must be titled `OpenWispr Keyboard vX.Y.Z`. The release
  workflow sets it; v0.1.11–v0.1.13 were retitled by hand.
- **Version matching.** Without a regex, Obtainium compares the installed versionName (`vX.Y.Z`)
  with the tag (`keyboard-vX.Y.Z`) as plain strings, which would mean "update available" forever.
  The import link sets `versionExtractionRegEx` to `\d+\.\d+\.\d+`.
- **Updates install.** versionCode is the release workflow's run number, so it always increases,
  and every build is signed with the same committed keystore.

The import link in `apps/android-keyboard/README.md` also enables `fallbackToOlderReleases` so the
filter can skip past non-keyboard releases. Checked on the API 36 emulator with Obtainium 1.7.1:
importing via the link, updating v0.1.12 → v0.1.13, and staying up to date afterwards.
