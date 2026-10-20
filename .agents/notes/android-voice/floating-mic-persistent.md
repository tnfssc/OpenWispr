---
name: Floating mic bubble is always on screen
description: The bubble no longer depends on a focused field or the keyboard being hidden
---
The bubble used to appear only while an editable field had focus and no keyboard was visible. The user
found it vanishing confusing and asked for it to stay put whatever has focus, keyboard included. It now
shows whenever `FLOATING_MIC_BUBBLE` is on, and the "Hide bubble for this field" menu item is gone.
Dictating with no focused field copies the text to the clipboard.

The long-press menu has "Undo last dictation" after a bubble dictation. It removes the inserted text
only if it is still exactly where it was put (`removeDictation`): through SET_TEXT, or by selecting and
cutting it for editors that only accepted a paste.
