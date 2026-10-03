# OpenWispr voice logo mockup

Run from the repository root:

```sh
python3 -m http.server 8765 --directory docs/mockups/voice-logo
```

Open <http://localhost:8765> for the interactive mockup and tuning controls.
Open <http://localhost:8765/?presentation=1> for only the mobile experience.
`voice.preview.html` shows default, hover, focus, active, disabled, loading,
error, and success states.

The animated logo is rebuilt as 15 individual SVG strokes and dots, based on
`apps/gnome/openwispr.png`. It contains no raster image or clipped image slices.
Every piece has its own phase, amplitude, and anchor. Strokes grow and shrink
independently with constant thickness; dots bob without stretching, and the
trailing dash widens. Joined strokes share their connection point. The keyboard's
voice button also uses the OpenWispr mark in place of the microphone icon.
The default is expressive
(strength 1.5), following the requested playful tone. Speech-like bursts and
pauses drive an envelope with a 90 ms attack and 240 ms release. Quiet listening
uses less motion. Transcription uses a distinct 1.4-second traveling pulse:
each piece expands and brightens in turn, then settles as the next picks up
the rhythm. The switch from speech to processing blends smoothly. Processing
keeps the logo at full overall opacity even while the finish button is disabled.
Reduced-motion mode keeps the logo still and communicates state with text.

Live preview simulates an on-device STT stream. Provisional words appear in
the note in muted text, with a short fade for newly received words. The last
partial remains visible during processing. The final provider transcript
replaces the provisional wording, including cleanup such as “a coffee on my
way home” becoming “coffee on the way home.” Cancellation discards the draft
and preserves the note's original text. The HTML does not run a real STT model.

Tap the small OpenWispr logo to begin. Tap the large logo to finish and insert
the sample transcript into the note. Cancel or Escape returns to the keyboard
without inserting text. There is no start toast. The only voice copy is the
current status: “Listening…” or “Transcribing…”. Hints, microphone-source
subtitles, and success messages are removed. Text insertion returns directly
to the keyboard. The keyboard keys provide context and are decorative.

This HTML is a simulated interaction, with no microphone capture or provider
calls. The sample transcript is fixture text. The Android implementation renders
the same shapes with Compose Canvas, drives listening motion from captured audio
magnitude, and uses the traveling pulse while awaiting the configured provider.
It respects the animation setting and Android's system animation scale.

On Android 13+, live preview uses an installed on-device SpeechRecognizer model
and receives a copy of the existing 16 kHz PCM stream through a bounded pipe.
Preview is optional: unsupported devices, missing local language models, or
recognition errors retain the normal final-provider workflow. It neither starts
another microphone capture nor downloads a model. Final text replaces the
preview; cancel removes provisional text and restores any selection it replaced.
The host editor controls composing-text appearance, so the muted text/fade in
this HTML is illustrative. Real-device recognition and host-editor behavior
still require device validation.

The latest recording is `voice-logo-live-v4.mp4`, captured with
agent-browser at 414 × 820, 30 fps. It demonstrates the individual vector
pieces, minimal copy, live provisional words, the new processing pulse, and
replacement with the final transcript.
The recording uses `?presentation=1&processingDelay=6000` to show several
processing cycles; the default simulated delay remains 1.6 seconds. Use the
“Transcribing” state in the tuning controls to inspect the loop indefinitely.
`voice-logo-clean-v3.mp4` shows the previous processing animation.
`voice-logo-pieces-v2.mp4` and `voice-logo-pieces-light-v2.mp4` show the previous
copy. Earlier `voice-logo-demo.mp4` and `voice-logo-light.mp4` recordings are the
superseded image-slice version.
