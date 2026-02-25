#!/bin/bash
# Automates one Tailscale OIDC enrollment test cycle via ADB.
# Usage: ./test-tailscale-enroll.sh [authelia_user] [authelia_pass]
#
# Requires: adb connected phone, Tailscale app installed with server URL already set.
# The server URL (http://192.168.50.120:8082) persists across force-stops.

USER="${1:-admin}"
PASS="${2:-changeme}"
PKG="com.tailscale.ipn"

log() { echo "[$(date +%T)] $*"; }

log "Force-stopping Tailscale..."
adb shell am force-stop "$PKG"
sleep 2

log "Launching Tailscale..."
adb shell am start -n "$PKG/.MainActivity" > /dev/null
sleep 4

# Dump UI and find Log in button center
adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
adb pull /sdcard/ui.xml /tmp/tailscale-ui.xml > /dev/null 2>&1

# Extract bounds of the clickable parent of "Log in" text
LOGIN_BOUNDS=$(grep -o 'bounds="[^"]*"[^>]*>[^<]*Log in' /tmp/tailscale-ui.xml \
  | grep -o 'bounds="[^"]*"' | tail -1 | tr -d 'bounds="')

if [ -z "$LOGIN_BOUNDS" ]; then
  log "ERROR: Could not find Log in button in UI. Is app on the login screen?"
  adb shell uiautomator dump --compressed /sdcard/ui.xml > /dev/null 2>&1
  adb pull /sdcard/ui.xml /tmp/tailscale-ui2.xml > /dev/null 2>&1
  grep -o 'text="[^"]*"' /tmp/tailscale-ui2.xml | sort -u
  exit 1
fi

# Parse bounds [x1,y1][x2,y2] → center
X=$(echo "$LOGIN_BOUNDS" | grep -o '\[[0-9]*,[0-9]*\]' | tr -d '[]' | awk -F',' 'NR==1{x1=$1} NR==2{x2=$1; print int((x1+x2)/2)}')
Y=$(echo "$LOGIN_BOUNDS" | grep -o '\[[0-9]*,[0-9]*\]' | tr -d '[]' | awk -F',' 'NR==1{y1=$2} NR==2{y2=$2; print int((y1+y2)/2)}')

log "Tapping Log in at ($X, $Y)..."
adb shell input tap "$X" "$Y"
sleep 5  # wait for browser to open

log "Looking for Authelia login form in Chrome..."
# Switch focus to Chrome (it may have opened in background)
adb shell input keyevent KEYCODE_APP_SWITCH
sleep 1
# Bring Chrome to front by finding its window
CHROME_TASK=$(adb shell dumpsys activity activities 2>/dev/null | grep -o 'com.android.chrome[^ ]*' | head -1)
if [ -n "$CHROME_TASK" ]; then
  adb shell am start -n "com.android.chrome/com.google.android.apps.chrome.Main" > /dev/null 2>&1
  sleep 2
fi

# Dump Chrome UI to find username field
adb shell uiautomator dump /sdcard/chrome-ui.xml > /dev/null 2>&1
adb pull /sdcard/chrome-ui.xml /tmp/chrome-ui.xml > /dev/null 2>&1

# Check if Authelia login page loaded
if grep -q "authelia\|Username\|Password\|thousand-pikes" /tmp/chrome-ui.xml 2>/dev/null; then
  log "Authelia login page detected. Entering credentials..."

  # Find and tap username field
  USER_BOUNDS=$(grep -i 'username\|user.*input\|EditText' /tmp/chrome-ui.xml \
    | grep 'clickable="true"' | grep -o 'bounds="[^"]*"' | head -1 | tr -d 'bounds="')

  if [ -n "$USER_BOUNDS" ]; then
    UX=$(echo "$USER_BOUNDS" | grep -o '\[[0-9]*,[0-9]*\]' | tr -d '[]' | awk -F',' 'NR==1{x1=$1} NR==2{x2=$1; print int((x1+x2)/2)}')
    UY=$(echo "$USER_BOUNDS" | grep -o '\[[0-9]*,[0-9]*\]' | tr -d '[]' | awk -F',' 'NR==1{y1=$2} NR==2{y2=$2; print int((y1+y2)/2)}')
    adb shell input tap "$UX" "$UY"
    sleep 1
    adb shell input text "$USER"
  else
    log "WARN: Could not auto-detect username field. Enter '$USER' manually in the browser."
  fi
else
  log "WARN: Authelia page not detected in Chrome. You may need to switch to the browser manually."
  log "      Enter credentials: $USER / $PASS"
fi

log "Watching headscale for node registration (30s)..."
ssh -i ~/.ssh/id_ed25519 root@192.168.50.120 \
  "docker logs headscale -f 2>&1" &
SSHPID=$!
sleep 30
kill $SSHPID 2>/dev/null

log "Checking enrolled nodes:"
ssh -i ~/.ssh/id_ed25519 root@192.168.50.120 \
  "docker exec headscale headscale nodes list"
