---
name: Floating mic can hide the keyboard
description: Voice-only mode keeps the keyboard hidden regardless of focus, except in password fields
---
The user wanted to stop the keyboard popping up while typing by voice. The bubble's long-press menu has
"Hide keyboard", which calls `softKeyboardController.setShowMode(SHOW_MODE_HIDDEN)`; no extra
accessibility flag is needed.

The first version dropped back to `SHOW_MODE_AUTO` whenever no editable field was focused. The user
saw the keyboard flash up for a moment on every tap into a field, so the hidden mode no longer depends
on focus. Only a focused password field brings the keyboard back. Lock-out is avoided because the
bubble is always on screen and its menu has "Show keyboard".

Restoring the keyboard is not enough on Android 16: the app focused while hidden still believes its
show request worked and ignores taps ("onCancelled at PHASE_CLIENT_REPORT_REQUESTED_VISIBLE_TYPES").
Even an IME `requestShowSelf` is cancelled the same way. `KeyboardBridge` (same process) has OpenWispr's
IME call `requestHideSelf` and then `requestShowSelf`, which resets the app's state and shows the
keyboard at once. This only helps while OpenWispr is the active keyboard.
