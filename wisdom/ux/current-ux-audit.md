# Current OpenWispr UX

Source audit from the read-only worker. See [recommendations](competitor-research.md) for the parent synthesis.

## Scope

**Source-verified, not runtime-tested.** Read architecture/provider/upstream docs, platform UI and recording pipelines, Android’s inherited keyboard/settings code, repository screenshots, and official competitor documentation. No files edited. Microphone behavior, shortcut reliability, paste compatibility, latency and accessibility still need device testing.

## Current UX inventory

### GNOME
- **Recording:** panel click or configurable toggle/hold shortcut; red recording icon and processing icon. No visible input-level meter, timer or live transcript. Clicking/toggling during processing cancels it. [extension.js:433](apps/gnome/extension/extension.js#L433), [state icons](apps/gnome/extension/extension.js#L1191).
- **Recovery:** errors become notifications; audio is deleted on the processing return path, including failure. No saved-recording retry UI found. This is substantially weaker than macOS. [engine.go:321](apps/gnome/cmd/openwispr/engine.go#L321).
- **Onboarding/shortcuts:** preferences offer provider-key links, shortcut capture, and **copyable installation/doctor/repair commands**—useful but terminal-heavy. Both shortcut defaults are empty, despite the screenshot advertising Ctrl+Alt+R. Hold daemon uses portal first, evdev fallback. [prefs.js](apps/gnome/extension/prefs.js), [schema](apps/gnome/extension/schemas/org.gnome.shell.extensions.openwispr.gschema.xml).
- **Insertion:** unusually good platform-specific controls: standard/terminal paste, Shift+Insert, clipboard-only, Wayland guidance and clipboard restoration. No last-result copy/edit UI. [prefs.js:112](apps/gnome/extension/prefs.js#L112).

### macOS
- **Recording:** menu-bar states, status text, Start/Stop and processing Cancel. Toggle and hold-to-talk supported; no recording meter/timer. Shortcut configuration is a text field rather than GNOME’s key capture. Toggle does nothing during processing; cancellation requires the menu button. [SettingsView.swift](apps/macos/Sources/OpenWisprMac/SettingsView.swift), [AppState.swift:111](apps/macos/Sources/OpenWisprMac/AppState.swift#L111).
- **Recovery already exists:** transient retries, processing deadlines, restart-persistent failed/canceled recordings, Retry/Discard, and last-transcript Copy. Retry processes the oldest recording without auto-paste; there is no recording picker or retention control. **Do not propose retry as a new macOS feature.** [menu UI](apps/macos/Sources/OpenWisprMac/OpenWisprMacApp.swift), [RecordingStore.swift](apps/macos/Sources/OpenWisprCore/RecordingStore.swift).
- **Onboarding/privacy:** microphone prompt, Accessibility permission button and dependency checks exist, but setup remains configuration-led. Keys are stored in UserDefaults, not Keychain. Clipboard restoration defaults **off**, versus **on** in GNOME. [SettingsStore.swift](apps/macos/Sources/OpenWisprMac/SettingsStore.swift), [README](apps/macos/README.md).

### Android keyboard
- **Recording feedback is richest:** audio-reactive logo, Listening/Transcribing/microphone-unavailable labels, optional sounds, Bluetooth microphone control and VAD auto-stop. Closing the action cancels and removes provisional text. [RecognizeViews.kt:113](apps/android-keyboard/voiceinput-shared/src/main/java/org/futo/voiceinput/shared/ui/RecognizeViews.kt#L113), [VoiceInputAction.kt](apps/android-keyboard/java/src/org/futo/inputmethod/latin/uix/actions/VoiceInputAction.kt).
- **Live preview exists, conditionally:** Android 13+, available on-device recognizer and installed language support. It updates the destination field provisionally; final transcription remains remote. Missing preview support silently degrades to recording without preview. [OnDeviceSpeechPreview.kt](apps/android-keyboard/voiceinput-shared/src/main/java/org/futo/voiceinput/shared/OnDeviceSpeechPreview.kt).
- **Recovery is weak:** backend exceptions collapse into “Transcription failed. Tap to check … provider settings”; capture resets, with no retry/persistent recovery flow found. Network failure therefore looks like configuration failure. [AudioRecognizer.kt:480](apps/android-keyboard/voiceinput-shared/src/main/java/org/futo/voiceinput/shared/AudioRecognizer.kt#L480).
- **Onboarding/privacy:** inherited enable/select-keyboard setup is separate from voice-provider setup. Groq/OpenRouter key links, language code, explicit network disclosure and encrypted credentials already exist. System voice input is an optional external bypass. **Unlike upstream FUTO, OpenWispr voice transcription is not offline.** [voice settings](apps/android-keyboard/java/src/org/futo/inputmethod/latin/uix/settings/pages/OpenWisprVoiceSettings.kt), [upstream notes](docs/upstreams/futo-keyboard.md).

### Correction and vocabulary across platforms
- All clients offer optional configurable cleanup; cleanup failure falls back to raw transcription, generally without an explicit user-facing failure indication.
- No dedicated dictation vocabulary/replacement UI found. Android **already has a typing personal dictionary, word shortcuts and autocorrect undo**, but these are not passed into OpenWispr transcription. Don’t confuse typing vocabulary with speech vocabulary. [PersonalDictionary.kt](apps/android-keyboard/java/src/org/futo/inputmethod/latin/uix/settings/pages/pdict/PersonalDictionary.kt), [transcription backend](apps/android-keyboard/java/src/org/futo/inputmethod/latin/openwispr/OpenWisprTranscriptionBackend.kt).
- Desktop remote-data disclosure is weaker than Android’s explicit in-settings wording; optional cleanup can send text to a different provider. [provider/privacy contract](docs/providers.md).

## Five small opportunities worth borrowing

1. **Compact desktop recording feedback:** input-level animation, elapsed time and visible Stop/Cancel. Borrow Superwhisper’s [mini recording window](https://superwhisper.com/docs/get-started/interface-rec-window), not its modes/context machinery. Android largely already covers feedback.

2. **One guided “test dictation” step:** verify permission, microphone, provider and insertion; show the active shortcut and exactly where audio/text go. Reuse existing health/dependency checks. Prioritize GNOME’s terminal-heavy setup and macOS shortcut capture.

3. **Failure-only recovery on GNOME/Android:** retain failed audio long enough for **Retry / Discard**, with clear local-storage disclosure. Borrow the reprocess action from [Superwhisper History](https://superwhisper.com/docs/get-started/history), **not** its permanent searchable archive. macOS supplies an internal precedent.

4. **A tiny dictation replacement list:** explicit “heard phrase → intended spelling,” applied locally, with optional short recognition hints where supported. Borrow [Superwhisper vocabulary](https://superwhisper.com/docs/get-started/interface-vocabulary) and [Flow’s dictionary](https://docs.wisprflow.ai/articles/4052411709-teach-flow-your-words-with-the-dictionary). No automatic correction surveillance or implicit upload of Android’s typing dictionary.

5. **One session-only last-result card:** Copy, optional Edit, and “cleanup unavailable—raw text used.” macOS already has Copy; extend that pattern to GNOME/Android before building history, analytics or an AI correction workspace.

**Priority:** recovery and first-run verification before vocabulary. They prevent lost speech and failed first impressions without broadening the product.