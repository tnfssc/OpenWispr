# GIO & Platform Patterns

## Table of Contents

- [File Operations](#file-operations)
- [GSettings](#gsettings)
- [Actions & Menus](#actions--menus)
- [D-Bus](#d-bus)
- [Subprocesses](#subprocesses)
- [List Models](#list-models)
- [GVariant](#gvariant)
- [Cancellable](#cancellable)
- [Memory Management Tips](#memory-management-tips)

## File Operations

### Promisify common methods (call once at module level)

```js
import GLib from "gi://GLib";
import Gio from "gi://Gio";

Gio._promisify(
  Gio.File.prototype,
  "load_contents_async",
  "load_contents_finish",
);
Gio._promisify(
  Gio.File.prototype,
  "replace_contents_bytes_async",
  "replace_contents_finish",
);
Gio._promisify(Gio.File.prototype, "delete_async", "delete_finish");
Gio._promisify(
  Gio.File.prototype,
  "make_directory_async",
  "make_directory_finish",
);
Gio._promisify(
  Gio.File.prototype,
  "enumerate_children_async",
  "enumerate_children_finish",
);
Gio._promisify(
  Gio.FileEnumerator.prototype,
  "next_files_async",
  "next_files_finish",
);
```

### Read a file

```js
const file = Gio.File.new_for_path("/path/to/file.txt");
try {
  const [contents] = await file.load_contents_async(null);
  const text = new TextDecoder().decode(contents);
  console.log(text);
} catch (e) {
  if (!e.matches(Gio.IOErrorEnum, Gio.IOErrorEnum.NOT_FOUND)) logError(e);
}
```

### Write a file

```js
const file = Gio.File.new_for_path("/path/to/output.txt");
const bytes = new TextEncoder().encode("Hello, GNOME!\n");
try {
  await file.replace_contents_bytes_async(
    new GLib.Bytes(bytes),
    null, // etag
    false, // make_backup
    Gio.FileCreateFlags.NONE,
    null, // cancellable
  );
} catch (e) {
  logError(e, "Failed to write file");
}
```

### File chooser dialog (GTK 4.10+)

```js
const dialog = new Gtk.FileDialog({
  title: "Open File",
  filters: (() => {
    const filters = new Gio.ListStore({ item_type: Gtk.FileFilter });
    const textFilter = new Gtk.FileFilter({ name: "Text Files" });
    textFilter.add_mime_type("text/plain");
    filters.append(textFilter);
    return filters;
  })(),
});

Gio._promisify(Gtk.FileDialog.prototype, "open", "open_finish");
try {
  const file = await dialog.open(this, null);
  console.log(file.get_path());
} catch (e) {
  if (!e.matches(Gtk.DialogError, Gtk.DialogError.DISMISSED)) logError(e);
}
```

### List directory contents

```js
const dir = Gio.File.new_for_path("/path/to/dir");
try {
  const enumerator = await dir.enumerate_children_async(
    "standard::name,standard::type",
    Gio.FileQueryInfoFlags.NONE,
    GLib.PRIORITY_DEFAULT,
    null,
  );

  const infos = await enumerator.next_files_async(
    100,
    GLib.PRIORITY_DEFAULT,
    null,
  );

  for (const info of infos) {
    console.log(info.get_name(), info.get_file_type());
  }
} catch (e) {
  logError(e);
}
```

## GSettings

### Reading and writing

```js
const settings = new Gio.Settings({ schema_id: "com.example.MyApp" });

// Read
const width = settings.get_int("window-width");
const name = settings.get_string("user-name");
const enabled = settings.get_boolean("feature-enabled");

// Write
settings.set_int("window-width", 800);
settings.set_string("user-name", "Alice");
settings.set_boolean("feature-enabled", true);
```

### Binding to widget properties

```js
settings.bind(
  "window-width",
  this,
  "default-width",
  Gio.SettingsBindFlags.DEFAULT,
);

settings.bind(
  "dark-mode",
  darkModeSwitch,
  "active",
  Gio.SettingsBindFlags.DEFAULT,
);
```

### Monitoring changes

```js
settings.connect("changed::feature-enabled", (settings, key) => {
  console.log(`${key} changed to ${settings.get_boolean(key)}`);
});
```

### Saving window state (common pattern)

```js
// In window constructor
const settings = new Gio.Settings({ schema_id: "com.example.MyApp" });
settings.bind(
  "window-width",
  this,
  "default-width",
  Gio.SettingsBindFlags.DEFAULT,
);
settings.bind(
  "window-height",
  this,
  "default-height",
  Gio.SettingsBindFlags.DEFAULT,
);
settings.bind(
  "window-maximized",
  this,
  "maximized",
  Gio.SettingsBindFlags.DEFAULT,
);
```

## Actions & Menus

### Application-level actions

```js
// In application constructor
const action = new Gio.SimpleAction({ name: "about" });
action.connect("activate", () => {
  const aboutDialog = new Adw.AboutDialog({
    application_name: "My App",
    application_icon: "com.example.MyApp",
    developer_name: "Developer",
    version: "0.1.0",
    website: "https://example.com",
    license_type: Gtk.License.GPL_3_0,
  });
  aboutDialog.present(this.active_window);
});
this.add_action(action);
```

### Window-level actions

```js
// In window constructor
const action = new Gio.SimpleAction({ name: "save" });
action.connect("activate", () => this._saveFile());
this.add_action(action);
this.application.set_accels_for_action("win.save", ["<Primary>s"]);
```

### Stateful actions (toggles)

```js
const toggleAction = Gio.SimpleAction.new_stateful(
  "show-sidebar",
  null,
  GLib.Variant.new_boolean(true),
);

toggleAction.connect("activate", (action) => {
  const current = action.get_state().get_boolean();
  action.set_state(GLib.Variant.new_boolean(!current));
});
this.add_action(toggleAction);
```

### Parameterized actions

```js
const openAction = Gio.SimpleAction.new("open-page", new GLib.VariantType("s"));

openAction.connect("activate", (_action, param) => {
  const pageName = param.get_string()[0];
  this._navigateTo(pageName);
});
this.add_action(openAction);
```

### Menu model in UI XML

```xml
<menu id="primary_menu">
  <section>
    <item>
      <attribute name="label" translatable="yes">_Preferences</attribute>
      <attribute name="action">app.preferences</attribute>
    </item>
  </section>
  <section>
    <item>
      <attribute name="label" translatable="yes">_Keyboard Shortcuts</attribute>
      <attribute name="action">win.show-help-overlay</attribute>
    </item>
    <item>
      <attribute name="label" translatable="yes">_About My App</attribute>
      <attribute name="action">app.about</attribute>
    </item>
  </section>
</menu>
```

### Keyboard shortcuts overlay

```xml
<object class="GtkShortcutsWindow" id="help_overlay">
  <property name="modal">True</property>
  <child>
    <object class="GtkShortcutsSection">
      <property name="section-name">shortcuts</property>
      <child>
        <object class="GtkShortcutsGroup">
          <property name="title" translatable="yes">General</property>
          <child>
            <object class="GtkShortcutsShortcut">
              <property name="title" translatable="yes">Quit</property>
              <property name="accelerator">&lt;Primary&gt;q</property>
            </object>
          </child>
        </object>
      </child>
    </object>
  </child>
</object>
```

## D-Bus

### Calling a D-Bus method

```js
const bus = Gio.bus_get_sync(Gio.BusType.SESSION, null);

Gio._promisify(Gio.DBusConnection.prototype, "call", "call_finish");

const result = await bus.call(
  "org.freedesktop.Notifications", // bus name
  "/org/freedesktop/Notifications", // object path
  "org.freedesktop.Notifications", // interface
  "Notify", // method
  new GLib.Variant("(susssasa{sv}i)", [
    "My App",
    0,
    "dialog-information",
    "Title",
    "Body",
    [],
    {},
    -1,
  ]),
  new GLib.VariantType("(u)"),
  Gio.DBusCallFlags.NONE,
  -1,
  null,
);
```

### Exporting an interface (server)

```js
const interfaceXml = `
<node>
  <interface name="com.example.MyApp">
    <method name="DoSomething">
      <arg type="s" direction="in" name="input"/>
      <arg type="s" direction="out" name="result"/>
    </method>
    <signal name="SomethingHappened">
      <arg type="s" name="details"/>
    </signal>
  </interface>
</node>`;

const nodeInfo = Gio.DBusNodeInfo.new_for_xml(interfaceXml);

Gio.bus_own_name(
  Gio.BusType.SESSION,
  "com.example.MyApp",
  Gio.BusNameOwnerFlags.NONE,
  (connection) => {
    connection.register_object(
      "/com/example/MyApp",
      nodeInfo.interfaces[0],
      (connection, sender, path, iface, method, params, invocation) => {
        if (method === "DoSomething") {
          const [input] = params.deep_unpack();
          invocation.return_value(
            new GLib.Variant("(s)", [`Processed: ${input}`]),
          );
        }
      },
      null,
      null,
    );
  },
  null,
  null,
);
```

## Subprocesses

```js
Gio._promisify(
  Gio.Subprocess.prototype,
  "communicate_utf8_async",
  "communicate_utf8_finish",
);

const proc = new Gio.Subprocess({
  argv: ["ls", "-la", "/tmp"],
  flags: Gio.SubprocessFlags.STDOUT_PIPE | Gio.SubprocessFlags.STDERR_PIPE,
});
proc.init(null);

try {
  const [stdout, stderr] = await proc.communicate_utf8_async(null, null);
  if (proc.get_successful()) console.log(stdout);
  else console.error(stderr);
} catch (e) {
  logError(e);
}
```

## List Models

GIO list models drive GTK widgets like `GtkListView`, `GtkGridView`, `GtkDropDown`, and `GtkColumnView`.

### Gio.ListStore

```js
const store = new Gio.ListStore({ item_type: GObject.TYPE_OBJECT });
store.append(new MyItem({ name: "Item 1" }));
store.append(new MyItem({ name: "Item 2" }));

// With GtkListView
const factory = new Gtk.SignalListItemFactory();
factory.connect("setup", (_factory, listItem) => {
  listItem.set_child(new Gtk.Label());
});
factory.connect("bind", (_factory, listItem) => {
  const item = listItem.get_item();
  listItem.get_child().label = item.name;
});

const selectionModel = new Gtk.SingleSelection({ model: store });
const listView = new Gtk.ListView({
  model: selectionModel,
  factory: factory,
});
```

### Filtering and sorting

```js
const filterModel = new Gtk.FilterListModel({
  model: store,
  filter: Gtk.CustomFilter.new((item) => {
    return item.name.includes(searchText);
  }),
});

const sortModel = new Gtk.SortListModel({
  model: filterModel,
  sorter: Gtk.CustomSorter.new((a, b) => {
    return a.name.localeCompare(b.name);
  }),
});
```

## GVariant

Common type strings:

| Type String | JavaScript Type         |
| ----------- | ----------------------- |
| `b`         | Boolean                 |
| `s`         | String                  |
| `i`         | Int32                   |
| `u`         | Uint32                  |
| `d`         | Double                  |
| `as`        | Array of strings        |
| `a{sv}`     | Dict (string → variant) |
| `(si)`      | Tuple (string, int)     |

```js
// Create
const variant = new GLib.Variant("(si)", ["hello", 42]);
const arrayVar = new GLib.Variant("as", ["one", "two", "three"]);
const dictVar = new GLib.Variant("a{sv}", {
  name: GLib.Variant.new_string("Alice"),
  age: GLib.Variant.new_int32(30),
});

// Unpack
const [str, num] = variant.deep_unpack();
const arr = arrayVar.deep_unpack(); // ['one', 'two', 'three']
const dict = dictVar.deep_unpack(); // {name: 'Alice', age: 30}
```

## Cancellable

Pass `Gio.Cancellable` to async operations for cancellation support:

```js
this._cancellable = new Gio.Cancellable();

try {
  const [contents] = await file.load_contents_async(this._cancellable);
} catch (e) {
  if (e.matches(Gio.IOErrorEnum, Gio.IOErrorEnum.CANCELLED)) return; // Normal cancellation, not an error
  logError(e);
}

// Cancel all operations using this cancellable
this._cancellable.cancel();
// Create fresh cancellable for future operations
this._cancellable = new Gio.Cancellable();
```

## Memory Management Tips

- Disconnect signal handlers in `vfunc_dispose()` or when no longer needed
- Use `object.disconnect(handlerId)` to disconnect specific handlers
- Clear references to GObject instances when disposing to break reference cycles
- Use `Gio.Cancellable` for async operations; cancel them in `vfunc_dispose()`
- Avoid closures that capture `this` in long-lived signal handlers — prefer `.bind(this)` and store the handler ID for later disconnection
- For template-based widgets, GTK handles disposal of template children automatically
