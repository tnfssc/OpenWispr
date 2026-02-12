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
const OUTPUT_FILE_BASENAME = 'openwispr_recording.wav';
const TRIMMED_OUTPUT_FILE_BASENAME = 'openwispr_recording_trimmed.wav';
const WHISPER_BINARY_FALLBACK = '/usr/bin/whisper-cli';
const FFMPEG_BINARY_FALLBACK = '/usr/bin/ffmpeg';
const CURL_BINARY_FALLBACK = '/usr/bin/curl';
const DEBUG_LOGS = false;
const DBUS_CONTROL_BUS_NAME = 'org.gnome.Shell.Extensions.OpenWispr';
const DBUS_CONTROL_PATH = '/org/gnome/Shell/Extensions/OpenWispr';
const DBUS_CONTROL_IFACE = `
<node>
  <interface name="org.gnome.Shell.Extensions.OpenWispr">
    <method name="Toggle">
      <arg name="source" type="s" direction="in"/>
      <arg name="recording" type="b" direction="out"/>
    </method>
    <method name="Start">
      <arg name="source" type="s" direction="in"/>
      <arg name="started" type="b" direction="out"/>
    </method>
    <method name="Stop">
      <arg name="transcribe" type="b" direction="in"/>
      <arg name="source" type="s" direction="in"/>
      <arg name="stopped" type="b" direction="out"/>
    </method>
    <method name="Status">
      <arg name="recording" type="b" direction="out"/>
      <arg name="processing" type="b" direction="out"/>
      <arg name="trigger" type="s" direction="out"/>
    </method>
  </interface>
</node>`;

class OpenWisprController {
    constructor(extension) {
        this._extension = extension;
        this.uuid = extension.uuid;
        this.metadata = extension.metadata;
        this.dir = extension.dir;
    }

    getSettings() {
        return this._extension.getSettings();
    }

    enable() {
        this._settings = this.getSettings();
        this._migrateLegacyProviderNames();
        this._recording = false;
        this._processing = false;
        this._recordingTrigger = null;
        this._remoteHoldBinding = null;
        this._recordProc = null;
        this._holdKeyPressed = false;
        this._holdStartCooldownUntilUs = 0;
        this._holdToSpeakEnabled = this._settings.get_boolean('hold-to-speak-enabled');
        this._holdToSpeakBinding = this._parseAccelerator(
            this._settings.get_strv('hold-to-speak-keybinding')[0] || '<Control><Alt>t'
        );
        this._autoPasteEnabled = this._settings.get_boolean('auto-paste-enabled');
        this._notificationsEnabled = this._settings.get_boolean('notifications-enabled');
        this._outputFile = GLib.build_filenamev([GLib.get_tmp_dir(), OUTPUT_FILE_BASENAME]);
        this._trimmedOutputFile = GLib.build_filenamev([GLib.get_tmp_dir(), TRIMMED_OUTPUT_FILE_BASENAME]);
        this._whisperBinary = GLib.find_program_in_path('whisper-cli') || WHISPER_BINARY_FALLBACK;
        this._ffmpegBinary = GLib.find_program_in_path('ffmpeg') || FFMPEG_BINARY_FALLBACK;
        this._curlBinary = GLib.find_program_in_path('curl') || CURL_BINARY_FALLBACK;

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

        try {
            this._dbusConn = null;
            this._dbusApi = {
                Toggle: source => this.Toggle(source),
                Start: source => this.Start(source),
                Stop: (transcribe, source) => this.Stop(transcribe, source),
                Status: () => this.Status(),
            };
            this._dbusNameOwnerId = Gio.DBus.own_name(
                Gio.BusType.SESSION,
                DBUS_CONTROL_BUS_NAME,
                Gio.BusNameOwnerFlags.NONE,
                connection => {
                    this._dbusConn = connection;
                    this._dbusControl = Gio.DBusExportedObject.wrapJSObject(DBUS_CONTROL_IFACE, this._dbusApi);
                    this._dbusControl.export(connection, DBUS_CONTROL_PATH);
                },
                null,
                () => {
                    if (this._dbusControl) {
                        this._dbusControl.unexport();
                        this._dbusControl = null;
                    }
                    this._dbusConn = null;
                    console.error('[openwispr-gnome-extension] Failed to acquire DBus bus name');
                }
            );
        } catch (e) {
            console.error(`[openwispr-gnome-extension] Failed to export DBus control interface: ${e}`);
            this._dbusNameOwnerId = null;
            this._dbusConn = null;
            this._dbusControl = null;
            this._dbusApi = null;
        }

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

        this._holdToSpeakBindingChangedId = this._settings.connect('changed::hold-to-speak-keybinding', () => {
            this._holdToSpeakBinding = this._parseAccelerator(
                this._settings.get_strv('hold-to-speak-keybinding')[0] || '<Control><Alt>t'
            );
        });

        this._notificationsChangedId = this._settings.connect('changed::notifications-enabled', () => {
            this._notificationsEnabled = this._settings.get_boolean('notifications-enabled');
        });

        this._debug(`Enabled. Model: ${this._modelPath}`);
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

        if (this._settings && this._holdToSpeakBindingChangedId) {
            this._settings.disconnect(this._holdToSpeakBindingChangedId);
            this._holdToSpeakBindingChangedId = null;
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

        if (this._dbusControl) {
            this._dbusControl.flush();
            this._dbusControl.unexport();
            this._dbusControl = null;
        }

        this._dbusApi = null;

        if (this._dbusNameOwnerId) {
            Gio.bus_unown_name(this._dbusNameOwnerId);
            this._dbusNameOwnerId = null;
        }

        this._dbusConn = null;

        Main.wm.removeKeybinding('toggle-recording');
        this._settings = null;
    }

    Toggle(source) {
        const triggerSource = source || 'external';

        if (this._recording) {
            this._debug(`DBus toggle stop requested by ${triggerSource}`);
            this._stopRecording(true);
            return false;
        }

        if (this._processing)
            return false;

        this._debug(`DBus toggle start requested by ${triggerSource}`);
        this._startRecording(`remote:${triggerSource}`);
        return this._recording;
    }

    Start(source) {
        const triggerSource = source || 'external';

        if (this._recording || this._processing)
            return false;

        this._remoteHoldBinding = this._parseRemotePortalBinding(triggerSource);
        this._debug(`DBus start requested by ${triggerSource}`);
        this._startRecording(`remote:${triggerSource}`);
        return this._recording;
    }

    Stop(transcribe, source) {
        const triggerSource = source || 'external';

        if (!this._recording)
            return false;

        this._debug(`DBus stop requested by ${triggerSource}`);
        this._stopRecording(Boolean(transcribe));
        return true;
    }

    Status() {
        return [
            this._recording,
            this._processing,
            this._recordingTrigger ?? '',
        ];
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

            if (this._recording && this._isRemotePortalReleaseEvent(keySymbol, modifiers)) {
                this._debug('Remote portal release detected from keyboard event');
                this._stopRecording(true);
            }

            if (!this._recording)
                return Clutter.EVENT_PROPAGATE;
        }

        return Clutter.EVENT_PROPAGATE;
    }

