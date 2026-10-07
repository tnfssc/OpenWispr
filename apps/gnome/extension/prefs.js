import Adw from 'gi://Adw';
import Gdk from 'gi://Gdk';
import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
import Gtk from 'gi://Gtk';

import { ExtensionPreferences, gettext as _ } from 'resource:///org/gnome/Shell/Extensions/js/extensions/prefs.js';

// Dialog dimensions used across the preferences window.
const DIALOG_DEFAULT_WIDTH = 780;
const DIALOG_DEFAULT_HEIGHT = 480;
const SHORTCUT_DIALOG_WIDTH = 360;
const SHORTCUT_DIALOG_HEIGHT = 90;
const MULTILINE_MIN_CONTENT_HEIGHT = 300;

export default class OpenWisprPreferences extends ExtensionPreferences {
    fillPreferencesWindow(window) {
        const settings = this.getSettings();
        // Keep switches, shortcuts and dialog edits local until Save is clicked.
        settings.delay();
        this._textSettings = [];
        this._spinRows = [];

        const page = new Adw.PreferencesPage({ title: _('Settings'), icon_name: 'emblem-system-symbolic' });
        this._settingsPage = page;
        this._draftSettings = settings;
        this._setupState = this.getSettings();
        let setupRequestedId = null;
        window.add(page);

        const saveGroup = new Adw.PreferencesGroup();
        this._saveRow = new Adw.ActionRow({
            title: _('Settings'),
            subtitle: _('Click Save to apply changes. Closing this window discards unsaved changes.'),
        });
        const saveButton = new Gtk.Button({
            label: _('Save'),
            css_classes: ['suggested-action'],
            valign: Gtk.Align.CENTER,
        });
        saveButton.connect('clicked', () => this._saveSettings(window, settings));
        this._saveRow.add_suffix(saveButton);
        this._saveRow.activatable_widget = saveButton;
        saveGroup.add(this._saveRow);
        page.add(saveGroup);
        const setupGroup = new Adw.PreferencesGroup();
        const setupRow = new Adw.ActionRow({title: _('Set up dictation'), subtitle: _('Provider, microphone, shortcuts, and a safe first dictation.')});
        const setupButton = new Gtk.Button({label: _('Open setup'), valign: Gtk.Align.CENTER});
        setupButton.connect('clicked', () => this._openSetup(window));
        setupRow.add_suffix(setupButton);
        setupRow.activatable_widget = setupButton;
        setupGroup.add(setupRow);
        page.add(setupGroup);
        const changedId = settings.connect('changed', (_settings, key) => {
            if (!key.startsWith('setup-')) this._markUnsaved();
        });
        window.connect('close-request', () => {
            if (this._setupIdle) { GLib.source_remove(this._setupIdle); this._setupIdle = null; }
            this._leaveSetup();
            if (setupRequestedId) this._setupState.disconnect(setupRequestedId);
            settings.disconnect(changedId);
            settings.revert();
            return false;
        });

        const companionGroup = new Adw.PreferencesGroup({
            title: _('Companion Setup'),
            description: _('Recording/transcription runs through the openwispr companion user service.'),
        });
        page.add(companionGroup);

        const releasesUrl = 'https://github.com/tnfssc/OpenWispr/releases/tag/gnome-latest';
        const installCommand = [
            'ARCH="$(uname -m)"',
            'case "$ARCH" in x86_64) BIN=openwispr-linux-amd64 ;; aarch64|arm64) BIN=openwispr-linux-arm64 ;; *) echo "Unsupported arch: $ARCH"; exit 1 ;; esac',
            'REPO="https://github.com/tnfssc/OpenWispr/releases/download/gnome-latest"',
            'TMP="$(mktemp -d)"',
            'mkdir -p ~/.local/bin ~/.config/systemd/user ~/.local/share/applications ~/.local/share/icons/hicolor/256x256/apps',
            'curl -fsSL "$REPO/${BIN}.tar.gz" -o "$TMP/${BIN}.tar.gz"',
            'tar -xzf "$TMP/${BIN}.tar.gz" -C "$TMP"',
            'install -Dm755 "$TMP/$BIN" ~/.local/bin/openwispr',
            'curl -fsSL "$REPO/openwispr-engine.service" -o ~/.config/systemd/user/openwispr-engine.service',
            'curl -fsSL "$REPO/openwispr-hotkeyd.service" -o ~/.config/systemd/user/openwispr-hotkeyd.service',
            'curl -fsSL "$REPO/io.github.tnfssc.openwispr.desktop" -o ~/.local/share/applications/io.github.tnfssc.openwispr.desktop',
            'curl -fsSL "$REPO/openwispr.png" -o ~/.local/share/icons/hicolor/256x256/apps/io.github.tnfssc.openwispr.png',
            'systemctl --user daemon-reload',
            'systemctl --user enable --now openwispr-engine.service',
        ].join(' && ');

        this._addLinkRow(
            companionGroup,
            _('Open Latest Release Assets'),
            _('Download companion binaries and service files from GitHub Releases.'),
            releasesUrl
        );
        this._addCommandRow(
            companionGroup,
            _('Copy Install Command'),
            _('Downloads companion binary + service files and enables openwispr-engine.service.'),
            installCommand
        );
        this._addCommandRow(
            companionGroup,
            _('Copy Optional Hold Daemon Command'),
            _('Enables hold-to-talk daemon (portal first, evdev fallback).'),
            'systemctl --user enable --now openwispr-hotkeyd.service'
        );
        this._addCommandRow(
            companionGroup,
            _('Copy Health Check Command'),
            _('Verifies extension DBus, portal support, and companion engine availability.'),
            'openwispr doctor'
        );
        this._addCommandRow(
            companionGroup,
            _('Copy Repair Command'),
            _('Restarts portal/engine/hotkey services and reruns health checks.'),
            'openwispr restart'
        );
        const shortcutsGroup = new Adw.PreferencesGroup({ title: _('Shortcuts') });
        page.add(shortcutsGroup);

        this._addShortcutCaptureRow(
            window,
            shortcutsGroup,
            settings,
            'toggle-recording',
            _('Toggle Recording'),
            _('Click the shortcut button, then press your keys.')
        );

        const holdToSpeakRow = new Adw.SwitchRow({
            title: _('Hold to Speak'),
            subtitle: _('Hold your configured shortcut to record. Release to transcribe.'),
            active: settings.get_boolean('hold-to-speak-enabled'),
        });
        holdToSpeakRow.connect('notify::active', () => settings.set_boolean('hold-to-speak-enabled', holdToSpeakRow.active));
        shortcutsGroup.add(holdToSpeakRow);

        this._addShortcutCaptureRow(
            window,
            shortcutsGroup,
            settings,
            'hold-to-speak-keybinding',
            _('Hold To Speak Shortcut'),
            _('Click the shortcut button, then press your keys.')
        );

        const pasteMethods = ['ctrl-v', 'ctrl-shift-v', 'shift-insert', 'clipboard-only'];
        const pasteLabels = [
            _('Ctrl+V (standard paste)'),
            _('Ctrl+Shift+V (terminal paste)'),
            _('Shift+Insert'),
            _('Clipboard only'),
        ];
        const pasteModel = Gtk.StringList.new(pasteLabels);
        const pasteRow = new Adw.ComboRow({
            title: _('Transcription Insertion'),
            subtitle: _('Choose how transcription is inserted into the active application.'),
            model: pasteModel,
        });
        let pasteMethod = settings.get_string('paste-method');
        if (!pasteMethods.includes(pasteMethod))
            pasteMethod = 'ctrl-v';
        pasteRow.selected = pasteMethods.indexOf(pasteMethod);
        pasteRow.connect('notify::selected', () => settings.set_string('paste-method', pasteMethods[pasteRow.selected]));
        shortcutsGroup.add(pasteRow);
        const sessionType = GLib.getenv('XDG_SESSION_TYPE') || '';
        const sessionSubtitle = sessionType === 'wayland'
            ? _('Wayland may restrict synthetic key input in terminals or sandboxed fields. Use Ctrl+Shift+V for terminals or Clipboard only when insertion is blocked.')
            : _('Use Ctrl+Shift+V for terminals; Clipboard only avoids synthetic key input.');
        shortcutsGroup.add(new Adw.ActionRow({
            title: _('Session guidance'),
            subtitle: sessionSubtitle,
        }));

        const restoreClipboardRow = new Adw.SwitchRow({
            title: _('Restore Clipboard'),
            subtitle: _('Restore original clipboard content after pasting transcription.'),
            active: settings.get_boolean('restore-clipboard-enabled'),
        });
        restoreClipboardRow.connect('notify::active', () => settings.set_boolean('restore-clipboard-enabled', restoreClipboardRow.active));
        shortcutsGroup.add(restoreClipboardRow);

        const notificationsRow = new Adw.SwitchRow({
            title: _('Enable Notifications'),
            subtitle: _('Show status and transcription notifications.'),
            active: settings.get_boolean('notifications-enabled'),
        });
        notificationsRow.connect('notify::active', () => settings.set_boolean('notifications-enabled', notificationsRow.active));
        shortcutsGroup.add(notificationsRow);


        const audioGroup = new Adw.PreferencesGroup({ title: _('Audio Pipeline') });
        page.add(audioGroup);

        const trimSilenceRow = new Adw.SwitchRow({
            title: _('Trim Silence With FFmpeg'),
            subtitle: _('Remove quiet segments before transcription.'),
            active: settings.get_boolean('silence-trim-enabled'),
        });
        trimSilenceRow.connect('notify::active', () => settings.set_boolean('silence-trim-enabled', trimSilenceRow.active));
        audioGroup.add(trimSilenceRow);

        const silenceThresholdRow = new Adw.EntryRow({
            title: _('Silence Threshold'),
            text: settings.get_string('silence-threshold'),
        });
        this._registerTextSetting(silenceThresholdRow, 'silence-threshold',
            value => /^-?\d+(\.\d+)?dB$/.test(value),
            _('Silence Threshold must be a number followed by dB, for example -35dB.'));
        audioGroup.add(silenceThresholdRow);

        const silenceDurationAdjustment = new Gtk.Adjustment({
            lower: 0.05,
            upper: 5.0,
            step_increment: 0.05,
            page_increment: 0.1,
            value: settings.get_double('silence-duration'),
        });
        // Stage any clamped value along with the other settings.
        if (settings.get_double('silence-duration') !== silenceDurationAdjustment.value)
            settings.set_double('silence-duration', silenceDurationAdjustment.value);
        const silenceDurationRow = new Adw.SpinRow({
            title: _('Silence Duration (seconds)'),
            adjustment: silenceDurationAdjustment,
            digits: 2,
        });
        silenceDurationRow.connect('notify::value', () => settings.set_double('silence-duration', silenceDurationRow.value));
        this._spinRows.push(silenceDurationRow);
        audioGroup.add(silenceDurationRow);

        const sttGroup = new Adw.PreferencesGroup({ title: _('Speech to Text'), description: _('Remote providers receive your audio. Local Whisper processes audio on this computer.') });
        page.add(sttGroup);

        const sttProviderOptions = [
            { id: 'local', label: _('Local whisper-cli') },
            { id: 'openai', label: _('OpenAI Whisper Endpoint') },
            { id: 'groq', label: _('Groq') },
            { id: 'openrouter', label: _('OpenRouter NVIDIA Parakeet') },
        ];
        const sttProviderModel = Gtk.StringList.new(sttProviderOptions.map(option => option.label));
        const sttProviderRow = new Adw.ComboRow({
            title: _('STT Provider'),
            subtitle: _('Choose local or remote speech-to-text.'),
            model: sttProviderModel,
        });
        const currentSttProvider = this._normalizeProvider(settings.get_string('stt-provider'));
        const sttIndex = sttProviderOptions.findIndex(option => option.id === currentSttProvider);
        if (sttIndex < 0)
            settings.set_string('stt-provider', sttProviderOptions[0].id);
        sttProviderRow.selected = sttIndex >= 0 ? sttIndex : 0;
        sttGroup.add(sttProviderRow);

        const sttOpenAiApiKeyRow = this._addSecretRow(sttGroup, settings, 'stt-openai-api-key', _('OpenAI STT API Key'));
        const sttGroqApiKeyRow = this._addSecretRow(sttGroup, settings, 'stt-groq-api-key', _('Groq STT API Key'));
        const sttOpenRouterApiKeyRow = this._addSecretRow(sttGroup, settings, 'stt-openrouter-api-key', _('OpenRouter STT API Key'));
        const sttOpenAiKeyLink = this._addLinkRow(sttGroup, _('Create OpenAI API key'), _('Open the provider website, then paste your key above.'), 'https://platform.openai.com/api-keys');
        const sttGroqKeyLink = this._addLinkRow(sttGroup, _('Create Groq API key'), _('Open the provider website, then paste your key above.'), 'https://console.groq.com/keys');
        const sttOpenRouterKeyLink = this._addLinkRow(sttGroup, _('Create OpenRouter API key'), _('Open the provider website, then paste your key above.'), 'https://openrouter.ai/keys');
        const sttOpenAiEndpointRow = this._addEntryRow(sttGroup, settings, 'stt-openai-endpoint', _('OpenAI STT Endpoint'));
        const sttOpenAiModelRow = this._addEntryRow(sttGroup, settings, 'stt-openai-model', _('OpenAI STT Model'));
        const sttGroqEndpointRow = this._addEntryRow(sttGroup, settings, 'stt-groq-endpoint', _('Groq STT Endpoint'));
        const sttGroqModelRow = this._addEntryRow(sttGroup, settings, 'stt-groq-model', _('Groq STT Model'));
        const sttOpenRouterEndpointRow = this._addEntryRow(sttGroup, settings, 'stt-openrouter-endpoint', _('OpenRouter STT Endpoint'));
        const sttOpenRouterModelRow = this._addEntryRow(sttGroup, settings, 'stt-openrouter-model', _('OpenRouter STT Model'));

        const updateSttProviderVisibility = providerId => {
            const showOpenAi = providerId === 'openai';
            const showGroq = providerId === 'groq';
            const showOpenRouter = providerId === 'openrouter';

            sttOpenAiKeyLink.set_visible(showOpenAi);
            sttGroqKeyLink.set_visible(showGroq);
            sttOpenRouterKeyLink.set_visible(showOpenRouter);
            sttOpenAiEndpointRow.set_visible(showOpenAi);
            sttOpenAiModelRow.set_visible(showOpenAi);
            sttOpenAiApiKeyRow.set_visible(showOpenAi);
            sttGroqEndpointRow.set_visible(showGroq);
            sttGroqModelRow.set_visible(showGroq);
            sttGroqApiKeyRow.set_visible(showGroq);
            sttOpenRouterEndpointRow.set_visible(showOpenRouter);
            sttOpenRouterModelRow.set_visible(showOpenRouter);
            sttOpenRouterApiKeyRow.set_visible(showOpenRouter);
        };

        updateSttProviderVisibility(sttProviderOptions[sttProviderRow.selected]?.id || sttProviderOptions[0].id);
        sttProviderRow.connect('notify::selected', () => {
            const selected = sttProviderOptions[sttProviderRow.selected] || sttProviderOptions[0];
            settings.set_string('stt-provider', selected.id);
            updateSttProviderVisibility(selected.id);
        });

        const llmGroup = new Adw.PreferencesGroup({ title: _('LLM Cleanup'), description: _('When enabled, transcript text is sent to the selected cleanup provider.') });
        page.add(llmGroup);

        this._addLinkRow(
            llmGroup,
            _('Create Groq API Key (Recommended)'),
            _('Use the same Groq key for the default Qwen cleanup model.'),
            'https://console.groq.com/keys'
        );
        this._addLinkRow(
            llmGroup,
            _('Create OpenRouter API Key'),
            _('Optional alternative using Qwen 3.6 27B for cleanup.'),
            'https://openrouter.ai/keys'
        );

        const llmCleanupRow = new Adw.SwitchRow({
            title: _('Enable LLM Transcript Cleanup'),
            subtitle: _('Clean STT output after transcription.'),
            active: settings.get_boolean('llm-filter-enabled'),
        });
        llmCleanupRow.connect('notify::active', () => settings.set_boolean('llm-filter-enabled', llmCleanupRow.active));
        llmGroup.add(llmCleanupRow);

        const llmProviderOptions = [
            { id: 'openai', label: _('OpenAI LLM Endpoint') },
            { id: 'groq', label: _('Groq LLM Endpoint') },
            { id: 'openrouter', label: _('OpenRouter Qwen') },
        ];
        const llmProviderModel = Gtk.StringList.new(llmProviderOptions.map(option => option.label));
        const llmProviderRow = new Adw.ComboRow({
            title: _('LLM Provider'),
            subtitle: _('Provider used for transcript cleanup.'),
            model: llmProviderModel,
        });
        const currentLlmProvider = this._normalizeProvider(settings.get_string('llm-provider'));
        const llmIndex = llmProviderOptions.findIndex(option => option.id === currentLlmProvider);
        if (llmIndex < 0)
            settings.set_string('llm-provider', llmProviderOptions[0].id);
        llmProviderRow.selected = llmIndex >= 0 ? llmIndex : 0;
        llmGroup.add(llmProviderRow);

        const llmOpenAiEndpointRow = this._addEntryRow(llmGroup, settings, 'llm-openai-endpoint', _('OpenAI LLM Endpoint'));
        const llmOpenAiModelRow = this._addEntryRow(llmGroup, settings, 'llm-openai-model', _('OpenAI LLM Model'));
        const llmOpenAiApiKeyRow = this._addSecretRow(llmGroup, settings, 'llm-openai-api-key', _('OpenAI LLM API Key'));
        const llmGroqEndpointRow = this._addEntryRow(llmGroup, settings, 'llm-groq-endpoint', _('Groq LLM Endpoint'));
        const llmGroqModelRow = this._addEntryRow(llmGroup, settings, 'llm-groq-model', _('Groq LLM Model'));
        const llmGroqApiKeyRow = this._addSecretRow(llmGroup, settings, 'llm-groq-api-key', _('Groq LLM API Key'));
        const llmOpenRouterEndpointRow = this._addEntryRow(llmGroup, settings, 'llm-openrouter-endpoint', _('OpenRouter LLM Endpoint'));
        const llmOpenRouterModelRow = this._addEntryRow(llmGroup, settings, 'llm-openrouter-model', _('OpenRouter LLM Model'));
        const llmOpenRouterApiKeyRow = this._addSecretRow(llmGroup, settings, 'llm-openrouter-api-key', _('OpenRouter LLM API Key'));

        const updateLlmProviderVisibility = providerId => {
            const showOpenAi = providerId === 'openai';
            const showGroq = providerId === 'groq';
            const showOpenRouter = providerId === 'openrouter';

            llmOpenAiEndpointRow.set_visible(showOpenAi);
            llmOpenAiModelRow.set_visible(showOpenAi);
            llmOpenAiApiKeyRow.set_visible(showOpenAi);
            llmGroqEndpointRow.set_visible(showGroq);
            llmGroqModelRow.set_visible(showGroq);
            llmGroqApiKeyRow.set_visible(showGroq);
            llmOpenRouterEndpointRow.set_visible(showOpenRouter);
            llmOpenRouterModelRow.set_visible(showOpenRouter);
            llmOpenRouterApiKeyRow.set_visible(showOpenRouter);
        };

        updateLlmProviderVisibility(llmProviderOptions[llmProviderRow.selected]?.id || llmProviderOptions[0].id);
        llmProviderRow.connect('notify::selected', () => {
            const selected = llmProviderOptions[llmProviderRow.selected] || llmProviderOptions[0];
            settings.set_string('llm-provider', selected.id);
            updateLlmProviderVisibility(selected.id);
        });

        this._addMultilineEditorRow(
            window,
            llmGroup,
            settings,
            'llm-cleanup-prompt',
            _('LLM Cleanup Prompt'),
            _('Edit multiline cleanup instructions.')
        );
        this._setupProviderGroups = [sttGroup, llmGroup];
        this._setupShortcutGroup = shortcutsGroup;
        this._setupExtraShortcutRows = [restoreClipboardRow, notificationsRow];
        setupRequestedId = this._setupState.connect('changed::setup-requested', () => {
            if (this._setupState.get_boolean('setup-requested')) this._openSetup(window);
        });
        const configured = settings.get_user_value('stt-provider') !== null || settings.get_strv('toggle-recording').length > 0 || settings.get_strv('hold-to-speak-keybinding').length > 0 || ['stt-openai-api-key', 'stt-groq-api-key', 'stt-openrouter-api-key']
            .some(key => settings.get_string(key).trim()) || settings.get_string('stt-provider') === 'local';
        if (this._setupState.get_boolean('setup-requested') || (!configured && !this._setupState.get_boolean('setup-completed'))) {
            this._setupIdle = GLib.idle_add(GLib.PRIORITY_DEFAULT_IDLE, () => {
                this._setupIdle = null;
                this._openSetup(window);
                return GLib.SOURCE_REMOVE;
            });
        }
    }


