# Project Setup & Packaging

## Table of Contents

- [Application ID](#application-id)
- [Directory Structure](#directory-structure)
- [Meson Build System](#meson-build-system)
- [GResource](#gresource)
- [Desktop File](#desktop-file)
- [AppStream Metadata](#appstream-metadata)
- [GSettings Schema](#gsettings-schema)
- [Flatpak Manifest](#flatpak-manifest)
- [Running Uninstalled](#running-uninstalled)

## Application ID

Use reverse-DNS format: `com.example.MyApp`. Requirements:

- Min 2 segments, each starting with a letter
- Only alphanumerics and dots; final segment often `PascalCase`
- Must match across: Flatpak manifest, desktop file, GSettings schema, GResource paths, D-Bus activation
- For development builds, append `.Devel` (e.g. `com.example.MyApp.Devel`)

## Directory Structure

```
my-app/
├── com.example.MyApp.json          # Flatpak manifest
├── meson.build                      # Root build file
├── src/
│   ├── meson.build                  # Source build file
│   ├── main.js                      # Entry point
│   ├── window.js                    # Main window class
│   ├── window.ui                    # UI template for window
│   ├── com.example.MyApp.src.gresource.xml  # JS GResource manifest
│   └── style.css                    # Custom CSS (optional)
├── data/
│   ├── meson.build
│   ├── com.example.MyApp.desktop.in
│   ├── com.example.MyApp.metainfo.xml.in
│   ├── com.example.MyApp.gschema.xml
│   └── icons/
│       └── hicolor/
│           ├── scalable/apps/com.example.MyApp.svg
│           └── symbolic/apps/com.example.MyApp-symbolic.svg
└── po/
    ├── meson.build
    ├── POTFILES
    └── LINGUAS
```

## Meson Build System

### Root meson.build

```meson
project('my-app',
  version: '0.1.0',
  meson_version: '>= 0.59.0',
)

gnome = import('gnome')
i18n = import('i18n')

dependency('gjs-1.0', version: '>= 1.76.0')
dependency('gtk4', version: '>= 4.12.0')
dependency('libadwaita-1', version: '>= 1.4')

APPLICATION_ID = 'com.example.MyApp'
PACKAGE_VERSION = meson.project_version()
PREFIX = get_option('prefix')
DATADIR = join_paths(PREFIX, get_option('datadir'))
PKGDATADIR = join_paths(DATADIR, APPLICATION_ID)
BINDIR = join_paths(PREFIX, get_option('bindir'))

subdir('data')
subdir('src')
subdir('po')

gnome.post_install(
  glib_compile_schemas: true,
  gtk_update_icon_cache: true,
  update_desktop_database: true,
)
```

### src/meson.build

```meson
src_res = gnome.compile_resources(
  APPLICATION_ID + '.src',
  APPLICATION_ID + '.src.gresource.xml',
  gresource_bundle: true,
  install: true,
  install_dir: PKGDATADIR,
)

bin_conf = configuration_data()
bin_conf.set('GJS', find_program('gjs').full_path())
bin_conf.set('PACKAGE_VERSION', PACKAGE_VERSION)
bin_conf.set('APPLICATION_ID', APPLICATION_ID)
bin_conf.set('PREFIX', PREFIX)
bin_conf.set('DATADIR', DATADIR)
bin_conf.set('PKGDATADIR', PKGDATADIR)

app_launcher = configure_file(
  input: APPLICATION_ID + '.in',
  output: APPLICATION_ID,
  configuration: bin_conf,
  install: true,
  install_dir: BINDIR,
  install_mode: 'rwxr-xr-x',
)
```

### src/com.example.MyApp.in (launcher script)

```sh
#!@GJS@
import('@PKGDATADIR@/@APPLICATION_ID@.src.gresource').then(({default: src}) => {
    src._register();

    const loop = new imports.gi.GLib.MainLoop(null, false);

    import('resource:///com/example/MyApp/js/main.js')
        .catch(logError);

    loop.runAsync().catch(logError);
});
```

For simpler setups (direct GJS invocation without GResource bundling):

```sh
#!@GJS@ -m
import GLib from 'gi://GLib';
GLib.setenv('DATADIR', '@DATADIR@', true);

const {main} = await import('./main.js');
main([imports.system.programInvocationName, ...ARGV]);
```

### data/meson.build

```meson
desktop_conf = configuration_data()
desktop_conf.set('APPLICATION_ID', APPLICATION_ID)

desktop_file = i18n.merge_file(
  input: APPLICATION_ID + '.desktop.in',
  output: APPLICATION_ID + '.desktop',
  type: 'desktop',
  po_dir: join_paths(meson.project_source_root(), 'po'),
  install: true,
  install_dir: join_paths(DATADIR, 'applications'),
)

metainfo_file = i18n.merge_file(
  input: APPLICATION_ID + '.metainfo.xml.in',
  output: APPLICATION_ID + '.metainfo.xml',
  po_dir: join_paths(meson.project_source_root(), 'po'),
  install: true,
  install_dir: join_paths(DATADIR, 'metainfo'),
)

install_data(
  APPLICATION_ID + '.gschema.xml',
  install_dir: join_paths(DATADIR, 'glib-2.0', 'schemas'),
)

compile_schemas = gnome.compile_schemas(
  build_by_default: true,
  depend_files: APPLICATION_ID + '.gschema.xml',
)

icondir = join_paths(DATADIR, 'icons', 'hicolor')
install_data(
  join_paths('icons', 'hicolor', 'scalable', 'apps', APPLICATION_ID + '.svg'),
  install_dir: join_paths(icondir, 'scalable', 'apps'),
)
install_data(
  join_paths('icons', 'hicolor', 'symbolic', 'apps', APPLICATION_ID + '-symbolic.svg'),
  install_dir: join_paths(icondir, 'symbolic', 'apps'),
)
```

## GResource

### src/com.example.MyApp.src.gresource.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<gresources>
  <gresource prefix="/com/example/MyApp/js">
    <file>main.js</file>
    <file>window.js</file>
  </gresource>
  <gresource prefix="/com/example/MyApp">
    <file preprocess="xml-stripblanks">window.ui</file>
    <file>style.css</file>
  </gresource>
</gresources>
```

Use `preprocess="xml-stripblanks"` for `.ui` XML files to save space.

## Desktop File

### data/com.example.MyApp.desktop.in

```ini
[Desktop Entry]
Name=My App
Comment=A GNOME application
Exec=com.example.MyApp
Icon=com.example.MyApp
Terminal=false
Type=Application
Categories=GNOME;GTK;Utility;
StartupNotify=true
```

## AppStream Metadata

### data/com.example.MyApp.metainfo.xml.in

```xml
<?xml version="1.0" encoding="UTF-8"?>
<component type="desktop-application">
  <id>com.example.MyApp</id>
  <metadata_license>CC0-1.0</metadata_license>
  <project_license>GPL-3.0-or-later</project_license>
  <name>My App</name>
  <summary>A GNOME application</summary>
  <description>
    <p>A description of the app.</p>
  </description>
  <launchable type="desktop-id">com.example.MyApp.desktop</launchable>
  <url type="homepage">https://example.com</url>
  <content_rating type="oars-1.1"/>
  <releases>
    <release version="0.1.0" date="2025-01-01"/>
  </releases>
</component>
```

## GSettings Schema

### data/com.example.MyApp.gschema.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<schemalist gettext-domain="my-app">
  <schema id="com.example.MyApp" path="/com/example/MyApp/">
    <key name="window-width" type="i">
      <default>600</default>
      <summary>Window width</summary>
    </key>
    <key name="window-height" type="i">
      <default>400</default>
      <summary>Window height</summary>
    </key>
    <key name="window-maximized" type="b">
      <default>false</default>
      <summary>Window maximized</summary>
    </key>
  </schema>
</schemalist>
```

## Flatpak Manifest

### com.example.MyApp.json

```json
{
  "app-id": "com.example.MyApp",
  "runtime": "org.gnome.Platform",
  "runtime-version": "47",
  "sdk": "org.gnome.Sdk",
  "command": "com.example.MyApp",
  "finish-args": [
    "--share=ipc",
    "--socket=fallback-x11",
    "--socket=wayland",
    "--device=dri"
  ],
  "cleanup": [
    "/include",
    "/lib/pkgconfig",
    "/man",
    "/share/doc",
    "/share/man",
    "/share/pkgconfig",
    "*.la",
    "*.a"
  ],
  "modules": [
    {
      "name": "my-app",
      "buildsystem": "meson",
      "sources": [
        {
          "type": "dir",
          "path": "."
        }
      ]
    }
  ]
}
```

Common `finish-args`:

- `--share=network` — network access
- `--filesystem=home` — home directory (avoid if possible)
- `--talk-name=org.freedesktop.secrets` — keyring/secrets
- `--socket=pulseaudio` — audio
- Remove `--socket=fallback-x11` and `--share=ipc` for Wayland-only apps

## Running Uninstalled

Build and run locally (without Flatpak):

```sh
meson setup builddir
meson compile -C builddir
meson install -C builddir --destdir=_install
# Then launch the installed binary
```

With Flatpak (preferred):

```sh
flatpak-builder --user --install --force-clean build com.example.MyApp.json
flatpak run com.example.MyApp
```

For development iteration in GNOME Builder: open the project directory and press **Run** (Shift+Ctrl+Space).
