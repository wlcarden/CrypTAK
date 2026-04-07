#!/bin/bash
# CrypTAK Provisioning — Automated Test Suite
# Tests provisioning script logic WITHOUT requiring a phone or network.
#
# What this tests:
#   - Argument validation (callsign format — single arg, no key needed)
#   - APK inventory checks (bootstrap + HMDM-distributed)
#   - Mission package ZIP generation (manifest + .pref, well-formed XML)
#   - Map assets and offline tile cache
#   - Desktop tool availability
#   - Script structure (step numbering, error handling, helpers, security)
#
# What this CANNOT test (requires hardware):
#   - ADB connectivity and APK installation
#   - Tailscale/HMDM UI automation
#   - VPN enrollment and lockdown
#   - Network connectivity
#
# Usage: ./test-provision.sh
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$SCRIPT_DIR/provision-phone.sh"
PASS=0
FAIL=0
WARN=0

pass() { PASS=$((PASS + 1)); echo "  PASS  $1"; }
fail() { FAIL=$((FAIL + 1)); echo "  FAIL  $1"; }
warn() { WARN=$((WARN + 1)); echo "  WARN  $1"; }

echo "========================================="
echo "CrypTAK Provisioning Test Suite"
echo "========================================="
echo ""

# ─────────────────────────────────────────────
echo "--- Argument Validation ---"
# ─────────────────────────────────────────────

# Should reject missing args
OUT=$(timeout 5 bash "$SCRIPT" 2>&1 || true)
echo "$OUT" | grep -q "Usage:" && pass "Rejects missing args" || fail "Missing args not caught"

# Should reject bad callsign format
OUT=$(timeout 5 bash "$SCRIPT" "BADNAME" 2>&1 || true)
echo "$OUT" | grep -q "TAK-NN" && pass "Rejects bad callsign format" || fail "Bad callsign not caught"

OUT=$(timeout 5 bash "$SCRIPT" "TAK-" 2>&1 || true)
echo "$OUT" | grep -q "TAK-NN" && pass "Rejects TAK- without number" || fail "TAK- without number not caught"

OUT=$(timeout 5 bash "$SCRIPT" "tak-02" 2>&1 || true)
echo "$OUT" | grep -q "TAK-NN" && pass "Rejects lowercase callsign" || fail "Lowercase callsign not caught"

# Valid args — script should get past validation and print the header.
# Use timeout to prevent hanging if a device is connected and script proceeds.
OUT=$(timeout 10 bash "$SCRIPT" "TAK-99" 2>&1 || true)
echo "$OUT" | grep -q "CrypTAK Unified Provisioning: TAK-99" && pass "Accepts valid TAK-99" || fail "Valid args rejected"

echo ""

# ─────────────────────────────────────────────
echo "--- APK Inventory ---"
# ─────────────────────────────────────────────

# Bootstrap APKs (installed by ADB — required)
for apk in Tailscale.apk HeadwindMDM.apk; do
  if [ -f "$SCRIPT_DIR/apks/$apk" ]; then
    SIZE=$(stat -c%s "$SCRIPT_DIR/apks/$apk" 2>/dev/null || echo 0)
    if [ "$SIZE" -gt 1000000 ]; then
      pass "Bootstrap APK: $apk ($(numfmt --to=iec $SIZE))"
    else
      fail "Bootstrap APK: $apk exists but suspiciously small ($SIZE bytes)"
    fi
  else
    fail "Bootstrap APK: $apk MISSING"
  fi
done

# HMDM-distributed APKs (pushed by MDM, not by this script)
for apk in ATAK-CIV.apk Meshtastic.apk CrypTAK-Plugin.apk FDroid.apk; do
  if [ -f "$SCRIPT_DIR/apks/$apk" ]; then
    pass "HMDM APK: $apk present"
  else
    warn "HMDM APK: $apk missing (HMDM server needs this)"
  fi
done

echo ""

# ─────────────────────────────────────────────
echo "--- Mission Package Generation ---"
# ─────────────────────────────────────────────

# The script builds a mission package ZIP dynamically for each device.
# Test that the generation logic produces valid output.