    _openSetup(parent) {
        this._setupState.set_boolean('setup-requested', false);
        if (this._setupWindow) { this._setupWindow.present(); return; }
        this._setupTestPassed = false;
        this._setupWindow = new Gtk.Window({title: _('Set up dictation'), transient_for: parent, modal: true,
            default_width: 680, default_height: 660});
        const root = new Gtk.Box({orientation: Gtk.Orientation.VERTICAL, spacing: 12,
            margin_top: 16, margin_bottom: 16, margin_start: 16, margin_end: 16});
        this._setupToastOverlay = new Adw.ToastOverlay();
        this._setupToastOverlay.set_child(root);
        this._setupWindow.set_child(this._setupToastOverlay);
        this._setupProgress = new Gtk.Label({xalign: 0});
        root.append(this._setupProgress);
        this._setupStack = new Gtk.Stack({vexpand: true});
        root.append(this._setupStack);
        this._setupPages = [];
        for (let i = 0; i < 4; i++) {
            const page = new Adw.PreferencesPage();
            this._setupPages.push(page);
            this._setupStack.add_named(page, String(i));
        }
        const intro = new Adw.PreferencesGroup({title: _('Where your speech goes'),
            description: _('Remote transcription sends audio to your provider. Local Whisper stays on this computer. Optional cleanup sends text to its provider. Keys are saved in existing settings. Save and continue applies your edits.')});
        this._setupPages[0].add(intro);
        this._sttDescription = this._setupProviderGroups[0].description;
        this._setupProviderGroups[0].description = '';
        for (const group of this._setupProviderGroups) { this._settingsPage.remove(group); this._setupPages[0].add(group); }
        const checks = new Adw.PreferencesGroup({title: _('Microphone and prerequisites'),
            description: _('Recording uses your default Sound input through PulseAudio or PipeWire. Choose an input, unmute it, and allow microphone access if your system asks. The test will verify real recording.')});
        this._setupPages[1].add(checks);
        this._checkRow = new Adw.ActionRow({title: _('Check this computer'), subtitle: _('No software is installed by setup.')});
        const checkButton = new Gtk.Button({label: _('Check again'), valign: Gtk.Align.CENTER});
        checkButton.connect('clicked', () => this._runSetupChecks());
        this._checkRow.add_suffix(checkButton);
        checks.add(this._checkRow);
        const soundRow = new Adw.ActionRow({title: _('Choose and unmute your microphone')});
        const sound = new Gtk.Button({label: _('Open Sound'), valign: Gtk.Align.CENTER});
        sound.connect('clicked', () => {
            try { Gio.Subprocess.new(['gnome-control-center', 'sound'], Gio.SubprocessFlags.NONE); }
            catch (e) { this._checkRow.subtitle = _('Could not open Sound. Open Settings → Sound and check Input.'); }
        });
        soundRow.add_suffix(sound); checks.add(soundRow);
        this._addLinkRow(checks, _('Installation guide'), _('FFmpeg, companion, and local Whisper model instructions. Nothing is installed automatically.'), 'https://github.com/tnfssc/OpenWispr/tree/develop/apps/gnome#installation');
        checks.add(new Adw.ActionRow({title: _('Shortcuts do not need raw keyboard access'),
            subtitle: _('Use the native shortcuts in the next step. Portal and /dev/input warnings from doctor apply to the optional hotkey daemon, not native shortcuts.')}));
        const doctorButton = new Gtk.Button({label: _('Run doctor checks'), halign: Gtk.Align.START});
        const doctorRow = new Adw.ActionRow({title: _('Existing companion checks')});
        doctorRow.add_suffix(doctorButton); checks.add(doctorRow);
        this._doctorResult = new Gtk.Label({wrap: true, selectable: true, xalign: 0});
        checks.add(this._doctorResult);
        doctorButton.connect('clicked', () => this._runDoctor(doctorButton));
        for (const row of this._setupExtraShortcutRows) row.visible = false;
        this._settingsPage.remove(this._setupShortcutGroup);
        this._setupPages[2].add(new Adw.PreferencesGroup({title: _('Speak, stop, and insert'),
            description: _('Choose your own shortcut; none is assigned for you. Toggle starts and stops. Hold records until release. You can also use Start dictation in the top-bar menu. Normal dictation uses your paste choice; Retry only copies.') }));
        this._setupPages[2].add(this._setupShortcutGroup);
        const test = new Adw.PreferencesGroup({title: _('Try a short dictation'),
            description: _('Say “This is my first dictation,” then stop. This uses your saved provider settings. The result stays here; nothing is pasted into another app. Failed audio stays on this device until Retry succeeds or you Discard it in the top-bar menu.')});
        this._setupPages[3].add(test);
        this._testStatus = new Adw.ActionRow({title: _('Ready to test'), subtitle: _('Use a short phrase without sensitive information.')});
        test.add(this._testStatus);
        this._testStartButton = new Gtk.Button({label: _('Start test'), valign: Gtk.Align.CENTER});
        this._testStopButton = new Gtk.Button({label: _('Stop test'), valign: Gtk.Align.CENTER, sensitive: false});
        this._testCancelButton = new Gtk.Button({label: _('Cancel'), valign: Gtk.Align.CENTER, sensitive: false});
        this._testStatus.add_suffix(this._testStartButton);
        this._testStatus.add_suffix(this._testStopButton);
        this._testStatus.add_suffix(this._testCancelButton);
        this._testResult = new Gtk.Label({wrap: true, selectable: true, xalign: 0});
        test.add(this._testResult);
        this._testCopyButton = new Gtk.Button({label: _('Copy result'), halign: Gtk.Align.START, sensitive: false});
        this._testCopyButton.connect('clicked', () => Gdk.Display.get_default()?.get_clipboard().set(this._testResult.label));
        test.add(this._testCopyButton);
        this._testStartButton.connect('clicked', () => {
            this._setupTestSession = GLib.uuid_string_random();
            this._setupTestPassed = false; this._testResult.label = ''; this._testCopyButton.sensitive = false;
            this._showTestState('starting');
            this._callSetupTest('TestStart');
        });
        this._testStopButton.connect('clicked', () => { this._showTestState('processing'); this._callSetupTest('TestStop'); });
        this._testCancelButton.connect('clicked', () => { this._testStatus.subtitle = _('Cancelling… Processing audio stays saved on this device.'); this._callSetupTest('TestCancel'); });
        const actions = new Gtk.Box({spacing: 8}); root.append(actions);
        const leave = new Gtk.Button({label: _('Leave setup')}); leave.connect('clicked', () => { this._leaveSetup(); parent.add_toast(new Adw.Toast({title: _('Setup left. Unsaved edits remain here; Save keeps them.')})); }); actions.append(leave);
        this._setupBack = new Gtk.Button({label: _('Back')});
        this._setupBack.connect('clicked', () => {
            this._cancelSetupTest(); this._setupTestPassed = false;
            this._showSetupStep(this._setupStep - 1);
        }); actions.append(this._setupBack);
        this._setupNext = new Gtk.Button({label: _('Continue'), hexpand: true, halign: Gtk.Align.END, css_classes: ['suggested-action']});
        this._setupNext.connect('clicked', () => {
            if (this._setupStep === 0 || this._setupStep === 2) {
                if (!this._saveSettings(parent, this._draftSettings)) return;
            }
            if (this._setupStep === 3) {
                if (!this._setupTestPassed) return;
                this._setupState.set_boolean('setup-completed', true);
                this._setupState.set_int('setup-step', 0);
                Gio.Settings.sync(); this._leaveSetup();
                parent.add_toast(new Adw.Toast({title: _('Setup complete. Use your shortcut or the top-bar menu.')}));
            } else this._showSetupStep(this._setupStep + 1);
        }); actions.append(this._setupNext);
        this._setupWindow.connect('close-request', () => { this._leaveSetup(); return true; });
        this._showSetupStep(this._setupState.get_int('setup-step'));
        this._setupWindow.present();
    }

