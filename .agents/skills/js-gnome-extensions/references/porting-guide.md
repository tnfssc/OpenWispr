# Porting Guide

Breaking changes between GNOME Shell versions (45–49). Reference this when targeting or updating to a specific version.

## Table of Contents

- [GNOME 49](#gnome-49)
- [GNOME 48](#gnome-48)
- [GNOME 47](#gnome-47)
- [GNOME 46](#gnome-46)
- [GNOME 45](#gnome-45)
- [shell-version in metadata.json](#shell-version-in-metadatajson)

## GNOME 49

### Meta.Rectangle → Mtk.Rectangle

`Meta.Rectangle` has been removed. Use `Mtk.Rectangle` instead:

```js
// Before (GNOME ≤48)
import Meta from "gi://Meta";
const rect = new Meta.Rectangle({ x: 0, y: 0, width: 100, height: 100 });

// After (GNOME 49+)
import Mtk from "gi://Mtk";
const rect = new Mtk.Rectangle({ x: 0, y: 0, width: 100, height: 100 });
```

### Gesture Actions Replace Click/Tap Actions

`Clutter.ClickAction` and `Clutter.TapAction` have been removed:

```js
// Before (GNOME ≤48)
const clickAction = new Clutter.ClickAction();
clickAction.connect("clicked", () => {
  /* ... */
});
actor.add_action(clickAction);

// After (GNOME 49+)
const clickGesture = new Clutter.ClickGesture();
clickGesture.connect("pressed", () => {
  /* ... */
});
actor.add_action(clickGesture);

// TapAction → LongPressGesture
const longPress = new Clutter.LongPressGesture();
longPress.connect("long-pressed", () => {
  /* ... */
});
actor.add_action(longPress);
```

### Meta.Window Maximize Changes

Methods/properties renamed:

| Before            | After                     |
| ----------------- | ------------------------- |
| `maximize()`      | `maximize_by_request()`   |
| `unmaximize()`    | `unmaximize_by_request()` |
| `get_maximized()` | `get_maximized_type()`    |

### Testing with --devkit

```sh
# --nested is replaced by --devkit in GNOME 49
dbus-run-session -- gnome-shell --devkit --wayland
```

### CLI Upload

```sh
# New in GNOME 49: upload directly from CLI
gnome-extensions upload extension.shell-extension.zip
```

### AppMenuButton Removed

`Main.panel.statusArea.appMenu` no longer exists. Extensions referencing this must be updated.

## GNOME 48

### Structured Logging

```js
// Before (GNOME ≤47)
import { Extension } from "resource:///org/gnome/shell/extensions/extension.js";
// Use console.log, console.error directly

// After (GNOME 48+): use getLogger() for filtered logging
import {
  Extension,
  getLogger,
} from "resource:///org/gnome/shell/extensions/extension.js";

export default class MyExtension extends Extension {
  enable() {
    this._logger = getLogger();
    this._logger.debug("debug"); // only with SHELL_DEBUG
    this._logger.message("info"); // always logged
    this._logger.warning("warn"); // warning level
  }
}
```

`console.*` still works but does not benefit from the new logging infrastructure and extension-UUID-based filtering.

### Clutter.Image → St.ImageContent

`Clutter.Image` has been removed:

```js
// Before (GNOME ≤47)
const image = new Clutter.Image();
image.set_data(pixels, Cogl.PixelFormat.RGBA_8888, width, height, rowstride);

// After (GNOME 48+)
const image = St.ImageContent.new_with_preferred_size(width, height);
image.set_data(pixels, Cogl.PixelFormat.RGBA_8888, width, height, rowstride);
```

### Meta Function Renames

Functions on `Meta` object have moved to `Meta.Compositor`:

```js
// Before (GNOME ≤47)
Meta.disable_unredirect_for_display(global.display);
Meta.enable_unredirect_for_display(global.display);

// After (GNOME 48+)
Meta.Compositor.disable_unredirect_for_display(global.display);
Meta.Compositor.enable_unredirect_for_display(global.display);
```

### vertical Property Deprecated

```js
// Before (GNOME ≤47)
const box = new St.BoxLayout({ vertical: true });

// After (GNOME 48+)
import Clutter from "gi://Clutter";
const box = new St.BoxLayout({ orientation: Clutter.Orientation.VERTICAL });
```

### QuickMenuToggle Style Class

```js
// Before (GNOME ≤47)
// Menu header set via internal structure

// After (GNOME 48+)
// QuickMenuToggle has new style class 'quick-menu-toggle'
// Submenus have 'quick-sub-menu-toggle' style class
// Override with CSS if needed
```

### Grouped Notifications

`MessageTray.Source` constructor and notification grouping have changed:

```js
// Before (GNOME ≤47)
const source = new MessageTray.Source("Title", "icon-name");

// After (GNOME 48+)
const source = new MessageTray.Source({
  title: "Title",
  iconName: "icon-name",
});
```

## GNOME 47

Notable changes:

- `Shell.ActionMode` replaces `Shell.KeyBindingMode`
- Various internal UI refactors (check specific components you use)
- `ExtensionState` enum changes for `error` states

## GNOME 46

Notable changes:

- `Global.compositor` removed; use `global.display` methods directly
- `WorkspaceAnimation` refactored
- `MonitorManager` refactored

## GNOME 45

The ESModule migration (most significant change):

### Legacy → ESModule Conversion

```js
// === BEFORE (GNOME 44 and earlier) ===
const { GObject, St } = imports.gi;
const Main = imports.ui.main;
const PanelMenu = imports.ui.panelMenu;
const ExtensionUtils = imports.misc.extensionUtils;

function init() {
  /* ... */
}
function enable() {
  /* ... */
}
function disable() {
  /* ... */
}

// === AFTER (GNOME 45+) ===
import GObject from "gi://GObject";
import St from "gi://St";
import * as Main from "resource:///org/gnome/shell/ui/main.js";
import * as PanelMenu from "resource:///org/gnome/shell/ui/panelMenu.js";
import { Extension } from "resource:///org/gnome/shell/extensions/extension.js";

export default class MyExtension extends Extension {
  enable() {
    /* ... */
  }
  disable() {
    /* ... */
  }
}
```

### Key Differences

| Legacy                                        | ESModule (45+)                                                                  |
| --------------------------------------------- | ------------------------------------------------------------------------------- |
| `imports.gi.Foo`                              | `import Foo from 'gi://Foo'`                                                    |
| `imports.ui.main`                             | `import * as Main from 'resource:///org/gnome/shell/ui/main.js'`                |
| `imports.misc.extensionUtils`                 | `import {Extension} from 'resource:///org/gnome/shell/extensions/extension.js'` |
| `ExtensionUtils.getSettings()`                | `this.getSettings()` (in Extension subclass)                                    |
| `ExtensionUtils.getCurrentExtension()`        | `this` (in Extension subclass)                                                  |
| `Me.path`                                     | `this.path`                                                                     |
| `Me.dir`                                      | `this.dir` (returns `Gio.File`)                                                 |
| `init()` / `enable()` / `disable()` functions | `Extension` class with `enable()` / `disable()` methods                         |
| `init()` function in prefs.js                 | `ExtensionPreferences` class                                                    |

## shell-version in metadata.json

Since GNOME 40, use major version only:

```json
{
  "shell-version": ["47", "48", "49"]
}
```

Do NOT include versions the extension hasn't been tested with. Do NOT include more than one unreleased version.
