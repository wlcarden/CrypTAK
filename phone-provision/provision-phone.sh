#!/bin/bash
# CrypTAK Unified Phone Provisioning Script
#
# Takes a fresh GrapheneOS phone from zero to fully provisioned CrypTAK device.
# Merges the previous provision-phone.sh, enroll-tailscale.sh, and post-provision.sh
# into a single automated flow.
#
# Usage: ./provision-phone.sh <CALLSIGN>
# Example: ./provision-phone.sh TAK-03
#
# Prerequisites:
#   - Phone running GrapheneOS with USB debugging ON
#   - USB-C cable connected
#   - Phone on home WiFi (split DNS enables Tailscale enrollment from LAN)
#   - Desktop has SSH access to Unraid (ssh unraid)
#   - APKs: Tailscale.apk, HeadwindMDM.apk in apks/
set -eo pipefail

# ── Configuration ─────────────────────────────────
HEADSCALE_URL="https://vpn.thousand-pikes.com"
HMDM_SERVER="http://100.64.0.1:8095"
FTS_PRIMARY="100.64.0.1:8087"
FTS_SECONDARY="100.64.0.2:8087"
UNRAID_SSH="unraid"
SCREEN_TIMEOUT_PROVISION=600000
SCREEN_TIMEOUT_DEFAULT=120000
DESKTOP_PUBKEY="ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHzLTikGXhZQfdkyXelIvALwZCugO7PH0xaQbh8D9Kig wlcarden@gmail.com"

DEVICE_ID="${1:-}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK_DIR="$SCRIPT_DIR/apks"

# State tracking for cleanup trap
PRIVATE_DNS_DISABLED=0
SCREEN_MODIFIED=0
USB_SERIAL=""
STEP_COMPLETED=0
ASSIGNED_IP=""
NODE_ID=""

# ── Argument Validation ───────────────────────────
if [ -z "$DEVICE_ID" ]; then
  echo "Usage: $0 <CALLSIGN>"
  echo "Example: $0 TAK-03"
  echo ""
  echo "Everything else is automatic (pre-auth key, enrollment, HMDM, lockdown)."
  exit 1
fi

if [[ ! "$DEVICE_ID" =~ ^TAK-[0-9]+$ ]]; then
  echo "ERROR: Callsign must match TAK-NN format (e.g. TAK-03)"
  exit 1
fi

CALLSIGN_LOWER=$(echo "$DEVICE_ID" | tr '[:upper:]' '[:lower:]')

# ── Cleanup Trap ──────────────────────────────────
cleanup() {
  local exit_code=$?
  echo ""
  if [ $exit_code -ne 0 ]; then
    echo "========================================="
    echo "PROVISIONING FAILED at step $STEP_COMPLETED"
    echo "========================================="
  fi

  # Restore Private DNS if we disabled it
  if [ "$PRIVATE_DNS_DISABLED" -eq 1 ] && [ -n "$USB_SERIAL" ]; then
    echo "Restoring Private DNS..."
    adb -s "$USB_SERIAL" shell "settings put global private_dns_mode opportunistic" 2>/dev/null || true
  fi

  # Restore screen settings
  if [ "$SCREEN_MODIFIED" -eq 1 ] && [ -n "$USB_SERIAL" ]; then
    adb -s "$USB_SERIAL" shell svc power stayon false 2>/dev/null || true
    adb -s "$USB_SERIAL" shell settings put system screen_off_timeout "$SCREEN_TIMEOUT_DEFAULT" 2>/dev/null || true
  fi

  if [ $exit_code -ne 0 ]; then
    echo ""
    echo "To retry, re-run: $0 $DEVICE_ID"
    echo "All steps are idempotent — safe to re-run from scratch."
  fi
}
trap cleanup EXIT

# ── ADB Wrapper ───────────────────────────────────
# After adb tcpip, two connections exist. This wrapper targets USB.
adb_cmd() {
  if [ -n "$USB_SERIAL" ]; then
    adb -s "$USB_SERIAL" "$@"
  else
    adb "$@"
  fi
}