    _showSetupStep(step) {
        this._setupStep = Math.max(0, Math.min(step, 3));
        this._setupState.set_int('setup-step', this._setupStep);
        this._setupStack.visible_child_name = String(this._setupStep);
        const titles = [_('Provider and privacy'), _('Microphone'), _('Shortcuts and insertion'), _('Test dictation')];
        this._setupProgress.label = `${this._setupStep + 1} of 4 · ${titles[this._setupStep]}`;
        this._setupBack.sensitive = this._setupStep > 0;
        this._setupNext.label = this._setupStep === 3 ? _('Finish setup') : [0, 2].includes(this._setupStep) ? _('Save and continue') : _('Continue');
        this._setupNext.sensitive = this._setupStep !== 3 || this._setupTestPassed;
        if (this._setupStep === 1) this._runSetupChecks();
        if (this._setupStep === 3 && !this._testBusy && !this._setupTestPassed) {
            this._testResult.label = '';
            this._testCopyButton.sensitive = false;
            this._showTestState('ready');
        }
    }

    _runSetupChecks() {
        const missing = [];
        if (!GLib.find_program_in_path('ffmpeg') && !GLib.file_test('/usr/bin/ffmpeg', GLib.FileTest.IS_EXECUTABLE)) missing.push(_('Install FFmpeg, then check again.'));
        if (this._draftSettings.get_string('stt-provider') === 'local') {
            if (!GLib.find_program_in_path('whisper-cli') && !GLib.file_test('/usr/bin/whisper-cli', GLib.FileTest.IS_EXECUTABLE)) missing.push(_('Install whisper-cli for local transcription.'));
            const model = this.dir?.get_child('models').get_child('ggml-base.en.bin').get_path();
            if (!model || !GLib.file_test(model, GLib.FileTest.IS_REGULAR)) missing.push(_('Local model missing. Open the GNOME install guide to download ggml-base.en.bin.'));
        }
        this._checkRow.subtitle = missing.length ? missing.join(' ') : _('Recording tools found. Check Sound input, then try a dictation.');
        Gio.DBus.session.call('io.github.tnfssc.OpenWispr.Recorder', '/io/github/tnfssc/OpenWispr/Recorder',
            'io.github.tnfssc.OpenWispr.Recorder', 'Status', null, null, Gio.DBusCallFlags.NONE, 5000, null, (conn, res) => {
                if (!this._setupWindow) return;
                try { conn.call_finish(res); }
                catch (e) { this._checkRow.subtitle += _(' Companion unavailable. Install the companion from Settings → Companion Setup, or start openwispr-engine.service.'); }
            });
    }

