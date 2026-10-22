---
name: Mic opens on touch-down, without call routing
description: Why the floating mic starts recording before the tap ends, and why AudioRecognizer skips setCommunicationDevice
---
The user found the first words after tapping the floating mic were lost and wanted it as close to
instant as possible, without an always-listening mic.

- `FloatingMicService` opens the mic on touch-down (`FloatingBubble.onTouchDown`). Until the tap ends,
  the bubble shows nothing and plays no sound. A drag or long press (`onTouchAbandoned`) drops the
  recording silently, so the privacy indicator flashes briefly then. The undo history is cleared only
  once the tap is confirmed, so a long press still offers "Undo last dictation".
- Upstream `AudioRecognizer` called `setCommunicationDevice` on every start. Without Bluetooth that
  picks the earpiece and sets up a call-style route. It is now called only when Bluetooth is preferred
  and an SCO device is available, and cleared only if we set it (it is shared with other apps).
- The 0.6 s window that ignores loud audio as the start sound (start.wav is 0.56 s) now runs from when
  the sound actually plays (`startSoundPlayed`), and is skipped when sounds are off. It cannot simply
  be dropped: WebRTC VAD in very aggressive mode marks up to 7 consecutive frames of start.wav as speech.

Measured on the API 36 emulator: touch-down to first audio frame about 45 ms warm. Before the change,
the recognizer alone took about 98 ms from the tap, and the tap fired only on finger-up. Real phones
spend more on routing and HAL start-up; not measured on hardware.
