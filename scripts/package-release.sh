#!/usr/bin/env bash
set -euo pipefail

APP_NAME="OpenWispr"
TAG="${1:-dev}"
ARCH="$(uname -m)"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST_DIR="$ROOT_DIR/dist"
APP_BUNDLE_PATH="$DIST_DIR/${APP_NAME}.app"
ZIP_NAME="${APP_NAME}-${TAG}-macos-${ARCH}.zip"
ZIP_PATH="$DIST_DIR/$ZIP_NAME"

BUNDLE_VERSION="${TAG#v}"
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

echo "[package] creating app bundle"
rm -rf "$APP_BUNDLE_PATH"
mkdir -p "$APP_BUNDLE_PATH/Contents/MacOS"

cp "$BIN_PATH" "$APP_BUNDLE_PATH/Contents/MacOS/$APP_NAME"
chmod +x "$APP_BUNDLE_PATH/Contents/MacOS/$APP_NAME"

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

echo "[package] zipping app bundle"
mkdir -p "$DIST_DIR"
rm -f "$ZIP_PATH" "$ZIP_PATH.sha256"
ditto -c -k --sequesterRsrc --keepParent "$APP_BUNDLE_PATH" "$ZIP_PATH"
shasum -a 256 "$ZIP_PATH" > "$ZIP_PATH.sha256"

echo "[package] created artifact"
echo "  $ZIP_PATH"
echo "  $ZIP_PATH.sha256"
