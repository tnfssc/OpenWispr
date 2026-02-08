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
const WHISPER_BINARY = '/usr/bin/whisper-cli';
const OUTPUT_FILE = GLib.get_tmp_dir() + '/openwispr_recording.wav';

export default class OpenWisprExtension extends Extension {
    enable() {
        this._settings = this.getSettings();
        this._recording = false;
        this._recordProc = null;

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

        console.log(`[openwispr-gnome-extension] Enabled. Model: ${this._modelPath}`);
    }

    disable() {
        this._stopRecording(false); // Force stop without transcription if disabling
        
        if (this._indicator) {
            this._indicator.destroy();
            this._indicator = null;
        }

        Main.wm.removeKeybinding('toggle-recording');
        this._settings = null;
    }

    _toggleRecording() {
        if (this._recording) {
            this._stopRecording(true);
        } else {
            this._startRecording();
        }
    }

    _startRecording() {
        if (this._recording) return;

        console.log('[openwispr-gnome-extension] Starting recording...');
        this._recording = true;
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

        console.log('[openwispr-gnome-extension] Stopping recording...');
        this._recording = false;
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
            const seat = Clutter.get_default_backend().get_default_seat();
            const virtualDevice = seat.create_virtual_device(Clutter.InputDeviceType.KEYBOARD_DEVICE);
            
            const now = () => GLib.get_monotonic_time() / 1000;
            
            for (const char of text) {
                // This is a simplification. Clutter.KEY_* are needed.
                // Converting arbitrary string to keycodes is hard without a map.
                // Hack: Copy to clipboard and Paste?
                // Or use GLib.unichar_to_utf8() ? No that's char to bytes.
                // Clutter virtual device needs KEYVALS.
                // 
                // For a robust "Superwhisper" experience, "Paste" is often safest for blocks of text.
                // Let's try Clipboard + Ctrl-V.
            }

            // Strategy: Clipboard + Paste
            const clipboard = St.Clipboard.get_default();
            clipboard.set_text(St.ClipboardType.CLIPBOARD, text);
            
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
        this._recordProc = null;
        if (this._icon) {
            this._icon.icon_name = 'microphone-sensitivity-high-symbolic';
            this._icon.style_class = 'system-status-icon';
        }
    }
}