    _runDoctor(button) {
        const binary = GLib.find_program_in_path('openwispr') || GLib.build_filenamev([GLib.get_home_dir(), '.local', 'bin', 'openwispr']);
        button.sensitive = false;
        try {
            const proc = Gio.Subprocess.new([binary, 'doctor'], Gio.SubprocessFlags.STDOUT_PIPE | Gio.SubprocessFlags.STDERR_PIPE);
            proc.communicate_utf8_async(null, null, (p, res) => {
                if (!this._setupWindow) return;
                button.sensitive = true;
                try {
                    const [, out, err] = p.communicate_utf8_finish(res);
                    this._doctorResult.label = (out + err).trim();
                } catch (e) { this._doctorResult.label = _('Could not run doctor. Check that the companion is installed.'); }
            });
        } catch (e) { button.sensitive = true; this._doctorResult.label = _('Companion not found. Install it from Settings → Companion Setup.'); }
    }

    _ensureSetupTestSignals() {
        if (this._testSignalId) return;
        this._testSignalId = Gio.DBus.session.signal_subscribe('org.gnome.Shell.Extensions.OpenWispr',
            'org.gnome.Shell.Extensions.OpenWispr', 'TestState', '/org/gnome/Shell/Extensions/OpenWispr', null,
            Gio.DBusSignalFlags.NONE, (_conn, _sender, _path, _iface, _name, params) => {
                const [session, ...state] = params.deep_unpack();
                if (this._setupWindow && session === this._setupTestSession) this._showTestState(...state);
            });
    }

