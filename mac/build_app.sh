#!/usr/bin/env bash
set -e

cd "$(dirname "$0")"

echo "==> Building ClipItMac Menu Bar App..."
mkdir -p dist
CACHE_DIR="/tmp/clipit-clang-cache"
mkdir -p "$CACHE_DIR"

swiftc -module-cache-path "$CACHE_DIR" -O -o dist/ClipItMac Sources/ClipItMac/*.swift

APP_DIR="dist/ClipIt.app"
MACOS_DIR="$APP_DIR/Contents/MacOS"

echo "==> Packaging into $APP_DIR..."
rm -rf "$APP_DIR"
mkdir -p "$MACOS_DIR"

cp dist/ClipItMac "$MACOS_DIR/ClipItMac"
cp Info.plist "$APP_DIR/Contents/Info.plist"
if [ -f "AppIcon.icns" ]; then
    mkdir -p "$APP_DIR/Contents/Resources"
    cp AppIcon.icns "$APP_DIR/Contents/Resources/AppIcon.icns"
fi
if [ -f "AppIcon.png" ]; then
    mkdir -p "$APP_DIR/Contents/Resources"
    cp AppIcon.png "$APP_DIR/Contents/Resources/AppIcon.png"
    swift -module-cache-path "$CACHE_DIR" -e '
    import AppKit
    if let img = NSImage(contentsOfFile: "AppIcon.png") {
        _ = NSWorkspace.shared.setIcon(img, forFile: "'"$APP_DIR"'", options: [])
    }
    ' 2>/dev/null || true
fi
chmod +x "$MACOS_DIR/ClipItMac"

echo "==> Build successful! You can now launch: open $APP_DIR"
