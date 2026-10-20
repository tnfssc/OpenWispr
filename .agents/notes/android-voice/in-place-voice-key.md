---
name: Keyboard dictation runs on the voice key itself
description: The keyboard mic records in place with no window or strip; the key's icon shows the state
---
The user first rejected the full-panel voice window, then the 64dp strip above the keys that replaced
it: dictation must take no extra space. `VoiceInputAction` is now a simple press that toggles
`InlineDictation`: tap to record, tap again to stop. The key is drawn by `VoiceKeyIcon` through
`Action.iconContent`, the same look as the floating bubble: a warm disc with voice-driven bars while
listening, and an indigo disc with a wave while transcribing.

While recording, the active IME is `ActionInputTransactionIME`, which cannot type. Its `onEvent` calls
`InlineDictation.onKeyPressed()`, so any key, including the voice key itself, means "done talking" and
is not typed. `UixManager.onInputFinishing` cancels the session because its transaction dies with the
field.

What can't fit on a key still opens `VoiceInputPanelAction` as a full panel: setup, the locked-device
message, and recovering a failed recording (opened automatically after a failed transcription).
