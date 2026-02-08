# Development

Getting started, testing, debugging, translations, InjectionManager, and packaging for GNOME 45+.

## Table of Contents

- [Getting Started](#getting-started)
- [File Structure](#file-structure)
- [Testing](#testing)
- [Debugging](#debugging)
- [Translations](#translations)
- [InjectionManager](#injectionmanager)
- [Packaging and Publishing](#packaging-and-publishing)
- [CLI Reference](#cli-reference)

## Getting Started

### Create with CLI

```sh
gnome-extensions create \
    --name="My Extension" \
    --description="Does something useful" \
    --uuid="my-extension@example.com"
```

This scaffolds `extension.js`, `prefs.js` (if requested interactively), `metadata.json`, and `stylesheet.css`.

### Create Manually

Minimum files for an ESModule extension (GNOME 45+):

```
my-extension@example.com/
├── metadata.json
└── extension.js
```

To install for development, symlink to the extensions directory:

```sh
ln -s "$(pwd)/my-extension@example.com" \
    ~/.local/share/gnome-shell/extensions/my-extension@example.com
```

### Enable/Disable

```sh
gnome-extensions enable my-extension@example.com
gnome-extensions disable my-extension@example.com
```

## File Structure

```
my-extension@example.com/
├── metadata.json          # Required: extension metadata
├── extension.js           # Required: Extension subclass (enable/disable)
├── prefs.js               # Optional: ExtensionPreferences subclass
├── stylesheet.css          # Optional: CSS for shell widgets
├── schemas/
│   ├── org.gnome.shell.extensions.myext.gschema.xml
│   └── gschemas.compiled  # Auto-compiled
├── locale/                # Optional: compiled translations (.mo files)
│   └── de/
│       └── LC_MESSAGES/
│           └── my-extension@example.com.mo
├── po/                    # Source translations (not shipped)
│   ├── LINGUAS
│   └── de.po
└── icons/                 # Optional: custom icons (hicolor structure)
    └── hicolor/
        └── scalable/
            └── actions/
                └── my-icon-symbolic.svg
```

## Testing

### Wayland Nested Session (recommended)

Test without affecting your main session:

```sh
# GNOME 49+ (uses --devkit)
dbus-run-session -- gnome-shell --devkit --wayland

# GNOME 45-48 (uses --nested)
dbus-run-session -- gnome-shell --nested --wayland
```

This runs a second GNOME Shell instance in a window with independent state.

### Restart GNOME Shell

On X11:

- Press `Alt+F2`, type `r`, press `Enter`

On Wayland:

- Log out and log back in
- Or use nested sessions

### Enable Extension in Test Session

```sh
# In the nested session's terminal:
gnome-extensions enable my-extension@example.com

# Or use dconf:
gsettings set org.gnome.shell enabled-extensions \
    "['my-extension@example.com']"
```

## Debugging

### Looking Glass

Built-in inspector for GNOME Shell:

- Press `Alt+F2`, type `lg`, press `Enter`
- Tabs: Evaluator (JS console), Windows, Extensions, Flags

Use the Evaluator tab to run JavaScript directly.  
Extensions tab shows all loaded extensions with errors.

### Journal Logs

```sh
# Shell logs (extension.js errors)
journalctl -f -o cat GNOME_SHELL_EXTENSION_UUID=my-extension@example.com

# All gnome-shell logs
journalctl -f -o cat /usr/bin/gnome-shell

# Preferences logs (prefs.js errors)
journalctl -f -o cat /usr/bin/gjs
```

### Environment Variables

```sh
# Enable debug messages for all shell components
SHELL_DEBUG=all dbus-run-session -- gnome-shell --nested --wayland

# Enable debug for specific component
SHELL_DEBUG=extensionSystem dbus-run-session -- gnome-shell --nested --wayland
```

### SafeMode

Start GNOME Shell with all extensions disabled:

```sh
gnome-shell --safe-mode
```

### GDB Debugging (advanced)

```sh
dbus-run-session -- gdb -ex run --args gnome-shell --nested --wayland
```

### console Methods

```js
console.log("info"); // Standard info
console.debug("detailed"); // Debug (hidden by default, enable with SHELL_DEBUG)
console.warn("warning"); // Logged + shown in Looking Glass as warning
console.error("error"); // Logged + shown in Looking Glass as error
console.trace("stack"); // Log with stack trace
```

## Translations

### Setup

1. Add `gettext-domain` to `metadata.json`:

```json
{
  "gettext-domain": "my-extension@example.com"
}
```

2. Use `gettext` in `extension.js`:

```js
import {
  Extension,
  gettext as _,
} from "resource:///org/gnome/shell/extensions/extension.js";

export default class MyExtension extends Extension {
  enable() {
    const label = _("Hello World");
    // ngettext for plurals:
    // import {ngettext} from '...'
    // ngettext('%d item', '%d items', count).format(count);
  }
}
```

3. Use `gettext` in `prefs.js`:

```js
import {
  ExtensionPreferences,
  gettext as _,
} from "resource:///org/gnome/Shell/Extensions/js/extensions/prefs.js";
```

### Extract and Compile

```sh
# Extract translatable strings
xgettext --from-code=UTF-8 \
    --output=po/my-extension@example.com.pot \
    extension.js prefs.js

# Create/update translation for a language
msginit --locale=de --input=po/my-extension@example.com.pot \
    --output=po/de.po
# ...or merge into existing:
msgmerge --update po/de.po po/my-extension@example.com.pot

# Edit po/de.po with your translations...

# Compile .mo file
msgfmt po/de.po \
    --output-file=locale/de/LC_MESSAGES/my-extension@example.com.mo
```

## InjectionManager

Override existing GNOME Shell methods cleanly. All overrides are tracked and can be restored.

```js
import { InjectionManager } from "resource:///org/gnome/shell/extensions/extension.js";

export default class MyExtension extends Extension {
  enable() {
    this._injectionManager = new InjectionManager();

    // Override an existing method
    this._injectionManager.overrideMethod(
      SomeClass.prototype,
      "someMethod",
      (originalMethod) => {
        return function (...args) {
          // Custom code before
          console.debug("Before original");

          // Call original
          const result = originalMethod.call(this, ...args);

          // Custom code after
          console.debug("After original");
          return result;
        };
      },
    );
  }

  disable() {
    this._injectionManager.clear();
    this._injectionManager = null;
  }
}
```

### Important Notes

- Always call `clear()` in `disable()` to restore originals
- Use `Function.prototype.call(this, ...)` to preserve the original `this` context
- The outer function receives the original method; it returns a replacement
- Do NOT store the original method yourself — `InjectionManager` handles it

## Packaging and Publishing

### Build ZIP

```sh
# Pack with gnome-extensions CLI
cd my-extension@example.com
gnome-extensions pack \
    --podir=po \
    --extra-source=icons \
    --force

# This creates my-extension@example.com.shell-extension.zip
```

You can specify additional sources with `--extra-source`:

```sh
gnome-extensions pack \
    --podir=po \
    --extra-source=lib \
    --extra-source=icons \
    --extra-source=data \
    --force
```

### Upload to extensions.gnome.org

```sh
# GNOME 49+: upload directly from CLI
gnome-extensions upload my-extension@example.com.shell-extension.zip

# Older versions: upload manually via https://extensions.gnome.org/upload/
```

### Install from ZIP (testing)

```sh
gnome-extensions install my-extension@example.com.shell-extension.zip
```

## CLI Reference

```sh
# List installed extensions
gnome-extensions list

# Show extension info
gnome-extensions info my-extension@example.com

# Show extension prefs
gnome-extensions prefs my-extension@example.com

# Enable / disable
gnome-extensions enable my-extension@example.com
gnome-extensions disable my-extension@example.com

# Uninstall
gnome-extensions uninstall my-extension@example.com

# Create new extension
gnome-extensions create

# Pack for distribution
gnome-extensions pack [--podir=DIR] [--extra-source=DIR] [--force]

# Upload (GNOME 49+)
gnome-extensions upload FILE.zip

# Reset extension state
gnome-extensions reset my-extension@example.com
```
