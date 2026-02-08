import Adw from 'gi://Adw';

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
            subtitle: _('Hold Ctrl+Alt+Space to record. Release to transcribe.'),
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

        const notificationsRow = new Adw.SwitchRow({
            title: _('Enable Notifications'),
            subtitle: _('Show status and transcription notifications.'),
            active: settings.get_boolean('notifications-enabled'),
        });
        group.add(notificationsRow);

        notificationsRow.connect('notify::active', () => {
            settings.set_boolean('notifications-enabled', notificationsRow.active);
        });

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
