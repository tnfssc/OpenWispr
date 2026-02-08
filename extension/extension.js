import Clutter from 'gi://Clutter';
import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
import Meta from 'gi://Meta';
import Shell from 'gi://Shell';
import St from 'gi://St';

import * as Main from 'resource:///org/gnome/shell/ui/main.js';
import * as PanelMenu from 'resource:///org/gnome/shell/ui/panelMenu.js';
import { Extension, gettext as _ } from 'resource:///org/gnome/shell/extensions/extension.js';

// Constants
const OUTPUT_FILE = GLib.get_tmp_dir() + '/openwispr_recording.wav';
const WHISPER_BINARY = GLib.find_program_in_path('whisper-cli') || '/usr/bin/whisper-cli';

export default class OpenWisprExtension extends Extension {
    enable() {
        this._settings = this.getSettings();
        this._recording = false;
        this._processing = false;
        this._recordingTrigger = null;
        this._recordProc = null;
        this._holdKeyPressed = false;
        this._holdStartCooldownUntilUs = 0;
        this._holdToSpeakEnabled = this._settings.get_boolean('hold-to-speak-enabled');
        this._holdToSpeakTrigger = this._settings.get_string('hold-to-speak-trigger');
        this._autoPasteEnabled = this._settings.get_boolean('auto-paste-enabled');

        // resolve paths relative to extension dir
        this._modelPath = this.dir.get_child('models').get_child('ggml-base.en.bin').get_path();
        this._recorderScript = this.dir.get_child('scripts').get_child('record_audio.js').get_path();

        // UI: Panel Indicator
        this._indicator = new PanelMenu.Button(0.0, this.metadata.name, false);
        this._icon = new St.Icon({
            icon_name: 'microphone-sensitivity-high-symbolic',
            style_class: 'system-status-icon',
        });
        this._indicator.add_child(this._icon);
        
        // Click to toggle
        this._indicator.connect('button-press-event', () => {
            this._toggleRecording();
            return Clutter.EVENT_PROPAGATE;
        });

        Main.panel.addToStatusArea(this.uuid, this._indicator);

        // Global Shortcut
        Main.wm.addKeybinding(
            'toggle-recording',
            this._settings,
            Meta.KeyBindingFlags.NONE,
            Shell.ActionMode.ALL,
            () => this._toggleRecording()
        );

        this._capturedEventId = global.stage.connect(
            'captured-event',
            this._onCapturedEvent.bind(this)
        );

        this._holdToSpeakChangedId = this._settings.connect('changed::hold-to-speak-enabled', () => {
            this._holdToSpeakEnabled = this._settings.get_boolean('hold-to-speak-enabled');

            if (!this._holdToSpeakEnabled) {
                this._holdKeyPressed = false;

                if (this._recording && this._recordingTrigger === 'hold')
                    this._stopRecording(true);
            }
        });

        this._holdToSpeakTriggerChangedId = this._settings.connect('changed::hold-to-speak-trigger', () => {
            this._holdToSpeakTrigger = this._settings.get_string('hold-to-speak-trigger');
        });

        this._autoPasteChangedId = this._settings.connect('changed::auto-paste-enabled', () => {
            this._autoPasteEnabled = this._settings.get_boolean('auto-paste-enabled');
        });

        console.log(`[openwispr-gnome-extension] Enabled. Model: ${this._modelPath}`);
    }

    disable() {
        this._stopRecording(false); // Force stop without transcription if disabling

        if (this._capturedEventId) {
            global.stage.disconnect(this._capturedEventId);
            this._capturedEventId = null;
        }

        if (this._settings && this._holdToSpeakChangedId) {
            this._settings.disconnect(this._holdToSpeakChangedId);
            this._holdToSpeakChangedId = null;
        }

        if (this._settings && this._holdToSpeakTriggerChangedId) {
            this._settings.disconnect(this._holdToSpeakTriggerChangedId);
            this._holdToSpeakTriggerChangedId = null;
        }

        if (this._settings && this._autoPasteChangedId) {
            this._settings.disconnect(this._autoPasteChangedId);
            this._autoPasteChangedId = null;
        }
        
        if (this._indicator) {
            this._indicator.destroy();
            this._indicator = null;
        }

        this._icon = null;

        Main.wm.removeKeybinding('toggle-recording');
        this._settings = null;
    }

    _toggleRecording() {
        if (this._recording) {
            this._stopRecording(true);
        } else {
            this._startRecording('toggle');
        }
    }

