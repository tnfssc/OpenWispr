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
    print(`FAIL: Schema '${schemaId}' not found in ./extension/schemas`);
    imports.system.exit(1);
}

print("Schema found successfully.");

try {
    // Note: To actually instantiate Gio.Settings with a custom source is a bit different
    // than the standard constructor.
    // But verifying the schema exists in the compiled source is usually enough proof 
    // that Settings will work if initialized correctly.
    
    // Let's try to instantiate it using the schema object we found.
    const settings = new Gio.Settings({ settings_schema: schema });
    print("PASS: Successfully initialized Gio.Settings with the schema.");
    
    // Verify a key
    const defaultVal = settings.get_value('toggle-recording');
    print(`Default value for 'toggle-recording': ${defaultVal.deep_unpack()}`);
    
} catch (e) {
    print(`FAIL: Could not initialize settings: ${e.message}`);
    imports.system.exit(1);
}
