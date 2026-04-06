#!/bin/bash
# CrypTAK Post-Provisioning Automation
# Runs AFTER provision-phone.sh and AFTER Tailscale enrollment.
#
# This script handles:
#   1. Termux SSH setup (needs WiFi internet, before VPN lockdown)
#   2. VPN always-on + kill-switch
#   3. HMDM enrollment (pushes all mission apps: ATAK, Meshtastic, etc.)
#   4. Connectivity verification
#
# Usage: ./post-provision.sh <CALLSIGN>
# Example: ./post-provision.sh TAK-03
#
# Prerequisites:
#   - provision-phone.sh already run (Tailscale + HMDM installed)
#   - Tailscale enrolled and connected (phone is on VPN)
#   - Phone connected via USB with ADB debugging on
#   - Phone on home WiFi (NOT hotspot — Termux mirrors need direct internet)
set -euo pipefail

DEVICE_ID="${1:-}"

if [ -z "$DEVICE_ID" ]; then
  echo "Usage: $0 <CALLSIGN>"
  exit 1
fi

if [[ ! "$DEVICE_ID" =~ ^TAK-[0-9]+$ ]]; then
  echo "ERROR: Callsign must match TAK-NN format"
  exit 1
fi

# Check ADB connection
if ! adb devices | grep -q "device$"; then
  echo "ERROR: No device found via ADB"
  exit 1
fi

