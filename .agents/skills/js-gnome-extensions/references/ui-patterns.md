# UI Patterns

Complete code examples for building UI components in GNOME Shell extensions (GNOME 45+).

## Table of Contents

- [Panel Indicators](#panel-indicators)
- [Popup Menus](#popup-menus)
- [Quick Settings](#quick-settings)
- [Dialogs](#dialogs)
- [Notifications](#notifications)
- [Search Providers](#search-providers)
- [Stylesheet CSS](#stylesheet-css)

## Panel Indicators

### Basic Panel Button

```js
import St from "gi://St";
import { Extension } from "resource:///org/gnome/shell/extensions/extension.js";
import * as Main from "resource:///org/gnome/shell/ui/main.js";
import * as PanelMenu from "resource:///org/gnome/shell/ui/panelMenu.js";

export default class MyExtension extends Extension {
  enable() {
    // args: menuAlignment (0.0=left, 0.5=center, 1.0=right),
    //       nameText, dontCreateMenu
    this._indicator = new PanelMenu.Button(0.0, this.metadata.name, false);

    const icon = new St.Icon({
      icon_name: "face-laugh-symbolic",
      style_class: "system-status-icon",
    });
    this._indicator.add_child(icon);

    // Position: 'left', 'center', or 'right' panel box
    Main.panel.addToStatusArea(this.uuid, this._indicator);
  }

  disable() {
    this._indicator?.destroy();
    this._indicator = null;
  }
}
```

## Popup Menus

Popup menus are used with `PanelMenu.Button` (which has a built-in `.menu`).

### Menu Item Types

```js
import * as PopupMenu from "resource:///org/gnome/shell/ui/popupMenu.js";

// Simple text item
const item = new PopupMenu.PopupMenuItem("Label");
item.connect("activate", () => console.debug("clicked"));

// Image + text item
const imgItem = new PopupMenu.PopupImageMenuItem("Label", "icon-name-symbolic");

// Switch item
const switchItem = new PopupMenu.PopupSwitchMenuItem(
  "Label",
  true /* initial state */,
);
switchItem.connect("toggled", (item, state) =>
  console.debug(`state: ${state}`),
);

// Separator (optional label)
const separator = new PopupMenu.PopupSeparatorMenuItem("Section");

// Submenu
const subMenuItem = new PopupMenu.PopupSubMenuMenuItem(
  "Submenu",
  true /* wantIcon */,
);
subMenuItem.icon.icon_name = "info-symbolic";
subMenuItem.menu.addAction("Sub Item", () => console.debug("sub-click"));
```

### Ornaments

```js
menuItem.setOrnament(PopupMenu.Ornament.NONE); // No ornament
menuItem.setOrnament(PopupMenu.Ornament.DOT); // Radio-style dot
menuItem.setOrnament(PopupMenu.Ornament.CHECK); // Check mark
menuItem.setOrnament(PopupMenu.Ornament.HIDDEN); // Hidden, content expands
```

### Adding Items to Panel Button Menu

```js
enable() {
    this._indicator = new PanelMenu.Button(0.0, this.metadata.name, false);
    // ...add icon...

    // Quick way: addAction returns a PopupMenuItem
    this._indicator.menu.addAction('Open Preferences', () => this.openPreferences());

    // Manual way: addMenuItem
    const item = new PopupMenu.PopupMenuItem('Do Something');
    item.connect('activate', () => { /* ... */ });
    this._indicator.menu.addMenuItem(item);

    this._indicator.menu.addMenuItem(new PopupMenu.PopupSeparatorMenuItem());

    // Settings page shortcut
    this._indicator.menu.addSettingsAction('Sound Settings',
        'gnome-sound-panel.desktop');

    Main.panel.addToStatusArea(this.uuid, this._indicator);
}
```

### Menu Sections

```js
// A PopupMenuSection groups items within a parent menu
const section = new PopupMenu.PopupMenuSection();
section.addAction("Item 1", () => {});
section.addAction("Item 2", () => {});
this._indicator.menu.addMenuItem(section);
```

### Popup Animation

```js
import * as BoxPointer from "resource:///org/gnome/shell/ui/boxpointer.js";

menu.open(BoxPointer.PopupAnimation.FADE);
menu.close(BoxPointer.PopupAnimation.NONE);
// Options: NONE, SLIDE, FADE, FULL
```

## Quick Settings

Quick settings appear in the system menu (top-right panel).

### Imports

```js
import GObject from "gi://GObject";
import Gio from "gi://Gio";
import St from "gi://St";
import {
  Extension,
  gettext as _,
} from "resource:///org/gnome/shell/extensions/extension.js";
import * as Main from "resource:///org/gnome/shell/ui/main.js";
import * as PopupMenu from "resource:///org/gnome/shell/ui/popupMenu.js";
import * as QuickSettings from "resource:///org/gnome/shell/ui/quickSettings.js";
```

### Basic Toggle

```js
const MyToggle = GObject.registerClass(
  class MyToggle extends QuickSettings.QuickToggle {
    constructor(extensionObject) {
      super({
        title: _("Feature"),
        subtitle: _("Description"),
        iconName: "selection-mode-symbolic",
        toggleMode: true,
      });
      this._settings = extensionObject.getSettings();
      this._settings.bind(
        "feature-enabled",
        this,
        "checked",
        Gio.SettingsBindFlags.DEFAULT,
      );
    }
  },
);
```

### Toggle with Menu

```js
const MyMenuToggle = GObject.registerClass(
  class MyMenuToggle extends QuickSettings.QuickMenuToggle {
    constructor(extensionObject) {
      super({
        title: _("Feature"),
        subtitle: _("With options"),
        iconName: "selection-mode-symbolic",
        toggleMode: true,
      });
      this.menu.setHeader(
        "selection-mode-symbolic",
        _("Feature"),
        _("Subtitle"),
      );

      this._itemsSection = new PopupMenu.PopupMenuSection();
      this._itemsSection.addAction(_("Option 1"), () => console.debug("opt1"));
      this._itemsSection.addAction(_("Option 2"), () => console.debug("opt2"));
      this.menu.addMenuItem(this._itemsSection);

      this.menu.addMenuItem(new PopupMenu.PopupSeparatorMenuItem());
      const settingsItem = this.menu.addAction("More Settings", () =>
        extensionObject.openPreferences(),
      );
      settingsItem.visible = Main.sessionMode.allowSettings;
      this.menu._settingsActions[extensionObject.uuid] = settingsItem;
    }
  },
);
```

### Slider

```js
const MySlider = GObject.registerClass(
  class MySlider extends QuickSettings.QuickSlider {
    constructor(extensionObject) {
      super({
        iconName: "audio-volume-medium-symbolic",
        iconLabel: _("Volume"),
      });
      this.slider.accessible_name = _("Custom Slider");

      this._sliderChangedId = this.slider.connect(
        "notify::value",
        this._onSliderChanged.bind(this),
      );

      this._settings = extensionObject.getSettings();
      this._settings.connect(
        "changed::slider-value",
        this._onSettingsChanged.bind(this),
      );
      this._onSettingsChanged();
    }

    _onSettingsChanged() {
      this.slider.block_signal_handler(this._sliderChangedId);
      this.slider.value = this._settings.get_uint("slider-value") / 100.0;
      this.slider.unblock_signal_handler(this._sliderChangedId);
    }

    _onSliderChanged() {
      const percent = Math.floor(this.slider.value * 100);
      this._settings.set_uint("slider-value", percent);
    }
  },
);
```

### System Indicator (Container)

Every quick settings extension needs a `SystemIndicator`:

```js
const MyIndicator = GObject.registerClass(
  class MyIndicator extends QuickSettings.SystemIndicator {
    constructor(extensionObject) {
      super();

      // Optional: show icon in panel
      this._indicator = this._addIndicator();
      this._indicator.icon_name = "selection-mode-symbolic";
      this._settings = extensionObject.getSettings();
      this._settings.bind(
        "feature-enabled",
        this._indicator,
        "visible",
        Gio.SettingsBindFlags.DEFAULT,
      );

      // Add toggle(s) to quick settings
      this.quickSettingsItems.push(new MyToggle(extensionObject));
    }

    destroy() {
      this.quickSettingsItems.forEach((item) => item.destroy());
      super.destroy();
    }
  },
);
```

### Registration in Extension

```js
export default class MyExtension extends Extension {
  enable() {
    this._indicator = new MyIndicator(this);
    Main.panel.statusArea.quickSettings.addExternalIndicator(this._indicator);
    // For sliders spanning 2 columns:
    // Main.panel.statusArea.quickSettings.addExternalIndicator(this._indicator, 2);
  }

  disable() {
    this._indicator.destroy();
    this._indicator = null;
  }
}
```

## Dialogs

Use dialogs from `extension.js` instead of GTK dialogs (GTK is unavailable in the shell process).

### Modal Dialog

```js
import GLib from "gi://GLib";
import St from "gi://St";
import * as Dialog from "resource:///org/gnome/shell/ui/dialog.js";
import * as ModalDialog from "resource:///org/gnome/shell/ui/modalDialog.js";

// Create dialog
const dialog = new ModalDialog.ModalDialog({
  destroyOnClose: true, // destroy when closed (default: true)
  styleClass: "my-dialog",
});

// Content
const content = new Dialog.MessageDialogContent({
  title: "Important",
  description: "Something happened!",
});
dialog.contentLayout.add_child(content);

// Buttons
dialog.setButtons([
  {
    label: "Cancel",
    action: () => dialog.close(global.get_current_time()),
  },
  {
    label: "OK",
    isDefault: true,
    action: () => {
      // handle confirmation
      dialog.close(global.get_current_time());
    },
  },
]);

// Show
dialog.open(global.get_current_time());
```

### Dialog with List

```js
const listLayout = new Dialog.ListSection({ title: "Items" });
dialog.contentLayout.add_child(listLayout);

const item = new Dialog.ListSectionItem({
  icon_actor: new St.Icon({ icon_name: "dialog-information-symbolic" }),
  title: "Item Title",
  description: "Item description",
});
listLayout.list.add_child(item);
```

## Notifications

### Simple Notifications

```js
import * as Main from "resource:///org/gnome/shell/ui/main.js";

Main.notify("Title", "Body text");
Main.notifyError("Error Title", "Error body"); // also logs a warning
```

### Custom Source + Notification

```js
import Gio from "gi://Gio";
import GObject from "gi://GObject";
import * as Main from "resource:///org/gnome/shell/ui/main.js";
import * as MessageTray from "resource:///org/gnome/shell/ui/messageTray.js";

// Custom policy (optional)
const MyPolicy = GObject.registerClass(
  class MyPolicy extends MessageTray.NotificationPolicy {
    get enable() {
      return true;
    }
    get enableSound() {
      return true;
    }
    get showBanners() {
      return true;
    }
    get forceExpanded() {
      return false;
    }
    get showInLockScreen() {
      return false;
    }
    get detailsInLockScreen() {
      return false;
    }
    store() {}
  },
);

// Create source
let source = new MessageTray.Source({
  title: "My Extension",
  iconName: "dialog-information",
  policy: new MyPolicy(),
});
source.connect("destroy", () => {
  source = null;
});
Main.messageTray.add(source);

// Create notification
const notification = new MessageTray.Notification({
  source: source,
  title: "Notification Title",
  body: "Notification body text",
  iconName: "dialog-information",
  urgency: MessageTray.Urgency.NORMAL,
});

// Add action buttons (up to 3)
notification.addAction("Open", () => console.debug("Open clicked"));

// Show
source.addNotification(notification);
```

### Urgency Levels

- `MessageTray.Urgency.LOW` — tray only, no popup
- `MessageTray.Urgency.NORMAL` — popup per policy
- `MessageTray.Urgency.HIGH` — popup per policy
- `MessageTray.Urgency.CRITICAL` — always shown, must be acknowledged

## Search Providers

Register a search provider to appear in GNOME Shell overview search.

### Provider Class

```js
import St from "gi://St";

class MySearchProvider {
  constructor(extension) {
    this._extension = extension;
  }

  get appInfo() {
    return null;
  }
  get canLaunchSearch() {
    return false;
  }
  get id() {
    return this._extension.uuid;
  }

  activateResult(result, terms) {
    console.debug(`Activate: ${result}`);
  }

  launchSearch(terms) {}
  createResultObject(meta) {
    return null;
  }

  getResultMetas(results, cancellable) {
    const { scaleFactor } = St.ThemeContext.get_for_stage(global.stage);
    return new Promise((resolve, reject) => {
      const cancelledId = cancellable.connect(() => reject(Error("Cancelled")));
      const metas = results.map((id) => ({
        id,
        name: `Result: ${id}`,
        description: "A search result",
        createIcon: (size) =>
          new St.Icon({
            icon_name: "dialog-information",
            width: size * scaleFactor,
            height: size * scaleFactor,
          }),
      }));
      cancellable.disconnect(cancelledId);
      if (!cancellable.is_cancelled()) resolve(metas);
    });
  }

  getInitialResultSet(terms, cancellable) {
    return new Promise((resolve, reject) => {
      const cancelledId = cancellable.connect(() => reject(Error("Cancelled")));
      // Return matching result IDs
      const ids = ["result-1", "result-2"];
      cancellable.disconnect(cancelledId);
      if (!cancellable.is_cancelled()) resolve(ids);
    });
  }

  getSubsearchResultSet(results, terms, cancellable) {
    return this.getInitialResultSet(terms, cancellable);
  }

  filterResults(results, maxResults) {
    return results.slice(0, maxResults);
  }
}
```

### Registration

```js
import * as Main from "resource:///org/gnome/shell/ui/main.js";

export default class MyExtension extends Extension {
  enable() {
    this._provider = new MySearchProvider(this);
    Main.overview.searchController.addProvider(this._provider);
  }
  disable() {
    Main.overview.searchController.removeProvider(this._provider);
    this._provider = null;
  }
}
```

## Stylesheet CSS

`stylesheet.css` applies to GNOME Shell (St widgets), NOT prefs or apps.

### Selectors

```css
/* By GType name */
StLabel {
  color: red;
}

/* By CSS class (set via style_class property) */
.my-extension-label {
  color: green;
}

/* By GType + class */
StLabel.my-extension-label {
  color: blue;
}

/* Custom GTypeName (from GObject.registerClass) */
MyCustomWidget {
  background-color: rgba(0, 0, 0, 0.5);
}
```

### Setting CSS class on widgets

```js
const label = new St.Label({
  text: "Hello",
  style_class: "my-extension-label",
});

// Or dynamically
label.add_style_class_name("active");
label.remove_style_class_name("active");
```