    _onCapturedEvent(_actor, event) {
        if (!this._holdToSpeakEnabled)
            return Clutter.EVENT_PROPAGATE;

        const eventType = event.type();
        if (eventType !== Clutter.EventType.KEY_PRESS && eventType !== Clutter.EventType.KEY_RELEASE)
            return Clutter.EVENT_PROPAGATE;

        const keySymbol = event.get_key_symbol();
        const modifiers = event.get_state();
        const nowUs = GLib.get_monotonic_time();

        if (eventType === Clutter.EventType.KEY_PRESS) {
            if (this._recording || this._processing)
                return Clutter.EVENT_PROPAGATE;

            if (nowUs < this._holdStartCooldownUntilUs)
                return Clutter.EVENT_PROPAGATE;

            if (!this._holdKeyPressed && this._isHoldToSpeakPressEvent(keySymbol, modifiers)) {
                this._holdKeyPressed = true;
                this._startRecording('hold');
            }
        } else if (eventType === Clutter.EventType.KEY_RELEASE) {
            if (this._isHoldToSpeakReleaseEvent(keySymbol)) {
                this._holdKeyPressed = false;

                if (this._recording && this._recordingTrigger === 'hold')
                    this._stopRecording(true);
            }

            if (!this._recording)
                return Clutter.EVENT_PROPAGATE;
        }

        return Clutter.EVENT_PROPAGATE;
    }

    _isHoldToSpeakChordPress(keySymbol, modifiers) {
        const isSlashKey = keySymbol === Clutter.KEY_slash || keySymbol === Clutter.KEY_KP_Divide;
        const hasControlModifier = Boolean(modifiers & Clutter.ModifierType.CONTROL_MASK);

        return isSlashKey && hasControlModifier;
    }

    _isHoldToSpeakCtrlSpacePress(keySymbol, modifiers) {
        return keySymbol === Clutter.KEY_space && Boolean(modifiers & Clutter.ModifierType.CONTROL_MASK);
    }

    _isHoldToSpeakPressEvent(keySymbol, modifiers) {
        if (this._holdToSpeakTrigger === 'right-ctrl')
            return keySymbol === Clutter.KEY_Control_R;

        if (this._holdToSpeakTrigger === 'f8')
            return keySymbol === Clutter.KEY_F8;

        if (this._holdToSpeakTrigger === 'f9')
            return keySymbol === Clutter.KEY_F9;

        if (this._holdToSpeakTrigger === 'ctrl-space')
            return this._isHoldToSpeakCtrlSpacePress(keySymbol, modifiers);

        return this._isHoldToSpeakChordPress(keySymbol, modifiers);
    }

    _isHoldToSpeakReleaseEvent(keySymbol) {
        if (this._holdToSpeakTrigger === 'right-ctrl')
            return keySymbol === Clutter.KEY_Control_R;

        if (this._holdToSpeakTrigger === 'f8')
            return keySymbol === Clutter.KEY_F8;

        if (this._holdToSpeakTrigger === 'f9')
            return keySymbol === Clutter.KEY_F9;

        if (this._holdToSpeakTrigger === 'ctrl-space')
            return keySymbol === Clutter.KEY_space || keySymbol === Clutter.KEY_Control_L || keySymbol === Clutter.KEY_Control_R;

        return keySymbol === Clutter.KEY_slash || keySymbol === Clutter.KEY_KP_Divide ||
            keySymbol === Clutter.KEY_Control_L || keySymbol === Clutter.KEY_Control_R;
    }

    _startRecording(trigger = 'toggle') {
        if (this._recording || this._processing)
            return;

        console.log(`[openwispr-gnome-extension] Starting recording (${trigger})...`);
        this._recording = true;
        this._recordingTrigger = trigger;
        this._icon.icon_name = 'media-record-symbolic';
        this._icon.style_class = 'system-status-icon destructive-action'; // Red-ish if theme supports

        try {
            // Spawn gjs -m script
            // Note: record_audio.js writes to hardcoded 'test_output.wav' in current dir in prototype
            // We should update it to accept an argument, but for now let's use the prototype's default or PWD
            // Actually, best to update the script to write to OUTPUT_FILE. 
            // For now, let's pass cwd to the subprocess so it writes there, or modify the script.
            // Let's assume we modify the script to take a filename argument. 
            // I'll update the script in a separate step if needed. 
            // For now, I'll assume standard gjs execution.
            
            // To be safe, I'll update the script to use the first argument as filename
            // and default to OUTPUT_FILE if not provided.
            // But let's pass the logic here.
            
            const proc = new Gio.Subprocess({
                argv: ['gjs', '-m', this._recorderScript, OUTPUT_FILE],
                flags: Gio.SubprocessFlags.NONE
            });
            
            proc.init(null);
            this._recordProc = proc;
            
        } catch (e) {
            console.error(`[openwispr-gnome-extension] Failed to start recorder: ${e}`);
            this._resetState();
        }
    }

