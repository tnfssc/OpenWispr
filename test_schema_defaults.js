// test_schema_defaults.js — smoke test that the compiled GSettings schema
// matches the defaults asserted by the extension.
//
// Run: gjs -m test_schema_defaults.js
// Requires: GJS + a D-Bus session. Reads extension/schemas/gschemas.compiled
// (build it first with: glib-compile-schemas extension/schemas).

import Gio from 'gi://Gio';
import GLib from 'gi://GLib';

// Named booleans for default-value assertions — avoid magic true/false at
// call sites so the intent (enabled vs disabled default) is explicit.
const ENABLED = true;
const DISABLED = false;
const ALLOWED_PASTE_METHODS = ['ctrl-v', 'ctrl-shift-v', 'shift-insert', 'clipboard-only'];

// Exact key count asserted by settings.list_keys(). The schema XML defines
// 26 keys; update this constant alongside the .xml when keys are added or
// removed. A loose >= assertion would silently hide a missing key.
const EXPECTED_KEY_COUNT = 26;

// Load metadata.json
const metadataPath = './extension/metadata.json';
const [ok, contents] = GLib.file_get_contents(metadataPath);
if (!ok) {
    print('Error: Could not read metadata.json');
    imports.system.exit(1);
}

const textDecoder = new TextDecoder();
const jsonString = textDecoder.decode(contents);
const metadata = JSON.parse(jsonString);

print(`Checking metadata for ${metadata.uuid}...`);

const schemaId = metadata['settings-schema'];

if (!schemaId) {
    print("FAIL: 'settings-schema' is missing in metadata.json");
    imports.system.exit(1);
}

print(`Found settings-schema: ${schemaId}`);

function fail(message) {
    print(`FAIL: ${message}`);
    imports.system.exit(1);
}

function assertEqual(actual, expected, label) {
    if (actual !== expected)
        fail(`${label} expected '${expected}', got '${actual}'`);

    print(`PASS: ${label} = ${actual}`);
}

function assertTrue(condition, label) {
    if (!condition)
        fail(label);

    print(`PASS: ${label}`);
}

function defaultValue(schemaObj, key) {
    return schemaObj.get_key(key).get_default_value().deep_unpack();
}

// Warn if gschemas.compiled is missing or stale relative to the .xml source.
// A stale compiled file causes this test to assert against out-of-date
// defaults, silently masking schema regressions.
const xmlPath = './extension/schemas/org.gnome.shell.extensions.openwispr.gschema.xml';
const compiledPath = './extension/schemas/gschemas.compiled';
try {
    const xmlInfo = Gio.File.new_for_path(xmlPath).query_info('time::modified', Gio.FileQueryInfoFlags.NONE, null);
    const compiledFile = Gio.File.new_for_path(compiledPath);
    if (!compiledFile.query_exists(null)) {
        print(`WARN: ${compiledPath} not found — run \`glib-compile-schemas extension/schemas\` first.`);
    } else {
        const compiledInfo = compiledFile.query_info('time::modified', Gio.FileQueryInfoFlags.NONE, null);
        const xmlMtime = xmlInfo.get_attribute_uint64('time::modified');
        const compiledMtime = compiledInfo.get_attribute_uint64('time::modified');
        if (compiledMtime < xmlMtime) {
            print(`WARN: ${compiledPath} is older than ${xmlPath} — recompile with \`glib-compile-schemas extension/schemas\` to avoid stale-default assertions.`);
        }
    }
} catch (e) {
    print(`WARN: could not compare schema mtimes: ${e.message}`);
}

// Now try to load it.
// We need to tell Gio where the schema is.
const schemaDir = Gio.File.new_for_path('./extension/schemas');
const schemaSource = Gio.SettingsSchemaSource.new_from_directory(
    schemaDir.get_path(),
    Gio.SettingsSchemaSource.get_default(),
    false
);

const schema = schemaSource.lookup(schemaId, true);
if (!schema) {
    fail(`Schema '${schemaId}' not found in ./extension/schemas`);
}

print('Schema found successfully.');

