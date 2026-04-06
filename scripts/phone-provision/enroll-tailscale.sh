#!/bin/bash
# CrypTAK Tailscale Enrollment Automation
# Automates the full Tailscale UI flow: alternate server + pre-auth key
#
# REQUIRES: Phone on cellular/hotspot (NOT home WiFi — hairpin NAT blocks it)
#
# Usage: ./enroll-tailscale.sh <PREAUTH_KEY> [HEADSCALE_URL]
# Example: ./enroll-tailscale.sh hskey-auth-XXXXX...
set -euo pipefail

PREAUTH_KEY="${1:-}"
HEADSCALE_URL="${2:-https://vpn.thousand-pikes.com}"

if [ -z "$PREAUTH_KEY" ]; then
  echo "Usage: $0 <PREAUTH_KEY> [HEADSCALE_URL]"
  echo "Default URL: https://vpn.thousand-pikes.com"
  echo ""
  echo "Generate a key:"
  echo "  ssh unraid \"docker exec headscale headscale preauthkeys create --user 1 --expiration 72h\""
  exit 1
fi

if ! adb devices | grep -q "device$"; then
  echo "ERROR: No device found via ADB"
  exit 1
fi

# Helper: dump UI and find the center of an element by text
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
# Strategy: find any node whose text/desc matches, then walk up to find
# the nearest clickable ancestor. If none, use the text node's own bounds.
all_nodes = list(tree.iter('node'))
parent_map = {c: p for p in tree.iter() for c in p}
for node in all_nodes:
    text = (node.get('text', '') or '').lower()
    desc = (node.get('content-desc', '') or '').lower()
    if search in text or search in desc:
        # Walk up to find clickable parent
        current = node
        while current is not None:
            if current.get('clickable', '') == 'true':
                print(current.get('bounds', ''))
                sys.exit(0)
            current = parent_map.get(current)
        # No clickable parent — use the text node's bounds directly
        print(node.get('bounds', ''))
        sys.exit(0)
" 2>/dev/null)

    if [ -n "$bounds" ]; then
      # Parse bounds [x1,y1][x2,y2] and compute center via Python
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

  echo "  WARNING: Could not find element '$search_text' after $retries attempts"
  return 1
}

echo "========================================="
echo "Tailscale Enrollment Automation"
echo "========================================="
echo "Server: $HEADSCALE_URL"
echo ""

# Check network — must NOT be on home WiFi
PHONE_IP=$(adb shell "ip addr show wlan0 2>/dev/null" | grep -o 'inet [0-9.]*' | awk '{print $2}' | tr -d '\r')
if [[ "$PHONE_IP" == 192.168.50.* ]]; then
  echo "ERROR: Phone is on home WiFi ($PHONE_IP). Enrollment requires cellular/hotspot."
  echo "Connect to a mobile hotspot or cellular data first."
  exit 1
fi
echo "Network: $PHONE_IP (not home WiFi — OK)"

# Step 1: Clear Tailscale
echo ""
echo "[1/7] Clearing Tailscale..."
adb shell pm clear com.tailscale.ipn 2>&1 | grep -q "Success" && echo "  Cleared"

# Step 2: Launch and tap "Get Started"
echo "[2/7] Launching Tailscale..."
adb shell am start -n com.tailscale.ipn/.MainActivity 2>/dev/null
sleep 3
tap_element "Get Started"
sleep 2
echo "  Past welcome screen"

# Step 3: Navigate to alternate server setting
# Gear → Accounts → Menu → "Use an alternate server"
echo "[3/7] Setting alternate server..."
tap_element "Open settings"
sleep 2
tap_element "Accounts"
sleep 2
tap_element "menu"
sleep 2
tap_element "Use an alternate server"
sleep 2