    _callSetupTest(method) {
        this._ensureSetupTestSignals();
        const window = this._setupWindow;
        const session = this._setupTestSession;
        Gio.DBus.session.call('org.gnome.Shell.Extensions.OpenWispr', '/org/gnome/Shell/Extensions/OpenWispr',
            'org.gnome.Shell.Extensions.OpenWispr', method, new GLib.Variant('(s)', [session || '']), null, Gio.DBusCallFlags.NONE, 15000, null, (conn, res) => {
                if (this._setupWindow !== window || session !== this._setupTestSession) return;
                try {
                    const [accepted] = conn.call_finish(res).deep_unpack();
                    if (!accepted && method !== 'TestCancel') this._showTestState('failed', '', _('The recorder is busy or unavailable. Check the companion and finish other dictation.'));
                } catch (e) { this._showTestState('failed', '', _('Could not reach the extension. Enable OpenWispr and check the companion service.')); }
            });
    }

    _showTestState(state, text = '', error = '') {
        if (!this._setupWindow || this._setupStep !== 3) return;
        const busy = ['starting', 'recording', 'processing'].includes(state);
        this._testBusy = busy;
        this._testStartButton.sensitive = !busy;
        this._testStopButton.sensitive = state === 'recording';
        this._testCancelButton.sensitive = busy;
        if (state === 'result') {
            this._setupTestPassed = true;
            this._testResult.label = text;
            this._testCopyButton.sensitive = true;
        }
        const labels = {ready: _('Ready to test'), starting: _('Starting microphone…'), recording: _('Recording · say a short phrase'), processing: _('Processing · nothing will be pasted'),
            result: _('Your dictation worked'), empty: _('No speech detected'), cancelled: _('Test cancelled'), failed: _('Test did not finish')};
        if (state !== 'idle') {
            this._testStatus.title = labels[state] || _('Ready to test');
            this._testStatus.subtitle = error || (state === 'result' ? _('Select the text or copy it. Finish saves setup completion.') : '');
        }
        this._setupNext.sensitive = this._setupStep !== 3 || this._setupTestPassed;
    }

