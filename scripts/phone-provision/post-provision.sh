#!/bin/bash
# CrypTAK Post-Provisioning Automation
# Runs AFTER provision-phone.sh and AFTER Tailscale enrollment.
# Automates everything that doesn't require on-screen interaction.
#
# Usage: ./post-provision.sh <CALLSIGN>
# Example: ./post-provision.sh TAK-03
#
# Prerequisites:
#   - provision-phone.sh already run (apps installed)
#   - Tailscale enrolled and connected (phone is on VPN)
#   - Phone connected via USB with ADB debugging on
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

echo "========================================="
echo "CrypTAK Post-Provisioning: $DEVICE_ID"
echo "========================================="

# ── 1. Termux SSH Setup (BEFORE VPN lockdown — needs mirror access) ──
echo ""
echo "[1/6] Running Termux SSH setup..."
echo "  (Must run before VPN lockdown — Termux mirrors need direct internet)"
echo "  (Phone must be on WiFi with internet — NOT hotspot, NOT VPN-locked)"

# Grant storage permissions
adb shell pm grant com.termux android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
adb shell pm grant com.termux android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true
adb shell appops set --uid com.termux MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true

# Keep screen awake during provisioning
adb shell settings put system screen_off_timeout 600000 2>/dev/null

# Launch Termux fresh (first launch triggers bootstrap ~5s)
adb shell am force-stop com.termux 2>/dev/null
sleep 1
adb shell am start -n com.termux/.HomeActivity 2>/dev/null
sleep 8

# Step 1a: Install packages (type directly — most reliable)
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

# Step 1b: Copy and run setup script using $HOME (~ doesn't expand via ADB input)
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

# ── 2. VPN Lockdown (after Termux setup) ─────
echo ""
echo "[2/6] Configuring VPN always-on + kill-switch..."
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

# (Termux storage permissions handled in step 1 above)

# (Termux SSH setup handled in step 1 above)

# ── 3. HMDM Enrollment ──────────────────────
echo ""
echo "[3/6] Enrolling in Headwind MDM..."
HMDM_SERVER="http://100.64.0.1:8095"

# Check VPN routing first
if adb shell "ping -c 1 -W 3 100.64.0.1" &>/dev/null; then
  echo "  VPN routing: OK"
else
  echo "  WARNING: Cannot reach 100.64.0.1 — VPN may not be routing yet"
  echo "  Attempting HMDM enrollment anyway..."
fi

# Launch HMDM and handle permission dialogs
adb shell am start -n com.hmdm.launcher/.ui.MainActivity 2>/dev/null
sleep 5

# HMDM may show permission dialogs first — grant them
for i in 1 2 3 4 5; do
  adb shell uiautomator dump /sdcard/ui.xml 2>/dev/null
  HAS_ALLOW=$(adb shell cat /sdcard/ui.xml 2>/dev/null | grep -c "Allow\|ALLOW\|While using" || true)
  if [ "$HAS_ALLOW" -gt 0 ]; then
    # Tap "Allow" or "While using the app"
    ALLOW_BOUNDS=$(adb shell cat /sdcard/ui.xml 2>/dev/null | python3 -c "
import sys, xml.etree.ElementTree as ET
tree = ET.parse(sys.stdin)
for node in tree.iter('node'):
    text = (node.get('text','') or '').lower()
    if 'allow' in text or 'while using' in text:
        if node.get('clickable','') == 'true':
            print(node.get('bounds',''))
            break
" 2>/dev/null)
    if [ -n "$ALLOW_BOUNDS" ]; then
      COORDS=$(python3 -c "
import re
m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', '$ALLOW_BOUNDS')
if m:
    x1,y1,x2,y2 = map(int, m.groups())
    print(f'{(x1+x2)//2} {(y1+y2)//2}')
" 2>/dev/null)
      [ -n "$COORDS" ] && adb shell input tap $COORDS
      sleep 2
    fi
  else
    break
  fi
done

# Now check if we're on the HMDM enrollment screen
adb shell uiautomator dump /sdcard/ui.xml 2>/dev/null
HMDM_HAS_SERVER=$(adb shell cat /sdcard/ui.xml 2>/dev/null | grep -ci "server\|hmdm\|device" || true)

if [ "$HMDM_HAS_SERVER" -gt 0 ]; then
  # Find EditText fields and fill them
  EDIT_FIELDS=$(adb shell cat /sdcard/ui.xml 2>/dev/null | python3 -c "
import sys, xml.etree.ElementTree as ET
tree = ET.parse(sys.stdin)
import re
fields = []
for node in tree.iter('node'):
    cls = node.get('class', '')
    if 'EditText' in cls:
        bounds = node.get('bounds', '')
        m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', bounds)
        if m:
            x1,y1,x2,y2 = map(int, m.groups())
            fields.append(f'{(x1+x2)//2} {(y1+y2)//2}')
for f in fields:
    print(f)
" 2>/dev/null)

  FIELD1=$(echo "$EDIT_FIELDS" | head -1)
  FIELD2=$(echo "$EDIT_FIELDS" | tail -1)

  if [ -n "$FIELD1" ] && [ -n "$FIELD2" ]; then
    # Tap first field (Server URL), type URL
    adb shell input tap $FIELD1
    sleep 1
    adb shell input text "$HMDM_SERVER"
    sleep 1

    # Tap second field (Device ID), type callsign
    adb shell input tap $FIELD2
    sleep 1
    adb shell input text "$DEVICE_ID"
    sleep 1

    # Submit — look for a button
    adb shell input keyevent 66  # Enter
    sleep 5
    echo "  HMDM enrollment: SUBMITTED"
  else
    echo "  WARNING: Could not find HMDM input fields"
    echo "  Manual enrollment needed: Server=$HMDM_SERVER Device=$DEVICE_ID"
  fi
else
  echo "  HMDM enrollment screen not detected (may already be enrolled)"
fi

# ── 4. Verify Connectivity ───────────────────
echo ""
echo "[4/6] Verifying connectivity..."

# Check VPN
if adb shell "ping -c 1 -W 3 100.64.0.1" &>/dev/null; then
  echo "  VPN → Unraid (100.64.0.1): OK"
else
  echo "  VPN → Unraid (100.64.0.1): FAIL"
fi

# Check FTS port
if adb shell "echo | nc -w 2 100.64.0.1 8087" &>/dev/null; then
  echo "  FTS (100.64.0.1:8087): REACHABLE"
else
  echo "  FTS (100.64.0.1:8087): NOT REACHABLE (nc may not be available)"
fi

# ── 5. Cleanup ───────────────────────────────
echo ""
echo "[5/6] Post-provisioning security..."
echo "  NOTE: USB debugging left ON for now"
echo "  Disable manually when provisioning is complete:"
echo "  Settings → Developer Options → USB Debugging → OFF"

# Return phone to home screen
adb shell input keyevent 3  # HOME
echo "  Phone returned to home screen"

echo ""
echo "========================================="
echo "POST-PROVISIONING COMPLETE for $DEVICE_ID"
echo "========================================="
echo ""
echo "REMAINING MANUAL STEPS:"
echo ""
echo "1. ATAK FIRST LAUNCH:"
echo "   - Open ATAK → set encryption passphrase"
echo "   - Verify callsign = $DEVICE_ID, team = Cyan"
echo "   - Verify server: CrypTAK-Home (100.64.0.1:8087)"
echo ""
echo "2. USB DEBUGGING → OFF"
