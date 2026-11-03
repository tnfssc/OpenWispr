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

Last synced upstream commit: `7d61ab413` (2026-11-02), previous base `70a5d390c`. Use it as
`<last-synced>` next time and update this note. Submodules are bumped separately: take upstream's
pin for any submodule its code depends on (futo-swipe follows the HF `prod` branch, not `main`;
upstream code names the model directories), and the remote's latest for the rest.

Conflicts are rare because OpenWispr code lives outside upstream files (see openwispr-module). In the
70a5d390c sync, upstream's per-app voice input picker replaced our "use system voice input" toggle.
README.md and uix/actions/VoiceInputAction.kt conflict on every sync that touches them: both are
replaced wholesale by our versions, so keep ours and port only what our dictation code needs.
