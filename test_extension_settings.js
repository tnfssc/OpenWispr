import Gio from 'gi://Gio';
import GLib from 'gi://GLib';

// Load metadata.json
const metadataPath = './extension/metadata.json';
const [ok, contents] = GLib.file_get_contents(metadataPath);
if (!ok) {
    print("Error: Could not read metadata.json");
    imports.system.exit(1);
}

const textDecoder = new TextDecoder();
const jsonString = textDecoder.decode(contents);
const metadata = JSON.parse(jsonString);

print(`Checking metadata for ${metadata.uuid}...`);

const schemaId = metadata['settings-schema'];

if (!schemaId) {
    print("FAIL: 'settings-schema' is missing in metadata.json");
    // We simulate what the Extension class does: if missing, it might use uuid or undefined?
    // In our case, we know it results in undefined being passed if the code relies on metadata.
    // So we fail here to enforce the fix.
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

print("Schema found successfully.");

try {
    const settings = new Gio.Settings({ settings_schema: schema });
    print("PASS: Successfully initialized Gio.Settings with the schema.");

    const toggleShortcut = defaultValue(schema, 'toggle-recording');
    assertTrue(toggleShortcut.length > 0, "'toggle-recording' has a default shortcut");
    assertEqual(toggleShortcut[0], '<Control><Alt>r', "'toggle-recording' default");

    assertEqual(defaultValue(schema, 'hold-to-speak-enabled'), true, "'hold-to-speak-enabled' default");
    assertEqual(defaultValue(schema, 'hold-to-speak-trigger'), 'ctrl-alt-t', "'hold-to-speak-trigger' default");
    assertEqual(defaultValue(schema, 'hold-to-speak-keybinding')[0], '<Control><Alt>t', "'hold-to-speak-keybinding' default");

    assertEqual(defaultValue(schema, 'auto-paste-enabled'), true, "'auto-paste-enabled' default");
    assertEqual(defaultValue(schema, 'notifications-enabled'), true, "'notifications-enabled' default");

    assertEqual(defaultValue(schema, 'silence-trim-enabled'), true, "'silence-trim-enabled' default");
    assertEqual(defaultValue(schema, 'silence-threshold'), '-35dB', "'silence-threshold' default");
    assertEqual(defaultValue(schema, 'silence-duration'), 0.25, "'silence-duration' default");

    assertEqual(defaultValue(schema, 'stt-provider'), 'local', "'stt-provider' default");
    assertEqual(defaultValue(schema, 'stt-openai-endpoint'), 'https://api.openai.com/v1/audio/transcriptions', "'stt-openai-endpoint' default");
    assertEqual(defaultValue(schema, 'stt-openai-model'), 'whisper-1', "'stt-openai-model' default");
    assertEqual(defaultValue(schema, 'stt-openai-api-key'), '', "'stt-openai-api-key' default");
    assertEqual(defaultValue(schema, 'stt-groq-endpoint'), 'https://api.groq.com/openai/v1/audio/transcriptions', "'stt-groq-endpoint' default");
    assertEqual(defaultValue(schema, 'stt-groq-model'), 'whisper-large-v3-turbo', "'stt-groq-model' default");
    assertEqual(defaultValue(schema, 'stt-groq-api-key'), '', "'stt-groq-api-key' default");

    assertEqual(defaultValue(schema, 'llm-filter-enabled'), false, "'llm-filter-enabled' default");
    assertEqual(defaultValue(schema, 'llm-provider'), 'openai', "'llm-provider' default");
    assertEqual(defaultValue(schema, 'llm-openai-endpoint'), 'https://api.openai.com/v1/chat/completions', "'llm-openai-endpoint' default");
    assertEqual(defaultValue(schema, 'llm-openai-model'), 'gpt-4o-mini', "'llm-openai-model' default");
    assertEqual(defaultValue(schema, 'llm-openai-api-key'), '', "'llm-openai-api-key' default");
    assertEqual(defaultValue(schema, 'llm-groq-endpoint'), 'https://api.groq.com/openai/v1/chat/completions', "'llm-groq-endpoint' default");
    assertEqual(defaultValue(schema, 'llm-groq-model'), 'llama-3.1-8b-instant', "'llm-groq-model' default");
    assertEqual(defaultValue(schema, 'llm-groq-api-key'), '', "'llm-groq-api-key' default");

    const prompt = defaultValue(schema, 'llm-cleanup-prompt');
    assertTrue(prompt.length > 20, "'llm-cleanup-prompt' default is non-empty");

    assertTrue(settings.list_keys().length >= 20, 'settings object exposes expected key count');

    print('PASS: All required settings keys validated.');
    
} catch (e) {
    fail(`Could not initialize settings: ${e.message}`);
}
