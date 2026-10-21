---
name: Syncing the keyboard with upstream FUTO Keyboard
description: How apps/android-keyboard pulls upstream changes, and which upstream commit it is on now
---
`apps/android-keyboard` was imported as a squashed subtree, so it shares no history with
`futo-org/android-keyboard` (remote `futo`). `git subtree merge --squash` would diff against the old
squash, which lacks our fork commits, and revert them. Instead apply the upstream delta as a patch:

```sh
git fetch futo
git diff <last-synced> futo/master --binary --ignore-submodules=all > .tmp/upstream.patch
git apply --3way --directory=apps/android-keyboard .tmp/upstream.patch
```

Last synced upstream commit: `70a5d390c` (2026-09-23), previous base `94aea3d4a`. Use it as
`<last-synced>` next time and update this note. Submodules are bumped separately.

Conflicts are rare because OpenWispr code lives outside upstream files (see openwispr-module). In the
70a5d390c sync, upstream's per-app voice input picker replaced our "use system voice input" toggle.