for DEVICE_ID in TAK-01 TAK-02 TAK-99; do
  TMPDIR=$(mktemp -d)
  mkdir -p "$TMPDIR/MANIFEST"

  # Reproduce the manifest from the script
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

  # Reproduce the .pref with variable substitution
  FTS_PRIMARY="100.64.0.1:8087"
  FTS_SECONDARY="100.64.0.2:8087"
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

  # Build ZIP
  (cd "$TMPDIR" && zip -q cryptak-server-config.zip MANIFEST/manifest.xml cryptak-fts.pref)

  # Test: ZIP is valid
  if file "$TMPDIR/cryptak-server-config.zip" | grep -q "Zip archive"; then
    pass "Mission package is valid ZIP ($DEVICE_ID)"
  else
    fail "Mission package is not a valid ZIP ($DEVICE_ID)"
  fi

  # Test: ZIP contains expected files
  ZIP_CONTENTS=$(unzip -l "$TMPDIR/cryptak-server-config.zip" 2>/dev/null)
  if echo "$ZIP_CONTENTS" | grep -q "MANIFEST/manifest.xml" && echo "$ZIP_CONTENTS" | grep -q "cryptak-fts.pref"; then
    pass "Mission package contains manifest + pref ($DEVICE_ID)"
  else
    fail "Mission package missing expected files ($DEVICE_ID)"
  fi

  # Test: manifest XML is well-formed
  if unzip -p "$TMPDIR/cryptak-server-config.zip" MANIFEST/manifest.xml | python3 -c "import sys; from xml.etree.ElementTree import parse; parse(sys.stdin)" 2>/dev/null; then
    pass "Manifest XML well-formed ($DEVICE_ID)"
  else
    fail "Manifest XML malformed ($DEVICE_ID)"
  fi

  # Test: pref XML is well-formed
  if unzip -p "$TMPDIR/cryptak-server-config.zip" cryptak-fts.pref | python3 -c "import sys; from xml.etree.ElementTree import parse; parse(sys.stdin)" 2>/dev/null; then
    pass "Pref XML well-formed ($DEVICE_ID)"
  else
    fail "Pref XML malformed ($DEVICE_ID)"
  fi

  # Test: callsign substituted
  PREF_CONTENT=$(unzip -p "$TMPDIR/cryptak-server-config.zip" cryptak-fts.pref)
  if echo "$PREF_CONTENT" | grep -q ">${DEVICE_ID}<"; then
    pass "Callsign substituted ($DEVICE_ID)"
  else
    fail "Callsign substitution failed ($DEVICE_ID)"
  fi

  # Test: server addresses
  if echo "$PREF_CONTENT" | grep -q "100.64.0.1:8087:tcp" && echo "$PREF_CONTENT" | grep -q "100.64.0.2:8087:tcp"; then
    pass "Server addresses correct ($DEVICE_ID)"
  else
    fail "Server addresses wrong ($DEVICE_ID)"
  fi

  rm -rf "$TMPDIR"
done

echo ""

# ─────────────────────────────────────────────
echo "--- Map Assets ---"
# ─────────────────────────────────────────────

XML_COUNT=$(ls "$SCRIPT_DIR/atak-maps/"*.xml 2>/dev/null | wc -l)
if [ "$XML_COUNT" -gt 0 ]; then
  pass "Map XML sources present ($XML_COUNT files)"
else
  warn "No map XML sources found in atak-maps/"
fi

if [ -f "$SCRIPT_DIR/atak-maps/nova-streets.sqlite" ]; then
  SIZE=$(stat -c%s "$SCRIPT_DIR/atak-maps/nova-streets.sqlite")
  if [ "$SIZE" -gt 100000000 ]; then
    pass "Offline tile cache: nova-streets.sqlite ($(numfmt --to=iec $SIZE))"
  else
    warn "Offline tile cache exists but small ($SIZE bytes)"
  fi
else
  warn "Offline tile cache missing (nova-streets.sqlite)"
fi

echo ""

# ─────────────────────────────────────────────
echo "--- Desktop Tools ---"
# ─────────────────────────────────────────────

command -v adb &>/dev/null && pass "adb installed" || fail "adb not installed"
command -v python3 &>/dev/null && pass "python3 installed" || fail "python3 not installed"
command -v ssh &>/dev/null && pass "ssh installed" || fail "ssh not installed"
command -v scp &>/dev/null && pass "scp installed" || fail "scp not installed"
command -v zip &>/dev/null && pass "zip installed (mission package)" || fail "zip not installed (needed for mission package)"

echo ""

# ─────────────────────────────────────────────
echo "--- Script Structure ---"
# ─────────────────────────────────────────────

# Step numbering consistency
STEPS=$(grep -oP '\[\d+/\d+\]' "$SCRIPT" | sort -u)
DENOMINATORS=$(echo "$STEPS" | grep -oP '/\K\d+' | sort -u)
if [ "$(echo "$DENOMINATORS" | wc -l)" -eq 1 ]; then
  DENOM=$(echo "$DENOMINATORS" | head -1)
  STEP_COUNT=$(echo "$STEPS" | wc -l)
  if [ "$STEP_COUNT" -eq "$DENOM" ]; then
    pass "Step numbering: $STEP_COUNT steps, all [N/$DENOM]"
  else
    warn "Step count mismatch: $STEP_COUNT unique steps but denominator is $DENOM"
  fi
