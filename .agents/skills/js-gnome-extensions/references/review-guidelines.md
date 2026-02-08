# Review Guidelines

Complete rules for extensions distributed on https://extensions.gnome.org/.

## Table of Contents

- [General Guidelines](#general-guidelines)
- [Initialization Rules](#initialization-rules)
- [Cleanup Rules](#cleanup-rules)
- [Import Rules](#import-rules)
- [Code Quality](#code-quality)
- [Metadata Rules](#metadata-rules)
- [GSettings Schema Rules](#gsettings-schema-rules)
- [Session Modes](#session-modes)
- [Scripts and Binaries](#scripts-and-binaries)
- [Security Rules](#security-rules)
- [Legal Restrictions](#legal-restrictions)
- [Recommendations](#recommendations)

## General Guidelines

Three basic principles:

1. Don't create or modify anything before `enable()` is called
2. Use `enable()` to create objects, connect signals and add main loop sources
3. Use `disable()` to cleanup anything done in `enable()`

Tips for passing review:

- Write clean code with consistent indentation and style
- Use modern ES6+ features (classes, `async`/`await`, template literals)
- Use ESLint to check for logic and syntax errors
- Ask for advice on [Matrix #extensions:gnome.org](https://matrix.to/#/#extensions:gnome.org)

## Initialization Rules

Extensions MUST NOT create any objects, connect any signals, add any main loop sources, or modify GNOME Shell during initialization.

In GNOME 45+, initialization = when `extension.js` is imported and `Extension` `constructor()` is called.

Extensions MAY create:

- Static data structures (RegExp, Map, Set, plain objects)

Extensions MUST NOT create in constructor:

- GObject instances (Gio.Settings, St.Widget, etc.)
- Signal connections
- Main loop sources (timeouts, idles)

All dynamically stored memory from static structures must be cleared in `disable()` (e.g., `Map.prototype.clear()`).

## Cleanup Rules

### Destroy all objects

Any objects or widgets created in `enable()` MUST be destroyed in `disable()`.

```js
disable() {
    this._indicator?.destroy();
    this._indicator = null;
}
```

### Disconnect all signals

Any signal connections MUST be disconnected in `disable()`:

```js
disable() {
    if (this._handlerId) {
        someObject.disconnect(this._handlerId);
        this._handlerId = null;
    }
}
```

### Remove main loop sources

Any main loop sources MUST be removed in `disable()`, even if the callback would eventually return `GLib.SOURCE_REMOVE`:

```js
disable() {
    if (this._timeoutId) {
        GLib.Source.remove(this._timeoutId);
        this._timeoutId = null;
    }
}
```

## Import Rules

### Do not use deprecated modules

| Deprecated  | Replacement                                 |
| ----------- | ------------------------------------------- |
| `ByteArray` | `TextDecoder` and `TextEncoder`             |
| `Lang`      | ES6 Classes and `Function.prototype.bind()` |
| `Mainloop`  | `GLib.timeout_add()`, `setTimeout()`, etc.  |

### Do not import GTK libraries in GNOME Shell

Extensions MUST NOT import `Gdk`, `Gtk`, or `Adw` in the GNOME Shell process (`extension.js`). These conflict with Clutter and libraries used by GNOME Shell.

### Do not import GNOME Shell libraries in Preferences

Extensions MUST NOT import `Clutter`, `Meta`, `St`, or `Shell` in the preferences process (`prefs.js`). These conflict with GTK and libraries used by the prefs process.

### Avoid interfering with the Extension System

Extensions that modify, reload, or interact with other extensions will be reviewed case-by-case and may be rejected.

## Code Quality

### Code must not be obfuscated

- JavaScript code must be readable and reasonably structured
- Must not be minified or obfuscated
- TypeScript must be transpiled to well-formatted JavaScript

### No excessive logging

Only use the log for important messages and errors. Excessive logging leads to rejection.

Recommended:

- `console.debug()` for development-only info
- `console.warn()` for unexpected errors indicating bugs
- `console.error()` for programmer errors and assertion failures

### Do not force dispose GObjects

Do NOT call `GObject.Object.run_dispose()` unless absolutely necessary. If used, MUST include a comment explaining why.

## Metadata Rules

### `metadata.json` requirements

| Field           | Rules                                                                                                                            |
| --------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| `name`          | Must not conflict with another extension. Forks MUST have unique names.                                                          |
| `uuid`          | Format: `extension-id@namespace`. Only `[a-zA-Z0-9._-]`. MUST NOT use `gnome.org`.                                               |
| `description`   | Reasonable length. May use `\n` for paragraphs, `*` for bullets.                                                                 |
| `shell-version` | Only stable releases + up to one dev release. Must not claim future versions. Since GNOME 40, major version only (e.g., `"47"`). |
| `url`           | Link to GitHub/GitLab repository.                                                                                                |
| `session-modes` | Drop if only using `user`. Valid: `user`, `unlock-dialog`.                                                                       |
| `version`       | **Do not set.** Managed by extensions.gnome.org.                                                                                 |
| `donations`     | Only include valid keys. Drop if unused.                                                                                         |

### Example

```json
{
  "uuid": "color-button@my-account.github.io",
  "name": "ColorButton",
  "description": "ColorButton adds a colored button to the panel.\n\nIt is a fork of MonochromeButton.",
  "shell-version": ["47", "48", "49"],
  "url": "https://github.com/my-account/color-button"
}
```

## GSettings Schema Rules

- Schema ID MUST use `org.gnome.shell.extensions` as base (e.g., `org.gnome.shell.extensions.myext`)
- Schema path MUST use `/org/gnome/shell/extensions/` as base
- Schema XML file MUST be included in the extension ZIP
- Schema XML filename MUST be `<schema-id>.gschema.xml`

## Session Modes

To use `unlock-dialog` session mode:

- It MUST be necessary for the extension to operate correctly
- All keyboard event signals MUST be disconnected in unlock-dialog mode
- `disable()` MUST have a comment explaining why `unlock-dialog` is used
- Extensions MUST NOT selectively disable

## Scripts and Binaries

- MUST NOT include binary executables or libraries
- Processes MUST be spawned carefully and exit cleanly
- Scripts MUST be written in GJS unless absolutely necessary
- Scripts must use an OSI-approved license
- Extensions may install modules from `pip`/`npm`/`yarn` but MUST require explicit user action

## Security Rules

### Clipboard access

- Must declare clipboard access in the description
- MUST NOT share clipboard data with third parties without explicit user interaction
- MUST NOT ship default keyboard shortcuts for clipboard

### Privileged subprocesses

- Avoid at all costs
- If necessary, MUST use `pkexec` and MUST NOT be user-writable

### Telemetry

- MUST NOT use any telemetry to track users or share data online

## Legal Restrictions

### Licensing

GNOME Shell is `GPL-2.0-or-later`. Extensions MUST be distributed under compatible terms. Code from other extensions MUST include attribution.

### Code of Conduct

Subject to GNOME Code of Conduct. No hateful, violent, or offensive content in names, descriptions, icons, or screenshots.

### Political Statements

Extensions MUST NOT promote political agendas.

### Copyrights and Trademarks

MUST NOT include copyrighted/trademarked content without permission.

### AI-generated code

While AI may be used as a development tool, developers must be able to justify and explain submitted code. Submissions with imaginary API usage, unnecessary code, inconsistent style, or LLM-prompt comments will be rejected.

## Recommendations

### Don't include unnecessary files

No build scripts, `.po`/`.pot` files, unused icons/images.

### Use a linter

Use [ESLint](https://eslint.org/) with the [GNOME Shell lint rules](https://gitlab.gnome.org/GNOME/gnome-shell-extensions/tree/main/lint).

### UI Design

Follow the [GNOME Human Interface Guidelines](https://developer.gnome.org/hig/) for preferences.

### Extensions must be functional

If tested and found fundamentally broken or purposeless, extensions will be rejected.
