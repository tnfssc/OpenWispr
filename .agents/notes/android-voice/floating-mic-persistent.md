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

Chrome needs several workarounds, found on the API 36 emulator:
- Until its tree has been read, Chrome reports the whole web view as the input focus, so
  `focusedField()` looks inside a non-editable focus for the focused editable node.
- In contenteditable fields, SET_TEXT is applied asynchronously and the caret is reset to the start.
  `placeCursor` re-checks at 120/400/900 ms and moves it to the end of the dictation while it is
  still at 0, so a caret the user moved meanwhile is left alone.
- After SET_TEXT, the node keeps answering 0 for its selection and changes identity, while
  TYPE_VIEW_TEXT_SELECTION_CHANGED events stay accurate. The service keeps the latest event's caret
  (cleared on focus change) and prefers it. Without this, the second dictation landed at the start.
- The service does not subscribe to text-change events, so cached nodes hold old text. `insert`
  refreshes the node first; otherwise a dictation could replace the previous one.
