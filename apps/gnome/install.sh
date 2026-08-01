#!/bin/bash
# Install script for the openwispr-gnome-extension.
#
# Builds the companion CLI (if go is available), installs the GNOME Shell
# extension via an atomically-swapped symlink, drops desktop/icon/systemd
# artifacts into XDG locations, and enables the engine service.
set -euo pipefail

UUID="openwispr-gnome-extension@tnfssc.github.com"

# Resolve the directory containing this script so the installer works from any
# CWD (e.g. `bash /some/path/install.sh`), not just from the repo root.
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
cd "$SCRIPT_DIR"

# Honor XDG user directories; fall back to the GNOME defaults.
DATA_DIR=${XDG_DATA_HOME:-$HOME/.local/share}
CONFIG_DIR=${XDG_CONFIG_HOME:-$HOME/.config}
BIN_DIR=${XDG_BIN_HOME:-$HOME/.local/bin}

EXTENSIONS_DIR="$DATA_DIR/gnome-shell/extensions"
DEST="$EXTENSIONS_DIR/$UUID"
SYSTEMD_USER_DIR="$CONFIG_DIR/systemd/user"
APP_DIR="$DATA_DIR/applications"
ICON_DIR="$DATA_DIR/icons/hicolor/256x256/apps"

echo "📦 Installing openwispr-gnome-extension..."

# --- Pre-flight checks -------------------------------------------------------
# Verify every required tool and source file exists BEFORE mutating anything,
# so a missing prerequisite does not leave the install half-done.
command -v glib-compile-schemas >/dev/null 2>&1 || {
    echo "missing required tool: glib-compile-schemas" >&2
    exit 1
}
for required in \
    "$SCRIPT_DIR/extension/schemas" \
    "$SCRIPT_DIR/openwispr.png" \
    "$SCRIPT_DIR/companion/openwispr-engine.service" \
    "$SCRIPT_DIR/companion/openwispr-hotkeyd.service" \
    "$SCRIPT_DIR/companion/io.github.tnfssc.openwispr.desktop" \
    "$SCRIPT_DIR/cmd/openwispr"
do
    [ -e "$required" ] || {
        echo "missing required source: $required" >&2
        exit 1
    }
done

# --- Staging area ------------------------------------------------------------
# Build into a temp dir and only move artifacts into place on success, so a
# build failure cannot leave a half-installed tree behind. The trap cleans up
# the staging dir on every exit path.
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT

# --- Build companion FIRST (before touching $DEST) ---------------------------
# A build failure must abort before we swap the extension symlink or copy
# service/desktop/icon files, otherwise the extension is left half-installed.
COMPANION_INSTALLED=0
if command -v go >/dev/null 2>&1; then
    echo "⚙️  Building companion CLI..."
    go build -o "$STAGE/openwispr" "$SCRIPT_DIR/cmd/openwispr"
    mkdir -p "$BIN_DIR"
    mv "$STAGE/openwispr" "$BIN_DIR/openwispr"
    COMPANION_INSTALLED=1
else
    echo "  [WARN] go not found in PATH. Companion CLI was not built."
fi

# --- Install extension (atomic symlink swap) --------------------------------
# `ln -sfn` replaces an existing symlink/dir entry without an observable
# `rm -rf`-shaped gap, so the extension is never missing between uninstall and
# reinstall.
mkdir -p "$EXTENSIONS_DIR"
ln -sfn "$SCRIPT_DIR/extension" "$DEST"

# Compile schemas inside the installed extension tree.
echo "⚙️  Compiling schemas..."
glib-compile-schemas "$DEST/schemas"

# --- Install companion artifacts (only when the companion was built) --------
if [ "$COMPANION_INSTALLED" -eq 1 ]; then
    mkdir -p "$SYSTEMD_USER_DIR" "$APP_DIR" "$ICON_DIR"
    cp "$SCRIPT_DIR/companion/openwispr-engine.service" "$SYSTEMD_USER_DIR/openwispr-engine.service"
    cp "$SCRIPT_DIR/companion/openwispr-hotkeyd.service" "$SYSTEMD_USER_DIR/openwispr-hotkeyd.service"
    cp "$SCRIPT_DIR/companion/io.github.tnfssc.openwispr.desktop" "$APP_DIR/io.github.tnfssc.openwispr.desktop"
    cp "$SCRIPT_DIR/openwispr.png" "$ICON_DIR/io.github.tnfssc.openwispr.png"

    # Reload the user manager so the freshly-copied units are picked up. We do
    # NOT swallow failures here: a broken daemon-reload/enable should surface,
    # not silently produce a half-installed service.
    systemctl --user daemon-reload

    # `enable --now` both enables the unit for future logins AND starts it
    # immediately. Auto-start at install time is intentional — to enable
    # without starting, run `systemctl --user enable openwispr-engine.service`
    # manually before running this script.
    systemctl --user enable --now openwispr-engine.service
fi

# --- Result banner -----------------------------------------------------------
echo "✅ Installed to $DEST"
if [ "$COMPANION_INSTALLED" -eq 0 ]; then
    echo "  [WARN] Companion CLI was not installed (go missing)."
    echo "         Engine service, desktop entry, and icon were skipped."
fi

echo ""
echo "👉 Next steps:"
echo "1. Log out and log back in (or restart GNOME Shell if on X11 with Alt+F2, 'r')."
echo "2. Enable the extension: gnome-extensions enable \"$UUID\""
echo "3. Open extension preferences to set an optional keyboard shortcut."
echo "4. Engine service: systemctl --user enable --now openwispr-engine.service"
echo "5. Optional hold daemon: systemctl --user enable --now openwispr-hotkeyd.service"
echo ""
echo "Dependencies check:"

# check_dep prints whether a given runtime dependency is on PATH. It is advisory
# only — missing deps do not fail the install (the extension still works with
# remote STT endpoints, for example).
check_dep() {
    local dep=$1
    if command -v "$dep" >/dev/null 2>&1; then
        echo "  [OK] $dep found at $(command -v "$dep")"
    else
        echo "  [WARN] $dep NOT found"
    fi
}
check_dep whisper-cli
check_dep ffmpeg
check_dep go