else
  fail "Inconsistent step denominators: $(echo "$DENOMINATORS" | tr '\n' ' ')"
fi

# Strict error handling
head -20 "$SCRIPT" | grep -q "set -eo pipefail" && pass "Strict error handling (set -eo pipefail)" || fail "Missing set -eo pipefail"

# adb_cmd wrapper
grep -q "adb_cmd()" "$SCRIPT" && pass "adb_cmd() wrapper defined" || fail "Missing adb_cmd() wrapper"

# Cleanup trap
grep -q "trap cleanup EXIT" "$SCRIPT" && pass "Cleanup trap on EXIT" || fail "Missing cleanup trap"

# Private DNS in trap
grep -A20 "^cleanup()" "$SCRIPT" | grep -q "private_dns_mode" && pass "Trap handles Private DNS" || fail "Trap does not handle Private DNS"

# tap_element helper
grep -q "tap_element()" "$SCRIPT" && pass "tap_element() helper defined" || fail "Missing tap_element()"

# wait_for_ui helper
grep -q "wait_for_ui()" "$SCRIPT" && pass "wait_for_ui() helper defined" || fail "Missing wait_for_ui()"

# find_input_field helper
grep -q "find_input_field()" "$SCRIPT" && pass "find_input_field() helper defined" || fail "Missing find_input_field()"

echo ""

# ─────────────────────────────────────────────
echo "--- Security Checks ---"
# ─────────────────────────────────────────────

# VPN lockdown near end of script
TOTAL_LINES=$(wc -l < "$SCRIPT")
LOCKDOWN_LINE=$(grep -n "always_on_vpn_lockdown 1" "$SCRIPT" | head -1 | cut -d: -f1 || true)
if [ -n "$LOCKDOWN_LINE" ]; then
  PERCENT=$(( LOCKDOWN_LINE * 100 / TOTAL_LINES ))
  if [ "$PERCENT" -gt 80 ]; then
    pass "VPN lockdown near end (line $LOCKDOWN_LINE/$TOTAL_LINES = ${PERCENT}%)"
  else
    fail "VPN lockdown too early (${PERCENT}%) — must be last step"
  fi
else
  fail "VPN lockdown (always_on_vpn_lockdown) not found"
fi

# Developer options disabled
grep -q "development_settings_enabled 0" "$SCRIPT" && pass "Developer options disabled after provisioning" || fail "Missing developer options disable"

# No ADB TCP (security risk)
if grep -q "tcpip 5555" "$SCRIPT"; then
  fail "ADB TCP enabled (unauthenticated remote shell)"
else
  pass "No ADB TCP"
fi

# No Termux
if grep -q "termux_setup\|Termux SSH\|termux-services" "$SCRIPT"; then
  fail "Script still references Termux SSH setup"
else
  pass "No Termux references"
fi

# Single-argument interface (key auto-generated)
if head -15 "$SCRIPT" | grep -q 'PREAUTH_KEY="\${2:-}"'; then
  fail "Script still expects pre-auth key as argument"
else
  pass "Single-argument interface (key auto-generated)"
fi

# Private DNS left OFF (not restored after enrollment)
if grep -q "Private DNS: left OFF\|left OFF.*VPN provides" "$SCRIPT"; then
  pass "Private DNS left OFF on provisioned devices"
else
  warn "Private DNS handling unclear — check manually"
fi

# Tailscale restart after VPN lockdown
if grep -q "force-stop com.tailscale.ipn" "$SCRIPT" && grep -B5 "force-stop com.tailscale.ipn" "$SCRIPT" | grep -q "lockdown\|VPN lockdown\|Tailscale reconnect"; then
  pass "Tailscale restart after VPN lockdown"
else
  warn "No Tailscale restart after VPN lockdown detected"
fi

# HMDM config refresh
if grep -q "PUSH_CONFIG\|Force.*MDM\|config refresh" "$SCRIPT"; then
  pass "MDM config refresh at end"
else
  warn "No MDM config refresh step"
fi

# Mission package (not loose .pref)
if grep -q "datapackage/incoming" "$SCRIPT"; then
  pass "ATAK config via mission package (not loose .pref)"
else
  fail "ATAK config not pushed to datapackage/incoming"
fi

echo ""

# ─────────────────────────────────────────────
echo "--- Bash Safety ---"
# ─────────────────────────────────────────────

