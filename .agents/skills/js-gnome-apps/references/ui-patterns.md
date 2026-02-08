# GTK4 + Libadwaita UI Patterns

## Table of Contents

- [Widget Templates (UI XML)](#widget-templates-ui-xml)
- [Window Pattern](#window-pattern)
- [Header Bar](#header-bar)
- [AdwToolbarView](#adwtoolbarview)
- [Boxed Lists](#boxed-lists)
- [Adaptive Layouts](#adaptive-layouts)
- [AdwClamp](#adwclamp)
- [Breakpoints](#breakpoints)
- [Navigation Split View](#navigation-split-view)
- [Overlay Split View](#overlay-split-view)
- [View Switcher](#view-switcher)
- [Tabs](#tabs)
- [Dialogs](#dialogs)
- [Toast Notifications](#toast-notifications)
- [Preferences Window](#preferences-window)
- [Style Classes](#style-classes)
- [Custom CSS](#custom-css)
- [Common Widgets Quick Reference](#common-widgets-quick-reference)

## Widget Templates (UI XML)

Define composite widget UI in `.ui` files, reference in GObject subclass:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<interface>
  <requires lib="gtk" version="4.0"/>
  <template class="MyAppWindow" parent="AdwApplicationWindow">
    <property name="title" translatable="yes">My App</property>
    <property name="default-width">600</property>
    <property name="default-height">400</property>
    <property name="content">
      <object class="AdwToolbarView">
        <child type="top">
          <object class="AdwHeaderBar" id="header_bar"/>
        </child>
        <property name="content">
          <object class="GtkLabel" id="content_label">
            <property name="label">Hello, GNOME!</property>
          </object>
        </property>
      </object>
    </property>
  </template>
</interface>
```

Corresponding JS class:

```js
export const MyAppWindow = GObject.registerClass(
  {
    GTypeName: "MyAppWindow",
    Template: "resource:///com/example/MyApp/window.ui",
    InternalChildren: ["header_bar", "content_label"],
  },
  class MyAppWindow extends Adw.ApplicationWindow {
    constructor(application) {
      super({ application });
    }
  },
);
```

## Window Pattern

Use `AdwApplicationWindow` (not `Gtk.ApplicationWindow`) for Libadwaita features:

```xml
<template class="MyAppWindow" parent="AdwApplicationWindow">
  <property name="title" translatable="yes">My App</property>
  <property name="default-width">800</property>
  <property name="default-height">600</property>
  <property name="width-request">360</property>
  <property name="height-request">200</property>
  <property name="content">
    <!-- AdwToolbarView or other content -->
  </property>
  <!-- Breakpoints go here as direct children of AdwApplicationWindow -->
  <child>
    <object class="AdwBreakpoint">
      <condition>max-width: 500sp</condition>
      <!-- setters -->
    </object>
  </child>
</template>
```

Set `width-request` / `height-request` when using breakpoints (removes implicit minimum size).

## Header Bar

Use `AdwHeaderBar` for GNOME-style title bars. It auto-manages window controls and back buttons in split views.

```xml
<object class="AdwHeaderBar" id="header_bar">
  <child type="start">
    <object class="GtkButton">
      <property name="icon-name">list-add-symbolic</property>
      <property name="tooltip-text" translatable="yes">Add Item</property>
      <property name="action-name">win.add-item</property>
    </object>
  </child>
  <child type="end">
    <object class="GtkMenuButton">
      <property name="primary">True</property>
      <property name="icon-name">open-menu-symbolic</property>
      <property name="tooltip-text" translatable="yes">Main Menu</property>
      <property name="menu-model">primary_menu</property>
    </object>
  </child>
</object>

<menu id="primary_menu">
  <section>
    <item>
      <attribute name="label" translatable="yes">_Preferences</attribute>
      <attribute name="action">app.preferences</attribute>
    </item>
    <item>
      <attribute name="label" translatable="yes">_About My App</attribute>
      <attribute name="action">app.about</attribute>
    </item>
  </section>
</menu>
```

## AdwToolbarView

Wraps content with top/bottom toolbars. Use for proper undershoot indicators and toolbar styling:

```xml
<object class="AdwToolbarView">
  <property name="top-bar-style">flat</property> <!-- flat, raised, raised-border -->
  <child type="top">
    <object class="AdwHeaderBar"/>
  </child>
  <child type="top">
    <object class="GtkSearchBar" id="search_bar">
      <property name="child">
        <object class="GtkSearchEntry" id="search_entry"/>
      </property>
    </object>
  </child>
  <property name="content">
    <!-- Main content -->
  </property>
  <child type="bottom">
    <object class="GtkActionBar" id="action_bar"/>
  </child>
</object>
```

## Boxed Lists

Standard GNOME pattern for settings-style lists. Apply `.boxed-list` to `GtkListBox` with `selection-mode=none`:

```xml
<object class="AdwClamp">
  <property name="child">
    <object class="GtkBox">
      <property name="orientation">vertical</property>
      <property name="margin-top">24</property>
      <property name="margin-bottom">24</property>
      <property name="margin-start">12</property>
      <property name="margin-end">12</property>
      <property name="spacing">24</property>
      <child>
        <object class="AdwPreferencesGroup">
          <property name="title" translatable="yes">General</property>
          <property name="description" translatable="yes">App settings</property>
          <child>
            <object class="AdwSwitchRow">
              <property name="title" translatable="yes">Dark Mode</property>
              <property name="subtitle" translatable="yes">Force dark color scheme</property>
            </object>
          </child>
          <child>
            <object class="AdwComboRow">
              <property name="title" translatable="yes">Language</property>
            </object>
          </child>
          <child>
            <object class="AdwEntryRow">
              <property name="title" translatable="yes">Username</property>
            </object>
          </child>
          <child>
            <object class="AdwSpinRow">
              <property name="title" translatable="yes">Font Size</property>
              <property name="adjustment">
                <object class="GtkAdjustment">
                  <property name="lower">8</property>
                  <property name="upper">72</property>
                  <property name="step-increment">1</property>
                  <property name="value">12</property>
                </object>
              </property>
            </object>
          </child>
        </object>
      </child>
    </object>
  </property>
</object>
```

Row types: `AdwActionRow`, `AdwSwitchRow`, `AdwComboRow`, `AdwEntryRow`, `AdwPasswordEntryRow`, `AdwSpinRow`, `AdwExpanderRow`, `AdwButtonRow`.

Use `AdwPreferencesGroup` for titled boxed lists. Use `AdwPreferencesPage` to wrap multiple groups with scrolling.

## Adaptive Layouts

### AdwClamp

Constrains max width while allowing shrinking. Standard for form/settings views:

```xml
<object class="AdwClamp">
  <property name="maximum-size">600</property>
  <property name="tightening-threshold">400</property>
  <property name="child"><!-- content --></property>
</object>
```

### Breakpoints

Change widget properties at specific size thresholds. Conditions use `sp` (scale-independent pixels):

```xml
<!-- As direct child of AdwWindow/AdwApplicationWindow/AdwDialog -->
<child>
  <object class="AdwBreakpoint">
    <condition>max-width: 500sp</condition>
    <setter object="my_widget" property="visible">False</setter>
    <setter object="split_view" property="collapsed">True</setter>
  </object>
</child>
```

In JS:

```js
const bp = new Adw.Breakpoint({
  condition: Adw.BreakpointCondition.parse("max-width: 500sp"),
});
bp.add_setter(myWidget, "visible", false);
this.add_breakpoint(bp);
```

### Navigation Split View

Sidebar + content pattern. Collapses to stack navigation on narrow:

```xml
<object class="AdwNavigationSplitView" id="split_view">
  <property name="sidebar">
    <object class="AdwNavigationPage">
      <property name="title" translatable="yes">Sidebar</property>
      <property name="tag">sidebar</property>
      <property name="child">
        <object class="AdwToolbarView">
          <child type="top"><object class="AdwHeaderBar"/></child>
          <property name="content"><!-- sidebar content --></property>
        </object>
      </property>
    </object>
  </property>
  <property name="content">
    <object class="AdwNavigationPage">
      <property name="title" translatable="yes">Content</property>
      <property name="tag">content</property>
      <property name="child">
        <object class="AdwToolbarView">
          <child type="top"><object class="AdwHeaderBar"/></child>
          <property name="content"><!-- main content --></property>
        </object>
      </property>
    </object>
  </property>
</object>
```

Collapse with breakpoint:

```xml
<child>
  <object class="AdwBreakpoint">
    <condition>max-width: 400sp</condition>
    <setter object="split_view" property="collapsed">True</setter>
  </object>
</child>
```

### Overlay Split View

Sidebar overlays content when collapsed (utility pane pattern):

```xml
<object class="AdwOverlaySplitView" id="split_view">
  <property name="sidebar-position">end</property>
  <property name="show-sidebar"
            bind-source="show_sidebar_btn"
            bind-property="active"
            bind-flags="sync-create|bidirectional"/>
  <property name="sidebar"><!-- pane content --></property>
  <property name="content"><!-- main content --></property>
</object>
```

### View Switcher

Adaptive navigation with header bar switcher → bottom bar on narrow:

```xml
<child>
  <object class="AdwBreakpoint">
    <condition>max-width: 550sp</condition>
    <setter object="switcher_bar" property="reveal">True</setter>
    <setter object="header_bar" property="title-widget"/>
  </object>
</child>
<property name="content">
  <object class="AdwToolbarView">
    <child type="top">
      <object class="AdwHeaderBar" id="header_bar">
        <property name="title-widget">
          <object class="AdwViewSwitcher">
            <property name="stack">stack</property>
            <property name="policy">wide</property>
          </object>
        </property>
      </object>
    </child>
    <property name="content">
      <object class="AdwViewStack" id="stack">
        <child>
          <object class="AdwViewStackPage">
            <property name="name">page1</property>
            <property name="title" translatable="yes">Page 1</property>
            <property name="icon-name">view-grid-symbolic</property>
            <property name="child"><!-- page content --></property>
          </object>
        </child>
      </object>
    </property>
    <child type="bottom">
      <object class="AdwViewSwitcherBar" id="switcher_bar">
        <property name="stack">stack</property>
      </object>
    </child>
  </object>
</property>
```

## Tabs

Dynamic tabbed UI with `AdwTabView`, `AdwTabBar`, and `AdwTabOverview`:

```xml
<object class="AdwTabOverview">
  <property name="view">view</property>
  <property name="enable-new-tab">True</property>
  <property name="child">
    <object class="AdwToolbarView">
      <child type="top">
        <object class="AdwTabBar" id="tab_bar">
          <property name="view">view</property>
        </object>
      </child>
      <property name="content">
        <object class="AdwTabView" id="view"/>
      </property>
    </object>
  </property>
</object>
```

## Dialogs

Use `AdwDialog` for adaptive floating/bottom-sheet dialogs. Requires `AdwWindow` or `AdwApplicationWindow` as parent.

In JS:

```js
const dialog = new Adw.Dialog({
  title: "My Dialog",
  content_width: 400,
  content_height: 300,
});
dialog.set_child(content_widget);
dialog.present(this); // pass parent widget
```

For alerts, use `AdwAlertDialog`:

```js
const dialog = new Adw.AlertDialog({
  heading: "Delete Item?",
  body: "This action cannot be undone.",
});
dialog.add_response("cancel", "Cancel");
dialog.add_response("delete", "Delete");
dialog.set_response_appearance("delete", Adw.ResponseAppearance.DESTRUCTIVE);
dialog.set_default_response("cancel");
dialog.set_close_response("cancel");
dialog.connect("response", (dlg, response) => {
  if (response === "delete") this._deleteItem();
});
dialog.present(this);
```

## Toast Notifications

`AdwToastOverlay` shows in-app toast messages:

```xml
<object class="AdwToastOverlay" id="toast_overlay">
  <property name="child">
    <!-- main content -->
  </property>
</object>
```

```js
const toast = new Adw.Toast({ title: "Item deleted" });
toast.set_button_label("Undo");
toast.connect("button-clicked", () => this._undoDelete());
this._toast_overlay.add_toast(toast);
```

## Preferences Window

```js
const PrefsWindow = GObject.registerClass(
  {
    GTypeName: "MyAppPrefsWindow",
    Template: "resource:///com/example/MyApp/prefs-window.ui",
  },
  class PrefsWindow extends Adw.PreferencesWindow {},
);
```

```xml
<template class="MyAppPrefsWindow" parent="AdwPreferencesWindow">
  <property name="title" translatable="yes">Preferences</property>
  <child>
    <object class="AdwPreferencesPage">
      <property name="title" translatable="yes">General</property>
      <property name="icon-name">preferences-system-symbolic</property>
      <child>
        <object class="AdwPreferencesGroup">
          <property name="title" translatable="yes">Appearance</property>
          <child>
            <object class="AdwSwitchRow" id="dark_mode_row">
              <property name="title" translatable="yes">Dark Mode</property>
            </object>
          </child>
        </object>
      </child>
    </object>
  </child>
</template>
```

## Style Classes

Apply via UI XML `<style><class name="..."/></style>` or JS `widget.add_css_class('...')`.

### Buttons

| Class                 | Effect                                    |
| --------------------- | ----------------------------------------- |
| `.suggested-action`   | Accent-colored button for primary actions |
| `.destructive-action` | Red/warning-colored for dangerous actions |
| `.flat`               | Borderless look (default in toolbars)     |
| `.raised`             | Force non-flat in toolbars                |
| `.circular`           | Round button (icon-only or 1-2 chars)     |
| `.pill`               | Pill-shaped (standalone actions)          |

### Typography

| Class                          | Effect                                |
| ------------------------------ | ------------------------------------- |
| `.title-1` to `.title-4`       | Heading hierarchy                     |
| `.heading`                     | Standard UI heading                   |
| `.body`                        | Increased line height for readability |
| `.caption`, `.caption-heading` | Small secondary text                  |
| `.monospace`                   | Monospace font                        |
| `.numeric`                     | Tabular (fixed-width) figures         |

### Containers & Layout

| Class                  | Effect                                |
| ---------------------- | ------------------------------------- |
| `.boxed-list`          | Card-style list (on `GtkListBox`)     |
| `.boxed-list-separate` | Each row as separate card             |
| `.card`                | Card appearance on any widget         |
| `.navigation-sidebar`  | Sidebar list style                    |
| `.toolbar`             | Toolbar style on `GtkBox`             |
| `.linked`              | Group children visually (on `GtkBox`) |

### Semantic Colors

| Class      | Effect         |
| ---------- | -------------- |
| `.accent`  | Accent color   |
| `.success` | Green/success  |
| `.warning` | Yellow/warning |
| `.error`   | Red/error      |

### Other

| Class            | Effect                                            |
| ---------------- | ------------------------------------------------- |
| `.osd`           | Semi-transparent dark overlay style               |
| `.devel`         | Striped header (development builds)               |
| `.compact`       | Compact `AdwStatusPage`                           |
| `.dimmed` (1.7+) | Partially transparent                             |
| `.inline`        | Neutral background for `GtkSearchBar`/`AdwTabBar` |

## Custom CSS

Load app-specific CSS in application constructor:

```js
vfunc_startup() {
    super.vfunc_startup();
    const cssProvider = new Gtk.CssProvider();
    cssProvider.load_from_resource('/com/example/MyApp/style.css');
    Gtk.StyleContext.add_provider_for_display(
        Gdk.Display.get_default(),
        cssProvider,
        Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION);
}
```

Use Libadwaita CSS variables for theme consistency:

```css
/* Key named colors */
@define-color accent_bg_color var(--accent-bg-color);

.my-custom-class {
  background-color: var(--view-bg-color);
  color: var(--view-fg-color);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  padding: 12px;
}
```

## Common Widgets Quick Reference

| Widget                             | Purpose                                             |
| ---------------------------------- | --------------------------------------------------- |
| `AdwApplicationWindow`             | App window with Adw features (breakpoints, dialogs) |
| `AdwHeaderBar`                     | Title bar with back button and window controls      |
| `AdwToolbarView`                   | Content wrapper with top/bottom bars                |
| `AdwNavigationView`                | Stack-based page navigation                         |
| `AdwNavigationSplitView`           | Sidebar + content (collapses to stack)              |
| `AdwOverlaySplitView`              | Sidebar overlays content when collapsed             |
| `AdwViewStack` / `AdwViewSwitcher` | Tab-like view switching                             |
| `AdwStatusPage`                    | Empty/error/welcome states                          |
| `AdwPreferencesWindow`             | Settings window                                     |
| `AdwPreferencesPage` / `Group`     | Settings page/group containers                      |
| `AdwActionRow`                     | Basic list row with title/subtitle                  |
| `AdwSwitchRow`                     | Row with toggle switch                              |
| `AdwComboRow`                      | Row with dropdown selector                          |
| `AdwEntryRow`                      | Row with text entry                                 |
| `AdwExpanderRow`                   | Expandable row with children                        |
| `AdwSpinRow`                       | Row with numeric spinner                            |
| `AdwButtonRow`                     | Clickable action row                                |
| `AdwClamp`                         | Constrains max width                                |
| `AdwDialog`                        | Adaptive dialog (floating/bottom sheet)             |
| `AdwAlertDialog`                   | Confirmation/action dialog                          |
| `AdwToastOverlay` / `AdwToast`     | In-app notifications                                |
| `AdwAboutDialog`                   | Standard about dialog                               |
| `AdwBreakpoint`                    | Responsive layout triggers                          |
| `AdwTabView` / `AdwTabBar`         | Dynamic tabs                                        |
| `AdwBanner`                        | Info bar across top of content                      |
| `AdwWrapBox`                       | Flow-wrap children                                  |
| `GtkBox`                           | Linear container (horizontal/vertical)              |
| `GtkListBox`                       | Vertical list of rows                               |
| `GtkFlowBox`                       | Grid-like flow container                            |
| `GtkStack` / `GtkStackSwitcher`    | Page switching                                      |
| `GtkScrolledWindow`                | Scrollable container                                |
| `GtkOverlay`                       | Stack widgets on top of each other                  |
| `GtkButton`                        | Push button                                         |
| `GtkToggleButton`                  | Two-state button                                    |
| `GtkMenuButton`                    | Button with popup menu                              |
| `GtkLabel`                         | Text display                                        |
| `GtkEntry`                         | Single-line text input                              |
| `GtkTextView`                      | Multi-line text editor                              |
| `GtkImage`                         | Image display                                       |
| `GtkSpinner`                       | Loading indicator                                   |
| `GtkProgressBar`                   | Progress indicator                                  |
| `GtkSwitch`                        | Toggle switch                                       |
| `GtkCheckButton`                   | Checkbox or radio button                            |
| `GtkDropDown`                      | Dropdown selector                                   |
