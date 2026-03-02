#!/usr/bin/env bash
# verify-signing.sh — Compare signing certificates between ATAK-CIV and plugin APKs
# Both must share the same SHA-256 cert fingerprint for the plugin to load in ATAK.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ATAK_APK=$(ls "$REPO_ROOT/apks/atak-civ/"*.apk 2>/dev/null | sort -V | tail -1)
PLUGIN_APK=$(ls "$REPO_ROOT/apks/releases/"*.apk 2>/dev/null | sort -V | tail -1)

fingerprint() {
    apksigner verify --print-certs "$1" 2>/dev/null \
        | grep "SHA-256 digest" \
        | awk '{print $NF}'
}

echo "=== APK Signing Verification ==="
echo ""

if [ -n "$ATAK_APK" ] && [ -f "$ATAK_APK" ]; then
    ATAK_FP=$(fingerprint "$ATAK_APK")
    echo "ATAK-CIV:  $ATAK_FP"
else
    echo "ATAK-CIV:  NOT FOUND at $ATAK_APK"
    ATAK_FP="MISSING"
fi

if [ -n "$PLUGIN_APK" ]; then
    PLUGIN_FP=$(fingerprint "$PLUGIN_APK")
    echo "Plugin:    $PLUGIN_FP"
else
    echo "Plugin:    NOT FOUND in apks/releases/"
    exit 1
fi

echo ""
if [ "$ATAK_FP" = "$PLUGIN_FP" ]; then
    echo "MATCH — plugin will load in ATAK"
else
    echo "MISMATCH — plugin will be rejected by ATAK"
    echo ""
    echo "Use the debug ATAK-CIV APK from the same SDK release,"
    echo "not the Play Store or production version."
fi