# ── Helper: tap a UI element by text ──────────────
# Dumps uiautomator, finds element by text/content-desc, walks up to
# nearest clickable ancestor, taps center of bounds.
tap_element() {
  local search_text="$1"
  local retries="${2:-3}"
  local attempt=0

  while [ $attempt -lt $retries ]; do
    adb_cmd shell uiautomator dump /sdcard/ui.xml 2>/dev/null
    local bounds
    bounds=$(adb_cmd shell cat /sdcard/ui.xml 2>/dev/null | python3 -c "
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
        adb_cmd shell input tap $coords
        return 0
      fi
    fi

    ((attempt++))
    sleep 2
  done

  echo "  WARNING: Could not find element '$search_text' after $retries attempts"
  return 1
}

# ── Helper: find first EditText and return center coords ──
find_input_field() {
  adb_cmd shell uiautomator dump /sdcard/ui.xml 2>/dev/null
  adb_cmd shell cat /sdcard/ui.xml 2>/dev/null | python3 -c "
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

# ── Helper: wait for UI text to appear ────────────
wait_for_ui() {
  local search_text="$1"
  local timeout="${2:-30}"
  local elapsed=0
  while [ $elapsed -lt $timeout ]; do
    adb_cmd shell uiautomator dump /sdcard/ui.xml 2>/dev/null
    if adb_cmd shell cat /sdcard/ui.xml 2>/dev/null | grep -qi "$search_text"; then
      return 0
    fi
    sleep 2
    ((elapsed+=2))
  done
  return 1
}

echo "========================================="
echo "CrypTAK Unified Provisioning: $DEVICE_ID"
echo "========================================="

# ── Pre-flight Checks ─────────────────────────────
echo ""
echo "--- Pre-flight checks ---"

# Check ADB connection
if ! adb devices | grep -q "device$"; then
  echo "ERROR: No device found via ADB. Enable USB debugging and connect USB-C."
  exit 1
fi
USB_SERIAL=$(adb devices | grep -w "device" | head -1 | awk '{print $1}')
DEVICE_MODEL=$(adb_cmd shell getprop ro.product.model 2>/dev/null | tr -d '\r')
echo "  Device: $DEVICE_MODEL (serial: $USB_SERIAL)"

# Check APK inventory
MISSING=0
for apk in Tailscale.apk HeadwindMDM.apk; do
  if [ ! -f "$APK_DIR/$apk" ]; then
    echo "  MISSING APK: $APK_DIR/$apk"
    MISSING=1
  fi
done
[ "$MISSING" -eq 0 ] || { echo "Download missing APKs first."; exit 1; }
echo "  APKs: OK"

# Check desktop tools
for tool in python3 ssh; do
  if ! command -v "$tool" &>/dev/null; then
    echo "  MISSING tool: $tool"
    exit 1
  fi
done
echo "  Desktop tools: OK"

# Check SSH to Unraid
if ! ssh -o ConnectTimeout=5 "$UNRAID_SSH" "echo ok" &>/dev/null; then
  echo "  ERROR: Cannot SSH to Unraid ($UNRAID_SSH). Fix SSH before proceeding."
  exit 1
fi
echo "  SSH to Unraid: OK"

# Clear stale ATAK databases
if adb_cmd shell "ls /sdcard/atak/Databases/" &>/dev/null; then
  echo "  Clearing stale ATAK database (avoids passphrase lockout)"
  adb_cmd shell "rm -rf /sdcard/atak/Databases" 2>/dev/null
fi

# Uninstall conflicting ATAK packages
INSTALLED_ATAK=$(adb_cmd shell pm list packages com.atakmap.app.civ 2>/dev/null | grep -c "package:" || true)
if [ "$INSTALLED_ATAK" -gt 0 ]; then
  echo "  Removing existing ATAK (avoids signature conflicts)"
  adb_cmd shell pm list packages 2>/dev/null | grep "com.atakmap" | sed 's/package://' | while read -r pkg; do
    adb_cmd shell pm uninstall "$pkg" 2>/dev/null && echo "    Removed: $pkg" || true
  done
fi

# Clear Google accounts (device owner requires none)
ACCOUNTS=$(adb_cmd shell dumpsys account 2>/dev/null | grep -c "Account {" || true)
if [ "$ACCOUNTS" -gt 0 ]; then
  echo "  WARNING: $ACCOUNTS account(s) found — clearing for device owner"
  adb_cmd shell pm clear com.google.android.gms 2>/dev/null || true
  sleep 2
fi

# Keep screen awake during provisioning
adb_cmd shell settings put system screen_off_timeout "$SCREEN_TIMEOUT_PROVISION" 2>/dev/null
adb_cmd shell svc power stayon true 2>/dev/null
SCREEN_MODIFIED=1

echo "  Pre-flight: PASSED"

# ── Step 1: Generate Pre-Auth Key ─────────────────
echo ""
echo "[1/9] Generating Headscale pre-auth key..."

PREAUTH_KEY=$(ssh "$UNRAID_SSH" "docker exec headscale headscale preauthkeys create --user 1 --expiration 72h" 2>/dev/null | tr -d '\r')
if [[ ! "$PREAUTH_KEY" =~ ^hskey-auth- ]]; then
  echo "ERROR: Failed to generate pre-auth key. Got: $PREAUTH_KEY"
  echo "Generate manually:"
  echo "  ssh unraid \"docker exec headscale headscale preauthkeys create --user 1 --expiration 72h\""
  exit 1
fi
echo "  Key: ${PREAUTH_KEY:0:30}..."
STEP_COMPLETED=1

# ── Step 2: Install Bootstrap APKs ────────────────
echo ""
echo "[2/9] Installing bootstrap APKs..."

# Only install if not already present (idempotent)
if ! adb_cmd shell pm list packages com.tailscale.ipn 2>/dev/null | grep -q "package:"; then
  adb_cmd install -r "$APK_DIR/Tailscale.apk" || { echo "ERROR: Tailscale install failed"; exit 1; }
  echo "  Tailscale: installed"
else
  echo "  Tailscale: already installed"
fi

if ! adb_cmd shell pm list packages com.hmdm.launcher 2>/dev/null | grep -q "package:"; then
  adb_cmd install -r "$APK_DIR/HeadwindMDM.apk" || { echo "ERROR: HMDM install failed"; exit 1; }
  echo "  HeadwindMDM: installed"
else
  echo "  HeadwindMDM: already installed"
fi
STEP_COMPLETED=2

# ── Step 3: Push Pre-Staged Data ──────────────────
echo ""
echo "[3/9] Pushing ATAK configuration and maps..."

# ATAK server preferences
adb_cmd shell mkdir -p /sdcard/atak/tools 2>/dev/null || true
cat > /tmp/atak_servers.pref << PREFEOF
<?xml version='1.0' standalone='yes'?>
<preferences>
  <preference version="1" name="cot_streams">
    <entry key="count" class="class java.lang.Integer">2</entry>
    <entry key="description0" class="class java.lang.String">CrypTAK-Home</entry>
    <entry key="enabled0" class="class java.lang.Boolean">true</entry>
    <entry key="connectString0" class="class java.lang.String">${FTS_PRIMARY}:tcp</entry>
    <entry key="description1" class="class java.lang.String">CrypTAK-Field</entry>
    <entry key="enabled1" class="class java.lang.Boolean">false</entry>
    <entry key="connectString1" class="class java.lang.String">${FTS_SECONDARY}:tcp</entry>
  </preference>
  <preference version="1" name="com.atakmap.app_preferences">
    <entry key="locationCallsign" class="class java.lang.String">${DEVICE_ID}</entry>
    <entry key="locationTeam" class="class java.lang.String">Cyan</entry>
    <entry key="atakRoleType" class="class java.lang.String">Team Member</entry>
    <entry key="locationReportingStrategy" class="class java.lang.String">Dynamic</entry>
    <entry key="minReportingIntervalSecs" class="class java.lang.Integer">30</entry>
    <entry key="staleRemoteCallsignMonitoringInterval" class="class java.lang.Integer">90</entry>
  </preference>
</preferences>
PREFEOF
adb_cmd push /tmp/atak_servers.pref /sdcard/atak/servers.pref >/dev/null
rm /tmp/atak_servers.pref
echo "  ATAK server config: pushed (callsign=$DEVICE_ID, team=Cyan)"

# ATAK offline map sources
adb_cmd shell mkdir -p /sdcard/atak/imagery 2>/dev/null || true
MAP_COUNT=0
for XML in "$SCRIPT_DIR/atak-maps/"*.xml; do
  [ -f "$XML" ] || continue
  adb_cmd push "$XML" "/sdcard/atak/imagery/$(basename "$XML")" >/dev/null 2>&1 && ((MAP_COUNT++)) || true
done
echo "  Map sources: $MAP_COUNT XML files pushed"

if [ -f "$SCRIPT_DIR/atak-maps/nova-streets.sqlite" ]; then
  adb_cmd push "$SCRIPT_DIR/atak-maps/nova-streets.sqlite" "/sdcard/atak/imagery/nova-streets.sqlite" >/dev/null
  echo "  Offline tiles: nova-streets.sqlite (250MB)"
else
  echo "  WARNING: nova-streets.sqlite not found — no offline map cache"
fi

# HMDM wallpaper (non-fatal)
if [ -f "$SCRIPT_DIR/cryptak-wallpaper.png" ]; then
  scp "$SCRIPT_DIR/cryptak-wallpaper.png" "$UNRAID_SSH:/mnt/user/appdata/tak-server/mdm/volumes/files/cryptak-wallpaper.png" 2>/dev/null && \
    echo "  Wallpaper: uploaded to HMDM server" || \
    echo "  Wallpaper: upload failed (may already exist)"
fi
STEP_COMPLETED=3

# ── Step 4: Tailscale Enrollment ──────────────────
echo ""
echo "[4/9] Enrolling in Tailscale VPN..."

# 4a. Disable Private DNS (DoT bypasses router's split DNS)
ORIG_DNS=$(adb_cmd shell settings get global private_dns_mode 2>/dev/null | tr -d '\r')
adb_cmd shell "settings put global private_dns_mode off"
PRIVATE_DNS_DISABLED=1
echo "  Private DNS: disabled (was: $ORIG_DNS)"

# 4b. Cycle WiFi for DHCP DNS refresh (router pushes 192.168.50.1 first)
adb_cmd shell "svc wifi disable"
sleep 2
adb_cmd shell "svc wifi enable"
echo "  WiFi cycled for DNS refresh"
sleep 8

# 4c. Network check (warning, not blocker)
PHONE_IP=$(adb_cmd shell "ip addr show wlan0 2>/dev/null" | grep -o 'inet [0-9.]*' | awk '{print $2}' | tr -d '\r' || true)
if [[ "$PHONE_IP" == 192.168.50.* ]]; then
  echo "  Network: $PHONE_IP (home WiFi — split DNS path)"
else
  echo "  WARNING: Phone IP is $PHONE_IP (not home WiFi). Split DNS may not work."
  echo "  Enrollment may still work if phone can reach $HEADSCALE_URL"
fi

# 4d. Clear Tailscale
adb_cmd shell pm clear com.tailscale.ipn >/dev/null 2>&1
echo "  Tailscale: cleared"

# 4e. Launch and navigate UI
# Welcome screen → Get Started
adb_cmd shell "monkey -p com.tailscale.ipn -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1
sleep 3
if wait_for_ui "Get Started" 10; then
  tap_element "Get Started" 2 >/dev/null
  sleep 2
fi

# Gear → Accounts → ⋮ menu → "Use an alternate server"
tap_element "Open settings" 2 >/dev/null
sleep 2
tap_element "Accounts" 2 >/dev/null
sleep 2
tap_element "menu" 2 >/dev/null
sleep 2
tap_element "Use an alternate server" 2 >/dev/null
sleep 2

# Enter server URL
FIELD=$(find_input_field)
if [ -n "$FIELD" ]; then
  adb_cmd shell input tap $FIELD
  sleep 1
fi
adb_cmd shell input text "$HEADSCALE_URL"
sleep 1
echo "  Server URL: entered"

# Submit (opens browser briefly — expected for OIDC server check)
tap_element "Add account" 2 >/dev/null
sleep 3
# Close browser
adb_cmd shell input keyevent 4
sleep 1
adb_cmd shell input keyevent 4
sleep 1

# 4f. Navigate back for auth key entry
adb_cmd shell "monkey -p com.tailscale.ipn -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1
sleep 3
tap_element "Open settings" 2 >/dev/null
sleep 2
tap_element "Accounts" 2 >/dev/null
sleep 2
tap_element "menu" 2 >/dev/null
sleep 2
tap_element "Use an auth key" 2 >/dev/null
sleep 2

# Enter pre-auth key
FIELD=$(find_input_field)
if [ -n "$FIELD" ]; then
  adb_cmd shell input tap $FIELD
  sleep 1
fi
adb_cmd shell input text "$PREAUTH_KEY"
sleep 1
# Dismiss keyboard before tapping Add account
adb_cmd shell input keyevent 4
sleep 1
echo "  Auth key: entered"

# Submit enrollment
tap_element "Add account" 2 >/dev/null
sleep 5

# 4g. Handle notification permission
if wait_for_ui "Notifications" 10; then
  tap_element "Continue" 2 >/dev/null
  sleep 2
fi
# Android system notification permission dialog
if wait_for_ui "Allow.*notifications" 5; then
  tap_element "Allow" 2 >/dev/null
  sleep 2
fi

# 4h. Verify enrollment via Headscale
echo "  Verifying enrollment..."
sleep 5
LATEST_LOG=$(ssh "$UNRAID_SSH" "docker logs headscale --since 2m 2>&1" || true)
NODE_ID=$(echo "$LATEST_LOG" | grep -oP 'Node connected node\.id=\K[0-9]+' | tail -1 || true)

if [ -n "$NODE_ID" ]; then
  echo "  Enrollment: SUCCESS (node.id=$NODE_ID)"

  # Get assigned VPN IP
  ASSIGNED_IP=$(ssh "$UNRAID_SSH" "docker exec headscale headscale nodes list 2>/dev/null" | grep -P "^\s*${NODE_ID}\b" | grep -oP '100\.64\.[0-9]+\.[0-9]+' | head -1 || true)
  [ -n "$ASSIGNED_IP" ] && echo "  VPN IP: $ASSIGNED_IP"

  # 4i. Rename node
  ssh "$UNRAID_SSH" "docker exec headscale headscale nodes rename $CALLSIGN_LOWER --identifier $NODE_ID" >/dev/null 2>&1 && \
    echo "  Node renamed: $CALLSIGN_LOWER" || \
    echo "  WARNING: Node rename failed — do manually"
else
  echo "  WARNING: Enrollment not confirmed in logs — check manually"
  echo "  ssh unraid \"docker logs headscale --tail 20\""
  echo "  Continuing anyway (Tailscale may still be connected)..."
fi

# 4j. Restore Private DNS
adb_cmd shell "settings put global private_dns_mode opportunistic"
PRIVATE_DNS_DISABLED=0
echo "  Private DNS: restored"
STEP_COMPLETED=4

# ── Step 5: HMDM Enrollment ──────────────────────
echo ""
echo "[5/9] Enrolling in Headwind MDM..."

# Check VPN routing
VPN_OK=0
for i in 1 2 3; do
  if adb_cmd shell "ping -c 1 -W 3 100.64.0.1" &>/dev/null; then
    VPN_OK=1
    break
  fi
  sleep 3
done
if [ "$VPN_OK" -eq 1 ]; then
  echo "  VPN routing: OK"
else
  echo "  WARNING: Cannot reach 100.64.0.1 — HMDM enrollment may fail"
  echo "  Verify Tailscale is connected on the phone, then re-run."
fi

# Pre-grant ALL permissions to skip dialogs
echo "  Pre-granting permissions..."
adb_cmd shell pm grant com.hmdm.launcher android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
adb_cmd shell pm grant com.hmdm.launcher android.permission.ACCESS_COARSE_LOCATION 2>/dev/null || true
adb_cmd shell pm grant com.hmdm.launcher android.permission.ACCESS_BACKGROUND_LOCATION 2>/dev/null || true
adb_cmd shell pm grant com.hmdm.launcher android.permission.READ_PHONE_STATE 2>/dev/null || true
adb_cmd shell pm grant com.hmdm.launcher android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
adb_cmd shell pm grant com.hmdm.launcher android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null || true
adb_cmd shell pm grant com.hmdm.launcher android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb_cmd shell appops set --uid com.hmdm.launcher MANAGE_EXTERNAL_STORAGE allow 2>/dev/null || true
adb_cmd shell appops set com.hmdm.launcher REQUEST_INSTALL_PACKAGES allow 2>/dev/null || true

# Set device owner (enables silent APK installs)
echo "  Setting device owner..."
DPM_RESULT=$(adb_cmd shell dpm set-device-owner com.hmdm.launcher/.AdminReceiver 2>&1)
if echo "$DPM_RESULT" | grep -q "Success\|already"; then
  echo "  Device owner: SET"
else
  if echo "$DPM_RESULT" | grep -q "already several accounts\|Not allowed to set"; then
    echo "  WARNING: Cannot set device owner — accounts exist on device"
    echo "  HMDM will prompt for each app install (still works, just slower)"
  else
    echo "  WARNING: Device owner: $(echo "$DPM_RESULT" | tail -1)"
  fi
fi

# Launch HMDM
adb_cmd shell am start -n com.hmdm.launcher/.ui.MainActivity 2>/dev/null
sleep 5

# Handle permission dialogs
for i in 1 2 3; do
  adb_cmd shell uiautomator dump /sdcard/ui.xml 2>/dev/null
  HAS_ALLOW=$(adb_cmd shell cat /sdcard/ui.xml 2>/dev/null | grep -c "While using\|ALLOW\|Allow" || true)
  if [ "$HAS_ALLOW" -gt 0 ]; then
    tap_element "While using the app" 1 2>/dev/null || tap_element "Allow" 1 2>/dev/null || true
    sleep 2
  else
    break
  fi
done

# Handle "permissions required" retry screen
adb_cmd shell uiautomator dump /sdcard/ui.xml 2>/dev/null
HAS_RETRY=$(adb_cmd shell cat /sdcard/ui.xml 2>/dev/null | grep -c "RETRY" || true)
if [ "$HAS_RETRY" -gt 0 ]; then
  tap_element "RETRY" 1 2>/dev/null || true
  sleep 3
fi

# Enter server URL
echo "  Entering server URL..."
adb_cmd shell uiautomator dump /sdcard/ui.xml 2>/dev/null
HAS_SERVER=$(adb_cmd shell cat /sdcard/ui.xml 2>/dev/null | grep -ci "server URL\|server url" || true)

if [ "$HAS_SERVER" -gt 0 ]; then
  FIELD=$(find_input_field)
  if [ -n "$FIELD" ]; then
    adb_cmd shell input tap $FIELD
    sleep 0.5
    # Select all + delete (clear default text)
    adb_cmd shell input keyevent 123  # MOVE_END
    for i in $(seq 1 50); do adb_cmd shell input keyevent 67; done
    sleep 0.3
    adb_cmd shell input text "$HMDM_SERVER"
    sleep 1
    tap_element "OK" 2 2>/dev/null || adb_cmd shell input keyevent 66
    sleep 3
    echo "  Server URL: SET"
  fi
fi

# Enter device ID
echo "  Entering device ID..."
adb_cmd shell uiautomator dump /sdcard/ui.xml 2>/dev/null
HAS_DEVICE=$(adb_cmd shell cat /sdcard/ui.xml 2>/dev/null | grep -ci "device ID\|device id" || true)

if [ "$HAS_DEVICE" -gt 0 ]; then
  FIELD=$(find_input_field)
  if [ -n "$FIELD" ]; then
    adb_cmd shell input tap $FIELD
    sleep 0.5
    adb_cmd shell input text "$DEVICE_ID"
    sleep 1
    tap_element "SAVE" 2 2>/dev/null || adb_cmd shell input keyevent 66
    sleep 5
    echo "  Device ID: SET"
  fi
fi

# Wait for HMDM to connect and install apps
echo "  Waiting for HMDM to pull configuration and install apps..."
sleep 10

# Handle install dialogs (device owner = silent installs, but just in case)
for i in $(seq 1 10); do
  adb_cmd shell uiautomator dump /sdcard/ui.xml 2>/dev/null
  UI_TEXT=$(adb_cmd shell cat /sdcard/ui.xml 2>/dev/null)

  HAS_CHOOSER=$(echo "$UI_TEXT" | grep -c "Package installer" || true)
  if [ "$HAS_CHOOSER" -gt 0 ]; then
    tap_element "Package installer" 1 2>/dev/null || true
    sleep 1
    tap_element "Always" 1 2>/dev/null || tap_element "Just once" 1 2>/dev/null || true
    sleep 2
    continue
  fi

  HAS_INSTALL=$(echo "$UI_TEXT" | grep -c "Install this app" || true)
  if [ "$HAS_INSTALL" -gt 0 ]; then
    tap_element "Install" 1 2>/dev/null || true
    sleep 5
    tap_element "Done" 1 2>/dev/null || true
    sleep 2
    continue
  fi

  HAS_LAUNCHER=$(echo "$UI_TEXT" | grep -c "Tailscale\|ATAK\|Meshtastic" || true)
  if [ "$HAS_LAUNCHER" -gt 0 ]; then
    echo "  HMDM enrollment: SUCCESS"
    break
  fi

  HAS_ERROR=$(echo "$UI_TEXT" | grep -c "Error connecting" || true)
  if [ "$HAS_ERROR" -gt 0 ]; then
    echo "  HMDM connection error — retrying..."
    tap_element "RETRY" 1 2>/dev/null || true
    sleep 5
    continue
  fi

  sleep 5
done

# Wait for app installs to complete
echo "  Waiting for app installs to complete..."
for i in $(seq 1 30); do
  ATAK_OK=$(adb_cmd shell pm list packages com.atakmap.app.civ 2>/dev/null | grep -c "package:" || true)
  MESH_OK=$(adb_cmd shell pm list packages com.geeksville.mesh 2>/dev/null | grep -c "package:" || true)
  if [ "$ATAK_OK" -gt 0 ] && [ "$MESH_OK" -gt 0 ]; then
    echo "  Core apps installed (${i}x5s)"
    break
  fi
  sleep 5
done

# Verify installed apps
echo "  Verifying apps..."
for PKG in com.atakmap.app.civ com.tailscale.ipn com.geeksville.mesh com.hmdm.launcher; do
  if adb_cmd shell pm list packages "$PKG" 2>/dev/null | grep -q "package:"; then
    echo "    $PKG: OK"
  else
    echo "    $PKG: MISSING (HMDM may still be installing)"
  fi
done
# Check plugin separately (different package pattern)
if adb_cmd shell pm list packages com.atakmap.android.meshtastic.plugin 2>/dev/null | grep -q "package:"; then
  echo "    CrypTAK Plugin: OK"
else
  echo "    CrypTAK Plugin: MISSING (HMDM may still be installing)"
fi
STEP_COMPLETED=5

# ── Step 6: Cellular Data Policy ──────────────────
echo ""
echo "[6/9] Configuring cellular data policy..."

adb_cmd shell settings put global data_saver_enabled 1
echo "  Data Saver: enabled"

# Whitelist ops apps for cellular (UIDs available now that HMDM installed them)
for PKG in com.tailscale.ipn com.atakmap.app.civ com.geeksville.mesh; do
  PKG_UID=$(adb_cmd shell pm list packages -U "$PKG" 2>/dev/null | grep -oP 'uid:\K[0-9]+' || true)
  if [ -n "$PKG_UID" ]; then
    adb_cmd shell cmd netpolicy add restrict-background-whitelist "$PKG_UID" 2>/dev/null && \
      echo "  Whitelisted: $PKG (uid $PKG_UID)" || true
  fi
done

# Restrict F-Droid if present
FDROID_UID=$(adb_cmd shell pm list packages -U org.fdroid.fdroid 2>/dev/null | grep -oP 'uid:\K[0-9]+' || true)
if [ -n "$FDROID_UID" ]; then
  adb_cmd shell cmd netpolicy set restrict-background "$FDROID_UID" true 2>/dev/null || true
  echo "  Restricted: F-Droid (uid $FDROID_UID)"
fi
STEP_COMPLETED=6

# ── Step 7: Enable ADB over TCP ──────────────────
echo ""
echo "[7/9] Enabling ADB over TCP/IP..."

adb_cmd tcpip 5555
sleep 3
PHONE_IP=$(adb_cmd shell "ip addr show wlan0 2>/dev/null" | grep -o 'inet [0-9.]*' | awk '{print $2}' | tr -d '\r' || true)
if [ -n "$PHONE_IP" ]; then
  echo "  ADB TCP: enabled on ${PHONE_IP}:5555"
  # Verify TCP connection
  adb connect "${PHONE_IP}:5555" 2>/dev/null | grep -q "connected" && \
    echo "  TCP connection: verified" || \
    echo "  TCP connection: verify manually with 'adb connect ${PHONE_IP}:5555'"
else
  echo "  WARNING: Could not determine phone WiFi IP"
fi
STEP_COMPLETED=7

# ── Step 8: Connectivity Verification ─────────────
echo ""
echo "[8/9] Verifying connectivity..."

if adb_cmd shell "ping -c 1 -W 3 100.64.0.1" &>/dev/null; then
  echo "  VPN → Unraid (100.64.0.1): OK"
else
  echo "  VPN → Unraid (100.64.0.1): FAIL"
fi

if adb_cmd shell "echo | nc -w 2 100.64.0.1 8087" &>/dev/null; then
  echo "  FTS (100.64.0.1:8087): REACHABLE"
else
  echo "  FTS (100.64.0.1:8087): NOT REACHABLE (nc may not be available)"
fi
STEP_COMPLETED=8

# ── Step 9: VPN Lockdown (LAST) ──────────────────
echo ""
echo "[9/9] Applying VPN lockdown..."

adb_cmd shell settings put secure always_on_vpn_app com.tailscale.ipn
adb_cmd shell settings put secure always_on_vpn_lockdown 1

VPN_APP=$(adb_cmd shell settings get secure always_on_vpn_app 2>/dev/null | tr -d '\r')
VPN_LOCK=$(adb_cmd shell settings get secure always_on_vpn_lockdown 2>/dev/null | tr -d '\r')
if [ "$VPN_APP" = "com.tailscale.ipn" ] && [ "$VPN_LOCK" = "1" ]; then
  echo "  VPN lockdown: ENABLED (always-on + kill-switch)"
else
  echo "  WARNING: VPN lockdown may not have applied (app=$VPN_APP lock=$VPN_LOCK)"
fi
STEP_COMPLETED=9

# ── Cleanup ───────────────────────────────────────
echo ""
adb_cmd shell input keyevent 3  # HOME

echo "========================================="
echo "PROVISIONING COMPLETE: $DEVICE_ID"
echo "========================================="
echo ""
echo "  VPN IP:      ${ASSIGNED_IP:-unknown}"
echo "  Node name:   $CALLSIGN_LOWER"
echo "  ADB TCP:     adb connect ${PHONE_IP:-unknown}:5555"
if [ -n "$ASSIGNED_IP" ]; then
  echo "  ADB via VPN: adb connect ${ASSIGNED_IP}:5555 (from any tailnet machine)"
fi
echo ""
echo "REMAINING MANUAL STEP:"
echo ""
echo "  ATAK FIRST LAUNCH:"
echo "    Open ATAK → set encryption passphrase"
echo "    Verify callsign = $DEVICE_ID, team = Cyan"
echo "    Verify server: CrypTAK-Home ($FTS_PRIMARY)"
echo ""
echo "  Then: Settings → Developer Options → USB Debugging → OFF"