    _stopRecording(transcribe = true) {
        if (!this._recording) return;

        console.log(`[openwispr-gnome-extension] Stopping recording (${this._recordingTrigger ?? 'unknown'})...`);
        if (this._recordingTrigger === 'hold')
            this._holdStartCooldownUntilUs = GLib.get_monotonic_time() + 750000;

        this._recording = false;
        this._processing = true;
        this._icon.icon_name = 'process-working-symbolic'; // Spinner
        
        if (this._recordProc) {
            // Send SIGINT (2) to stop recording cleanly
            this._recordProc.send_signal(2);
            
            // Wait for it to exit
            this._recordProc.wait_async(null, (proc, res) => {
                try {
                    proc.wait_finish(res);
                    console.log('[openwispr-gnome-extension] Recorder exited.');
                    if (transcribe) {
                        this._transcribe();
                    } else {
                        this._resetState();
                    }
                } catch (e) {
                    console.error(`[openwispr-gnome-extension] Recorder wait failed: ${e}`);
                    this._resetState();
                }
            });
            this._recordProc = null;
        } else {
            this._resetState();
        }
    }

    _transcribe() {
        console.log('[openwispr-gnome-extension] Transcribing...');
        
        // The recorder script currently writes to "test_output.wav" in CWD.
        // We need to know where that is. Since extension runs in gnome-shell, CWD is often home.
        // I will update the recorder script to use an absolute path to be safe.
        // I'll assume the updated script uses: /tmp/openwispr_recording.wav (OUTPUT_FILE)
        
        try {
            const proc = new Gio.Subprocess({
                argv: [
                    WHISPER_BINARY,
                    '-m', this._modelPath,
                    '-f', OUTPUT_FILE,
                    '-otxt',
                    '-np',
                    '-nt'
                ],
                flags: Gio.SubprocessFlags.STDOUT_PIPE | Gio.SubprocessFlags.STDERR_PIPE
            });
            
            proc.init(null);
            
            proc.communicate_utf8_async(null, null, (proc, res) => {
                try {
                    const [, stdout, stderr] = proc.communicate_utf8_finish(res);
                    
                    if (!proc.get_successful()) {
                        console.error(`[openwispr-gnome-extension] Transcription failed: ${stderr}`);
                        Main.notify('openwispr-gnome-extension Error', 'Transcription failed.');
                        this._resetState();
                        return;
                    }

                    // Success! 
                    // whisper-cli with -otxt creates <file>.txt. 
                    // But we can also just capture stdout if we remove -otxt?
                    // Let's try to just read the file for reliability.
                    const txtPath = OUTPUT_FILE + '.txt'; 
                    const file = Gio.File.new_for_path(txtPath);
                    
                    file.load_contents_async(null, (file, res) => {
                        try {
                            const [, contents] = file.load_contents_finish(res);
                            const text = new TextDecoder().decode(contents).trim();
                            console.log(`[openwispr-gnome-extension] Text: ${text}`);
                            
                            if (text) {
                                Main.notify('openwispr-gnome-extension', `Transcribed: ${text}`);
                                this._injectText(text);
                            } else {
                                Main.notify('openwispr-gnome-extension', 'No speech detected.');
                            }
                        } catch (e) {
                            console.error(`[openwispr-gnome-extension] Failed to read output: ${e}`);
                        }
                        this._resetState();
                    });

                } catch (e) {
                    console.error(`[openwispr-gnome-extension] Transcribe error: ${e}`);
                    this._resetState();
                }
            });
            
        } catch (e) {
            console.error(`[openwispr-gnome-extension] Failed to launch whisper: ${e}`);
            this._resetState();
        }
    }

    _injectText(text) {
        // Clutter Virtual Input
        try {
            const clipboard = St.Clipboard.get_default();
            clipboard.set_text(St.ClipboardType.CLIPBOARD, text);

            if (!this._autoPasteEnabled) {
                Main.notify('openwispr-gnome-extension', 'Transcription copied to clipboard.');
                return;
            }

            const seat = Clutter.get_default_backend().get_default_seat();
            const virtualDevice = seat.create_virtual_device(Clutter.InputDeviceType.KEYBOARD_DEVICE);
            const now = () => GLib.get_monotonic_time() / 1000;
            
            let time = now();
            
            // Simulate Ctrl+V
            // Ctrl Press
            virtualDevice.notify_keyval(time++, Clutter.KEY_Control_L, Clutter.KeyState.PRESSED);
            // V Press
            virtualDevice.notify_keyval(time++, Clutter.KEY_v, Clutter.KeyState.PRESSED);
            // V Release
            virtualDevice.notify_keyval(time++, Clutter.KEY_v, Clutter.KeyState.RELEASED);
            // Ctrl Release
            virtualDevice.notify_keyval(time++, Clutter.KEY_Control_L, Clutter.KeyState.RELEASED);
            
            console.log('[openwispr-gnome-extension] Text injected via Clipboard Paste');
            
        } catch (e) {
            console.error(`[openwispr-gnome-extension] Injection failed: ${e}`);
            Main.notify('openwispr-gnome-extension', `Copied to clipboard: ${text}`);
        }
    }

    _resetState() {
        this._recording = false;
        this._processing = false;
        this._recordingTrigger = null;
        this._recordProc = null;
        if (this._icon) {
            this._icon.icon_name = 'microphone-sensitivity-high-symbolic';
            this._icon.style_class = 'system-status-icon';
        }
    }
}