    _cancelSetupTest() {
        if (this._testBusy) {
            Gio.DBus.session.call('org.gnome.Shell.Extensions.OpenWispr', '/org/gnome/Shell/Extensions/OpenWispr',
                'org.gnome.Shell.Extensions.OpenWispr', 'TestCancel', new GLib.Variant('(s)', [this._setupTestSession || '']), null, Gio.DBusCallFlags.NONE, 15000, null, null);
        }
        this._setupTestSession = null;
        this._testBusy = false;
    }

    _leaveSetup() {
        if (!this._setupWindow) return;
        this._cancelSetupTest();
        if (this._testSignalId) { Gio.DBus.session.signal_unsubscribe(this._testSignalId); this._testSignalId = null; }
        this._setupProviderGroups[0].description = this._sttDescription;
        for (const group of this._setupProviderGroups) { this._setupPages[0].remove(group); this._settingsPage.add(group); }
        for (const row of this._setupExtraShortcutRows) row.visible = true;
        this._setupPages[2].remove(this._setupShortcutGroup); this._settingsPage.add(this._setupShortcutGroup);
        const window = this._setupWindow; this._setupWindow = null;
        this._testBusy = false;
        window.destroy();
    }

    _markUnsaved() {
        this._saveRow.subtitle = _('Unsaved changes. Click Save to apply.');
    }

