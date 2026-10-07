# Recovery and first dictation

## User request and scope

Build feature 5 from the competitor research: failure-only Retry / Discard on GNOME and Android. Build good native onboarding on GNOME and macOS. Check Android's existing onboarding and fill its voice-setup gap without another keyboard wizard.

User experience comes first. No new accounts, modes, archives, dashboards, or auto-installs. Keep existing macOS recovery. Setup gets someone to a real test dictation and explains where audio and optional cleanup text go. Test results stay in setup unless the user explicitly copies them.

## Implemented shape

- GNOME: four setup steps, existing Save semantics and key/shortcut controls, microphone/dependency checks and doctor access, Back/leave/reopen with progress, safe test via extension D-Bus, completion only after a nonempty test result. Configured users retain settings. Failure audio lives in private persistent storage; Retry uses current settings and only copies. Multiple pending recordings are recovered oldest first.
- macOS: native first-run/reopenable six-step window, provider/privacy, permissions, native shortcut capture, safe real test, ready screen and saved progress. Configured users are not forced through setup. Tests use an isolated recorder rather than consuming everyday pending audio. Existing macOS recovery stays intact. Failed setup-test audio is session-only, unlike the durable everyday pending queue.
- Android: inherited keyboard enable/select wizard stays. Three voice steps live in Speech provider: provider/key/privacy, mic permission, safe real test. Saved progress and current configured users are respected. One failed PCM packet stays in backup-excluded private storage. Retry/Discard gate new capture so it cannot overwrite saved speech. Retry uses current settings and shows text; Insert here and Copy are explicit. Backend failure messages distinguish key, network, and quota errors.

Successful recordings are not archived. See the platform notes for audio lifetime, cancellation, and manual checks.

## Workers and integrated commits

All platform workers started from b196d37c8, the research/brief commit, and used separate durable worktrees.

- GNOME task_f278ac08: feat/gnome-recovery-onboarding, /home/tnfssc/.bruv/worktrees/t3-300cd7f4-1ec14282a223-task_f278ac08. Commit 81a854f20463b812c95df93caafb71e063c8170f integrated as aa1373afd.
- macOS task_fd34b74d: feat/macos-onboarding, /home/tnfssc/.bruv/worktrees/t3-300cd7f4-1ec14282a223-task_fd34b74d. Commit ea256348b137d2d2a1e76c81aedf58da453067b2 integrated as 0bf2d3d1b.
- Android task_bee88dcc: feat/android-recovery-onboarding, /home/tnfssc/.bruv/worktrees/t3-300cd7f4-1ec14282a223-task_bee88dcc. Commit b95f66f454547cb669cef6b445002ae041e4bc2f integrated as 0413831a6.

Platform code and each worker's wisdom are committed together. Workers did not push or open PRs. Parent owns integration, shared docs, and the final GNOME review refinements.

## Reviews and fixes

Independent review task_971199cc confirmed a GNOME wizard bug: going Back during processing ignored later state signals and stranded disabled controls. The final worker fixed it by invalidating the test session and resetting controls on return, rather than trusting a late result. Parent added a native GTK/D-Bus regression for processing -> Back -> hidden cancellation -> return -> stale result -> fresh successful test. It passed.

Parent hid GNOME saved-audio/Retry/Discard controls when there is no saved audio or while current processing is active. Missing-companion status remains actionable. Two controller tests cover counts zero and two plus busy state. This keeps failure recovery out of the normal menu.

Reviewer found no other consequential Android/macOS defect. It did not supply native Mac verification. Final focused review task_13f682fa completed with no further actionable issue. It confirmed the GNOME Back/reset and hidden recovery controls, and found no consequential regression in focused Android navigation/setup/recovery. It did not verify Mac SDK or Android device behavior.

## Validation

Parent on the integrated GNOME tree:

- go test -race -count=1 ./...: passed.
- go vet ./... and go build: passed.
- node --test test_recovery.js: nine controller tests passed.
- glib-compile-schemas --strict plus test_schema_defaults.js: passed, 35 keys.
- Native GTK preferences test with isolated session bus and memory settings: passed, including Save/validation, setup re-entry/progress, mock D-Bus successful result, configured-user preservation, and both recording/processing Back cancellation. This is real native widget/API testing with a fake recorder, not live microphone/provider testing.

Parent rendered all four GNOME wizard pages under real GTK4/libadwaita with only the ExtensionPreferences base stubbed to supply memory Gio.Settings. Missing extension/service showed a specific error and kept Finish disabled. The worker also visually reviewed final page layout, moved the selected signup link/key before advanced fields, and kept unrelated switches out of setup.

Host Xvfb initially failed because libnettle.so.9 is missing. Parent downloaded the matching nettle package from the configured distribution mirror into /tmp/openwispr-gnome-smoke/deps and used LD_LIBRARY_PATH only for disposable tests. No system package was installed. GSK_RENDERER=cairo avoided broken GPU rendering. Isolated portal/FUSE warnings are not product failures; assertions and exit codes passed.

Android worker: 27 JVM tests, eight focused emulator instrumentation tests, and offline StableDebug app/test APK assembly passed. Device panel access is off, but the worker used a disposable read-only local emulator directly through the SDK; no AVD changes were saved. Tests cover real storage/coroutine lifecycle and fake provider responses. Manual emulator checks covered wizard navigation, permission denial/grant, safe recording and Back, retained failure/Retry with a synthetic fixture and invalid key, and Discard.

macOS worker: strict formatting, source syntax parsing, 11 Foundation setup/shortcut tests, and five isolated setup-recording lifecycle tests passed in Linux Swift containers with documented platform shims. Full swift build fails at missing Carbon on Linux. This is not macOS SDK typechecking, native clipboard verification, or UI verification.

## What still needs care

- Run the macOS native check script and window/permission/clipboard/shortcut checklist on a Mac before release.
- Verify physical-mic and valid-provider dictation, normal insertion, Bluetooth, and preview behavior on Android; verify loaded extension/engine and real key/paste delivery in GNOME.
- Android emulator cold start after pm clear with this IME already selected stalled in inherited LatinIME.onCreate -> Subtypes.addDefaultSubtypesIfNecessary -> SettingsKt.setSettingBlocking, before the new setup/recovery code. Switching IMEs and opening inherited setup worked. Root cause is not established. Do not call clean cold-start behavior verified or this stall fixed. See the Android note for evidence and reproduction limits.
- Update GNOME extension and engine together and restart the engine while idle. Old binaries lack the new recovery API.

## Wisdom and values

Added platform notes, lifecycle decisions, tests, and release-check limits. Values remain unchanged: the existing simplicity, honest processing disclosure, and native-flow principles fit this work. Session IDs and state cleanup are feature-specific lessons, not a reason to add another broad value.

## Handoff state

Parent checkout: /home/tnfssc/.t3/worktrees/OpenWispr/t3-300cd7f4. Branch: t3/competitor-ux-research. No PR or push requested; code is local. Parent review refinements and shared docs are committed with this note after final review. Pre-existing dirty Android asset submodules were not modified or staged.

Related notes: [GNOME](gnome-recovery-onboarding.md), [macOS](macos-recovery-onboarding.md), [Android](android-recovery-onboarding.md), [research](competitor-research.md).
