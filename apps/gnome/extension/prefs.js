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

        const page = new Adw.PreferencesPage();
        window.add(page);

        const companionGroup = new Adw.PreferencesGroup({
            title: _('Companion Setup'),
            description: _('Recording/transcription runs through the openwispr companion user service.'),
        });
        page.add(companionGroup);

        const releasesUrl = 'https://github.com/tnfssc/openwispr-gnome-extension/releases/latest';
        const installCommand = [
            'ARCH="$(uname -m)"',
            'case "$ARCH" in x86_64) BIN=openwispr-linux-amd64 ;; aarch64|arm64) BIN=openwispr-linux-arm64 ;; *) echo "Unsupported arch: $ARCH"; exit 1 ;; esac',
            'REPO="https://github.com/tnfssc/openwispr-gnome-extension/releases/latest/download"',
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
            _('Click Set, then press the key combination.')
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

        this._addShortcutCaptureRow(
            window,
            shortcutsGroup,
            settings,
            'toggle-recording',
            _('Toggle Recording'),
            _('Click Set, then press the key combination.')
        );

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
        // Validate the ffmpeg threshold format before persisting. Use the
        // `apply` signal (fires on Enter / focus-out) so we don't hammer dconf
        // on every keystroke.
        silenceThresholdRow.connect('apply', () => {
            const value = silenceThresholdRow.text.trim();
            if (!/^-?\d+(\.\d+)?dB$/.test(value)) {
                silenceThresholdRow.add_css_class('error');
                return;
            }
            silenceThresholdRow.remove_css_class('error');
            settings.set_string('silence-threshold', value);
        });
        audioGroup.add(silenceThresholdRow);

        const silenceDurationAdjustment = new Gtk.Adjustment({
            lower: 0.05,
            upper: 5.0,
            step_increment: 0.05,
            page_increment: 0.1,
            value: settings.get_double('silence-duration'),
        });
        // The adjustment clamps the stored value into [0.05, 5.0]; write the
        // clamped value back so GSettings never holds a stale out-of-range number.
        if (settings.get_double('silence-duration') !== silenceDurationAdjustment.value)
            settings.set_double('silence-duration', silenceDurationAdjustment.value);
        const silenceDurationRow = new Adw.SpinRow({
            title: _('Silence Duration (seconds)'),
            adjustment: silenceDurationAdjustment,
            digits: 2,
        });
        silenceDurationRow.connect('notify::value', () => settings.set_double('silence-duration', silenceDurationRow.value));
        audioGroup.add(silenceDurationRow);

        const sttGroup = new Adw.PreferencesGroup({ title: _('Speech to Text') });
        page.add(sttGroup);

        const sttProviderOptions = [
            { id: 'local', label: _('Local whisper-cli') },
            { id: 'openai', label: _('OpenAI Whisper Endpoint') },
            { id: 'groq', label: _('Groq Endpoint') },
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

        const sttOpenAiEndpointRow = this._addEntryRow(sttGroup, settings, 'stt-openai-endpoint', _('OpenAI STT Endpoint'));
        const sttOpenAiModelRow = this._addEntryRow(sttGroup, settings, 'stt-openai-model', _('OpenAI STT Model'));
        const sttOpenAiApiKeyRow = this._addSecretRow(sttGroup, settings, 'stt-openai-api-key', _('OpenAI STT API Key'));
        const sttGroqEndpointRow = this._addEntryRow(sttGroup, settings, 'stt-groq-endpoint', _('Groq STT Endpoint'));
        const sttGroqModelRow = this._addEntryRow(sttGroup, settings, 'stt-groq-model', _('Groq STT Model'));
        const sttGroqApiKeyRow = this._addSecretRow(sttGroup, settings, 'stt-groq-api-key', _('Groq STT API Key'));

        const updateSttProviderVisibility = providerId => {
            const showOpenAi = providerId === 'openai';
            const showGroq = providerId === 'groq';

            sttOpenAiEndpointRow.set_visible(showOpenAi);
            sttOpenAiModelRow.set_visible(showOpenAi);
            sttOpenAiApiKeyRow.set_visible(showOpenAi);
            sttGroqEndpointRow.set_visible(showGroq);
            sttGroqModelRow.set_visible(showGroq);
            sttGroqApiKeyRow.set_visible(showGroq);
        };

        updateSttProviderVisibility(sttProviderOptions[sttProviderRow.selected]?.id || sttProviderOptions[0].id);
        sttProviderRow.connect('notify::selected', () => {
            const selected = sttProviderOptions[sttProviderRow.selected] || sttProviderOptions[0];
            settings.set_string('stt-provider', selected.id);
            updateSttProviderVisibility(selected.id);
        });

        const llmGroup = new Adw.PreferencesGroup({ title: _('LLM Cleanup') });
        page.add(llmGroup);

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

        const updateLlmProviderVisibility = providerId => {
            const showOpenAi = providerId === 'openai';
            const showGroq = providerId === 'groq';

            llmOpenAiEndpointRow.set_visible(showOpenAi);
            llmOpenAiModelRow.set_visible(showOpenAi);
            llmOpenAiApiKeyRow.set_visible(showOpenAi);
            llmGroqEndpointRow.set_visible(showGroq);
            llmGroqModelRow.set_visible(showGroq);
            llmGroqApiKeyRow.set_visible(showGroq);
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
    }

    _addEntryRow(group, settings, key, title) {
        const row = new Adw.EntryRow({
            title,
            text: settings.get_string(key),
        });
        const isEndpoint = key.endsWith('-endpoint');
        // Use the `apply` signal (libadwaita 1.2+, fires on Enter / focus-out)
        // so we write to dconf once per committed edit instead of on every
        // keystroke. Trim whitespace and, for endpoint keys, require an
        // http(s) scheme so a malformed URL can't silently break the pipeline.
        row.connect('apply', () => {
            const value = row.text.trim();
            if (isEndpoint && value && !/^https?:\/\//i.test(value)) {
                row.add_css_class('error');
                return;
            }
            row.remove_css_class('error');
            settings.set_string(key, value);
        });
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
        // Gtk.PasswordEntry has no `apply` signal; commit on Enter and on
        // focus-out instead of every keystroke. Trim whitespace so a stray
        // newline or space can't silently invalidate the API key.
        const commit = () => settings.set_string(key, entry.text.trim());
        entry.connect('activate', commit);
        const focusController = new Gtk.EventControllerFocus();
        focusController.connect('leave', commit);
        entry.add_controller(focusController);

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
            label: _('Save'),
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
