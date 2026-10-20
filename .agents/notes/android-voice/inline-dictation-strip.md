---
name: Inline dictation strip
description: Android keyboard mic records in a 64dp strip above the keys, not a full panel
---
The user found the full-panel voice window (keyboard hidden, big logo) disruptive and asked for inline
dictation. `VoiceInputActionWindow` sets `onlyShowAboveKeyboard` and a 64dp `fixedWindowHeight`, and
`RecognizerView.Content(compact = true)` renders one row (logo, status, Stop).

Keys stay visible, but the active IME while recording is `ActionInputTransactionIME`, which cannot type.
Its `onEvent` asks the open action window (`onKeyPressedDuringInputTransaction`); the voice window
treats any key as "done talking" and finishes the recording, so the key itself is not typed. Saved-failure recovery needs room, so the strip shows a "Recover" button that
reopens the action; with failed speech on disk the window opens as the full panel.
