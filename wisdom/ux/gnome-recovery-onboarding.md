# GNOME: saved speech and first dictation

## Decisions

Read values, recovery brief, UX audit, architecture/provider docs, GNOME README,
and the Go/JS lifecycle before editing. Borrow macOS's current-settings Retry and
copy-only recovery, not a history product.

- Record original audio directly in a private persistent directory under XDG_STATE_HOME
  (default ~/.local/state/openwispr/failed-audio). Unique names preserve every older
  recording. Successful processing deletes only its own audio. Failure, pipeline
  cancellation, or an engine restart leaves it available. Explicit Discard deletes
  the oldest file; cancelling capture before processing deletes the current file.
- Fix the real async Stop ownership bug: Stop no longer defers removal of the file
  it hands to its goroutine. Ownership belongs to the durable store through processing.
- Keep the original Shell-only authorization on engine mutations. Add RecoveryStatus,
  Retry, Discard; generate recorder introspection from Go methods and test signatures.
  Retry selects oldest audio while idle, using current config and fresh GSettings keys.
- Top-bar click opens a useful native menu rather than recording while selecting a
  recovery action. Saved-audio count, Retry/copy, Discard, Cancel/keep audio, Setup,
  and Settings are immediately available. Network, timeout, key, rate-limit, and
  local-tool errors suggest actual next steps. Recovery never synthesizes paste.
- A four-step native GTK/libadwaita wizard lives in prefs: provider/privacy,
  microphone/prerequisites, toggle/hold shortcuts and insertion, then real test.
  It moves the existing settings groups into the wizard temporarily: one draft,
  one validation path, one Save transaction, no competing credential editors.
  Save and continue deliberately saves provider/shortcut edits. Back does not save.
  Leave restores controls with edits intact; closing prefs keeps existing discard
  semantics. Progress and successful completion use independent settings.
- Setup auto-opens only for unconfigured users opening prefs, never traps existing
  users, and is reopenable from prefs/top-bar even when prefs is already open.
  Shortcut defaults remain empty. Users capture their own combinations or use the
  menu; do not guess a system shortcut or install a hotkey daemon behind their back.
- Prerequisites check FFmpeg, companion reachability, and local tools/model when
  selected. Native Sound settings handles input/mute; a real recording verifies
  microphone access. Existing doctor runs from a button, with optional portal/evdev
  warnings explained as unrelated to native shortcuts. Links explain manual install.
- Prefs talks to Shell's TestStart/TestStop/TestCancel API and TestState signals;
  it does not weaken recorder authorization. The test uses saved provider settings,
  displays selectable text and an explicit Copy action, and never pastes or copies
  automatically. Leave/Back cancels capture/processing, including a pending Start.
  Each test carries a session ID; Back/leave invalidates it, so old results cannot complete
  a reopened wizard or stop a newer test. Finish requires actual nonempty text; only
  Finish saves completion.

## Focused checks

From apps/gnome:

- go test -race -count=1 ./cmd/openwispr: pass. Includes retained audio after failure,
  oldest-first restart discovery, current config/credentials, concurrent Retry/Discard
  rejection, late-result cancellation, private unique files, actual async Stop ownership,
  explicit recording deletion, HTTP 503 then successful Retry with original audio,
  and generated D-Bus signatures. Existing cleanup tests remain passing.
- go build -o /tmp/openwispr-gnome-check ./cmd/openwispr and go vet ./cmd/openwispr: pass.
- node --test test_recovery.js: pass (7 controller tests). Safe test routing while
  Start is pending, cancel-before-Start reply, copy-only Retry/early completion,
  cancelled late result, actionable saved-audio error, busy Discard, and stale-session rejection.
- glib-compile-schemas --strict extension/schemas and gjs -m test_schema_defaults.js:
  pass; 35 keys, unchanged provider/shortcut defaults, new setup defaults checked.
- dbus-run-session -- env GTK_A11Y=none gjs -m test_preferences.js: pass. Real GTK
  widgets and isolated memory settings, asynchronous mock D-Bus recorder, Save/validation,
  Back, leave/reopen, top-bar request in existing prefs, selectable result, completion,
  configured-user preservation, and Back during recording with an ignored late result. No user credentials or microphone used by tests.
- git diff --check: pass at review.

## Observed environment and remaining limits

Xvfb cannot start: libnettle.so.9 is missing. xvfb-run wrappers reported nonzero teardown
while GTK used the available display and assertions passed. Retested directly with the
available display and an isolated session bus: exit 0. No dependency installation was
attempted. The isolated bus emits portal/systemd/keyring/FUSE warnings and a Settings
portal timeout; these are not the product doctor/microphone checks being claimed as
passing. A standalone registered GTK application renders setup for visual review;
Snapshots require compositing their transparent background for review. Visual review
  moved the selected provider key and signup link above advanced endpoint/model fields,
  so first-run credentials are visible without scrolling; inactive providers do not add links.
  Toggle and hold capture appear before insertion. Unrelated notification/clipboard-restore
  switches remain in Settings rather than filling the wizard. Shortcut and result layouts
  were visually reviewed too.

Tests cover the actual engine pipeline with a local HTTP server and native prefs with
mock recorder signals, not a live paid provider or physical microphone. Full GNOME
Shell extension loading, global key delivery, and real-app insertion need parent/manual
verification on the target GNOME session. Update extension and engine together and
restart the engine while idle. Older companion binaries do not implement recovery.
No accounts, analytics, modes, archive browser, automatic installs, or new credential
store. Successful engine processing deletes audio even if Shell is subsequently lost;
this deliberately remains failure-only recovery, not a durable transcript archive.
Cancellation reaching the engine before completion retains audio. Cancellation after
successful engine completion cannot resurrect already-deleted audio; Shell still never
inserts a cancelled late result.
Abrupt interruption during capture can leave a partial WAV; it is retained for explicit
Retry/Discard, not falsely promised to be a valid full recording. Cleanup remains
best-effort and returns raw transcript if optional cleanup fails, as before.

Only apps/gnome and this note changed. No root/shared docs or Android submodules touched.
