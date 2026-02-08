#!/bin/bash
set -e

UUID="openwispr-gnome-extension@tnfssc.github.com"
DEST="$HOME/.local/share/gnome-shell/extensions/$UUID"

echo "📦 Installing openwispr-gnome-extension..."

# Create extensions dir if missing
mkdir -p "$HOME/.local/share/gnome-shell/extensions"

# Remove existing link/folder
rm -rf "$DEST"

# Symlink
ln -s "$(pwd)/extension" "$DEST"

# Compile schemas
echo "⚙️  Compiling schemas..."
glib-compile-schemas "$DEST/schemas"


echo "✅ Installed to $DEST"
echo ""
echo "👉 Next steps:"
echo "1. Log out and log back in (or restart GNOME Shell if on X11 with Alt+F2, 'r')."
echo "2. Enable the extension: gnome-extensions enable $UUID"
echo "3. Open extension preferences to set an optional keyboard shortcut."
echo ""
echo "Dependencies check:"
if which whisper-cli >/dev/null; then
    echo "  [OK] whisper-cli found at $(which whisper-cli)"
else
    echo "  [WARN] whisper-cli NOT found in PATH. Local STT will fail unless you use remote endpoints."
fi

if which ffmpeg >/dev/null; then
    echo "  [OK] ffmpeg found at $(which ffmpeg)"
else
    echo "  [WARN] ffmpeg NOT found in PATH. Silence trimming will be skipped."
fi

if which curl >/dev/null; then
    echo "  [OK] curl found at $(which curl)"
else
    echo "  [WARN] curl NOT found in PATH. Remote STT and LLM cleanup will fail."
fi
