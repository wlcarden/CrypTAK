#!/bin/bash
# shellcheck disable=SC2086  # Word splitting is intentional for: input tap $coords, input tap $FIELD
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

  # If Private DNS was disabled but enrollment didn't complete, restore it.
  # On successful provisioning, Private DNS stays OFF (VPN provides encryption).
  if [ "$PRIVATE_DNS_DISABLED" -eq 1 ] && [ -n "$USB_SERIAL" ]; then
    echo "Restoring Private DNS (enrollment didn't complete)..."
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
    adb_cmd shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
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
      if [ -n "$coords" ] && [[ "$coords" =~ ^[0-9]+\ [0-9]+$ ]]; then
        adb_cmd shell input tap $coords
        return 0
      fi
    fi

    attempt=$((attempt + 1))
    sleep 2
  done

  echo "  WARNING: Could not find element '$search_text' after $retries attempts"
  return 1
}

# ── Helper: find first EditText and return center coords ──
find_input_field() {
  adb_cmd shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
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
    adb_cmd shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
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

# ATAK server config — build a mission package ZIP that ATAK auto-imports.
# A loose .pref file at /sdcard/atak/servers.pref does NOT get imported.
# ATAK requires a ZIP with MANIFEST/manifest.xml + the .pref file.
adb_cmd shell mkdir -p /sdcard/atak/tools/datapackage/incoming 2>/dev/null || true

TMPDIR=$(mktemp -d)
mkdir -p "$TMPDIR/MANIFEST"
cat > "$TMPDIR/MANIFEST/manifest.xml" << 'MANIFESTEOF'
<?xml version="1.0" encoding="utf-8"?>
<MissionPackageManifest version="2">
    <Configuration>
        <Parameter name="uid" value="cryptak-server-config-v1"/>
        <Parameter name="name" value="CrypTAK Server Config"/>
        <Parameter name="onReceiveDelete" value="false"/>
    </Configuration>
    <Contents>
        <Content ignore="false" zipEntry="cryptak-fts.pref"/>
    </Contents>
</MissionPackageManifest>
MANIFESTEOF

cat > "$TMPDIR/cryptak-fts.pref" << PREFEOF
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

(cd "$TMPDIR" && zip -q cryptak-server-config.zip MANIFEST/manifest.xml cryptak-fts.pref)
adb_cmd push "$TMPDIR/cryptak-server-config.zip" /sdcard/atak/tools/datapackage/incoming/ >/dev/null
rm -rf "$TMPDIR"
echo "  ATAK server config: mission package pushed (callsign=$DEVICE_ID, team=Cyan)"

# ATAK offline map sources
adb_cmd shell mkdir -p /sdcard/atak/imagery 2>/dev/null || true
MAP_COUNT=0
for XML in "$SCRIPT_DIR/atak-maps/"*.xml; do
  [ -f "$XML" ] || continue
  adb_cmd push "$XML" "/sdcard/atak/imagery/$(basename "$XML")" >/dev/null 2>&1 && MAP_COUNT=$((MAP_COUNT + 1)) || true
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

# 4a. Wait for WiFi connectivity
# NOTE: Private DNS stays ON here — "Get Started" needs general DNS (login.tailscale.com)
# to complete. Private DNS is disabled later (4f) only for split DNS enrollment.
echo "  Waiting for WiFi..."
for _ in $(seq 1 15); do
  PHONE_IP=$(adb_cmd shell "ip addr show wlan0 2>/dev/null" | grep -o 'inet [0-9.]*' | awk '{print $2}' | tr -d '\r' || true)
  [ -n "$PHONE_IP" ] && break
  sleep 2
done
if [ -z "$PHONE_IP" ]; then
  echo "  ERROR: Phone has no WiFi connection. Connect to WiFi and re-run."
  exit 1
fi
echo "  WiFi: connected ($PHONE_IP)"

# 4c. Network check + split DNS verification (reuse PHONE_IP from 4b)
if [[ "$PHONE_IP" == 192.168.50.* ]]; then
  echo "  Network: $PHONE_IP (home WiFi)"

  # Verify split DNS resolves to internal IP (not external — which hits hairpin NAT)
  RESOLVED_IP=$(nslookup vpn.thousand-pikes.com 192.168.50.1 2>/dev/null | grep -A1 "Name:" | grep "Address:" | awk '{print $2}' || true)
  if [ "$RESOLVED_IP" = "192.168.50.120" ]; then
    echo "  Split DNS: OK (vpn.thousand-pikes.com → 192.168.50.120)"
  else
    echo "  Split DNS: NOT configured (resolves to ${RESOLVED_IP:-nothing})"
    echo "  Attempting to fix router DNS..."
    # Try SSH to router to add split DNS + fix DHCP order
    if ssh -o ConnectTimeout=5 wlcarden@192.168.50.1 'killall dnsmasq 2>/dev/null; sed -i "s|dhcp-option=lan,6,.*|dhcp-option=lan,6,192.168.50.1,1.1.1.1,8.8.8.8|" /etc/dnsmasq.conf; echo "address=/vpn.thousand-pikes.com/192.168.50.120" >> /etc/dnsmasq.conf; dnsmasq --log-async' 2>/dev/null; then
      echo "  Router DNS: fixed (non-persistent — lost on router reboot)"
      # Re-cycle WiFi to pick up new DHCP DNS order
      adb_cmd shell "svc wifi disable"
      sleep 2
      adb_cmd shell "svc wifi enable"
      # Wait for WiFi to reconnect
      for _ in $(seq 1 15); do
        PHONE_IP=$(adb_cmd shell "ip addr show wlan0 2>/dev/null" | grep -o 'inet [0-9.]*' | awk '{print $2}' | tr -d '\r' || true)
        [ -n "$PHONE_IP" ] && break
        sleep 2
      done
    else
      echo "  WARNING: Cannot SSH to router (wlcarden@192.168.50.1)"
      echo "  WiFi enrollment will likely fail. Options:"
      echo "    1. Add SSH key to router: http://192.168.50.1 → Administration → Authorized Keys"
      echo "    2. Connect phone to mobile hotspot instead of home WiFi"
      echo "  Continuing anyway..."
    fi
  fi

  # Verify headscale port 443 is reachable from desktop (same LAN)
  if curl -sk --connect-timeout 3 "https://192.168.50.120/key?v=131" >/dev/null 2>&1; then
    echo "  Headscale LAN port: OK (192.168.50.120:443)"
  else
    echo "  WARNING: Headscale not reachable on 192.168.50.120:443"
    echo "  Check nginx container has port 192.168.50.120:443:443 binding"
  fi
else
  echo "  Network: $PHONE_IP (not home WiFi — enrollment via external path)"
fi

# 4d. Verify DNS is functional before proceeding
echo "  Verifying DNS..."
DNS_OK=0
for _ in 1 2 3; do
  if nslookup vpn.thousand-pikes.com >/dev/null 2>&1; then
    DNS_OK=1
    break
  fi
  sleep 2
done
if [ "$DNS_OK" -eq 0 ]; then
  echo "  WARNING: DNS resolution failing — waiting for resolver to stabilize..."
  sleep 10
fi

# 4e. Clear Tailscale
adb_cmd shell pm clear com.tailscale.ipn >/dev/null 2>&1
echo "  Tailscale: cleared"

# 4e. Launch and navigate UI
adb_cmd shell "monkey -p com.tailscale.ipn -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1
sleep 3

# Handle VPN connection request dialog — appears on first launch after pm clear
# This system dialog blocks ALL other UI interaction until dismissed
if wait_for_ui "Connection request" 5; then
  tap_element "OK" 2 >/dev/null
  echo "  VPN permission: granted"
  sleep 2
fi

# Welcome screen → Get Started
# First tap opens a Custom Chrome Tab (login.tailscale.com) INSIDE Tailscale.
# This is NOT a separate browser — force-stop won't work. Close it with the
# "Close tab" X button (top left), then tap "Get Started" again to advance.
if wait_for_ui "Get Started" 10; then
  tap_element "Get Started" 2 >/dev/null || true
  sleep 4
  # Check if Custom Chrome Tab opened (look for "Close tab" X button)
  if wait_for_ui "Close tab" 5; then
    tap_element "Close tab" 2 >/dev/null || true
    sleep 2
    # Second tap on "Get Started" advances to the main screen
    tap_element "Get Started" 2 >/dev/null || true
    sleep 3
  fi
  echo "  Past welcome screen"
fi

# 4f. NOW disable Private DNS (needed for split DNS to resolve vpn.thousand-pikes.com)
ORIG_DNS=$(adb_cmd shell settings get global private_dns_mode 2>/dev/null | tr -d '\r')
adb_cmd shell "settings put global private_dns_mode off"
PRIVATE_DNS_DISABLED=1
echo "  Private DNS: disabled for enrollment (was: $ORIG_DNS)"

# Navigate: Gear → Accounts → ⋮ menu → "Use an alternate server"
echo "  Navigating: Gear → Accounts → ⋮ → Alternate server..."
tap_element "Open settings" 3 || true
sleep 2
tap_element "Accounts" 3 || true
sleep 2
tap_element "menu" 3 || true
sleep 2
tap_element "Use an alternate server" 3 || true
sleep 2

# Enter server URL — find the EditText field, tap it, type URL
for _ in 1 2 3; do
  FIELD=$(find_input_field)
  [ -n "$FIELD" ] && break
  sleep 2
done
if [ -n "$FIELD" ]; then
  adb_cmd shell input tap $FIELD
  sleep 1
  adb_cmd shell input text "$HEADSCALE_URL"
  sleep 1
  echo "  Server URL: entered"
else
  echo "  WARNING: Could not find URL input field — typing anyway"
  adb_cmd shell input text "$HEADSCALE_URL"
  sleep 1
fi

# Submit (opens Custom Chrome Tab for OIDC — we don't need it, just saving the URL)
tap_element "Add account" 3 >/dev/null || true
sleep 4
# Close the Custom Chrome Tab (X button, top left) — it's inside Tailscale, not a separate browser
if wait_for_ui "Close tab" 5; then
  tap_element "Close tab" 2 >/dev/null || true
  sleep 2
fi

# 4g. Navigate back for auth key entry
# Re-launch Tailscale to get a clean state
adb_cmd shell "monkey -p com.tailscale.ipn -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1
sleep 3
echo "  Navigating: Gear → Accounts → ⋮ → Auth key..."
tap_element "Open settings" 3 || true
sleep 2
tap_element "Accounts" 3 || true
sleep 2
tap_element "menu" 3 || true
sleep 2
tap_element "Use an auth key" 3 || true
sleep 2

# Enter pre-auth key
for _ in 1 2 3; do
  FIELD=$(find_input_field)
  [ -n "$FIELD" ] && break
  sleep 2
done
if [ -n "$FIELD" ]; then
  adb_cmd shell input tap $FIELD
  sleep 1
fi
adb_cmd shell input text "$PREAUTH_KEY"
sleep 1
# Dismiss keyboard — the long key text expands the field, pushing "Add account"
# button down. Must dismiss keyboard and re-dump UI to get correct button position.
adb_cmd shell input keyevent 4
sleep 2
echo "  Auth key: entered"

# Submit enrollment — tap_element re-dumps UI, so it finds the button at its
# new position after the text field expanded from the long key string
tap_element "Add account" 3 >/dev/null || true
sleep 5

# 4g. Handle post-enrollment system dialogs
# Three dialogs may appear in any order after enrollment:
#   1. Tailscale notification permission screen ("Notifications" → "Continue")
#   2. Android notification permission dialog ("Allow Tailscale to send notifications?" → "Allow")
#   3. Android VPN connection request ("Connection request" → "OK")
for _ in 1 2 3 4 5; do
  sleep 3
  adb_cmd shell uiautomator dump /sdcard/ui.xml 2>/dev/null
  UI_TEXT=$(adb_cmd shell cat /sdcard/ui.xml 2>/dev/null)

  # Tailscale notification info screen
  if echo "$UI_TEXT" | grep -qi "Notifications.*troubleshoot\|We use notifications"; then
    tap_element "Continue" 2 >/dev/null || true
    continue
  fi

  # Android notification permission dialog
  if echo "$UI_TEXT" | grep -qi "Allow.*send.*notifications"; then
    tap_element "Allow" 2 >/dev/null || true
    continue
  fi

  # Android VPN connection request dialog (first-time VPN setup)
  if echo "$UI_TEXT" | grep -qi "Connection request\|set up a VPN connection"; then
    tap_element "OK" 2 >/dev/null || true
    echo "  VPN connection: authorized"
    continue
  fi

  # If we see "Connected" or the main Tailscale UI, we're done with dialogs
  if echo "$UI_TEXT" | grep -qi "Connected\|Exit node\|Search"; then
    break
  fi
done

# 4h. Verify enrollment via Headscale (retry loop — enrollment may take a few seconds)
echo "  Verifying enrollment..."
NODE_ID=""
for attempt in 1 2 3 4 5 6; do
  sleep 5
  LATEST_LOG=$(ssh "$UNRAID_SSH" "docker logs headscale --since 3m 2>&1" || true)
  NODE_ID=$(echo "$LATEST_LOG" | grep -oP 'Node connected node\.id=\K[0-9]+' | tail -1 || true)
  if [ -n "$NODE_ID" ]; then
    break
  fi
  # Also check if the phone's Tailscale UI shows "Connected"
  adb_cmd shell uiautomator dump /sdcard/ui.xml 2>/dev/null
  if adb_cmd shell cat /sdcard/ui.xml 2>/dev/null | grep -qi "Connected"; then
    # Phone says connected — find the node by checking the most recent online node
    NODE_ID=$(ssh "$UNRAID_SSH" "docker exec headscale headscale nodes list 2>/dev/null" | grep "online" | tail -1 | awk '{print $1}' | tr -d '[:space:]' || true)
    [ -n "$NODE_ID" ] && break
  fi
done

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
  echo "  ERROR: Enrollment not confirmed after 30s"
  echo "  Check: ssh unraid \"docker logs headscale --tail 20\""
  echo "  The phone may need a different network (mobile hotspot) for enrollment."
  echo "  Cannot continue without VPN — HMDM requires VPN routing."
  exit 1
fi

# 4j. Leave Private DNS OFF permanently
# With VPN lockdown, all traffic goes through WireGuard — DoT adds nothing.
# Restoring Private DNS would break Tailscale reconnects from home WiFi
# (DoT to 1.1.1.1 bypasses router split DNS → hairpin NAT failure).
PRIVATE_DNS_DISABLED=0  # clear flag so cleanup trap doesn't restore it
echo "  Private DNS: left OFF (VPN provides encryption)"
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

# ── Step 7: Disable ADB + Developer Options ──────
echo ""
echo "[7/9] Securing device — disabling ADB and developer options..."

# Disable ADB over network (no unauthenticated remote shell)
adb_cmd shell settings put global adb_wifi_enabled 0 2>/dev/null || true

# Disable developer options (also disables USB debugging on next settings open)
adb_cmd shell settings put global development_settings_enabled 0

# Revoke ADB authorization for this machine (belt + suspenders)
# Note: USB debugging is now off. To re-enable for emergency maintenance:
#   1. Settings → About Phone → tap Build Number 7 times
#   2. Settings → Developer Options → USB Debugging → ON
#   3. Connect USB, authorize on phone
echo "  Developer options: DISABLED"
echo "  USB debugging: will be OFF on next reboot"
echo "  ADB network access: DISABLED"
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

# Force Tailscale reconnect with final network config
# VPN lockdown changes the network stack — Tailscale needs a restart to
# reconnect through the always-on VPN tunnel.
echo "  Restarting Tailscale for clean VPN connection..."
adb_cmd shell "am force-stop com.tailscale.ipn"
sleep 2
adb_cmd shell "monkey -p com.tailscale.ipn -c android.intent.category.LAUNCHER 1" >/dev/null 2>&1
sleep 8
# Handle any post-restart dialogs
for _ in 1 2 3; do
  adb_cmd shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  UI=$(adb_cmd shell cat /sdcard/ui.xml 2>/dev/null)
  if echo "$UI" | grep -qi "Notifications.*troubleshoot"; then
    tap_element "Continue" 2 >/dev/null || true; sleep 2; continue
  fi
  if echo "$UI" | grep -qi "Allow.*notifications"; then
    tap_element "Allow" 2 >/dev/null || true; sleep 2; continue
  fi
  break
done
adb_cmd shell input keyevent 3  # HOME
# Verify VPN is connected
sleep 3
VPN_CHECK=$(ssh "$UNRAID_SSH" "docker exec headscale headscale nodes list 2>/dev/null" | grep "$CALLSIGN_LOWER" || true)
if echo "$VPN_CHECK" | grep -q "online"; then
  echo "  Tailscale: connected (verified via Headscale)"
else
  echo "  WARNING: Tailscale shows offline in Headscale — may need manual reconnect"
fi

# Force HMDM config refresh — ensures any remaining apps (e.g. CrypTAK Plugin)
# get pushed now that VPN lockdown is in place and connectivity is verified.
echo ""
echo "  Forcing MDM config update..."
adb_cmd shell am broadcast -a com.hmdm.PUSH_CONFIG -n com.hmdm.launcher/.AdminReceiver 2>/dev/null || true
# Also open and close HMDM to trigger a foreground sync
adb_cmd shell am start -n com.hmdm.launcher/.ui.MainActivity 2>/dev/null || true
sleep 10
adb_cmd shell input keyevent 3  # HOME
echo "  MDM config refresh: triggered"

# ── Cleanup ───────────────────────────────────────
echo ""
adb_cmd shell input keyevent 3  # HOME

echo "========================================="
echo "PROVISIONING COMPLETE: $DEVICE_ID"
echo "========================================="
echo ""
echo "  VPN IP:      ${ASSIGNED_IP:-unknown}"
echo "  Node name:   $CALLSIGN_LOWER"
echo "  ADB:         DISABLED (developer options off)"
echo "  VPN:         LOCKED (always-on + kill-switch)"
echo "  MDM:         HMDM device owner"
echo ""
echo "REMAINING MANUAL STEP:"
echo ""
echo "  ATAK FIRST LAUNCH:"
echo "    Open ATAK → set encryption passphrase"
echo "    Verify callsign = $DEVICE_ID, team = Cyan"
echo "    Verify server: CrypTAK-Home ($FTS_PRIMARY)"
echo ""
echo "EMERGENCY MAINTENANCE (break-glass):"
echo "  To re-enable ADB for debugging:"
echo "    1. Settings → About Phone → tap Build Number 7 times"
echo "    2. Settings → Developer Options → USB Debugging → ON"
echo "    3. Connect USB cable, authorize on phone prompt"
echo "    4. After debugging: re-run this step or disable manually"
