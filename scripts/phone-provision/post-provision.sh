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

# ── 1. VPN Lockdown ──────────────────────────
echo ""
echo "[1/6] Configuring VPN always-on + kill-switch..."
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

# ── 2. Termux Storage Permission ─────────────
echo ""
echo "[2/6] Granting Termux storage permissions..."
adb shell pm grant com.termux android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
adb shell pm grant com.termux android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true
adb shell appops set --uid com.termux MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true
echo "  Termux storage: GRANTED"

# ── 3. Run Termux SSH Setup ──────────────────
echo ""
echo "[3/6] Running Termux SSH setup..."
# Copy script into Termux-accessible location and execute
# Launch Termux first to initialize its environment
adb shell am start -n com.termux/.HomeActivity 2>/dev/null
sleep 5

# Type the setup command into Termux
adb shell input text 'cp'
adb shell input keyevent 62  # space
adb shell input text '/sdcard/termux_setup.sh'
adb shell input keyevent 62
adb shell input text '~/setup.sh'
adb shell input keyevent 62
adb shell input text '&&'
adb shell input keyevent 62
adb shell input text 'bash'
adb shell input keyevent 62
adb shell input text '~/setup.sh'
adb shell input keyevent 66  # Enter

echo "  Termux SSH setup: LAUNCHED (runs in background, ~60s)"
sleep 30

# ── 4. HMDM Enrollment ──────────────────────
echo ""
echo "[4/6] Enrolling in Headwind MDM..."
HMDM_SERVER="http://100.64.0.1:8095"

# Check VPN routing first
if adb shell "ping -c 1 -W 3 100.64.0.1" &>/dev/null; then
  echo "  VPN routing: OK"
else
  echo "  WARNING: Cannot reach 100.64.0.1 — VPN may not be routing yet"
  echo "  Attempting HMDM enrollment anyway..."
fi

# Launch HMDM
adb shell am start -n com.hmdm.launcher/.ui.MainActivity 2>/dev/null
sleep 3

# Dump UI to find the server URL field
adb shell uiautomator dump /sdcard/ui.xml 2>/dev/null
HMDM_SCREEN=$(adb shell cat /sdcard/ui.xml 2>/dev/null | grep -c "Server" || true)

if [ "$HMDM_SCREEN" -gt 0 ]; then
  # Find and tap the server URL field, type the URL
  # HMDM enrollment screen has Server URL field then Device ID field
  adb shell input tap 540 800 2>/dev/null  # Approximate server URL field
  sleep 1
  adb shell input text "$HMDM_SERVER"
  adb shell input keyevent 61  # Tab to next field
  sleep 1
  adb shell input text "$DEVICE_ID"
  adb shell input keyevent 66  # Enter/Submit
  echo "  HMDM enrollment: SUBMITTED"
  sleep 5
else
  echo "  WARNING: HMDM enrollment screen not detected"
  echo "  Manual enrollment needed: Server=$HMDM_SERVER Device=$DEVICE_ID"
fi

# ── 5. Verify Connectivity ───────────────────
echo ""
echo "[5/6] Verifying connectivity..."

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

# ── 6. Disable USB Debugging ─────────────────
echo ""
echo "[6/6] Post-provisioning security..."
echo "  NOTE: USB debugging left ON for now"
echo "  Disable manually when provisioning is complete:"
echo "  Settings → Developer Options → USB Debugging → OFF"

echo ""
echo "========================================="
echo "POST-PROVISIONING COMPLETE for $DEVICE_ID"
echo "========================================="
echo ""
echo "REMAINING MANUAL STEPS:"
echo ""
echo "1. TAILSCALE ENROLLMENT (if not done):"
echo "   - Phone must be on cellular/hotspot (NOT home WiFi)"
echo "   - Open Tailscale → three dots → 'Use alternate server'"
echo "   - Enter: https://vpn.thousand-pikes.com"
echo "   - Tap 'Log in with auth key'"
echo "   - Key will be typed via: adb shell input text 'hskey-auth-...'"
echo ""
echo "2. ATAK FIRST LAUNCH:"
echo "   - Open ATAK → set encryption passphrase"
echo "   - Verify callsign = $DEVICE_ID, team = Cyan"
echo "   - Verify server: CrypTAK-Home (100.64.0.1:8087)"
echo ""
echo "3. USB DEBUGGING → OFF"
