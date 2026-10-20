---
name: Silence auto-stop timeout
description: Android VAD auto-stop is a duration setting, default 5 s, 0 disables
---
Upstream stopped after ~2 s of silence (66 VAD frames), cutting off users who pause to think. The
boolean `use_vad_autostop` became `VAD_SILENCE_TIMEOUT_MS` (choices 0, 1–30 s; user picked 5 s as
default). `AudioRecognizer` converts it to 30 ms VAD frames (480 samples at 16 kHz). With 0 the VAD is
not created and recording runs until the user stops it or the buffer can't grow (long-form recording
off).
