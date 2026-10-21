---
name: OpenWispr code lives in its own module
description: Where OpenWispr code goes so upstream FUTO syncs stay easy
---
Upstream syncs conflict wherever we edit FUTO files, so OpenWispr code is kept out of them:

- `apps/android-keyboard/openwispr/` (Gradle `:openwispr`, package `app.openwispr`): anything that needs
  nothing from the keyboard. Provider calls, failed-speech recovery, `FieldWriter` (accessibility text
  insertion and undo), `FloatingBubble` (overlay windows, spring docking, menu, notes), the bubble and
  voice-key visuals (`MicVisuals` shared spec), `SecretBox`, `KeyboardBridge`. Unit tests live here too.
- `java/src/org/futo/inputmethod/latin/openwispr/`: glue that needs keyboard internals (settings keys,
  `R`, `ActionWindow`, `ModelOutputSanitizer`): `FloatingMicService`, `InlineDictation`, settings pages,
  `OpenWisprConfigStore`, `openWisprRecognizer()`.
- Upstream files get only small hooks (registry entries, `Action.iconContent`/`tintIcon`,
  `KeyboardBridge.attach`, `InlineDictation.cancel()` in `UixManager`). `voiceinput-shared` keeps our
  backend/preview interfaces and `SpeechWave` because its `AudioRecognizer` uses them.

The new module's build script needs JDK 17–21 (Gradle 8.14 Groovy can't read JDK 26 class files);
locally use `JAVA_HOME=/opt/android-studio/jbr`.
