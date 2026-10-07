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
    let settingsCalls = 0;
    prefs.getSettings = () => settingsCalls++ === 0 ? draft : saved;
    const window = new Adw.PreferencesWindow();
    prefs.fillPreferencesWindow(window);

    assert(prefs._setupIdle !== null && prefs._setupIdle !== undefined, 'A new unconfigured user is offered setup');
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
    // The wizard moves the same native controls, never creates divergent copies.
    if (prefs._setupIdle) { GLib.source_remove(prefs._setupIdle); prefs._setupIdle = null; }
    prefs._openSetup(window);
    assert(prefs._setupStep === 0, 'Setup begins with provider/privacy');
    const wizard = prefs._setupWindow;
    assert(wizard !== null && prefs._setupProviderGroups[0].get_parent() !== null, 'Wizard attaches existing provider controls');
    model.text = 'wizard-draft';
    prefs._showSetupStep(1);
    assert(saved.get_string('stt-groq-model') === 'second-model', 'Moving steps does not secretly save edits');
    prefs._setupBack.emit('clicked');
    assert(prefs._setupStep === 0 && model.text === 'wizard-draft', 'Back retains provider edits');
    prefs._setupNext.emit('clicked');
    assert(saved.get_string('stt-groq-model') === 'wizard-draft' && prefs._setupStep === 1, 'Save and continue deliberately applies edits');
    prefs._setupNext.emit('clicked');
    assert(prefs._setupStep === 2, 'Microphone step continues without hidden installs');
    prefs._setupNext.emit('clicked');
    assert(prefs._setupStep === 3 && !prefs._setupNext.sensitive, 'Completion requires a real result');
    prefs._leaveSetup();
    assert(prefs._setupWindow === null && prefs._setupProviderGroups[0].get_parent() !== null, 'Leave restores settings controls');
    saved.set_boolean('setup-requested', true);
    assert(prefs._setupWindow !== null && !saved.get_boolean('setup-requested'), 'Top-bar setup request reopens existing preferences');
    assert(prefs._setupStep === 3, 'Reopen resumes durable progress');
    assert(!prefs._setupNext.sensitive, 'Reopening does not invent a successful test');

    // Exercise actual asynchronous preferences D-Bus routing on an isolated bus.
    const conn = Gio.DBus.session;
    const apiXML = '<node><interface name="org.gnome.Shell.Extensions.OpenWispr"><method name="TestStart"><arg type="s" direction="in"/><arg type="b" direction="out"/></method><method name="TestStop"><arg type="s" direction="in"/><arg type="b" direction="out"/></method><method name="TestCancel"><arg type="s" direction="in"/><arg type="b" direction="out"/></method><signal name="TestState"><arg type="s"/><arg type="s"/><arg type="s"/><arg type="s"/></signal></interface></node>';
    let startCalls = 0;
    let stopCalls = 0;
    let deferStopResult = false;
    const emitState = (session, state, text = '') => GLib.idle_add(GLib.PRIORITY_DEFAULT_IDLE, () => {
        mock.emit_signal('TestState', new GLib.Variant('(ssss)', [session, state, text, '']));
        return GLib.SOURCE_REMOVE;
    });
    const mock = Gio.DBusExportedObject.wrapJSObject(apiXML, {
        TestStart: session => { startCalls++; emitState(session, 'recording'); return true; },
        TestStop: session => { stopCalls++; emitState(session, deferStopResult ? 'processing' : 'result', deferStopResult ? '' : 'This is my first dictation.'); return true; },
        TestCancel: session => { emitState(session, 'idle'); return true; },
    });
    mock.export(conn, '/org/gnome/Shell/Extensions/OpenWispr');
    let owner;
    await new Promise(resolve => { owner = Gio.bus_own_name_on_connection(conn, 'org.gnome.Shell.Extensions.OpenWispr', Gio.BusNameOwnerFlags.NONE, resolve, () => {}); });
    const waitFor = predicate => new Promise((resolve, reject) => {
        let attempts = 0;
        GLib.timeout_add(GLib.PRIORITY_DEFAULT, 10, () => {
            if (predicate()) { resolve(); return GLib.SOURCE_REMOVE; }
            if (++attempts > 300) { reject(new Error('Timed out waiting for wizard D-Bus state')); return GLib.SOURCE_REMOVE; }
            return GLib.SOURCE_CONTINUE;
        });
    });
    prefs._testStartButton.emit('clicked');
    await waitFor(() => prefs._testStopButton.sensitive);
    const abandonedSession = prefs._setupTestSession;
    prefs._setupBack.emit('clicked');
    prefs._setupNext.emit('clicked');
    assert(prefs._setupStep === 3 && prefs._testStartButton.sensitive, 'Back cancellation cannot strand a reopened test in busy state');
    emitState(abandonedSession, 'result', 'Stale words');
    await new Promise(resolve => GLib.timeout_add(GLib.PRIORITY_DEFAULT, 50, () => { resolve(); return GLib.SOURCE_REMOVE; }));
    assert(!prefs._setupTestPassed && !prefs._setupNext.sensitive && prefs._testResult.label === '', 'An abandoned test cannot complete later setup');
    prefs._testStartButton.emit('clicked');
    await waitFor(() => prefs._testStopButton.sensitive);
    assert(startCalls === 2 && !prefs._testStartButton.sensitive && prefs._testCancelButton.sensitive, 'Actual test Start requests recording and exposes Cancel');
    assert(!prefs._setupNext.sensitive, 'Recording is not successful completion');
    deferStopResult = true;
    const processingSession = prefs._setupTestSession;
    prefs._testStopButton.emit('clicked');
    await waitFor(() => prefs._testBusy && !prefs._testStopButton.sensitive);
    prefs._setupBack.emit('clicked');
    await new Promise(resolve => GLib.timeout_add(GLib.PRIORITY_DEFAULT, 50, () => { resolve(); return GLib.SOURCE_REMOVE; }));
    prefs._setupNext.emit('clicked');
    assert(prefs._setupStep === 3 && prefs._testStartButton.sensitive && !prefs._testStopButton.sensitive, 'Back during processing resets controls even after hidden cancellation completes');
    emitState(processingSession, 'result', 'Late processed words');
    await new Promise(resolve => GLib.timeout_add(GLib.PRIORITY_DEFAULT, 50, () => { resolve(); return GLib.SOURCE_REMOVE; }));
    assert(!prefs._setupTestPassed && !prefs._setupNext.sensitive, 'A late processed result cannot finish reopened setup');
    deferStopResult = false;
    prefs._testStartButton.emit('clicked');
    await waitFor(() => prefs._testStopButton.sensitive);
    assert(startCalls === 3, 'A fresh test starts after abandoning processing');
    prefs._testStopButton.emit('clicked');
    await waitFor(() => prefs._setupTestPassed);
    assert(stopCalls === 2 && prefs._testResult.label === 'This is my first dictation.', 'Actual Stop response shows a selectable result');
    assert(prefs._testCopyButton.sensitive && prefs._setupNext.sensitive, 'Success enables deliberate Copy and Finish');
    prefs._setupNext.emit('clicked');
    assert(saved.get_boolean('setup-completed') && saved.get_int('setup-step') === 0, 'Finish saves completion and clears resume step');
    mock.unexport(); Gio.bus_unown_name(owner);
    prefs._openSetup(window);
    assert(prefs._setupStep === 0, 'Completed setup remains reopenable');
    model.text = 'leave-with-edits';
    prefs._leaveSetup();
    assert(model.text === 'leave-with-edits' && saved.get_string('stt-groq-model') === 'wizard-draft', 'Leave does not lose drafts or silently save them');
    notifications.active = oldNotifications;
    model.text = 'discard-this-model';
    window.emit('close-request');
    assert(saved.get_boolean('notifications-enabled') === !oldNotifications, 'Closing discards staged switches');
    assert(saved.get_string('stt-groq-model') === 'wizard-draft', 'Closing discards unsaved text');
    assert(!draft.get_has_unapplied(), 'Closing clears staged changes');
    window.destroy();
    const configuredBackend = Gio.memory_settings_backend_new();
    const existing = new Gio.Settings({settings_schema: schema, backend: configuredBackend});
    existing.set_string('stt-groq-api-key', 'existing-user-key');
    existing.set_strv('toggle-recording', ['<Super>v']);
    const existingPrefs = new Preferences();
    existingPrefs.getSettings = () => new Gio.Settings({settings_schema: schema, backend: configuredBackend});
    const existingWindow = new Adw.PreferencesWindow();
    existingPrefs.fillPreferencesWindow(existingWindow);
    assert(!existingPrefs._setupIdle && !existingPrefs._setupWindow, 'Configured users are not forced into setup');
    assert(existing.get_string('stt-groq-api-key') === 'existing-user-key' && existing.get_strv('toggle-recording')[0] === '<Super>v', 'Existing settings survive first-run logic');
    existingWindow.emit('close-request'); existingWindow.destroy();
    print('PASS: Preferences Save, validation, wizard Back/leave/reopen, D-Bus test result, and saved completion.');
} finally {
    for (const file of files)
        GLib.unlink(`${directory}/${file}`);
    GLib.rmdir(directory);
}
