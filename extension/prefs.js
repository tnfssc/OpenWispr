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