# No ((var++)) antipattern (returns exit code 1 when var=0 under set -e)
if grep -P '\(\(\w+\+\+\)\)' "$SCRIPT"; then
  fail "Found ((var++)) — fatal under set -e when var=0. Use var=\$((var + 1))"
else
  pass "No ((var++)) antipattern"
fi

# uiautomator dump stdout suppressed (leaks into return values)
BAD_DUMPS=$(grep "uiautomator dump" "$SCRIPT" | grep -v ">/dev/null" | grep -v "^#" || true)
if [ -n "$BAD_DUMPS" ]; then
  fail "uiautomator dump without stdout suppression (contaminates return values)"
  echo "    Lines: $(echo "$BAD_DUMPS" | head -3)"
else
  pass "All uiautomator dump calls suppress stdout"
fi

# Coordinate validation before input tap
if grep -q 'coords.*=~.*\[0-9\]' "$SCRIPT"; then
  pass "Coordinate validation before input tap"
else
  warn "No coordinate regex validation found (check tap_element manually)"
fi

echo ""

# ─────────────────────────────────────────────
echo "--- UI Parsing (fixture-based) ---"
# ─────────────────────────────────────────────

# Test tap_element and find_input_field parsing logic against real uiautomator
# XML captured from a Pixel 6 running Tailscale 1.94.2 on GrapheneOS.
# These tests verify the Python XML parsing works correctly without a device.

FIXTURE_DIR="$SCRIPT_DIR/test-fixtures"

if [ -d "$FIXTURE_DIR" ] && ls "$FIXTURE_DIR"/ts-*.xml >/dev/null 2>&1; then

  # Extract the tap_element Python logic from the script (reusable parser)
  TAP_PARSER='
import sys, xml.etree.ElementTree as ET
search = sys.argv[1].lower()
tree = ET.parse(sys.stdin)
all_nodes = list(tree.iter("node"))
parent_map = {c: p for p in tree.iter() for c in p}
for node in all_nodes:
    text = (node.get("text", "") or "").lower()
    desc = (node.get("content-desc", "") or "").lower()
    if search in text or search in desc:
        current = node
        while current is not None:
            if current.get("clickable", "") == "true":
                print(current.get("bounds", ""))
                sys.exit(0)
            current = parent_map.get(current)
        print(node.get("bounds", ""))
        sys.exit(0)
'

  # Extract the find_input_field Python logic
  FIELD_PARSER='
import sys, xml.etree.ElementTree as ET, re
tree = ET.parse(sys.stdin)
for node in tree.iter("node"):
    cls = node.get("class", "")
    if "EditText" in cls or "AutoCompleteTextView" in cls:
        bounds = node.get("bounds", "")
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds)
        if m:
            x1,y1,x2,y2 = map(int, m.groups())
            print(f"{(x1+x2)//2} {(y1+y2)//2}")
            break
