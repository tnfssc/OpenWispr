# FUTO Keyboard upstream

apps/android-keyboard is a modified fork of [FUTO Keyboard](https://github.com/futo-org/android-keyboard), not an MIT-licensed OpenWispr implementation. It includes seven nested submodules and is governed by the FUTO Source First License 1.1-kb.

The OpenWispr-specific changes are intentionally small and live alongside the upstream code. Before importing FUTO updates:

1. Fetch the upstream futo-org/android-keyboard repository.
2. Merge or subtree-update it into apps/android-keyboard.
3. Reconcile OpenWispr's voice-input, branding, and build changes.
4. Update root .gitmodules if the upstream submodule set changes.
5. Build and test the Android app before release.

This repository tracks the component under apps/android-keyboard; it does not claim ownership of FUTO code or its submodules.
