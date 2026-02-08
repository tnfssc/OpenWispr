# Preferences

GSettings schemas, `prefs.js` with GTK4/Adwaita, and settings binding for GNOME 45+.

## Table of Contents

- [GSettings Schema](#gsettings-schema)
- [metadata.json Integration](#metadatajson-integration)
- [extension.js: Reading Settings](#extensionjs-reading-settings)
- [prefs.js: Building Preferences UI](#prefsjs-building-preferences-ui)
- [Common Adwaita Widgets](#common-adwaita-widgets)
- [Testing Preferences](#testing-preferences)

## GSettings Schema

### Create Schema File

Path: `schemas/org.gnome.shell.extensions.<name>.gschema.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<schemalist>
  <schema id="org.gnome.shell.extensions.myext"
          path="/org/gnome/shell/extensions/myext/">
    <key name="show-indicator" type="b">
      <default>true</default>
      <summary>Show panel indicator</summary>
      <description>Whether to show the indicator in the panel</description>
    </key>
    <key name="refresh-interval" type="u">
      <default>30</default>
      <summary>Refresh interval in seconds</summary>
    </key>
    <key name="custom-label" type="s">
      <default>''</default>
      <summary>Custom label text</summary>
    </key>
    <key name="items" type="as">
      <default>[]</default>
      <summary>List of items</summary>
    </key>
  </schema>
</schemalist>
```

### Common GVariant Types

| Type               | GVariant               | GSettings getter/setter           |
| ------------------ | ---------------------- | --------------------------------- |
| Boolean            | `b`                    | `get_boolean()` / `set_boolean()` |
| Integer (signed)   | `i`                    | `get_int()` / `set_int()`         |
| Integer (unsigned) | `u`                    | `get_uint()` / `set_uint()`       |
| Double             | `d`                    | `get_double()` / `set_double()`   |
| String             | `s`                    | `get_string()` / `set_string()`   |
| String array       | `as`                   | `get_strv()` / `set_strv()`       |
| Enum               | `s` (with `<choices>`) | `get_string()` / `set_string()`   |

### Enum Key Example

```xml
<key name="position" type="s">
  <default>'center'</default>
  <choices>
    <choice value="left"/>
    <choice value="center"/>
    <choice value="right"/>
  </choices>
</key>
```

### Compile Schema

```sh
# Done automatically by gnome-extensions tool and Extension Manager
# Manual compilation:
glib-compile-schemas schemas/
```

## metadata.json Integration

Add `settings-schema` to `metadata.json` so `this.getSettings()` works automatically:

```json
{
  "uuid": "myext@example.com",
  "name": "My Extension",
  "description": "An extension with preferences",
  "shell-version": ["47", "48", "49"],
  "url": "https://github.com/user/myext",
  "gettext-domain": "myext@example.com",
  "settings-schema": "org.gnome.shell.extensions.myext"
}
```

## extension.js: Reading Settings

```js
import Gio from "gi://Gio";
import St from "gi://St";
import {
  Extension,
  gettext as _,
} from "resource:///org/gnome/shell/extensions/extension.js";
import * as Main from "resource:///org/gnome/shell/ui/main.js";
import * as PanelMenu from "resource:///org/gnome/shell/ui/panelMenu.js";

export default class MyExtension extends Extension {
  enable() {
    this._indicator = new PanelMenu.Button(0.0, this.metadata.name, false);
    const icon = new St.Icon({
      icon_name: "face-laugh-symbolic",
      style_class: "system-status-icon",
    });
    this._indicator.add_child(icon);
    Main.panel.addToStatusArea(this.uuid, this._indicator);

    // Open prefs from menu
    this._indicator.menu.addAction(_("Preferences"), () =>
      this.openPreferences(),
    );

    // Get settings (uses metadata settings-schema automatically)
    this._settings = this.getSettings();

    // Bind a setting to a GObject property
    this._settings.bind(
      "show-indicator",
      this._indicator,
      "visible",
      Gio.SettingsBindFlags.DEFAULT,
    );

    // Watch for changes
    this._settingsChangedId = this._settings.connect(
      "changed::refresh-interval",
      (settings, key) => {
        const value = settings.get_uint(key);
        console.debug(`Refresh interval changed to ${value}`);
      },
    );
  }

  disable() {
    if (this._settingsChangedId) {
      this._settings.disconnect(this._settingsChangedId);
      this._settingsChangedId = null;
    }
    this._settings = null;
    this._indicator?.destroy();
    this._indicator = null;
  }
}
```

## prefs.js: Building Preferences UI

Preferences use GTK4 and Adwaita. Implement `fillPreferencesWindow()` (recommended) or `getPreferencesWidget()`.

```js
import Gio from "gi://Gio";
import Adw from "gi://Adw";
import Gtk from "gi://Gtk?version=4.0";

import {
  ExtensionPreferences,
  gettext as _,
} from "resource:///org/gnome/Shell/Extensions/js/extensions/prefs.js";

export default class MyPreferences extends ExtensionPreferences {
  fillPreferencesWindow(window) {
    const settings = this.getSettings();

    // Page
    const page = new Adw.PreferencesPage({
      title: _("General"),
      icon_name: "dialog-information-symbolic",
    });
    window.add(page);

    // Group
    const group = new Adw.PreferencesGroup({
      title: _("Appearance"),
      description: _("Configure the appearance"),
    });
    page.add(group);

    // Switch row (boolean)
    const showRow = new Adw.SwitchRow({
      title: _("Show Indicator"),
      subtitle: _("Show the panel indicator"),
    });
    group.add(showRow);
    settings.bind(
      "show-indicator",
      showRow,
      "active",
      Gio.SettingsBindFlags.DEFAULT,
    );

    // Spin row (integer)
    const intervalRow = new Adw.SpinRow({
      title: _("Refresh Interval"),
      subtitle: _("Seconds between updates"),
      adjustment: new Gtk.Adjustment({
        lower: 5,
        upper: 3600,
        step_increment: 5,
        page_increment: 60,
        value: settings.get_uint("refresh-interval"),
      }),
    });
    group.add(intervalRow);
    settings.bind(
      "refresh-interval",
      intervalRow,
      "value",
      Gio.SettingsBindFlags.DEFAULT,
    );

    // Entry row (string)
    const labelRow = new Adw.EntryRow({
      title: _("Custom Label"),
    });
    group.add(labelRow);
    settings.bind(
      "custom-label",
      labelRow,
      "text",
      Gio.SettingsBindFlags.DEFAULT,
    );

    // Combo row (enum/choices)
    const positionModel = new Gtk.StringList();
    positionModel.append(_("Left"));
    positionModel.append(_("Center"));
    positionModel.append(_("Right"));

    const positionRow = new Adw.ComboRow({
      title: _("Position"),
      subtitle: _("Panel position"),
      model: positionModel,
    });
    group.add(positionRow);

    // Manual binding for combo
    const positions = ["left", "center", "right"];
    positionRow.selected = positions.indexOf(settings.get_string("position"));
    positionRow.connect("notify::selected", () => {
      settings.set_string("position", positions[positionRow.selected]);
    });

    // Keep settings reference alive
    window._settings = settings;
  }
}
```

## Common Adwaita Widgets

| Widget                 | Use case                                        |
| ---------------------- | ----------------------------------------------- |
| `Adw.SwitchRow`        | Boolean toggle                                  |
| `Adw.SpinRow`          | Numeric value                                   |
| `Adw.EntryRow`         | Text input                                      |
| `Adw.ComboRow`         | Selection from list                             |
| `Adw.ExpanderRow`      | Collapsible section with child rows             |
| `Adw.ActionRow`        | Generic row with title/subtitle + suffix widget |
| `Adw.PreferencesGroup` | Group of related settings                       |
| `Adw.PreferencesPage`  | Tab page with icon                              |

Widget galleries:

- [GTK4 Widget Gallery](https://docs.gtk.org/gtk4/visual_index.html)
- [Adwaita Widget Gallery](https://gnome.pages.gitlab.gnome.org/libadwaita/doc/1-latest/widget-gallery.html)

## Testing Preferences

```sh
# Open prefs dialog
gnome-extensions prefs myext@example.com

# Watch prefs logs (separate from gnome-shell)
journalctl -f -o cat /usr/bin/gjs

# Watch GSettings changes
dconf watch /org/gnome/shell/extensions/myext/
```
