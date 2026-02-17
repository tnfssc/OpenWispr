# OpenWispr

A native macOS menu bar dictation app inspired by the Linux `openwispr-gnome-extension` flow.

## What it does

- Menu bar mic app with `idle -> recording -> processing` states
- Global hotkeys for toggle recording and hold-to-talk
- STT providers:
  - Local `whisper-cli`
  - OpenAI endpoint
  - Groq endpoint
- Optional LLM transcript cleanup (OpenAI or Groq)
- Optional FFmpeg silence trimming
- Clipboard copy always + optional auto-paste (`Cmd+V` simulation)
- Optional clipboard restore after auto-paste
- Optional start-at-login toggle (when running as a bundled `.app`)

## Dependencies (Homebrew)

```bash
brew install ffmpeg whisper-cpp
```

Expected default paths:

- `ffmpeg`: `/opt/homebrew/bin/ffmpeg` (Apple Silicon)
- `whisper-cli`: `/opt/homebrew/bin/whisper-cli` (Apple Silicon)

If your binaries are elsewhere, set paths in app Settings.

## Run

```bash
swift run OpenWispr
```

When running via `swift run`, macOS notifications are disabled automatically because this runtime is not a bundled `.app`.

## Permissions

For full behavior parity, macOS may prompt for:

- Microphone access
- Accessibility access (for synthetic paste)

Start at login requires running the packaged `.app` (not `swift run`).

## Local model

For local STT, point `Local Model Path` to a valid Whisper model file, for example:

`~/openwispr/models/ggml-base.en.bin`

## Notes

- This is a standalone macOS app codebase (not a GNOME extension port-in-place).
- API keys are currently stored in user defaults for speed of iteration.

## Packaging

Create an unsigned drag-and-drop `.dmg` locally:

```bash
./scripts/package-release.sh v0.1.0
```

Artifacts are written to `dist/`:

- `OpenWispr-<tag>-macos-<arch>.dmg`
- `OpenWispr-<tag>-macos-<arch>.dmg.sha256`

The app icon is generated from `openwispr.png` during packaging.

This package is intentionally unsigned for now.

## GitHub Releases

Pushing any git tag triggers `.github/workflows/release.yml`, which will:

- build the release package
- upload it as a workflow artifact
- create/update a GitHub Release for that tag with the dmg + checksum attached

## Tests and Linting

```bash
./scripts/lint.sh
./scripts/test.sh
./scripts/check.sh
```

- `scripts/lint.sh`: strict style checks using `swift format lint`.
- `scripts/test.sh`: self-test executable for shortcut parsing, response parsing, and path resolution.
- `scripts/check.sh`: lint + build + tests in one command.