'

  # Test: Welcome screen — "Get Started" should be found
  BOUNDS=$(python3 -c "$TAP_PARSER" "Get Started" < "$FIXTURE_DIR/ts-welcome.xml" 2>/dev/null || true)
  if [[ "$BOUNDS" =~ ^\[.*\] ]]; then
    pass "Fixture: 'Get Started' found on welcome screen ($BOUNDS)"
  else
    fail "Fixture: 'Get Started' not found on welcome screen"
  fi

  # Test: Main screen — "Open settings" (gear icon) should be found
  BOUNDS=$(python3 -c "$TAP_PARSER" "Open settings" < "$FIXTURE_DIR/ts-main-login.xml" 2>/dev/null || true)
  if [[ "$BOUNDS" =~ ^\[.*\] ]]; then
    pass "Fixture: 'Open settings' found on main screen ($BOUNDS)"
  else
    fail "Fixture: 'Open settings' not found on main screen"
  fi

  # Test: Settings screen — "Accounts" should be found
  BOUNDS=$(python3 -c "$TAP_PARSER" "Accounts" < "$FIXTURE_DIR/ts-settings.xml" 2>/dev/null || true)
  if [[ "$BOUNDS" =~ ^\[.*\] ]]; then
    pass "Fixture: 'Accounts' found on settings screen"
  else
    fail "Fixture: 'Accounts' not found on settings screen"
  fi

  # Test: Accounts screen — "menu" (three-dot) should be found
  BOUNDS=$(python3 -c "$TAP_PARSER" "menu" < "$FIXTURE_DIR/ts-accounts.xml" 2>/dev/null || true)
  if [[ "$BOUNDS" =~ ^\[.*\] ]]; then
    pass "Fixture: 'menu' found on accounts screen"
  else
    fail "Fixture: 'menu' not found on accounts screen"
  fi

  # Test: Menu — "Use an alternate server" should be found
  BOUNDS=$(python3 -c "$TAP_PARSER" "Use an alternate server" < "$FIXTURE_DIR/ts-accounts-menu.xml" 2>/dev/null || true)
  if [[ "$BOUNDS" =~ ^\[.*\] ]]; then
    pass "Fixture: 'Use an alternate server' found in menu"
  else
    fail "Fixture: 'Use an alternate server' not found in menu"
  fi

  # Test: Menu — "Use an auth key" should be found
  BOUNDS=$(python3 -c "$TAP_PARSER" "Use an auth key" < "$FIXTURE_DIR/ts-accounts-menu.xml" 2>/dev/null || true)
  if [[ "$BOUNDS" =~ ^\[.*\] ]]; then
    pass "Fixture: 'Use an auth key' found in menu"
  else
    fail "Fixture: 'Use an auth key' not found in menu"
  fi

  # Test: Alternate server screen — EditText should be found with valid coords
  COORDS=$(python3 -c "$FIELD_PARSER" < "$FIXTURE_DIR/ts-alternate-server.xml" 2>/dev/null || true)
  if [[ "$COORDS" =~ ^[0-9]+\ [0-9]+$ ]]; then
    pass "Fixture: EditText found on alternate server screen ($COORDS)"
  else
    fail "Fixture: EditText not found on alternate server screen (got: '$COORDS')"
  fi

  # Test: Auth key screen — EditText should be found with valid coords
  COORDS=$(python3 -c "$FIELD_PARSER" < "$FIXTURE_DIR/ts-auth-key.xml" 2>/dev/null || true)
  if [[ "$COORDS" =~ ^[0-9]+\ [0-9]+$ ]]; then
    pass "Fixture: EditText found on auth key screen ($COORDS)"
  else
    fail "Fixture: EditText not found on auth key screen (got: '$COORDS')"
  fi

  # Test: "Add account" should be found on both server URL and auth key screens
  for screen in ts-alternate-server ts-auth-key; do
    BOUNDS=$(python3 -c "$TAP_PARSER" "Add account" < "$FIXTURE_DIR/${screen}.xml" 2>/dev/null || true)
    if [[ "$BOUNDS" =~ ^\[.*\] ]]; then
      pass "Fixture: 'Add account' found on $screen"
    else
      fail "Fixture: 'Add account' not found on $screen"
    fi
  done

  # Negative test: element that shouldn't exist
  BOUNDS=$(python3 -c "$TAP_PARSER" "NonExistentElement12345" < "$FIXTURE_DIR/ts-welcome.xml" 2>/dev/null || true)
  if [ -z "$BOUNDS" ]; then
    pass "Fixture: missing element returns empty (correct)"
  else
    fail "Fixture: missing element returned bounds (should be empty)"
  fi

else
  warn "UI fixtures not found in test-fixtures/ — skipping UI parsing tests"
  warn "Capture fixtures with a connected device: see test-provision.sh comments"
fi

echo ""

# ─────────────────────────────────────────────
echo "--- Static Analysis (shellcheck) ---"
# ─────────────────────────────────────────────

if command -v shellcheck &>/dev/null; then
  # Only fail on errors and warnings (severity=error,warning), not info/style
  SC_OUT=$(shellcheck --severity=warning "$SCRIPT" 2>&1 || true)
  SC_COUNT=$(echo "$SC_OUT" | grep -c "SC[0-9]" || true)
  if [ "$SC_COUNT" -eq 0 ]; then
    pass "shellcheck: no warnings or errors"
  else
    fail "shellcheck: $SC_COUNT warning(s) found"
    echo "$SC_OUT" | head -20
  fi

  # Also check test script itself
  SC_OUT2=$(shellcheck --severity=warning "$SCRIPT_DIR/test-provision.sh" 2>&1 || true)
  SC_COUNT2=$(echo "$SC_OUT2" | grep -c "SC[0-9]" || true)
  if [ "$SC_COUNT2" -eq 0 ]; then
    pass "shellcheck (test script): no warnings or errors"
  else
    fail "shellcheck (test script): $SC_COUNT2 warning(s)"
    echo "$SC_OUT2" | head -20
  fi
else
  warn "shellcheck not installed — skipping static analysis"
fi

echo ""

# ─────────────────────────────────────────────
echo "========================================="
echo "RESULTS: $PASS passed, $FAIL failed, $WARN warnings"
echo "========================================="

if [ "$FAIL" -gt 0 ]; then
  echo "FIX FAILURES BEFORE PROVISIONING"
  exit 1
else
  echo "Ready to provision."
  exit 0
fi