    _registerTextSetting(widget, key, validate = () => true, error = '') {
        this._textSettings.push({ widget, key, validate, error });
        widget.connect('notify::text', () => {
            widget.remove_css_class('error');
            this._markUnsaved();
        });
    }

    _saveSettings(window, settings) {
        const toastHost = this._setupWindow ? this._setupToastOverlay : window;
        // Read even the currently focused field; no Enter/focus-out is needed.
        const edits = this._textSettings.map(edit => ({
            ...edit,
            value: edit.widget.text.trim(),
        }));
        let invalidEdit = null;
        for (const edit of edits) {
            if (!edit.validate(edit.value)) {
                edit.widget.add_css_class('error');
                invalidEdit ??= edit;
            } else {
                edit.widget.remove_css_class('error');
            }
        }
        if (invalidEdit) {
            this._saveRow.subtitle = _('Settings were not saved. Fix the highlighted fields and click Save.');
            toastHost.add_toast(new Adw.Toast({ title: invalidEdit.error }));
            invalidEdit.widget.grab_focus();
            return false;
        }

        for (const edit of edits) {
            if (settings.get_string(edit.key) !== edit.value &&
                !settings.set_string(edit.key, edit.value)) {
                this._saveRow.subtitle = _('Settings were not saved. A setting could not be written.');
                toastHost.add_toast(new Adw.Toast({ title: this._saveRow.subtitle }));
                return false;
            }
        }
        for (const row of this._spinRows)
            row.update();
        settings.apply();
        Gio.Settings.sync();
        this._saveRow.subtitle = _('Settings saved.');
        toastHost.add_toast(new Adw.Toast({ title: _('Settings saved.') }));
        return true;
    }

    _addEntryRow(group, settings, key, title) {
        const row = new Adw.EntryRow({
            title,
            text: settings.get_string(key),
        });
        const isEndpoint = key.endsWith('-endpoint');
        this._registerTextSetting(row, key,
            value => !isEndpoint || !value || /^https?:\/\//i.test(value),
            _('Endpoint URLs must start with http:// or https://.'));
        group.add(row);
        return row;
    }

    _addSecretRow(group, settings, key, title) {
        const row = new Adw.ActionRow({ title });

        const entry = new Gtk.PasswordEntry({
            text: settings.get_string(key),
            show_peek_icon: true,
            valign: Gtk.Align.CENTER,
            width_chars: 24,
        });
        this._registerTextSetting(entry, key);

        row.add_suffix(entry);
        row.activatable_widget = entry;
        group.add(row);
        return row;
    }

    _addMultilineEditorRow(window, group, settings, key, title, subtitle) {
        const row = new Adw.ActionRow({
            title,
            subtitle,
        });

        const editButton = new Gtk.Button({
            label: _('Edit'),
            valign: Gtk.Align.CENTER,
        });
        editButton.connect('clicked', () => {
            this._showMultilineEditDialog(window, title, settings.get_string(key), text => {
                settings.set_string(key, text);
            });
        });

        row.add_suffix(editButton);
        row.activatable_widget = editButton;
        group.add(row);
    }

    _showMultilineEditDialog(window, title, initialText, onSave) {
        window = this._setupWindow || window;
        const dialog = new Gtk.Window({
            title,
            transient_for: window,
            modal: true,
            resizable: true,
            default_width: DIALOG_DEFAULT_WIDTH,
            default_height: DIALOG_DEFAULT_HEIGHT,
        });

        const content = new Gtk.Box({
            orientation: Gtk.Orientation.VERTICAL,
            spacing: 10,
            margin_top: 12,
            margin_bottom: 12,
            margin_start: 12,
            margin_end: 12,
        });

        const scrolled = new Gtk.ScrolledWindow({
            hexpand: true,
            vexpand: true,
            min_content_height: MULTILINE_MIN_CONTENT_HEIGHT,
        });

        const textView = new Gtk.TextView({
            wrap_mode: Gtk.WrapMode.WORD_CHAR,
            monospace: true,
            top_margin: 8,
            bottom_margin: 8,
            left_margin: 8,
            right_margin: 8,
        });
        const buffer = textView.get_buffer();
        buffer.set_text(initialText, -1);
        scrolled.set_child(textView);
        content.append(scrolled);

        const actions = new Gtk.Box({
            orientation: Gtk.Orientation.HORIZONTAL,
            spacing: 8,
            halign: Gtk.Align.END,
        });

        const cancelButton = new Gtk.Button({ label: _('Cancel') });
        cancelButton.connect('clicked', () => dialog.close());

        const saveButton = new Gtk.Button({
            label: _('Done'),
            css_classes: ['suggested-action'],
        });
        saveButton.connect('clicked', () => {
            const [start, end] = buffer.get_bounds();
            onSave(buffer.get_text(start, end, false));
            dialog.close();
        });

        actions.append(cancelButton);
        actions.append(saveButton);
        content.append(actions);

        dialog.set_child(content);
        dialog.present();
    }

    _addShortcutCaptureRow(window, group, settings, key, title, subtitle) {
        const defaultAccelerator = this._getDefaultShortcut(settings, key);

        const row = new Adw.ActionRow({
            title,
            subtitle,
        });

        const buttonBox = new Gtk.Box({
            orientation: Gtk.Orientation.HORIZONTAL,
            spacing: 6,
            valign: Gtk.Align.CENTER,
        });

        const setButton = new Gtk.Button({
            label: this._formatShortcutLabel(settings.get_strv(key)[0] || ''),
            valign: Gtk.Align.CENTER,
        });
        setButton.connect('clicked', () => {
            this._showShortcutCaptureDialog(window, accelerator => {
                settings.set_strv(key, accelerator ? [accelerator] : []);
                setButton.set_label(this._formatShortcutLabel(accelerator));
            });
        });

        const resetButton = new Gtk.Button({
            label: _('Reset'),
            valign: Gtk.Align.CENTER,
        });
        resetButton.connect('clicked', () => {
            settings.set_strv(key, defaultAccelerator ? [defaultAccelerator] : []);
            setButton.set_label(this._formatShortcutLabel(defaultAccelerator));
        });

        const clearButton = new Gtk.Button({
            label: _('Clear'),
            valign: Gtk.Align.CENTER,
        });
        clearButton.connect('clicked', () => {
            settings.set_strv(key, []);
            setButton.set_label(this._formatShortcutLabel(''));
        });

        settings.connect(`changed::${key}`, () => {
            const current = settings.get_strv(key)[0] || '';
            setButton.set_label(this._formatShortcutLabel(current));
        });

        buttonBox.append(setButton);
        buttonBox.append(resetButton);
        buttonBox.append(clearButton);
        row.add_suffix(buttonBox);
        row.activatable_widget = setButton;
        group.add(row);
    }

