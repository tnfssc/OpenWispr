// Run: gjs -m test_preferences.js (requires GTK4, libadwaita and a display).
// Uses real widgets and an isolated memory backend; never writes user settings.
import Adw from 'gi://Adw';
import Gio from 'gi://Gio';
import GLib from 'gi://GLib';
import Gtk from 'gi://Gtk';

Adw.init();

function assert(condition, message) {
    if (!condition)
        throw new Error(message);
}

function* widgets(widget) {
    yield widget;
    for (let child = widget.get_first_child(); child; child = child.get_next_sibling())
        yield* widgets(child);
}

const directory = GLib.dir_make_tmp('openwispr-prefs-test-XXXXXX');
const files = ['prefs.js', 'org.gnome.shell.extensions.openwispr.gschema.xml', 'gschemas.compiled'];
try {
    const schemaFile = files[1];
    Gio.File.new_for_path(`extension/schemas/${schemaFile}`).copy(
        Gio.File.new_for_path(`${directory}/${schemaFile}`), Gio.FileCopyFlags.NONE, null, null);
    const [, , stderr, status] = GLib.spawn_sync(null,
        ['glib-compile-schemas', directory], null, GLib.SpawnFlags.SEARCH_PATH, null);
    assert(status === 0, new TextDecoder().decode(stderr));
    const source = Gio.SettingsSchemaSource.new_from_directory(directory, null, false);
    const schema = source.lookup('org.gnome.shell.extensions.openwispr', false);
    const backend = Gio.memory_settings_backend_new();
    const draft = new Gio.Settings({ settings_schema: schema, backend });
    const saved = new Gio.Settings({ settings_schema: schema, backend });

    // Supply the GNOME preferences host only; the app, widgets and GSettings
    // remain real. This allows the test to run outside gnome-extensions prefs.
    const [, contents] = GLib.file_get_contents('extension/prefs.js');
    const hostImport = "import { ExtensionPreferences, gettext as _ } from 'resource:///org/gnome/Shell/Extensions/js/extensions/prefs.js';";
    const code = new TextDecoder().decode(contents);
    assert(code.includes(hostImport), 'Preferences host import changed');
    GLib.file_set_contents(`${directory}/prefs.js`, code.replace(hostImport,
        'class ExtensionPreferences {}\nconst _ = text => text;'));
    const { default: Preferences } = await import(`file://${directory}/prefs.js`);
    const prefs = new Preferences();
    prefs.getSettings = () => draft;
    const window = new Adw.PreferencesWindow();
    prefs.fillPreferencesWindow(window);

    const find = predicate => [...widgets(window)].find(predicate);
    const row = title => find(widget => widget instanceof Adw.PreferencesRow && widget.title === title);
    const button = (root, label) => [...widgets(root)].find(widget =>
        widget instanceof Gtk.Button && widget.label === label);
    const save = button(window, 'Save');
    assert(save !== undefined, 'An explicit Save button is visible');
    const endpoint = row('Groq STT Endpoint');
    const model = row('Groq STT Model');
    const apiKey = [...widgets(row('Groq STT API Key'))].find(widget => widget instanceof Gtk.PasswordEntry);
    const threshold = row('Silence Threshold');
    const duration = row('Silence Duration (seconds)');
    const notifications = row('Enable Notifications');
    const oldEndpoint = saved.get_string('stt-groq-endpoint');
    const oldNotifications = saved.get_boolean('notifications-enabled');
    const oldPrompt = saved.get_string('llm-cleanup-prompt');

    endpoint.text = ' https://example.com/transcriptions ';
    model.text = ' test-model ';
    apiKey.text = ' test-api-key ';
    apiKey.emit('activate');
    notifications.active = !oldNotifications;
    duration.set_text('0.75');
    prefs._showShortcutCaptureDialog = (_window, captured) => captured('<Control><Alt>w');
    const shortcut = row('Toggle Recording');
    button(shortcut, 'Not set').emit('clicked');
    prefs._showMultilineEditDialog = (_window, _title, _initial, done) => done('Test cleanup prompt');
    button(row('LLM Cleanup Prompt'), 'Edit').emit('clicked');
    assert(saved.get_string('stt-groq-endpoint') === oldEndpoint, 'Typing must not persist text');
    assert(saved.get_string('stt-groq-api-key') === '', 'Enter must not persist API keys');
    assert(saved.get_boolean('notifications-enabled') === oldNotifications, 'Switches wait for Save');
    assert(saved.get_strv('toggle-recording').length === 0, 'Shortcuts wait for Save');
    assert(saved.get_string('llm-cleanup-prompt') === oldPrompt, 'Prompt edits wait for Save');

    endpoint.text = 'invalid-endpoint';
    threshold.text = 'invalid-threshold';
    save.emit('clicked');
    assert(endpoint.has_css_class('error') && threshold.has_css_class('error'), 'Invalid fields are highlighted');
    assert(saved.get_boolean('notifications-enabled') === oldNotifications, 'Invalid edits block the entire save');
    assert(saved.get_string('stt-groq-api-key') === '', 'Invalid edits cannot partially save API keys');

    endpoint.text = ' https://example.com/transcriptions ';
    threshold.text = ' -42dB ';
    save.emit('clicked');
    assert(saved.get_string('stt-groq-endpoint') === 'https://example.com/transcriptions', 'Save commits trimmed endpoint without Enter');
    assert(saved.get_string('stt-groq-model') === 'test-model', 'Save commits model without Enter');
    assert(saved.get_string('stt-groq-api-key') === 'test-api-key', 'Save commits trimmed API key');
    assert(saved.get_string('silence-threshold') === '-42dB', 'Save commits threshold');
    assert(saved.get_double('silence-duration') === 0.75, 'Save commits typed spin value');
    assert(saved.get_boolean('notifications-enabled') === !oldNotifications, 'Save commits switches');
    assert(saved.get_strv('toggle-recording')[0] === '<Control><Alt>w', 'Save commits shortcuts');
    assert(saved.get_string('llm-cleanup-prompt') === 'Test cleanup prompt', 'Save commits prompt edits');
    assert(!draft.get_has_unapplied(), 'Save applies the entire transaction');
    assert(prefs._saveRow.subtitle === 'Settings saved.', 'Save displays confirmation');

    model.text = 'second-model';
    save.emit('clicked');
    assert(saved.get_string('stt-groq-model') === 'second-model', 'Repeated saves commit new edits');
    notifications.active = oldNotifications;
    model.text = 'discard-this-model';
    window.emit('close-request');
    assert(saved.get_boolean('notifications-enabled') === !oldNotifications, 'Closing discards staged switches');
    assert(saved.get_string('stt-groq-model') === 'second-model', 'Closing discards unsaved text');
    assert(!draft.get_has_unapplied(), 'Closing clears staged changes');
    window.destroy();
    print('PASS: Preferences Save, validation, repeated saves and discard behavior.');
} finally {
    for (const file of files)
        GLib.unlink(`${directory}/${file}`);
    GLib.rmdir(directory);
}