try {
    const settings = new Gio.Settings({ settings_schema: schema });
    print('PASS: Successfully initialized Gio.Settings with the schema.');

    const toggleShortcut = defaultValue(schema, 'toggle-recording');
    assertEqual(toggleShortcut.length, 0, "'toggle-recording' default is empty");

    assertEqual(defaultValue(schema, 'hold-to-speak-enabled'), ENABLED, "'hold-to-speak-enabled' default");
    assertEqual(defaultValue(schema, 'hold-to-speak-trigger'), '', "'hold-to-speak-trigger' default");
    assertEqual(defaultValue(schema, 'hold-to-speak-keybinding').length, 0, "'hold-to-speak-keybinding' default is empty");

    assertEqual(defaultValue(schema, 'paste-method'), 'ctrl-v', "'paste-method' default");
    for (const method of ALLOWED_PASTE_METHODS)
        assertTrue(ALLOWED_PASTE_METHODS.includes(method), `'paste-method' allows ${method}`);
    assertEqual(defaultValue(schema, 'restore-clipboard-enabled'), ENABLED, "'restore-clipboard-enabled' default");
    assertEqual(defaultValue(schema, 'notifications-enabled'), ENABLED, "'notifications-enabled' default");

    assertEqual(defaultValue(schema, 'silence-trim-enabled'), ENABLED, "'silence-trim-enabled' default");
    assertEqual(defaultValue(schema, 'silence-threshold'), '-35dB', "'silence-threshold' default");
    assertEqual(defaultValue(schema, 'silence-duration'), 0.25, "'silence-duration' default");

    assertEqual(defaultValue(schema, 'stt-provider'), 'local', "'stt-provider' default");
    assertEqual(defaultValue(schema, 'stt-openai-endpoint'), 'https://api.openai.com/v1/audio/transcriptions', "'stt-openai-endpoint' default");
    assertEqual(defaultValue(schema, 'stt-openai-model'), 'whisper-1', "'stt-openai-model' default");
    assertEqual(defaultValue(schema, 'stt-openai-api-key'), '', "'stt-openai-api-key' default");
    assertEqual(defaultValue(schema, 'stt-groq-endpoint'), 'https://api.groq.com/openai/v1/audio/transcriptions', "'stt-groq-endpoint' default");
    assertEqual(defaultValue(schema, 'stt-groq-model'), 'whisper-large-v3-turbo', "'stt-groq-model' default");
    assertEqual(defaultValue(schema, 'stt-groq-api-key'), '', "'stt-groq-api-key' default");

    assertEqual(defaultValue(schema, 'llm-filter-enabled'), DISABLED, "'llm-filter-enabled' default");
    assertEqual(defaultValue(schema, 'llm-provider'), 'openai', "'llm-provider' default");
    assertEqual(defaultValue(schema, 'llm-openai-endpoint'), 'https://api.openai.com/v1/chat/completions', "'llm-openai-endpoint' default");
    assertEqual(defaultValue(schema, 'llm-openai-model'), 'gpt-4o-mini', "'llm-openai-model' default");
    assertEqual(defaultValue(schema, 'llm-openai-api-key'), '', "'llm-openai-api-key' default");
    assertEqual(defaultValue(schema, 'llm-groq-endpoint'), 'https://api.groq.com/openai/v1/chat/completions', "'llm-groq-endpoint' default");
    assertEqual(defaultValue(schema, 'llm-groq-model'), 'llama-3.1-8b-instant', "'llm-groq-model' default");
    assertEqual(defaultValue(schema, 'llm-groq-api-key'), '', "'llm-groq-api-key' default");

    const prompt = defaultValue(schema, 'llm-cleanup-prompt');
    assertTrue(prompt.length > 20, "'llm-cleanup-prompt' default is non-empty");

    const keys = settings.list_keys();
    assertEqual(keys.length, EXPECTED_KEY_COUNT, `settings object exposes ${EXPECTED_KEY_COUNT} keys`);

    print('PASS: All required settings keys validated.');

} catch (e) {
    fail(`Could not initialize settings: ${e.message}`);
}
