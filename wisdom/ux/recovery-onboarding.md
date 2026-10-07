# Recovery and first dictation

## User request

Build feature 5 from the competitor research: Retry / Discard for failed recordings. Build good native onboarding on GNOME and macOS. Check Android's current onboarding and improve its voice setup without duplicating keyboard enable/select steps.

## Product shape

The user puts UX first and does not want bloat. Onboarding should get someone to a working first dictation. Explain where audio and optional cleanup text go. Keep credentials in the existing provider setup. Show progress, Back, and a clear way to leave and reopen setup. Do not add accounts, modes, history products, or dashboards.

Keep existing macOS failure recovery. Add failed-audio recovery to GNOME and Android with explicit Retry / Discard. Failed audio stays on this device, not in an archive of successful recordings. Do not silently overwrite saved speech. Retrying must not insert into an old/stale target.

Three platform workers will use separate durable worktrees. Parent integrates their commits and checks the whole flow. Worktree paths, commits, tests, and limits will be added as they return.

Research baseline is in [competitor research](competitor-research.md) and [current UX audit](current-ux-audit.md). Pre-existing dirty Android asset submodules belong to the user/environment; do not commit them.

## Status

Implementation starting. No platform changes integrated yet.
