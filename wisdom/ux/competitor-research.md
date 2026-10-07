# Small dictation UX features

Research snapshot. The later implementation is recorded in [recovery and onboarding](recovery-onboarding.md).

## Brief and result

The user asked for competitor research, not implementation. Small features only. User experience comes first. No application code changed.

Recommendation: improve trust and the first successful dictation before adding more AI features. Ship last-result rescue, a short setup test, and clear desktop recording feedback first. Add a small correction list next. Failure-only audio retry is valuable, but is a separate, larger slice than a Copy button.

## Ranked shortlist

### 1. Rescue the last successful text

Borrow Wispr Flow's last-text recovery and Superwhisper's ability to retrieve a result. Show one session-only result with Copy. A shortcut can re-copy it without opening settings. Do not paste into an old target automatically or build a searchable archive.

macOS already has last-transcript Copy. GNOME has clipboard restoration but no last-result UI found. Android inserts into the destination field but has no separate recovery result card found. Treat this as parity and visibility work, not a wholly new macOS capability.

https://docs.wisprflow.ai/articles/2503460374-retry-failed-transcriptions
https://superwhisper.com/docs/get-started/history

### 2. One short test dictation

Borrow Superwhisper's first-run permission and microphone setup followed by a first dictation. Put a Try dictation action in existing setup. Show permission readiness, active shortcut, microphone input, and where audio and cleanup text go. Let people try a phrase without hunting through every setting.

GNOME already has shortcut capture and doctor/repair commands, but setup is terminal-heavy and shortcut defaults are empty. macOS already has permission/dependency controls, but its global shortcut is a typed string. Android already has provider links and clear network disclosure. Reuse these, not another large wizard or account flow.

https://superwhisper.com/docs/get-started/quickstart

### 3. Compact recording feedback on desktop

Borrow Superwhisper's mini recording window. Use a small, non-focus-stealing indicator with speech level, Recording/Processing, and Stop/Cancel. An honest level meter is enough; do not fake transcription progress. Add optional quiet start/stop cues in the same slice if needed.

Desktop clients have icon/state feedback but no input meter found. Android already has an audio-reactive logo, voice-state labels, and optional sounds; do not rebuild those. Use its design as an internal reference. A desktop HUD requires audio-level plumbing, so it is modest work, not just a CSS change.

https://superwhisper.com/docs/get-started/interface-rec-window
https://superwhisper.com/docs/get-started/essential-settings

### 4. A tiny correction list

Borrow Superwhisper's deterministic replacements: heard phrase -> intended text. Examples: a repeatedly wrong name, Open Whisper -> OpenWispr, my work email -> an address. Apply locally after transcription; keep matching clear and triggers specific. One list, no folders, sync, or prompt editor.

Recognition hints are a different, backend-dependent feature. They can help names but are not guarantees, and excessive hints can hurt accuracy. Leave automatic learning for later. Android's existing typing personal dictionary is not used by its voice transcription; never silently upload it.

https://superwhisper.com/docs/get-started/interface-vocabulary
https://docs.wisprflow.ai/articles/4052411709-teach-flow-your-words-with-the-dictionary

### 5. Failure-only Retry / Discard on GNOME and Android

Borrow the retry action, not the whole history product. macOS already saves failed/canceled recordings across restarts. GNOME deletes temporary audio on processing return paths. Android resets capture after backend errors and reports a generic provider-settings failure; no audio retry flow found.

Retain failed audio locally long enough to retry and make deletion clear. Network errors should offer Retry, not imply the key/settings are always wrong. This prevents repeated speech but needs explicit audio lifetime and storage choices. It is not in the same effort class as last-text Copy. Do not retain successful audio by default merely to support this feature.

https://superwhisper.com/docs/get-started/history
https://docs.wisprflow.ai/articles/2503460374-retry-failed-transcriptions

## Keep or defer

Keep existing desktop toggle/hold-to-talk, paste options, and clipboard restoration. Superwhisper's single tap/hold trigger is a nice later polish idea, not a priority over lost-work recovery. Both desktop clients already support the underlying gestures separately.

Defer app-specific modes, screen/clipboard context reading, automatic vocabulary learning, AI chat, selection-aware rewriting, full transcript/audio archives, analytics dashboards, and account/sync features. Their marketing surfaces look small; their permissions, state, and privacy costs are not.

Aqua Voice documents custom dictionary/history and selection-aware Edit Mode. Willow advertises snippets and automatic dictionary learning. Those did not displace the simpler, better-documented first-wave ideas. Vendor speed, accuracy, and privacy claims were not independently measured.

https://aquavoice.com
https://aquavoice.com/edit-mode
https://willowvoice.com/features/dictation

## Evidence and limits

Live web research used tvly CLI and direct official-page fetches. A later Tavily extract hit the hourly keyless limit; direct Superwhisper Markdown docs still worked. A guessed recording-window URL returned 404; the documentation index supplied the correct URL above.

Read architecture/provider docs, native UI source, recording and insertion paths, and upstream notes. No installed competitor or OpenWispr UI was tested hands-on. Shortcut feel, waveform responsiveness, paste compatibility, and usability benefits remain unmeasured. Rankings are product judgments grounded in docs and source, not user-study results.

Full findings: [competitor sources](competitor-sources.md), [current app audit](current-ux-audit.md).

## Handoff

Research workers task_43203556 and task_66822c4f completed. Both were read-only and used the current checkout; no worker worktree or branch was needed.

Current checkout: /home/tnfssc/.t3/worktrees/OpenWispr/t3-300cd7f4
Branch: t3/competitor-ux-research

At the research handoff, no code, build, or release work was requested. No commits or pushes had been made and the wisdom files were local and uncommitted. The later implementation saved this research in b196d37c8. Pre-existing dirty Android submodules were left alone.

The wisdom folder and values file were absent. Created a small values set from this brief and existing provider/architecture docs: easy dictation over bloat, honest remote-processing disclosure, and shared flow without forced platform parity. No prior lessons were invented. This pass adds no further recurring rule.