    _isHoldToSpeakPressEvent(keySymbol, modifiers) {
        if (!this._holdToSpeakBinding.valid)
            return false;

        if (!this._holdToSpeakBinding.keyvals.includes(keySymbol))
            return false;

        return (modifiers & this._holdToSpeakBinding.modifierMask) === this._holdToSpeakBinding.modifierMask;
    }

    _isHoldToSpeakReleaseEvent(keySymbol, modifiers) {
        return this._isBindingReleaseEvent(this._holdToSpeakBinding, keySymbol, modifiers);
    }

    _isBindingReleaseEvent(binding, keySymbol, modifiers) {
        if (!binding?.valid)
            return false;

        const releasedHoldKey = binding.keyvals.includes(keySymbol);
        const modifiersStillHeld =
            (modifiers & binding.modifierMask) === binding.modifierMask;

        return releasedHoldKey || !modifiersStillHeld;
    }

    _isRemotePortalReleaseEvent(keySymbol, modifiers) {
        if (!this._recordingTrigger?.startsWith('remote:portal'))
            return false;

        return this._isBindingReleaseEvent(this._remoteHoldBinding, keySymbol, modifiers);
    }

    _parseRemotePortalBinding(source) {
        if (!source || !source.startsWith('portal:'))
            return null;

        const trigger = source.slice('portal:'.length).trim();
        if (!trigger)
            return null;

        const parsed = this._parseAccelerator(trigger);
        return parsed.valid ? parsed : null;
    }

    _parseAccelerator(accelerator) {
        const fallback = {
            valid: false,
            keyvals: [],
            modifierMask: 0,
            raw: accelerator,
        };

        if (!accelerator)
            return fallback;

        let modifierTokens = [];
        let keyToken = '';

        if (accelerator.includes('<')) {
            modifierTokens = [...accelerator.matchAll(/<([^>]+)>/g)]
                .map(match => match[1].trim().toLowerCase());
            keyToken = accelerator.replace(/<[^>]+>/g, '').trim();
        } else if (accelerator.includes('+')) {
            const parts = accelerator
                .split('+')
                .map(part => part.trim())
                .filter(Boolean);

            keyToken = parts.pop() || '';
            modifierTokens = parts.map(part => part.toLowerCase());
        } else {
            keyToken = accelerator.trim();
        }

        const keyvals = this._resolveKeyvals(keyToken);
        if (keyvals.length === 0)
            return fallback;

        let modifierMask = 0;
        for (const token of modifierTokens) {
            if (token === 'control' || token === 'ctrl' || token === 'primary') {
                modifierMask |= Clutter.ModifierType.CONTROL_MASK;
            } else if (token === 'alt' || token === 'mod1') {
                modifierMask |= Clutter.ModifierType.MOD1_MASK;
            } else if (token === 'shift') {
                modifierMask |= Clutter.ModifierType.SHIFT_MASK;
            } else if (token === 'super' || token === 'meta' || token === 'mod4') {
                modifierMask |= Clutter.ModifierType.SUPER_MASK;
            }
        }

        return {
            valid: true,
            keyvals,
            modifierMask,
            raw: accelerator,
        };
    }

