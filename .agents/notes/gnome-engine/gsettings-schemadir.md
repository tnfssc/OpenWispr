---
name: Engine reads API keys with --schemadir pointing at the extension
description: Why a bare `gsettings get` fails for the engine and where it looks for the schema
---
The engine reads provider API keys from the extension's GSettings schema by shelling out to `gsettings`
(keys never cross D-Bus). GNOME Shell loads extension schemas from the extension's own `schemas/` folder,
so the schema is not on gsettings' default search path and a bare `gsettings get` exits 1 with
"No such schema". Every transcription failed this way with
`stt groq key: read gsettings key stt-groq-api-key: exit status 1` up to gnome-v0.4.5.

`extensionSchemaDir()` looks for `gnome-shell/extensions/<uuid>/schemas/gschemas.compiled` under
`$XDG_DATA_HOME` (default `~/.local/share`), then each `$XDG_DATA_DIRS` entry, matching Shell's
user-before-system order, and passes it as `--schemadir`. Installs need the compiled schema in that folder;
`install.sh` and `gnome-extensions install` both provide it.