    _showShortcutCaptureDialog(window, onCaptured) {
        window = this._setupWindow || window;
        const dialog = new Gtk.Window({
            title: _('Set Shortcut'),
            transient_for: window,
            modal: true,
            resizable: false,
            default_width: SHORTCUT_DIALOG_WIDTH,
            default_height: SHORTCUT_DIALOG_HEIGHT,
        });

        const content = new Gtk.Box({
            orientation: Gtk.Orientation.VERTICAL,
            spacing: 8,
            margin_top: 16,
            margin_bottom: 16,
            margin_start: 16,
            margin_end: 16,
        });

        const instructionLabel = new Gtk.Label({
            label: _('Press a key combination now. Press Esc to cancel.'),
            wrap: true,
            xalign: 0,
        });
        content.append(instructionLabel);

        const cancelButton = new Gtk.Button({
            label: _('Cancel'),
            halign: Gtk.Align.END,
        });
        cancelButton.connect('clicked', () => dialog.close());
        content.append(cancelButton);

        dialog.set_child(content);

        const keyController = new Gtk.EventControllerKey();
        keyController.connect('key-pressed', (_controller, keyval, _keycode, state) => {
            if (keyval === Gdk.KEY_Escape) {
                dialog.close();
                return Gdk.EVENT_STOP;
            }

            if (this._isModifierKey(keyval))
                return Gdk.EVENT_STOP;

            const modifierMask = state & Gtk.accelerator_get_default_mod_mask();
            if (!Gtk.accelerator_valid(keyval, modifierMask)) {
                instructionLabel.set_label(_('Invalid shortcut. Use one key plus optional modifiers.'));
                return Gdk.EVENT_STOP;
            }

            const accelerator = Gtk.accelerator_name(keyval, modifierMask);

            if (!accelerator)
                return Gdk.EVENT_STOP;

            onCaptured(accelerator);
            dialog.close();
            return Gdk.EVENT_STOP;
        });

        dialog.add_controller(keyController);
        dialog.present();
    }

    _isModifierKey(keyval) {
        return [
            Gdk.KEY_Shift_L,
            Gdk.KEY_Shift_R,
            Gdk.KEY_Control_L,
            Gdk.KEY_Control_R,
            Gdk.KEY_Alt_L,
            Gdk.KEY_Alt_R,
            Gdk.KEY_Meta_L,
            Gdk.KEY_Meta_R,
            Gdk.KEY_Super_L,
            Gdk.KEY_Super_R,
            Gdk.KEY_Hyper_L,
            Gdk.KEY_Hyper_R,
        ].includes(keyval);
    }

    _formatShortcutLabel(accelerator) {
        if (!accelerator)
            return _('Not set');

        const modifierTokens = [...accelerator.matchAll(/<([^>]+)>/g)]
            .map(match => match[1].trim().toLowerCase());
        const keyToken = accelerator.replace(/<[^>]+>/g, '').trim();

        const modifiers = modifierTokens
            .map(token => {
                if (token === 'control' || token === 'ctrl' || token === 'primary')
                    return _('Ctrl');
                if (token === 'shift')
                    return _('Shift');
                if (token === 'alt' || token === 'mod1')
                    return _('Alt');
                if (token === 'super' || token === 'meta' || token === 'mod4')
                    return _('Super');
                return token;
            })
            .filter(Boolean);

        if (keyToken)
            modifiers.push(keyToken.length === 1 ? keyToken.toUpperCase() : keyToken);

        return modifiers.length > 0 ? modifiers.join(' + ') : accelerator;
    }

    _getDefaultShortcut(settings, key) {
        const defaultVariant = settings.get_default_value(key);
        if (!defaultVariant)
            throw new Error(`unknown gsettings key: ${key}`);
        const defaultValue = defaultVariant.deep_unpack();

        if (!Array.isArray(defaultValue) || defaultValue.length === 0)
            return '';

        return defaultValue[0] || '';
    }

    _addLinkRow(group, title, subtitle, url) {
        const row = new Adw.ActionRow({
            title,
            subtitle,
        });

        const openButton = new Gtk.Button({
            label: _('Open'),
            valign: Gtk.Align.CENTER,
        });
        openButton.connect('clicked', () => this._openUri(url));

        row.add_suffix(openButton);
        row.activatable_widget = openButton;
        group.add(row);
        return row;
    }

    _addCommandRow(group, title, subtitle, command) {
        const row = new Adw.ActionRow({
            title,
            subtitle,
        });

        const copyButton = new Gtk.Button({
            label: _('Copy'),
            valign: Gtk.Align.CENTER,
        });
        copyButton.connect('clicked', () => this._copyToClipboard(command));

        row.add_suffix(copyButton);
        row.activatable_widget = copyButton;
        group.add(row);
    }

    _copyToClipboard(text) {
        const display = Gdk.Display.get_default();
        if (!display) {
            console.error('[openwispr] no display');
            return;
        }
        const clipboard = display.get_clipboard();
        if (!clipboard) {
            console.error('[openwispr] no clipboard');
            return;
        }
        clipboard.set(text);
    }

    _openUri(url) {
        try {
            if (!Gio.AppInfo.launch_default_for_uri(url, null))
                console.error(`[openwispr] no handler for ${url}`);
        } catch (e) {
            console.error(`[openwispr-gnome-extension] Failed to open URL: ${e}`);
        }
    }

    _normalizeProvider(provider) {
        if (provider === 'grok')
            return 'groq';

        return provider;
    }
}
