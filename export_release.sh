#!/usr/bin/env bash
set -e

REPO_DIR="$(cd "$(dirname "$0")" && pwd)"
RELEASE_DIR="$REPO_DIR/release"
mkdir -p "$RELEASE_DIR"

echo "=========================================="
echo "  🚀 Exporting ClipIt for Git & Release"
echo "=========================================="

# 1. Build macOS App
echo "==> [1/4] Building macOS Menu Bar App..."
cd "$REPO_DIR/mac"
./build_app.sh

# 2. Package macOS Zip & DMG
echo "==> [2/4] Creating macOS DMG & ZIP packages..."
cd "$REPO_DIR"

# ZIP
cd "$REPO_DIR/mac/dist"
ditto -c -k --sequesterRsrc --keepParent ClipIt.app "$RELEASE_DIR/ClipIt-macOS.zip"

# DMG with drag-to-Applications shortcut
DMG_STAGING="/tmp/ClipIt-DMG-Staging"
rm -rf "$DMG_STAGING"
mkdir -p "$DMG_STAGING"
cp -R "$REPO_DIR/mac/dist/ClipIt.app" "$DMG_STAGING/ClipIt.app"
ln -s /Applications "$DMG_STAGING/Applications"
rm -f "$RELEASE_DIR/ClipIt-macOS.dmg"
hdiutil create -volname "ClipIt" -srcfolder "$DMG_STAGING" -ov -format UDZO "$RELEASE_DIR/ClipIt-macOS.dmg"
rm -rf "$DMG_STAGING"

# 3. Export Android APK
echo "==> [3/4] Exporting Android APK..."
APK_SRC="$REPO_DIR/android/app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK_SRC" ]; then
    echo "    Compiling Android APK with Gradle..."
    cd "$REPO_DIR/android"
    JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
fi

cp "$APK_SRC" "$RELEASE_DIR/ClipIt.apk"
echo "    Exported APK to: release/ClipIt.apk"

# 4. Install macOS App into /Applications
echo "==> [4/4] Installing ClipIt to /Applications/ClipIt.app..."
killall ClipItMac 2>/dev/null || true
rm -rf /Applications/ClipIt.app
cp -R "$REPO_DIR/mac/dist/ClipIt.app" /Applications/ClipIt.app
touch /Applications/ClipIt.app

# Refresh LaunchServices
/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f /Applications/ClipIt.app 2>/dev/null || true

echo ""
echo "=========================================="
echo "  ✅ EXPORT COMPLETE!"
echo "=========================================="
echo "Artifacts generated in $RELEASE_DIR:"
ls -lh "$RELEASE_DIR"
echo ""
echo "ClipIt is now installed in /Applications/ClipIt.app!"
echo "Launching /Applications/ClipIt.app..."
open /Applications/ClipIt.app
