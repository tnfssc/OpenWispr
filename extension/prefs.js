import Gio from 'gi://Gio';
import Adw from 'gi://Adw';
import Gtk from 'gi://Gtk?version=4.0';

import { ExtensionPreferences, gettext as _ } from 'resource:///org/gnome/Shell/Extensions/js/extensions/prefs.js';

export default class OpenWisprPreferences extends ExtensionPreferences {
    fillPreferencesWindow(window) {
        const settings = this.getSettings();

        const page = new Adw.PreferencesPage();
        window.add(page);

        const group = new Adw.PreferencesGroup({
            title: _('Shortcuts'),
        });
        page.add(group);

        const holdToSpeakRow = new Adw.SwitchRow({
            title: _('Hold to Speak'),
            subtitle: _('Hold your selected trigger key to record. Release to transcribe.'),
            active: settings.get_boolean('hold-to-speak-enabled'),
        });
        group.add(holdToSpeakRow);

        holdToSpeakRow.connect('notify::active', () => {
            settings.set_boolean('hold-to-speak-enabled', holdToSpeakRow.active);
        });

        const autoPasteRow = new Adw.SwitchRow({
            title: _('Auto Paste Transcription'),
            subtitle: _('Turn off for apps where paste causes side effects.'),
            active: settings.get_boolean('auto-paste-enabled'),
        });
        group.add(autoPasteRow);

        autoPasteRow.connect('notify::active', () => {
            settings.set_boolean('auto-paste-enabled', autoPasteRow.active);
        });

        const holdTriggerOptions = [
            { id: 'ctrl-slash', label: _('Ctrl+/ (Recommended)') },
            { id: 'ctrl-space', label: _('Ctrl+Space') },
            { id: 'right-ctrl', label: _('Right Ctrl') },
            { id: 'f8', label: _('F8') },
            { id: 'f9', label: _('F9') },
        ];

        const holdTriggerModel = Gtk.StringList.new(holdTriggerOptions.map(option => option.label));
        const holdTriggerRow = new Adw.ComboRow({
            title: _('Hold Trigger Key'),
            subtitle: _('Used only when Hold to Speak is enabled.'),
            model: holdTriggerModel,
        });

        const currentHoldTrigger = settings.get_string('hold-to-speak-trigger');
        const currentHoldTriggerIndex = holdTriggerOptions.findIndex(option => option.id === currentHoldTrigger);
        holdTriggerRow.selected = currentHoldTriggerIndex >= 0 ? currentHoldTriggerIndex : 0;

        holdTriggerRow.connect('notify::selected', () => {
            const selectedOption = holdTriggerOptions[holdTriggerRow.selected] || holdTriggerOptions[0];
            settings.set_string('hold-to-speak-trigger', selectedOption.id);
        });
        group.add(holdTriggerRow);

        const currentShortcut = settings.get_strv('toggle-recording')[0] || '';

        const shortcutRow = new Adw.EntryRow({
            title: _('Toggle Recording'),
            text: currentShortcut,
        });
        group.add(shortcutRow);

        shortcutRow.connect('apply', () => {
            const text = shortcutRow.text;
            if (text) {
                settings.set_strv('toggle-recording', [text]);
            }
        });
    }
}