    _resolveKeyvals(keyToken) {
        if (!keyToken)
            return [];

        const key = keyToken.trim();
        const lower = key.toLowerCase();

        if (lower === 'space')
            return [Clutter.KEY_space];
        if (lower === 'slash')
            return [Clutter.KEY_slash, Clutter.KEY_KP_Divide];

        const collected = [];
        const maybeAdd = value => {
            if (typeof value === 'number' && !collected.includes(value))
                collected.push(value);
        };

        maybeAdd(Clutter[`KEY_${key}`]);
        maybeAdd(Clutter[`KEY_${key.toUpperCase()}`]);
        maybeAdd(Clutter[`KEY_${lower}`]);

        if (lower.length === 1) {
            maybeAdd(lower.charCodeAt(0));
            maybeAdd(lower.toUpperCase().charCodeAt(0));
        }

        return collected;
    }

    _startRecording(trigger = 'toggle') {
        if (this._recording || this._processing)
            return;

        this._debug(`Starting recording (${trigger})...`);
        this._recording = true;
        this._recordingTrigger = trigger;
        this._icon.icon_name = 'media-record-symbolic';
        this._icon.style_class = 'system-status-icon destructive-action'; // Red-ish if theme supports

        try {
            const proc = new Gio.Subprocess({
                argv: ['gjs', '-m', this._recorderScript, this._outputFile],
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

        this._debug(`Stopping recording (${this._recordingTrigger ?? 'unknown'})...`);
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
                    this._debug('Recorder exited.');
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
        this._trimSilence(this._outputFile, (processedPath) => {
            this._transcribeAudio(processedPath, (transcript) => {
                if (transcript === null) {
                    this._resetState();
                    return;
                }

                this._cleanupTranscript(transcript, (cleanedText) => {
                    const finalText = (cleanedText ?? transcript ?? '').trim();
                    this._debug(`Text: ${finalText}`);

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

        if (!GLib.file_test(this._ffmpegBinary, GLib.FileTest.EXISTS)) {
            console.warn('[openwispr-gnome-extension] ffmpeg not found; skipping silence trim.');
            callback(inputPath);
            return;
        }

        const threshold = this._settings.get_string('silence-threshold') || '-35dB';
        const duration = Math.max(0.05, this._settings.get_double('silence-duration'));
        const filter = `silenceremove=start_periods=1:start_duration=${duration}:start_threshold=${threshold}:stop_periods=-1:stop_duration=${duration}:stop_threshold=${threshold}`;

        this._runSubprocess(
            [
                this._ffmpegBinary,
                '-y',
                '-hide_banner',
                '-loglevel',
                'error',
                '-i',
                inputPath,
                '-af',
                filter,
                this._trimmedOutputFile,
            ],
            (ok, _stdout, stderr) => {
                if (!ok) {
                    console.warn(`[openwispr-gnome-extension] ffmpeg trim failed; using original audio. ${stderr}`);
                    callback(inputPath);
                    return;
                }

                try {
                    const trimmedFile = Gio.File.new_for_path(this._trimmedOutputFile);
                    const info = trimmedFile.query_info('standard::size', Gio.FileQueryInfoFlags.NONE, null);
                    if (info.get_size() > 0) {
                        callback(this._trimmedOutputFile);
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
        this._debug('Transcribing with local whisper-cli...');

        this._runSubprocess(
            [
                this._whisperBinary,
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
        if (!GLib.file_test(this._curlBinary, GLib.FileTest.EXISTS)) {
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

        this._debug(`Transcribing with ${provider} endpoint...`);
        this._runSubprocess(
            [
                this._curlBinary,
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

        if (!GLib.file_test(this._curlBinary, GLib.FileTest.EXISTS)) {
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
                this._curlBinary,
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

        const holdBinding = this._settings.get_strv('hold-to-speak-keybinding');
        const currentHoldBinding = holdBinding[0] || '';
        if (!currentHoldBinding || currentHoldBinding === '<Control><Alt>space')
            this._settings.set_strv('hold-to-speak-keybinding', ['<Control><Alt>t']);

        const holdTrigger = this._settings.get_string('hold-to-speak-trigger');
        if (!holdTrigger || holdTrigger === 'ctrl-alt-space')
            this._settings.set_string('hold-to-speak-trigger', 'ctrl-alt-t');
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
            
            this._debug('Text injected via clipboard paste');
            
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
        this._remoteHoldBinding = null;
        this._recordProc = null;
        if (this._icon) {
            this._icon.icon_name = 'microphone-sensitivity-high-symbolic';
            this._icon.style_class = 'system-status-icon';
        }
    }

    _debug(message) {
        if (!DEBUG_LOGS)
            return;

        console.debug(`[openwispr-gnome-extension] ${message}`);
    }
}

export default class OpenWisprExtension extends Extension {
    enable() {
        this._controller = new OpenWisprController(this);
        this._controller.enable();
    }

    disable() {
        if (!this._controller)
            return;

        this._controller.disable();
        this._controller = null;
    }
}
