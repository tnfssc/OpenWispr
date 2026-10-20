---
name: Floating mic uses an accessibility service
description: Why the keyboard-free floating mic is an AccessibilityService overlay, and its trade-offs
---
The bubble docks to the left or right screen edge with a damped spring after a drag or fling.

The user wanted a mic bubble with the keyboard hidden, to keep screen space. Two designs were offered:
collapsing the IME to a floating bubble (no new permissions, but only while OpenWispr is the active
keyboard) or a system overlay plus accessibility. The user chose accessibility.

`FloatingMicService` draws a `TYPE_ACCESSIBILITY_OVERLAY` window (no "display over other apps" grant)
while an editable, non-password field has input focus and no input-method window is visible. It inserts
with `ACTION_SET_TEXT` + `ACTION_SET_SELECTION`, falling back to clipboard + `ACTION_PASTE` for editors
that refuse SET_TEXT (web views). Sideloaded installs on Android 13+ need "Allow restricted settings"
before accessibility can be enabled.

Recording runs in the accessibility service without a foreground service: the system binds
accessibility services with BIND_INCLUDE_CAPABILITIES, which grants while-in-use mic access. Confirmed on
an Android 16 (API 36) emulator: audio is not silenced. The capability holds only while the screen is awake, so the bubble sets FLAG_KEEP_SCREEN_ON
while recording or transcribing.

Errors are shown in the service's own overlay rather than as a Toast. On a fresh install with
notifications off, the system suppresses toasts from a background app ("Suppressing toast ... by user
request"), so bubble failures would otherwise be invisible.
