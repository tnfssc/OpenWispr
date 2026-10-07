# macOS: first dictation without accidental insertion

## Decisions

- A single native SwiftUI setup window, retained by a process-lifetime window controller. Close hides it; reopening brings the same window and in-session result back. Menu and Settings both provide re-entry. No new account, mode, telemetry, or history product.
- Six short steps: privacy, provider, permissions, shortcuts, actual test, ready. Back and progress persist in existing UserDefaults. Finish later (including the titlebar close button) suppresses further automatic prompts. Done saves completion. Existing explicit provider configuration, keys, or saved local model paths suppress first-run presentation without resetting anything.
- Groq is recommended, not forced. Existing supported remote/local providers, endpoints, keys, paths, and models are reused. Model choice stays in advanced Settings, except a local model-file chooser. Optional cleanup names its own remote text destination and defaults off on fresh installs; sharing a speech key with cleanup is an explicit button.
- Microphone and Accessibility readiness are checked on activation/window focus and on request, with direct System Settings links. No hidden-window polling. Accessibility can be skipped by choosing clipboard-only insertion.
- Shortcuts are captured with native key events, validated through the existing Carbon parser/registration, and saved to existing Settings. Global handlers are suspended during capture; new everyday recordings are blocked while setup is open so trying the taught shortcut cannot escape into an auto-pasting dictation. An already-running everyday recording can still be stopped by its shortcut. Escape, Cancel, focus loss, and closing end capture; closing setup resumes everyday shortcut starts. Toggle and hold cannot capture the same combination. Right Option keeps its existing tap-to-toggle / hold-to-speak behavior.
- The test records real microphone audio and invokes the current transcription pipeline, including its retry/timeout and optional cleanup. It has an isolated recorder and temporary file, not the everyday pending queue. Its result goes only to the setup view: no clipboard write, app activation, or automatic paste. Failed test audio has Retry/Discard and remains session-only. Closing while test recording stops without uploading. Processing already started can finish safely with the window closed. Normal quit removes remaining test audio.
- Normal capture/processing and test capture/processing cannot overlap, including permission-request startup. Existing failed-audio recovery is preserved; neither running setup nor discarding its test deletes pending dictations. Ready explains focused-field insertion and recovery’s copy-only retry semantics.

## Tests and limits

Focused tests cover first presentation, existing-user suppression without settings mutation, Back/leave/restart/completion, delivery policy, capture round-trip/modifier requirements/Right Option, actual test state and file lifecycle via injected recorder/transcriber, clipboard non-interference, denied permission, failure/retry/discard, leave-without-upload, and cancellation rejecting late success.

Observed validation on this Linux worktree:

- No native `swift` executable was available. Used an isolated `swift:6.0.3-jammy` Docker container, with temporary copies outside the repository.
- Swift frontend syntax parsing passed for all macOS app, core, and test sources. `scripts/lint.sh` (strict Swift formatting) passed. `git diff --check` passed.
- Seven unchanged Foundation-only progress/delivery tests passed. Adding the unchanged shortcut parser and four capture/label tests produced 11 passing tests, using a temporary Carbon constants shim (not a real Carbon event system).
- Five setup-dictation state/file/cancellation tests passed against the unchanged production SetupDictation, SettingsStore, Models, and timeout sources. The temporary Linux harness supplied a minimal Combine observation shim and hardware/pipeline stubs; the tests inject their recorder/transcriber. The native NSPasteboard assertion was omitted in that harness and still needs macOS execution. No provider or microphone access was claimed by these tests.
- Full `swift build` was attempted and failed at `no such module 'Carbon'`. Full macOS SDK typechecking, the native test suite, and UI runtime tests were not possible here.

macOS runtime checks remain required for system permission prompts, Carbon conflicts, native window focus/retention, microphone device behavior, and real provider responses. Linux cannot supply AppKit/SwiftUI/AVFoundation or a macOS SDK. Test transcripts are not persisted. Temporary test audio is cleaned on normal quit, not guaranteed after a crash/force quit. Existing UserDefaults credential storage and cleanup’s raw-text fallback are unchanged.

## macOS review checklist

1. Fresh app data: one setup window opens after launch; configured data: no automatic window and settings intact.
2. Close each step, reopen from menu and Settings, press Back, finish, relaunch. Check saved progress and no second window; resizing and long error/result text remain usable.
3. Deny microphone, open privacy settings, grant and return. Test Accessibility off with automatic paste on and with clipboard-only insertion.
4. Capture toggle/hold, Escape/cancel/close mid-capture, use Right Option, try a conflicting shortcut. Confirm global handlers resume and no recording begins during capture.
5. Dictate a real test with a sentinel clipboard and a text editor behind setup. Confirm neither changes, and real text appears in setup. Fail the network, retry/discard only the test, cancel during processing, and leave while recording.
6. Keep a pending normal dictation and a live normal recording while opening setup. Test must wait for live work, and the pending dictation must survive. Normal retry remains copy-only. Close during test processing and reopen to see its result.
