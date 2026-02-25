#!/usr/bin/env bash
# install-dev.sh — Sideload ATAK-CIV debug APK + Meshtastic plugin onto a connected device
# Usage: ./scripts/install-dev.sh [device-serial]
#
# Signing: Both ATAK-CIV debug and the plugin debug build are signed with the TAK SDK debug
# keystore (ATAK-Plugin/sdk/android_keystore). They must come from the same SDK release (5.5.1.8).
# Download ATAK-CIV 5.5.1.8 debug APK from the TAK Product Center GitHub releases and place it
# at: apks/atak-civ/atak-civ-5.5.1.8-debug.apk

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ATAK_APK="$REPO_ROOT/apks/atak-civ/atak-civ-5.5.1.8-debug.apk"
PLUGIN_APK=$(ls "$REPO_ROOT/apks/releases/"*.apk 2>/dev/null | sort -V | tail -1)

# Serial selection
ADB_ARGS=""
if [ -n "${1:-}" ]; then
    ADB_ARGS="-s $1"
    echo "Targeting device: $1"
fi

# Check adb can see a device
if ! adb $ADB_ARGS get-state &>/dev/null; then
    echo "ERROR: No device connected (or device not authorized for adb)."
    echo "  - Enable USB debugging on the Pixel"
    echo "  - Accept the RSA fingerprint prompt on the device"
    echo "  - Run: adb devices"
    exit 1
fi

DEVICE=$(adb $ADB_ARGS get-serialno)
echo "Device: $DEVICE"

# 1. Install ATAK-CIV (if APK present)
if [ -f "$ATAK_APK" ]; then
    echo ""
    echo "Installing ATAK-CIV debug APK..."
    adb $ADB_ARGS install -r "$ATAK_APK"
    echo "ATAK-CIV installed."
else
    echo ""
    echo "SKIP: ATAK-CIV APK not found at $ATAK_APK"
    echo "  Download from: https://github.com/TAK-Product-Center/atak-civ/releases"
    echo "  Look for: ATAK-CIV-5.5.1.8-*.apk (debug variant)"
    echo "  Place at:  apks/atak-civ/atak-civ-5.5.1.8-debug.apk"
fi

# 2. Install Meshtastic plugin
if [ -n "$PLUGIN_APK" ]; then
    echo ""
    echo "Installing plugin: $(basename "$PLUGIN_APK")"
    adb $ADB_ARGS install -r "$PLUGIN_APK"
    echo "Plugin installed."
else
    echo "ERROR: No plugin APK found in apks/releases/. Run: ./gradlew assembleCivDebug"
    exit 1
fi

echo ""
echo "Done. Launch ATAK on the device to verify plugin loads."
