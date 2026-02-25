#!/usr/bin/env bash
# install-plugin.sh — Install Meshtastic ATAK plugin APK to one or both devices
# Usage: ./install-plugin.sh [apk_path] [device_serial_1] [device_serial_2]
#
# If no APK path given, uses the latest APK in apks/releases/
# If no serials given, installs to all connected devices

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RELEASES_DIR="$SCRIPT_DIR/../apks/releases"

# Resolve APK
if [[ $# -ge 1 && -f "$1" ]]; then
  APK="$1"
  shift
else
  APK=$(ls -t "$RELEASES_DIR"/*.apk 2>/dev/null | head -1)
  if [[ -z "$APK" ]]; then
    echo "ERROR: No APK found in $RELEASES_DIR" >&2
    echo "Run: ./gradlew assembleCivDebug && cp app/build/outputs/apk/civ/debug/*.apk $RELEASES_DIR/" >&2
    exit 1
  fi
fi

echo "APK: $APK"
echo ""

# Resolve target devices
if [[ $# -ge 1 ]]; then
  DEVICES=("$@")
else
  mapfile -t DEVICES < <(adb devices | awk '/\tdevice$/{print $1}')
fi

if [[ ${#DEVICES[@]} -eq 0 ]]; then
  echo "ERROR: No devices connected. Check USB debugging is enabled." >&2
  exit 1
fi

echo "Target devices: ${DEVICES[*]}"
echo ""

FAILED=0
for SERIAL in "${DEVICES[@]}"; do
  echo "Installing on $SERIAL..."
  if adb -s "$SERIAL" install -r "$APK"; then
    echo "  OK: $SERIAL"
  else
    echo "  FAILED: $SERIAL" >&2
    FAILED=1
  fi
done

[[ $FAILED -eq 0 ]] && echo "" && echo "All installs succeeded."
exit $FAILED
