# <img width="30" height="30" alt="image" src="https://github.com/user-attachments/assets/dec4aad1-b1d7-43a2-b8eb-6b7c12ea3d1a" /> openwispr-gnome-extension

[![Gnome Extensions](https://img.shields.io/badge/Install-Now-4a86cf?style=for-the-badge&logo=gnome)](https://extensions.gnome.org/extension/9314/openwispr-gnome-extension/)

Also checkout [OpenWispr for macOS](https://github.com/tnfssc/OpenWispr)

<img width="160" alt="openwispr logo" src="./openwispr.png" />

**openwispr-gnome-extension** is an AI-powered voice-to-text dictation extension for GNOME Shell. It supports local AI models via `whisper-cli` or remote STT endpoints to provide private, fast, and accurate speech recognition directly into any application.

<img width="429" height="255" alt="screenshot" src="https://github.com/user-attachments/assets/6a1856a2-228f-434a-8319-5386ec1b4cf0" />

## Features

- **Local or Remote Processing**: Use local `whisper-cli` or remote STT endpoints (OpenAI/Groq), configurable per setup.
- **Optional Keyboard Shortcuts**: Configure your own shortcut for toggle recording in preferences.
- **Hold to Speak**: Optional mode to hold `Ctrl+Alt+T` to record and release to transcribe.
- **Silence Cutting**: Uses `ffmpeg` to cut silent parts before transcription.
- **Configurable STT Backends**: Choose local `whisper-cli`, OpenAI Whisper endpoint, or Groq endpoint.
- **LLM Transcript Cleanup**: Optionally post-process transcript text with OpenAI or Groq models.
- **Companion Engine + Daemon**: Uses `openwispr` user services over D-Bus for recording/transcription and optional hold daemon shortcuts.
- **System Integration**: Seamless integration with the GNOME top bar.
- **Clipboard Injection**: Automatically pastes transcribed text into the active text field.
- **Clipboard Restore**: Optionally restore original clipboard content after auto-paste (enabled by default).
- **Clipboard-Only Mode**: Optionally copy transcription without auto-paste for apps where paste is unsafe.
- **Configurable Notifications**: Optionally enable or disable extension notifications.

## Prerequisites

Before installing, ensure you have the following dependencies:

1.  **GNOME Shell**: Supported versions 45 - 50.
2.  **whisper-cli** (local STT only): The command-line interface for the Whisper model.
    *   Required only if you choose local transcription. Skip it if you use Remote STT via OpenAI or Groq below.
    *   Ensure `whisper-cli` is installed and available in your system `PATH`.
    *   *Note: This extension expects the `whisper-cli` binary specifically.*
3.  **ffmpeg**: Required for silence trimming.
4.  **go** (optional): Needed only to build the companion `openwispr` binary from source.

## Choose Your STT Setup

### Local STT (`whisper-cli`)

Use this path if you want on-device transcription.

1.  **Build `whisper-cli` from source (recommended, works on most Linux distros)**
    ```bash
    git clone https://github.com/ggerganov/whisper.cpp.git
    cd whisper.cpp
    cmake -B build -DWHISPER_BUILD_EXAMPLES=ON
    cmake --build build -j
    mkdir -p ~/.local/bin
    install -Dm755 build/bin/whisper-cli ~/.local/bin/whisper-cli
    ```

2.  **Verify install**
    ```bash
    whisper-cli --help
    which whisper-cli
    ```

3.  **Add `~/.local/bin` to your shell PATH if needed**
    ```bash
    echo 'export PATH="$HOME/.local/bin:$PATH"' >> ~/.bashrc
    # or ~/.zshrc if you use zsh
    source ~/.bashrc
    # or source ~/.zshrc
    ```

4.  **Make `whisper-cli` visible to the GNOME user session**
    Use one of these options after updating your shell PATH:

    ```bash
    systemctl --user import-environment PATH
    systemctl --user restart openwispr-engine.service
    ```

    Or add an explicit PATH to the user service unit:

    ```ini
    Environment=PATH=%h/.local/bin:/usr/local/bin:/usr/bin
    ```

    Then reload and restart the service:

    ```bash
    systemctl --user daemon-reload
    systemctl --user restart openwispr-engine.service
    ```

5.  **Download a GGML-compatible model**
    After cloning the repository and switching into the project root during installation, download a Whisper model file into `extension/models/`.

    ```bash
    mkdir -p extension/models
    # Example: Download base.en model (adjust URL as needed for your preferred model source)
    wget https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin -O extension/models/ggml-base.en.bin
    ```

### Remote STT (OpenAI or Groq)

Use this path if you want to skip local Whisper binaries and models.

1.  Set the STT provider to OpenAI or Groq in extension preferences.
2.  Configure the relevant API key and endpoint settings.
3.  Skip the local `whisper-cli` and model download steps above.

## Installation

1.  **Clone the Repository**
    ```bash
    git clone https://github.com/tnfssc/openwispr-gnome-extension.git
    cd openwispr-gnome-extension
    ```

2.  **Install the Extension**
    Run the included installation script to symlink the extension to your GNOME extensions directory.
    ```bash
    ./install.sh
    ```

3.  **Restart GNOME Shell**
    *   **Wayland**: Log out and log back in.
    *   **X11**: Press `Alt+F2`, type `r`, and press Enter.

4.  **Enable the Extension**
    ```bash
    gnome-extensions enable openwispr-gnome-extension@tnfssc.github.com
    ```

## Usage

1.  **Start Dictation**: Click the microphone icon in the top bar, or set a custom keyboard shortcut in preferences.
    *   The icon will change to a recording indicator.
    *   You can also hold `Ctrl+Alt+T`.
2.  **Speak**: Dictate your text clearly.
3.  **Stop & Transcribe**: Press `Ctrl+Alt+R` again to stop.
    *   If using hold-to-speak, just release the hold key/chord.
    *   The extension trims silence with ffmpeg (if enabled), transcribes, then optionally runs LLM cleanup.
    *   Once complete, the text will be automatically pasted into your active window and copied to your clipboard.

### Companion CLI

The installer builds `~/.local/bin/openwispr` (when `go` is installed).

### Install Companion From GitHub Releases

If you installed the GNOME extension from extensions.gnome.org, you can install the companion without building from source:

```bash
ARCH="$(uname -m)"
case "$ARCH" in
  x86_64) BIN="openwispr-linux-amd64" ;;
  aarch64|arm64) BIN="openwispr-linux-arm64" ;;
  *) echo "Unsupported architecture: $ARCH"; exit 1 ;;
esac

REPO="https://github.com/tnfssc/openwispr-gnome-extension/releases/latest/download"
TMP="$(mktemp -d)"

mkdir -p ~/.local/bin ~/.config/systemd/user ~/.local/share/applications ~/.local/share/icons/hicolor/256x256/apps
curl -fsSL "$REPO/${BIN}.tar.gz" -o "$TMP/${BIN}.tar.gz"
tar -xzf "$TMP/${BIN}.tar.gz" -C "$TMP"
install -Dm755 "$TMP/$BIN" ~/.local/bin/openwispr

curl -fsSL "$REPO/openwispr-engine.service" -o ~/.config/systemd/user/openwispr-engine.service
curl -fsSL "$REPO/openwispr-hotkeyd.service" -o ~/.config/systemd/user/openwispr-hotkeyd.service
curl -fsSL "$REPO/io.github.tnfssc.openwispr.desktop" -o ~/.local/share/applications/io.github.tnfssc.openwispr.desktop
curl -fsSL "$REPO/openwispr.png" -o ~/.local/share/icons/hicolor/256x256/apps/io.github.tnfssc.openwispr.png

systemctl --user daemon-reload
systemctl --user enable --now openwispr-engine.service
```

Optional hold-to-talk daemon:

```bash
systemctl --user enable --now openwispr-hotkeyd.service
```

If you need to build manually:

```bash
mkdir -p ~/.local/bin
go build -o ~/.local/bin/openwispr ./cmd/openwispr
```

```bash
openwispr toggle
openwispr start
openwispr stop
openwispr status
openwispr doctor
openwispr restart
openwispr engine
```

Quick DBus check (extension must be enabled):

```bash
gdbus call --session --dest org.gnome.Shell.Extensions.OpenWispr --object-path /org/gnome/Shell/Extensions/OpenWispr --method org.gnome.Shell.Extensions.OpenWispr.Status
```

If you just changed extension code and DBus is still missing, log out and back in once to fully restart GNOME Shell.

The extension now talks to the companion engine over D-Bus for recording/transcription. Ensure this service is active:

```bash
systemctl --user enable --now openwispr-engine.service
```

### Hold Daemon (Super + Z)

For more reliable hold-to-talk behavior, use the companion daemon:

```bash
systemctl --user enable --now openwispr-hotkeyd.service
```

The default service runs:

```bash
openwispr daemon --backend auto --trigger <Super>z --evdev-key z
```

`auto` tries the portal backend first, then falls back to evdev if needed.

If you switch to modifier-only triggers (like `Alt_R`) and release detection is unreliable, GNOME may emit `Activated` without `Deactivated`.

To install/update the service manually:

```bash
mkdir -p ~/.config/systemd/user
cp companion/openwispr-engine.service ~/.config/systemd/user/
cp companion/openwispr-hotkeyd.service ~/.config/systemd/user/
mkdir -p ~/.local/share/applications
cp companion/io.github.tnfssc.openwispr.desktop ~/.local/share/applications/
mkdir -p ~/.local/share/icons/hicolor/256x256/apps
cp openwispr.png ~/.local/share/icons/hicolor/256x256/apps/io.github.tnfssc.openwispr.png
systemctl --user daemon-reload
systemctl --user enable --now openwispr-engine.service
systemctl --user enable --now openwispr-hotkeyd.service
```

The desktop entry is required so GNOME GlobalShortcuts can associate a valid app ID (`io.github.tnfssc.openwispr`) with the daemon.

Portal-only test run:

```bash
openwispr daemon --backend portal --trigger <Super>z
```

If `openwispr doctor` reports missing `org.freedesktop.portal.GlobalShortcuts`, start the GNOME portal backend and restart the daemon:

```bash
systemctl --user start xdg-desktop-portal-gnome.service
systemctl --user restart xdg-desktop-portal.service
systemctl --user restart openwispr-hotkeyd.service
```

Quick self-heal command (recommended):

```bash
openwispr restart
```

If evdev fallback is needed, ensure input permissions (example for current user):

```bash
sudo usermod -aG input "$USER"
# then log out and back in
```

Note: the GitHub Release companion binaries are built with `CGO_ENABLED=0` for portability, so evdev fallback is disabled there. Build from source on Linux with cgo enabled if you need evdev fallback.

> Note: Some apps (especially terminals, password fields, or secure/sandboxed inputs) may block simulated paste events. In those cases, use clipboard paste manually.

## Configuration

The extension ships with a default toggle shortcut. You can change it in preferences, with `dconf-editor`, or by modifying the schema:
*   **Schema**: `org.gnome.shell.extensions.openwispr`
*   **Key**: `toggle-recording`

Hold-to-speak can be enabled/disabled in extension preferences or via:
*   **Schema**: `org.gnome.shell.extensions.openwispr`
*   **Key**: `hold-to-speak-enabled`

Hold-to-speak shortcut can be configured in extension preferences or via:
*   **Schema**: `org.gnome.shell.extensions.openwispr`
*   **Key**: `hold-to-speak-keybinding`

For GNOME Settings > Keyboard > Custom Shortcuts, you can set:
*   **Name**: `openwispr-toggle`
*   **Command**: `openwispr toggle`

For start/stop split bindings (optional):
*   **Name**: `openwispr-start`
*   **Command**: `openwispr start`
*   **Name**: `openwispr-stop`
*   **Command**: `openwispr stop`

Auto-paste behavior can be toggled in extension preferences or via:
*   **Schema**: `org.gnome.shell.extensions.openwispr`
*   **Key**: `auto-paste-enabled`

Clipboard restore behavior can be toggled in extension preferences or via:
*   **Schema**: `org.gnome.shell.extensions.openwispr`
*   **Key**: `restore-clipboard-enabled`

Notification behavior can be toggled in extension preferences or via:
*   **Schema**: `org.gnome.shell.extensions.openwispr`
*   **Key**: `notifications-enabled`

Remote STT and LLM keys/endpoints are configurable in extension preferences. Relevant schema keys include:
*   **STT**: `stt-provider`, `stt-openai-*`, `stt-groq-*`
*   **LLM**: `llm-filter-enabled`, `llm-provider`, `llm-openai-*`, `llm-groq-*`, `llm-cleanup-prompt`
*   **FFmpeg**: `silence-trim-enabled`, `silence-threshold`, `silence-duration`

## Testing

LLM cleanup unit tests (request payload + response parsing):

```bash
go test ./cmd/openwispr
```

Optional live LLM cleanup tests (requires explicit env vars and network access):

```bash
OPENWISPR_TEST_OPENAI_ENDPOINT="https://api.openai.com/v1/chat/completions" \
OPENWISPR_TEST_OPENAI_MODEL="gpt-4o-mini" \
OPENWISPR_TEST_OPENAI_API_KEY="..." \
OPENWISPR_TEST_GROQ_ENDPOINT="https://api.groq.com/openai/v1/chat/completions" \
OPENWISPR_TEST_GROQ_MODEL="llama-3.1-8b-instant" \
OPENWISPR_TEST_GROQ_API_KEY="..." \
go test -tags=integration ./cmd/openwispr -run TestLiveCleanupTranscript -v
```

Live prompt benchmark against Groq (compares multiple prompt candidates and prints outputs/scores):

```bash
OPENWISPR_TEST_GROQ_ENDPOINT="https://api.groq.com/openai/v1/chat/completions" \
OPENWISPR_TEST_GROQ_MODEL="openai/gpt-oss-120b" \
OPENWISPR_TEST_GROQ_API_KEY="..." \
go test -tags=integration ./cmd/openwispr -run TestLivePromptBenchmarkGroq -v
```

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
