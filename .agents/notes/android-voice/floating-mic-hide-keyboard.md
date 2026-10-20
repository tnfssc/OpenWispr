---
name: Floating mic can hide the keyboard
description: Voice-only mode uses the accessibility SoftKeyboardController, and only where the bubble can replace the keyboard
---
The user wanted to stop the keyboard popping up while typing by voice. The bubble's long-press menu has
"Hide keyboard", which calls `softKeyboardController.setShowMode(SHOW_MODE_HIDDEN)`; no extra
accessibility flag is needed.

Hiding applies only while the bubble is shown for the focused field. Password fields, fields the bubble
was hidden for, screens with no recognisable editable field, and the bubble being switched off all set
`SHOW_MODE_AUTO`, so the user can never end up with neither keyboard nor bubble (OpenWispr is their
primary keyboard). The service also resets the mode in `onDestroy`, and Android resets it when the
service is disabled.

Checked on the API 36 emulator: no keyboard flash when entering a screen with an auto-focused field.
After "Show keyboard", an already-focused field needs a tap to bring the keyboard back; the service
cannot force the IME open.