# Helper: find a UI element by text and tap it
tap_element() {
  local search_text="$1"
  local retries="${2:-3}"
  local attempt=0

  while [ $attempt -lt $retries ]; do
    adb shell uiautomator dump /sdcard/ui.xml 2>/dev/null
    local bounds
    bounds=$(adb shell cat /sdcard/ui.xml 2>/dev/null | python3 -c "
import sys, xml.etree.ElementTree as ET
tree = ET.parse(sys.stdin)
search = '$search_text'.lower()
all_nodes = list(tree.iter('node'))
parent_map = {c: p for p in tree.iter() for c in p}
for node in all_nodes:
    text = (node.get('text', '') or '').lower()
    desc = (node.get('content-desc', '') or '').lower()
    if search in text or search in desc:
        current = node
        while current is not None:
            if current.get('clickable', '') == 'true':
                print(current.get('bounds', ''))
                sys.exit(0)
            current = parent_map.get(current)
        print(node.get('bounds', ''))
        sys.exit(0)
" 2>/dev/null)

    if [ -n "$bounds" ]; then
      local coords
      coords=$(python3 -c "
import re
m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', '$bounds')
if m:
    x1,y1,x2,y2 = map(int, m.groups())
    print(f'{(x1+x2)//2} {(y1+y2)//2}')
" 2>/dev/null)
      if [ -n "$coords" ]; then
        adb shell input tap $coords
        return 0
      fi
    fi

    ((attempt++))
    sleep 2
  done

  return 1
}

# Helper: find an input field (EditText/AutoCompleteTextView) and return center coords
find_input_field() {
  adb shell uiautomator dump /sdcard/ui.xml 2>/dev/null
  adb shell cat /sdcard/ui.xml 2>/dev/null | python3 -c "
import sys, xml.etree.ElementTree as ET, re
tree = ET.parse(sys.stdin)
for node in tree.iter('node'):
    cls = node.get('class', '')
    if 'EditText' in cls or 'AutoCompleteTextView' in cls:
        bounds = node.get('bounds', '')
        m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', bounds)
        if m:
            x1,y1,x2,y2 = map(int, m.groups())
            print(f'{(x1+x2)//2} {(y1+y2)//2}')
            break
" 2>/dev/null
}

echo "========================================="
echo "CrypTAK Post-Provisioning: $DEVICE_ID"
echo "========================================="

# Keep screen awake during provisioning
adb shell settings put system screen_off_timeout 600000 2>/dev/null
adb shell svc power stayon true 2>/dev/null

# ── 1. Termux SSH Setup (BEFORE VPN lockdown — needs mirror access) ──
echo ""
echo "[1/5] Running Termux SSH setup..."
echo "  (Must run before VPN lockdown — Termux mirrors need direct internet)"

# Grant storage permissions
adb shell pm grant com.termux android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
adb shell pm grant com.termux android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true
adb shell appops set --uid com.termux MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true

# Launch Termux fresh (first launch triggers bootstrap ~5s)
adb shell am force-stop com.termux 2>/dev/null
sleep 1
adb shell am start -n com.termux/.HomeActivity 2>/dev/null
sleep 8

# Install packages (type directly — most reliable for Termux)
echo "  Installing openssh + termux-services..."
adb shell input text 'pkg'
adb shell input keyevent 62
adb shell input text 'install'
adb shell input keyevent 62
adb shell input text '-y'
adb shell input keyevent 62
adb shell input text 'openssh'
adb shell input keyevent 62
adb shell input text 'termux-services'
adb shell input keyevent 66
sleep 90  # pkg install takes ~60-90s

# Copy and run setup script using $HOME (~ expands to / via ADB input)
echo "  Running SSH setup script..."
adb shell input text 'cp'
adb shell input keyevent 62
adb shell input text '/sdcard/termux_setup.sh'
adb shell input keyevent 62
adb shell input text '\$HOME/setup.sh'
adb shell input keyevent 66
sleep 3
adb shell input text 'bash'
adb shell input keyevent 62
adb shell input text '\$HOME/setup.sh'
adb shell input keyevent 66
sleep 15

echo "  Termux SSH setup: DONE"

# ── 2. VPN Lockdown ─────────────────────────────
echo ""
echo "[2/5] Configuring VPN always-on + kill-switch..."
adb shell settings put secure always_on_vpn_app com.tailscale.ipn
adb shell settings put secure always_on_vpn_lockdown 1

# Verify
VPN_APP=$(adb shell settings get secure always_on_vpn_app 2>/dev/null | tr -d '\r')
VPN_LOCK=$(adb shell settings get secure always_on_vpn_lockdown 2>/dev/null | tr -d '\r')
if [ "$VPN_APP" = "com.tailscale.ipn" ] && [ "$VPN_LOCK" = "1" ]; then
  echo "  VPN lockdown: ENABLED"
else
  echo "  WARNING: VPN lockdown may not have applied (app=$VPN_APP lock=$VPN_LOCK)"
fi

# ── 3. HMDM Enrollment ──────────────────────────
echo ""
echo "[3/5] Enrolling in Headwind MDM..."
HMDM_SERVER="http://100.64.0.1:8095"

# Check VPN routing first
if adb shell "ping -c 1 -W 3 100.64.0.1" &>/dev/null; then
  echo "  VPN routing: OK"
else
  echo "  WARNING: Cannot reach 100.64.0.1 — VPN may not be routing yet"
  echo "  HMDM enrollment requires VPN. Verify Tailscale is connected."
fi

# Pre-grant ALL permissions so HMDM skips permission dialogs entirely
echo "  Pre-granting permissions..."
adb shell pm grant com.hmdm.launcher android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
adb shell pm grant com.hmdm.launcher android.permission.ACCESS_COARSE_LOCATION 2>/dev/null || true
adb shell pm grant com.hmdm.launcher android.permission.ACCESS_BACKGROUND_LOCATION 2>/dev/null || true
adb shell pm grant com.hmdm.launcher android.permission.READ_PHONE_STATE 2>/dev/null || true
adb shell pm grant com.hmdm.launcher android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
adb shell pm grant com.hmdm.launcher android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true
adb shell pm grant com.hmdm.launcher android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell appops set --uid com.hmdm.launcher MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true
adb shell appops set com.hmdm.launcher REQUEST_INSTALL_PACKAGES allow 2>/dev/null || true

# Set HMDM as device owner (enables silent APK installs)
echo "  Setting device owner..."
if adb shell dpm set-device-owner com.hmdm.launcher/.AdminReceiver 2>&1 | grep -q "Success\|already"; then
  echo "  Device owner: SET"
else
  echo "  WARNING: Could not set device owner (may need account removal first)"
  echo "  HMDM will still work but will prompt for each app install"
fi

# Launch HMDM
adb shell am start -n com.hmdm.launcher/.ui.MainActivity 2>/dev/null
sleep 5

# Handle any remaining permission dialogs (location, etc.)
for i in 1 2 3; do
  adb shell uiautomator dump /sdcard/ui.xml 2>/dev/null
  HAS_ALLOW=$(adb shell cat /sdcard/ui.xml 2>/dev/null | grep -c "While using\|ALLOW\|Allow" || true)
  if [ "$HAS_ALLOW" -gt 0 ]; then
    tap_element "While using the app" 1 2>/dev/null || tap_element "Allow" 1 2>/dev/null || true
    sleep 2
  else
    break
  fi
done

# Handle "permissions required" retry screen
adb shell uiautomator dump /sdcard/ui.xml 2>/dev/null
HAS_RETRY=$(adb shell cat /sdcard/ui.xml 2>/dev/null | grep -c "RETRY" || true)
if [ "$HAS_RETRY" -gt 0 ]; then
  tap_element "RETRY" 1 2>/dev/null || true
  sleep 3
fi

# ── HMDM enrollment form: Step 1 — Server URL ──
echo "  Entering server URL..."
adb shell uiautomator dump /sdcard/ui.xml 2>/dev/null
HAS_SERVER=$(adb shell cat /sdcard/ui.xml 2>/dev/null | grep -c "server URL\|Server URL\|server url" || true)

if [ "$HAS_SERVER" -gt 0 ]; then
  FIELD=$(find_input_field)
  if [ -n "$FIELD" ]; then
    adb shell input tap $FIELD
    sleep 0.5
    # Clear default text (move to end, backspace all)
    adb shell input keyevent 123  # MOVE_END
    for i in $(seq 1 40); do adb shell input keyevent 67; done  # backspace
    sleep 0.3
    adb shell input text "$HMDM_SERVER"
    sleep 1
    tap_element "OK" 2 2>/dev/null || adb shell input keyevent 66
    sleep 3
    echo "  Server URL: SET"
  fi
fi

# ── HMDM enrollment form: Step 2 — Device ID ──
echo "  Entering device ID..."
adb shell uiautomator dump /sdcard/ui.xml 2>/dev/null
HAS_DEVICE=$(adb shell cat /sdcard/ui.xml 2>/dev/null | grep -c "device ID\|Device ID\|device id" || true)

if [ "$HAS_DEVICE" -gt 0 ]; then
  FIELD=$(find_input_field)
  if [ -n "$FIELD" ]; then
    adb shell input tap $FIELD
    sleep 0.5
    adb shell input text "$DEVICE_ID"
    sleep 1
    tap_element "SAVE" 2 2>/dev/null || adb shell input keyevent 66
    sleep 5
    echo "  Device ID: SET"
  fi
fi

# Wait for HMDM to connect and start installing apps
echo "  Waiting for HMDM to pull configuration..."
sleep 10

# Handle install dialogs (Package installer chooser + Install prompts)
# With device owner, installs are silent. Without, we need to tap through.
for i in $(seq 1 10); do
  adb shell uiautomator dump /sdcard/ui.xml 2>/dev/null
  UI_TEXT=$(adb shell cat /sdcard/ui.xml 2>/dev/null)

  # Check for "Open with" chooser (Package installer vs Termux)
  HAS_CHOOSER=$(echo "$UI_TEXT" | grep -c "Package installer" || true)
  if [ "$HAS_CHOOSER" -gt 0 ]; then
    tap_element "Package installer" 1 2>/dev/null || true
    sleep 1
    tap_element "Always" 1 2>/dev/null || tap_element "Just once" 1 2>/dev/null || true
    sleep 2
    continue
  fi

  # Check for "Install this app?" prompt
  HAS_INSTALL=$(echo "$UI_TEXT" | grep -c "Install this app\|Install" || true)
  if [ "$HAS_INSTALL" -gt 0 ]; then
    tap_element "Install" 1 2>/dev/null || true
    sleep 5
    # Tap "Done" after install completes
    tap_element "Done" 1 2>/dev/null || true
    sleep 2
    continue
  fi

  # Check for HMDM launcher (enrollment complete)
  HAS_LAUNCHER=$(echo "$UI_TEXT" | grep -c "Tailscale\|ATAK\|Meshtastic" || true)
  if [ "$HAS_LAUNCHER" -gt 0 ]; then
    echo "  HMDM enrollment: SUCCESS"
    break
  fi

  # Check for error
  HAS_ERROR=$(echo "$UI_TEXT" | grep -c "Error connecting" || true)
  if [ "$HAS_ERROR" -gt 0 ]; then
    echo "  WARNING: HMDM connection error — retrying..."
    tap_element "RETRY" 1 2>/dev/null || true
    sleep 5
    continue
  fi

  sleep 5
done

# Verify installed apps
echo "  Verifying app installs..."
MISSING_APPS=0
for PKG in com.atakmap.app.civ com.tailscale.ipn com.geeksville.mesh com.termux com.hmdm.launcher; do
  if adb shell pm list packages "$PKG" 2>/dev/null | grep -q "package:" ; then
    echo "    $PKG: OK"
  else
    echo "    $PKG: MISSING (HMDM may still be installing)"
    MISSING_APPS=1
  fi
done
if [ "$MISSING_APPS" -eq 1 ]; then
  echo "  NOTE: Some apps may still be downloading. Check HMDM launcher in 1-2 minutes."
fi

# ── 4. Verify Connectivity ───────────────────────
echo ""
echo "[4/5] Verifying connectivity..."

if adb shell "ping -c 1 -W 3 100.64.0.1" &>/dev/null; then
  echo "  VPN → Unraid (100.64.0.1): OK"
else
  echo "  VPN → Unraid (100.64.0.1): FAIL"
fi

if adb shell "echo | nc -w 2 100.64.0.1 8087" &>/dev/null; then
  echo "  FTS (100.64.0.1:8087): REACHABLE"
else
  echo "  FTS (100.64.0.1:8087): NOT REACHABLE (nc may not be available)"
fi

# ── 5. Cleanup ───────────────────────────────────
echo ""
echo "[5/5] Cleanup..."

# Restore screen timeout
adb shell svc power stayon false 2>/dev/null
adb shell settings put system screen_off_timeout 120000 2>/dev/null

# Return phone to home screen
adb shell input keyevent 3  # HOME

echo ""
echo "========================================="
echo "POST-PROVISIONING COMPLETE for $DEVICE_ID"
echo "========================================="
echo ""
echo "REMAINING MANUAL STEP:"
echo ""
echo "1. ATAK FIRST LAUNCH:"
echo "   - Open ATAK → set encryption passphrase"
echo "   - Verify callsign = $DEVICE_ID, team = Cyan"
echo "   - Verify server: CrypTAK-Home (100.64.0.1:8087)"
echo ""
echo "2. USB DEBUGGING → OFF"
echo "   Settings → Developer Options → USB Debugging → OFF"
