#!/usr/bin/env bash
set -euo pipefail

APP_NAME="OpenWispr"
TAG="${1:-dev}"
ARCH="$(uname -m)"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST_DIR="$ROOT_DIR/dist"
APP_BUNDLE_PATH="$DIST_DIR/${APP_NAME}.app"
DMG_NAME="${APP_NAME}-${TAG}-macos-${ARCH}.dmg"
DMG_PATH="$DIST_DIR/$DMG_NAME"
DMG_STAGING_DIR="$DIST_DIR/.dmg-staging"
ICON_SOURCE="$ROOT_DIR/openwispr.png"
ICONSET_DIR="$DIST_DIR/.AppIcon.iconset"
ICON_FILE_NAME="AppIcon.icns"
CHECKSUM_PATH="$DMG_PATH.sha256"

BUNDLE_VERSION="${TAG#macos-}"
BUNDLE_VERSION="${BUNDLE_VERSION#v}"
if [[ ! "$BUNDLE_VERSION" =~ ^[0-9]+(\.[0-9]+)*$ ]]; then
  BUNDLE_VERSION="0.0.0"
fi

echo "[package] building release binary"
swift build -c release --product "$APP_NAME"
BIN_PATH="$(swift build -c release --show-bin-path)/$APP_NAME"

if [[ ! -x "$BIN_PATH" ]]; then
  echo "release binary not found: $BIN_PATH" >&2
  exit 1
fi

if [[ ! -f "$ICON_SOURCE" ]]; then
  echo "missing icon source: $ICON_SOURCE" >&2
  exit 1
fi

echo "[package] creating app bundle"
rm -rf "$APP_BUNDLE_PATH"
mkdir -p "$APP_BUNDLE_PATH/Contents/MacOS" "$APP_BUNDLE_PATH/Contents/Resources"

cp "$BIN_PATH" "$APP_BUNDLE_PATH/Contents/MacOS/$APP_NAME"
chmod +x "$APP_BUNDLE_PATH/Contents/MacOS/$APP_NAME"

echo "[package] generating app icon"
rm -rf "$ICONSET_DIR"
mkdir -p "$ICONSET_DIR"

for size in 16 32 128 256 512; do
  sips -s format png -z "$size" "$size" "$ICON_SOURCE" --out "$ICONSET_DIR/icon_${size}x${size}.png" >/dev/null
  doubled_size="$((size * 2))"
  sips -s format png -z "$doubled_size" "$doubled_size" "$ICON_SOURCE" --out "$ICONSET_DIR/icon_${size}x${size}@2x.png" >/dev/null
done

iconutil -c icns "$ICONSET_DIR" -o "$APP_BUNDLE_PATH/Contents/Resources/$ICON_FILE_NAME"
rm -rf "$ICONSET_DIR"

cat > "$APP_BUNDLE_PATH/Contents/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key>
  <string>OpenWispr</string>
  <key>CFBundleDisplayName</key>
  <string>OpenWispr</string>
  <key>CFBundleExecutable</key>
  <string>OpenWispr</string>
  <key>CFBundleIdentifier</key>
  <string>io.github.tnfssc.openwispr</string>
  <key>CFBundleIconFile</key>
  <string>AppIcon</string>
  <key>CFBundlePackageType</key>
  <string>APPL</string>
  <key>CFBundleShortVersionString</key>
  <string>${BUNDLE_VERSION}</string>
  <key>CFBundleVersion</key>
  <string>${BUNDLE_VERSION}</string>
  <key>LSMinimumSystemVersion</key>
  <string>14.0</string>
  <key>LSUIElement</key>
  <true/>
  <key>NSMicrophoneUsageDescription</key>
  <string>OpenWispr records audio for speech transcription.</string>
</dict>
</plist>
EOF

echo "[package] building dmg"
mkdir -p "$DIST_DIR"
rm -rf "$DMG_STAGING_DIR"
mkdir -p "$DMG_STAGING_DIR"

cp -R "$APP_BUNDLE_PATH" "$DMG_STAGING_DIR/"
ln -s /Applications "$DMG_STAGING_DIR/Applications"

rm -f "$DMG_PATH" "$CHECKSUM_PATH"
hdiutil create -volname "$APP_NAME" -srcfolder "$DMG_STAGING_DIR" -ov -format UDZO "$DMG_PATH" >/dev/null
shasum -a 256 "$DMG_PATH" > "$CHECKSUM_PATH"
rm -rf "$DMG_STAGING_DIR"

echo "[package] created artifact"
echo "  $DMG_PATH"
echo "  $CHECKSUM_PATH"
