# openwispr-gnome-extension Research Report

## Objective
Build a Linux-native (Arch/GNOME/Wayland) alternative to Superwhisper using GJS.

## Feasibility Confirmation
✅ **Audio Recording**: Validated. GStreamer + PipeWire works natively in GJS.
✅ **AI Engine**: Validated. `whisper.cpp` runs efficiently and is installed.
✅ **Global Hotkeys**: Validated. GNOME Shell Extensions can intercept keys globally.
⚠️ **Text Injection**: Requires privileged access. The Shell Extension architecture allows this via internal APIs (Clutter/Mutter) or by spawning a helper (like `ydotool`).

## Architecture Recommendation

**1. The Brain: GNOME Shell Extension**
- **Role**: Main Controller.
- **Responsibilities**:
  - Registers Global Hotkey (`Ctrl+Alt+R`).
  - Shows Status Indicator (Panel Icon).
  - Spawns the Recorder subprocess.
  - Spawns the Transcriber subprocess.
  - Injects the resulting text.

**2. The Ears: Audio Recorder (`record_audio.js`)**
- **Role**: Helper Script.
- **Responsibilities**:
  - Connects to PipeWire source.
  - Records to WAV.
  - Handles SIGINT to close file cleanly.

**3. The Mind: Whisper (`whisper-cli`)**
- **Role**: AI Engine.
- **Responsibilities**:
  - Converts WAV to Text.
  - Runs locally on CPU/GPU.

## Prototype Artifacts
The `prototypes/` directory contains working code:
- `record_audio.js`: Captures microphone input.
- `run_pipeline.sh`: Demonstrates the full Record -> Stop -> Transcribe flow.
- `extension/`: A "skeleton" extension structure ready for development.

## Next Steps (Implementation)

1. **Develop the Extension Logic**:
   - Port the `run_pipeline.sh` logic into `extension.js` using `Gio.Subprocess`.
   - Instead of `read -r` (waiting for Enter), use the hotkey toggle state.

2. **Implement Text Injection**:
   - **Strategy**: Use `Clutter.VirtualInputDevice` directly in the extension. This avoids external dependencies like `ydotool` and works natively on Wayland.
   - **Code Reference**:
     ```javascript
     const virtualKeyboard = Clutter.get_default_backend()
         .get_default_seat()
         .create_virtual_device(Clutter.InputDeviceType.KEYBOARD_DEVICE);
     
     virtualKeyboard.notify_keyval(
         Clutter.get_current_event_time() * 1000,
         Clutter.KEY_h,
         Clutter.KeyState.PRESSED
     );
     ```

## Setup Requirements for User
- `gstreamer`, `gst-plugins-base`, `gst-plugin-pipewire` (Installed)
- `whisper.cpp` (Installed)
- `gnome-shell-extensions` (Development tools)

## Architectural Deep Dive (Updated)
We investigated "Standalone App" vs "Shell Extension" for GNOME 45+:
- **Standalone App**: Not viable. Tools like `wtype` fail on GNOME (Mutter rejects virtual-keyboard protocol). `ydotool` requires a root daemon.
- **Shell Extension**: The correct path. It has privileged access to input via `Clutter` and native global shortcuts.