# Step 4: Enter server URL
echo "[4/7] Entering server URL..."
adb shell uiautomator dump /sdcard/ui.xml 2>/dev/null
# Tap the EditText field
EDIT_BOUNDS=$(adb shell cat /sdcard/ui.xml 2>/dev/null | python3 -c "
import sys, xml.etree.ElementTree as ET
tree = ET.parse(sys.stdin)
for node in tree.iter('node'):
    cls = node.get('class', '')
    if 'EditText' in cls:
        print(node.get('bounds', ''))
        break
" 2>/dev/null)
if [ -n "$EDIT_BOUNDS" ]; then
  COORDS=$(python3 -c "
import re
m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', '$EDIT_BOUNDS')
if m:
    x1,y1,x2,y2 = map(int, m.groups())
    print(f'{(x1+x2)//2} {(y1+y2)//2}')
" 2>/dev/null)
  [ -n "$COORDS" ] && adb shell input tap $COORDS
  sleep 1
fi
adb shell input text "$HEADSCALE_URL"
sleep 1
echo "  URL entered: $HEADSCALE_URL"

# Step 5: Tap "Add account" (sets server, opens browser — expected)
echo "[5/7] Setting server (browser will open briefly)..."
tap_element "Add account"
sleep 3
# Close browser
adb shell input keyevent 4
sleep 1
adb shell input keyevent 4
sleep 1
echo "  Server configured"

# Step 6: Navigate back to auth key entry
echo "[6/7] Entering pre-auth key..."
adb shell am start -n com.tailscale.ipn/.MainActivity 2>/dev/null
sleep 3
tap_element "Open settings"
sleep 2
tap_element "Accounts"
sleep 2
tap_element "menu"
sleep 2
tap_element "Use an auth key"
sleep 2

# Find and tap the EditText field (position changes based on screen content)
adb shell uiautomator dump /sdcard/ui.xml 2>/dev/null
EDIT_BOUNDS=$(adb shell cat /sdcard/ui.xml 2>/dev/null | python3 -c "
import sys, xml.etree.ElementTree as ET
tree = ET.parse(sys.stdin)
for node in tree.iter('node'):
    cls = node.get('class', '')
    if 'EditText' in cls:
        print(node.get('bounds', ''))
        break
" 2>/dev/null)
if [ -n "$EDIT_BOUNDS" ]; then
  COORDS=$(python3 -c "
import re
m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', '$EDIT_BOUNDS')
if m:
    x1,y1,x2,y2 = map(int, m.groups())
    print(f'{(x1+x2)//2} {(y1+y2)//2}')
" 2>/dev/null)
  [ -n "$COORDS" ] && adb shell input tap $COORDS
  sleep 1
fi
adb shell input text "$PREAUTH_KEY"
sleep 1
echo "  Key entered"

# Step 7: Submit — dynamic button position (shifts after key text fills field)
echo "[7/7] Submitting enrollment..."
# Re-dump UI to get current "Add account" button position
tap_element "Add account"
sleep 10

# Verify enrollment
echo ""
echo "--- Verification ---"

# Check Headscale for new node connection (look for recent "Node connected" entries)
LATEST_LOG=$(ssh unraid "docker logs headscale --since 2m 2>&1" || true)
NEW_NODE_ID=$(echo "$LATEST_LOG" | grep -oP 'Node connected node\.id=\K[0-9]+' | tail -1 || true)

if [ -n "$NEW_NODE_ID" ]; then
  echo "ENROLLMENT: SUCCESS (node.id=$NEW_NODE_ID)"

  # Get the assigned VPN IP
  NODE_IP=$(ssh unraid "docker exec headscale headscale nodes list 2>/dev/null" | grep "^\s*${NEW_NODE_ID}\b" | grep -oP '100\.64\.[0-9]+\.[0-9]+' | head -1 || true)
  [ -n "$NODE_IP" ] && echo "  VPN IP: $NODE_IP"

  # Return phone to home screen
  adb shell am force-stop com.tailscale.ipn 2>/dev/null
  adb shell input keyevent 3  # HOME
  echo "  Phone returned to home screen"
else
  echo "ENROLLMENT: NOT CONFIRMED — check Headscale logs"
  echo "  ssh unraid \"docker logs headscale --tail 20\""
fi
