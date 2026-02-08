import Adw from 'gi://Adw';
import Gtk from 'gi://Gtk?version=4.0';

import { ExtensionPreferences, gettext as _ } from 'resource:///org/gnome/Shell/Extensions/js/extensions/prefs.js';

export default class OpenWisprPreferences extends ExtensionPreferences {
    fillPreferencesWindow(window) {
        const settings = this.getSettings();

        const page = new Adw.PreferencesPage();
        window.add(page);

        const shortcutsGroup = new Adw.PreferencesGroup({ title: _('Shortcuts') });
        page.add(shortcutsGroup);

        const holdToSpeakRow = new Adw.SwitchRow({
            title: _('Hold to Speak'),
            subtitle: _('Hold Ctrl+Alt+Space to record. Release to transcribe.'),
            active: settings.get_boolean('hold-to-speak-enabled'),
        });
        holdToSpeakRow.connect('notify::active', () => settings.set_boolean('hold-to-speak-enabled', holdToSpeakRow.active));
        shortcutsGroup.add(holdToSpeakRow);

        const autoPasteRow = new Adw.SwitchRow({
            title: _('Auto Paste Transcription'),
            subtitle: _('Turn off for apps where paste causes side effects.'),
            active: settings.get_boolean('auto-paste-enabled'),
        });
        autoPasteRow.connect('notify::active', () => settings.set_boolean('auto-paste-enabled', autoPasteRow.active));
        shortcutsGroup.add(autoPasteRow);

        const notificationsRow = new Adw.SwitchRow({
            title: _('Enable Notifications'),
            subtitle: _('Show status and transcription notifications.'),
            active: settings.get_boolean('notifications-enabled'),
        });
        notificationsRow.connect('notify::active', () => settings.set_boolean('notifications-enabled', notificationsRow.active));
        shortcutsGroup.add(notificationsRow);

        const currentShortcut = settings.get_strv('toggle-recording')[0] || '';
        const shortcutRow = new Adw.EntryRow({
            title: _('Toggle Recording'),
            text: currentShortcut,
        });
        shortcutRow.connect('apply', () => {
            if (shortcutRow.text)
                settings.set_strv('toggle-recording', [shortcutRow.text]);
        });
        shortcutsGroup.add(shortcutRow);

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
        silenceThresholdRow.connect('notify::text', () => settings.set_string('silence-threshold', silenceThresholdRow.text));
        audioGroup.add(silenceThresholdRow);

        const silenceDurationAdjustment = new Gtk.Adjustment({
            lower: 0.05,
            upper: 5.0,
            step_increment: 0.05,
            page_increment: 0.1,
            value: settings.get_double('silence-duration'),
        });
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
        sttProviderRow.selected = sttIndex >= 0 ? sttIndex : 0;
        sttProviderRow.connect('notify::selected', () => {
            const selected = sttProviderOptions[sttProviderRow.selected] || sttProviderOptions[0];
            settings.set_string('stt-provider', selected.id);
        });
        sttGroup.add(sttProviderRow);

        this._addEntryRow(sttGroup, settings, 'stt-openai-endpoint', _('OpenAI STT Endpoint'));
        this._addEntryRow(sttGroup, settings, 'stt-openai-model', _('OpenAI STT Model'));
        this._addEntryRow(sttGroup, settings, 'stt-openai-api-key', _('OpenAI STT API Key'));

        this._addEntryRow(sttGroup, settings, 'stt-groq-endpoint', _('Groq STT Endpoint'));
        this._addEntryRow(sttGroup, settings, 'stt-groq-model', _('Groq STT Model'));
        this._addEntryRow(sttGroup, settings, 'stt-groq-api-key', _('Groq STT API Key'));

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
        llmProviderRow.selected = llmIndex >= 0 ? llmIndex : 0;
        llmProviderRow.connect('notify::selected', () => {
            const selected = llmProviderOptions[llmProviderRow.selected] || llmProviderOptions[0];
            settings.set_string('llm-provider', selected.id);
        });
        llmGroup.add(llmProviderRow);

        this._addEntryRow(llmGroup, settings, 'llm-openai-endpoint', _('OpenAI LLM Endpoint'));
        this._addEntryRow(llmGroup, settings, 'llm-openai-model', _('OpenAI LLM Model'));
        this._addEntryRow(llmGroup, settings, 'llm-openai-api-key', _('OpenAI LLM API Key'));

        this._addEntryRow(llmGroup, settings, 'llm-groq-endpoint', _('Groq LLM Endpoint'));
        this._addEntryRow(llmGroup, settings, 'llm-groq-model', _('Groq LLM Model'));
        this._addEntryRow(llmGroup, settings, 'llm-groq-api-key', _('Groq LLM API Key'));

        const llmPromptRow = new Adw.EntryRow({
            title: _('LLM Cleanup Prompt'),
            text: settings.get_string('llm-cleanup-prompt'),
        });
        llmPromptRow.connect('notify::text', () => settings.set_string('llm-cleanup-prompt', llmPromptRow.text));
        llmGroup.add(llmPromptRow);
    }

    _addEntryRow(group, settings, key, title) {
        const row = new Adw.EntryRow({
            title,
            text: settings.get_string(key),
        });
        row.connect('notify::text', () => settings.set_string(key, row.text));
        group.add(row);
    }

    _normalizeProvider(provider) {
        if (provider === 'grok')
            return 'groq';

        return provider;
    }
}
