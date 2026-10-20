---
name: Floating mic on/off lives in the keyboard toolbar
description: Why the bubble has its own setting and why the service, not the keyboard, confirms the toggle
---
Only Android settings can enable or disable an accessibility service, so the bubble has its own
`FLOATING_MIC_BUBBLE` setting that the service observes. The keyboard's "Floating mic" toolbar action
(in default favorites after redo) flips it; if the service is not enabled it opens the setup page.

The keyboard's Toast is suppressed on Android 13+ when notifications are off, so the service shows the
on/off confirmation in its own touch-through overlay at the top of the screen.
