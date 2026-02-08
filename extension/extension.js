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
const TRIMMED_OUTPUT_FILE = GLib.get_tmp_dir() + '/openwispr_recording_trimmed.wav';
const WHISPER_BINARY = GLib.find_program_in_path('whisper-cli') || '/usr/bin/whisper-cli';
const FFMPEG_BINARY = GLib.find_program_in_path('ffmpeg') || '/usr/bin/ffmpeg';
const CURL_BINARY = GLib.find_program_in_path('curl') || '/usr/bin/curl';

export default class OpenWisprExtension extends Extension {
    enable() {
        this._settings = this.getSettings();
        this._migrateLegacyProviderNames();
        this._recording = false;
        this._processing = false;
        this._recordingTrigger = null;
        this._recordProc = null;
        this._holdKeyPressed = false;
        this._holdStartCooldownUntilUs = 0;
        this._holdToSpeakEnabled = this._settings.get_boolean('hold-to-speak-enabled');
        this._autoPasteEnabled = this._settings.get_boolean('auto-paste-enabled');
        this._notificationsEnabled = this._settings.get_boolean('notifications-enabled');

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

        this._autoPasteChangedId = this._settings.connect('changed::auto-paste-enabled', () => {
            this._autoPasteEnabled = this._settings.get_boolean('auto-paste-enabled');
        });

        this._notificationsChangedId = this._settings.connect('changed::notifications-enabled', () => {
            this._notificationsEnabled = this._settings.get_boolean('notifications-enabled');
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

        if (this._settings && this._autoPasteChangedId) {
            this._settings.disconnect(this._autoPasteChangedId);
            this._autoPasteChangedId = null;
        }

        if (this._settings && this._notificationsChangedId) {
            this._settings.disconnect(this._notificationsChangedId);
            this._notificationsChangedId = null;
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
            if (this._isHoldToSpeakReleaseEvent(keySymbol, modifiers)) {
                this._holdKeyPressed = false;

                if (this._recording && this._recordingTrigger === 'hold')
                    this._stopRecording(true);
            }

            if (!this._recording)
                return Clutter.EVENT_PROPAGATE;
        }

        return Clutter.EVENT_PROPAGATE;
    }

    _isHoldToSpeakPressEvent(keySymbol, modifiers) {
        const hasControlModifier = Boolean(modifiers & Clutter.ModifierType.CONTROL_MASK);
        const hasAltModifier = Boolean(modifiers & Clutter.ModifierType.MOD1_MASK);

        return keySymbol === Clutter.KEY_space && hasControlModifier && hasAltModifier;
    }

    _isHoldToSpeakReleaseEvent(keySymbol, modifiers) {
        const releasedHoldKey =
            keySymbol === Clutter.KEY_space ||
            keySymbol === Clutter.KEY_Control_L ||
            keySymbol === Clutter.KEY_Control_R ||
            keySymbol === Clutter.KEY_Alt_L ||
            keySymbol === Clutter.KEY_Alt_R ||
            keySymbol === Clutter.KEY_Meta_L ||
            keySymbol === Clutter.KEY_Meta_R;

        const hasControlModifier = Boolean(modifiers & Clutter.ModifierType.CONTROL_MASK);
        const hasAltModifier = Boolean(modifiers & Clutter.ModifierType.MOD1_MASK);

        return releasedHoldKey || !hasControlModifier || !hasAltModifier;
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
                        this._processRecordingPipeline();
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

    _processRecordingPipeline() {
        this._trimSilence(OUTPUT_FILE, (processedPath) => {
            this._transcribeAudio(processedPath, (transcript) => {
                if (transcript === null) {
                    this._resetState();
                    return;
                }

                this._cleanupTranscript(transcript, (cleanedText) => {
                    const finalText = (cleanedText ?? transcript ?? '').trim();
                    console.log(`[openwispr-gnome-extension] Text: ${finalText}`);

                    if (finalText) {
                        this._notify(`Transcribed: ${finalText}`);
                        this._injectText(finalText);
                    } else {
                        this._notify('No speech detected.');
                    }

                    this._resetState();
                });
            });
        });
    }

    _trimSilence(inputPath, callback) {
        if (!this._settings.get_boolean('silence-trim-enabled')) {
            callback(inputPath);
            return;
        }

        if (!GLib.file_test(FFMPEG_BINARY, GLib.FileTest.EXISTS)) {
            console.warn('[openwispr-gnome-extension] ffmpeg not found; skipping silence trim.');
            callback(inputPath);
            return;
        }

        const threshold = this._settings.get_string('silence-threshold') || '-35dB';
        const duration = Math.max(0.05, this._settings.get_double('silence-duration'));
        const filter = `silenceremove=start_periods=1:start_duration=${duration}:start_threshold=${threshold}:stop_periods=-1:stop_duration=${duration}:stop_threshold=${threshold}`;

        this._runSubprocess(
            [
                FFMPEG_BINARY,
                '-y',
                '-hide_banner',
                '-loglevel',
                'error',
                '-i',
                inputPath,
                '-af',
                filter,
                TRIMMED_OUTPUT_FILE,
            ],
            (ok, _stdout, stderr) => {
                if (!ok) {
                    console.warn(`[openwispr-gnome-extension] ffmpeg trim failed; using original audio. ${stderr}`);
                    callback(inputPath);
                    return;
                }

                try {
                    const trimmedFile = Gio.File.new_for_path(TRIMMED_OUTPUT_FILE);
                    const info = trimmedFile.query_info('standard::size', Gio.FileQueryInfoFlags.NONE, null);
                    if (info.get_size() > 0) {
                        callback(TRIMMED_OUTPUT_FILE);
                        return;
                    }
                } catch (e) {
                    console.warn(`[openwispr-gnome-extension] Could not inspect trimmed audio file: ${e}`);
                }

                callback(inputPath);
            }
        );
    }

    _transcribeAudio(inputPath, callback) {
        const provider = this._normalizeProvider(this._settings.get_string('stt-provider'));

        if (provider === 'openai' || provider === 'groq') {
            this._transcribeRemote(inputPath, provider, callback);
            return;
        }

        this._transcribeLocal(inputPath, callback);
    }

    _transcribeLocal(inputPath, callback) {
        console.log('[openwispr-gnome-extension] Transcribing with local whisper-cli...');

        this._runSubprocess(
            [
                WHISPER_BINARY,
                '-m',
                this._modelPath,
                '-f',
                inputPath,
                '-otxt',
                '-np',
                '-nt',
            ],
            (ok, _stdout, stderr) => {
                if (!ok) {
                    console.error(`[openwispr-gnome-extension] Local transcription failed: ${stderr}`);
                    this._notifyError('Local transcription failed.');
                    callback(null);
                    return;
                }

                const txtPath = `${inputPath}.txt`;
                const file = Gio.File.new_for_path(txtPath);

                file.load_contents_async(null, (loadedFile, res) => {
                    try {
                        const [, contents] = loadedFile.load_contents_finish(res);
                        const text = new TextDecoder().decode(contents).trim();
                        callback(text);
                    } catch (e) {
                        console.error(`[openwispr-gnome-extension] Failed to read local output: ${e}`);
                        callback(null);
                    }
                });
            }
        );
    }

    _transcribeRemote(inputPath, provider, callback) {
        if (!GLib.file_test(CURL_BINARY, GLib.FileTest.EXISTS)) {
            this._notifyError('curl is required for remote speech-to-text.');
            callback(null);
            return;
        }

        const isOpenAI = provider === 'openai';
        const endpoint = this._settings.get_string(isOpenAI ? 'stt-openai-endpoint' : 'stt-groq-endpoint');
        const model = this._settings.get_string(isOpenAI ? 'stt-openai-model' : 'stt-groq-model');
        const apiKey = this._settings.get_string(isOpenAI ? 'stt-openai-api-key' : 'stt-groq-api-key');

        if (!apiKey) {
            this._notifyError(`Missing API key for ${provider} speech-to-text.`);
            callback(null);
            return;
        }

        console.log(`[openwispr-gnome-extension] Transcribing with ${provider} endpoint...`);
        this._runSubprocess(
            [
                CURL_BINARY,
                '-sS',
                '-X',
                'POST',
                endpoint,
                '-H',
                `Authorization: Bearer ${apiKey}`,
                '-F',
                `model=${model}`,
                '-F',
                `file=@${inputPath}`,
            ],
            (ok, stdout, stderr) => {
                if (!ok) {
                    console.error(`[openwispr-gnome-extension] Remote transcription request failed: ${stderr}`);
                    this._notifyError('Remote transcription request failed.');
                    callback(null);
                    return;
                }

                const text = this._extractRemoteTranscription(stdout);
                if (!text) {
                    console.error(`[openwispr-gnome-extension] Remote transcription response parse failed: ${stdout}`);
                    this._notifyError('Remote transcription response was invalid.');
                    callback(null);
                    return;
                }

                callback(text);
            }
        );
    }

    _extractRemoteTranscription(stdout) {
        try {
            const payload = JSON.parse(stdout);

            if (typeof payload.text === 'string')
                return payload.text.trim();

            if (typeof payload.transcript === 'string')
                return payload.transcript.trim();

            if (payload.result && typeof payload.result.text === 'string')
                return payload.result.text.trim();
        } catch (e) {
            console.error(`[openwispr-gnome-extension] Failed to parse remote transcription JSON: ${e}`);
        }

        return '';
    }

    _cleanupTranscript(text, callback) {
        if (!this._settings.get_boolean('llm-filter-enabled')) {
            callback(text);
            return;
        }

        if (!GLib.file_test(CURL_BINARY, GLib.FileTest.EXISTS)) {
            this._notifyError('curl is required for LLM cleanup.');
            callback(text);
            return;
        }

        const provider = this._normalizeProvider(this._settings.get_string('llm-provider'));
        const isOpenAI = provider === 'openai';
        const endpoint = this._settings.get_string(isOpenAI ? 'llm-openai-endpoint' : 'llm-groq-endpoint');
        const model = this._settings.get_string(isOpenAI ? 'llm-openai-model' : 'llm-groq-model');
        const apiKey = this._settings.get_string(isOpenAI ? 'llm-openai-api-key' : 'llm-groq-api-key');
        const prompt = this._settings.get_string('llm-cleanup-prompt');

        if (!apiKey) {
            this._notifyError(`Missing API key for ${provider} LLM cleanup.`);
            callback(text);
            return;
        }

        const payload = {
            model,
            temperature: 0,
            messages: [
                { role: 'system', content: prompt },
                { role: 'user', content: text },
            ],
        };

        this._runSubprocess(
            [
                CURL_BINARY,
                '-sS',
                '-X',
                'POST',
                endpoint,
                '-H',
                'Content-Type: application/json',
                '-H',
                `Authorization: Bearer ${apiKey}`,
                '-d',
                JSON.stringify(payload),
            ],
            (ok, stdout, stderr) => {
                if (!ok) {
                    console.error(`[openwispr-gnome-extension] LLM cleanup request failed: ${stderr}`);
                    callback(text);
                    return;
                }

                const cleaned = this._extractLlmText(stdout);
                callback(cleaned || text);
            }
        );
    }

    _extractLlmText(stdout) {
        try {
            const payload = JSON.parse(stdout);

            if (typeof payload.output_text === 'string')
                return payload.output_text.trim();

            const choice = payload.choices?.[0];
            const message = choice?.message?.content;

            if (typeof message === 'string')
                return message.trim();

            if (Array.isArray(message)) {
                return message
                    .map(part => (typeof part?.text === 'string' ? part.text : ''))
                    .join('')
                    .trim();
            }
        } catch (e) {
            console.error(`[openwispr-gnome-extension] Failed to parse LLM response JSON: ${e}`);
        }

        return '';
    }

    _runSubprocess(argv, callback) {
        try {
            const proc = new Gio.Subprocess({
                argv,
                flags: Gio.SubprocessFlags.STDOUT_PIPE | Gio.SubprocessFlags.STDERR_PIPE,
            });
            proc.init(null);

            proc.communicate_utf8_async(null, null, (subproc, res) => {
                try {
                    const [, stdout, stderr] = subproc.communicate_utf8_finish(res);
                    callback(subproc.get_successful(), stdout, stderr);
                } catch (e) {
                    callback(false, '', `${e}`);
                }
            });
        } catch (e) {
            callback(false, '', `${e}`);
        }
    }

    _normalizeProvider(provider) {
        if (provider === 'grok')
            return 'groq';

        return provider;
    }

    _migrateLegacyProviderNames() {
        const sttProvider = this._settings.get_string('stt-provider');
        if (sttProvider === 'grok')
            this._settings.set_string('stt-provider', 'groq');

        const llmProvider = this._settings.get_string('llm-provider');
        if (llmProvider === 'grok')
            this._settings.set_string('llm-provider', 'groq');
    }

    _injectText(text) {
        // Clutter Virtual Input
        try {
            const clipboard = St.Clipboard.get_default();
            clipboard.set_text(St.ClipboardType.CLIPBOARD, text);

            if (!this._autoPasteEnabled) {
                this._notify('Transcription copied to clipboard.');
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
            this._notify(`Copied to clipboard: ${text}`);
        }
    }

    _notify(message) {
        if (!this._notificationsEnabled)
            return;

        Main.notify('openwispr-gnome-extension', message);
    }

    _notifyError(message) {
        if (!this._notificationsEnabled)
            return;

        Main.notify('openwispr-gnome-extension Error', message);
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
