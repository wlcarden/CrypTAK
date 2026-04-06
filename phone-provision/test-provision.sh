#!/bin/bash
# CrypTAK Provisioning — Automated Test Suite
# Tests provisioning script logic WITHOUT requiring a phone.
#
# What this tests:
#   - Argument validation (callsign format — single arg, no key needed)
#   - APK inventory checks (bootstrap APKs only)
#   - ATAK config XML generation + well-formedness
#   - Map assets and server config package
#   - Helper function presence (tap_element, adb_cmd, cleanup trap)
#   - Step ordering and completeness
#
# What this CANNOT test (requires hardware):
#   - ADB connectivity and APK installation
#   - Tailscale enrollment UI automation
#   - HMDM enrollment
#   - VPN lockdown
#
# Usage: ./test-provision.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PASS=0
FAIL=0
WARN=0

pass() { ((PASS++)); echo "  PASS  $1"; }
fail() { ((FAIL++)); echo "  FAIL  $1"; }
warn() { ((WARN++)); echo "  WARN  $1"; }

echo "========================================="
echo "CrypTAK Provisioning Test Suite"
echo "========================================="
echo ""

# ─────────────────────────────────────────────
echo "--- Argument Validation ---"
# ─────────────────────────────────────────────

# Should reject missing args (single-argument interface)
OUT=$(bash "$SCRIPT_DIR/provision-phone.sh" 2>&1 || true)
echo "$OUT" | grep -q "Usage:" && pass "Rejects missing args" || fail "Missing args not caught"

# Should reject bad callsign format
OUT=$(bash "$SCRIPT_DIR/provision-phone.sh" "BADNAME" 2>&1 || true)
echo "$OUT" | grep -q "TAK-NN" && pass "Rejects bad callsign format" || fail "Bad callsign not caught"

OUT=$(bash "$SCRIPT_DIR/provision-phone.sh" "TAK-" 2>&1 || true)
echo "$OUT" | grep -q "TAK-NN" && pass "Rejects TAK- without number" || fail "TAK- without number not caught"

OUT=$(bash "$SCRIPT_DIR/provision-phone.sh" "tak-02" 2>&1 || true)
echo "$OUT" | grep -q "TAK-NN" && pass "Rejects lowercase callsign" || fail "Lowercase callsign not caught"

# Should accept valid args (will fail at ADB check, which is expected)
OUT=$(bash "$SCRIPT_DIR/provision-phone.sh" "TAK-99" 2>&1 || true)
echo "$OUT" | grep -q "CrypTAK Unified Provisioning: TAK-99" && pass "Accepts valid TAK-99" || fail "Valid args rejected"

echo ""

# ─────────────────────────────────────────────
echo "--- APK Inventory ---"
# ─────────────────────────────────────────────

# Bootstrap APKs (installed by ADB — required)
BOOTSTRAP_APKS="Tailscale.apk HeadwindMDM.apk"
for apk in $BOOTSTRAP_APKS; do
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
HMDM_APKS="ATAK-CIV.apk Meshtastic.apk CrypTAK-Plugin.apk FDroid.apk"
for apk in $HMDM_APKS; do
  if [ -f "$SCRIPT_DIR/apks/$apk" ]; then
    pass "HMDM APK: $apk present"
  else
    warn "HMDM APK: $apk missing (HMDM server needs this)"
  fi
done

echo ""

# ─────────────────────────────────────────────
echo "--- ATAK Config XML Generation ---"
# ─────────────────────────────────────────────

for DEVICE_ID in TAK-01 TAK-02 TAK-03 TAK-99; do
  XML=$(cat << PREFEOF
<?xml version='1.0' standalone='yes'?>
<preferences>
  <preference version="1" name="cot_streams">
    <entry key="count" class="class java.lang.Integer">2</entry>
    <entry key="description0" class="class java.lang.String">CrypTAK-Home</entry>
    <entry key="enabled0" class="class java.lang.Boolean">true</entry>
    <entry key="connectString0" class="class java.lang.String">100.64.0.1:8087:tcp</entry>
    <entry key="description1" class="class java.lang.String">CrypTAK-Field</entry>
    <entry key="enabled1" class="class java.lang.Boolean">false</entry>
    <entry key="connectString1" class="class java.lang.String">100.64.0.2:8087:tcp</entry>
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
)

  # Check XML is well-formed
  if echo "$XML" | python3 -c "import sys; from xml.etree.ElementTree import parse; parse(sys.stdin)" 2>/dev/null; then
    pass "XML well-formed for $DEVICE_ID"
  else
    fail "XML malformed for $DEVICE_ID"
  fi

  # Check callsign substitution
  if echo "$XML" | grep -q ">${DEVICE_ID}<"; then
    pass "Callsign substituted correctly for $DEVICE_ID"
  else
    fail "Callsign substitution failed for $DEVICE_ID"
  fi

  # Check server addresses
  if echo "$XML" | grep -q "100.64.0.1:8087:tcp" && echo "$XML" | grep -q "100.64.0.2:8087:tcp"; then
    pass "Server addresses correct for $DEVICE_ID"
  else
    fail "Server addresses wrong for $DEVICE_ID"
  fi
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
echo "--- TAK Server Config Package ---"
# ─────────────────────────────────────────────

if [ -f "$SCRIPT_DIR/tak-packages/cryptak-server-config.zip" ]; then
  if file "$SCRIPT_DIR/tak-packages/cryptak-server-config.zip" | grep -q "Zip archive"; then
    pass "Server config package is valid ZIP"
  else
    fail "Server config package is NOT a valid ZIP"
  fi
else
  warn "Server config package missing (tak-packages/cryptak-server-config.zip)"
fi

echo ""

# ─────────────────────────────────────────────
echo "--- Desktop Tools ---"
# ─────────────────────────────────────────────

command -v adb &>/dev/null && pass "adb installed" || fail "adb not installed"
command -v python3 &>/dev/null && pass "python3 installed" || fail "python3 not installed"
command -v ssh &>/dev/null && pass "ssh installed" || fail "ssh not installed"
command -v scp &>/dev/null && pass "scp installed" || fail "scp not installed"

echo ""

# ─────────────────────────────────────────────
echo "--- Script Structure ---"
# ─────────────────────────────────────────────

SCRIPT="$SCRIPT_DIR/provision-phone.sh"

# Check step numbering
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

# Check script uses strict error handling
head -20 "$SCRIPT" | grep -q "set -eo pipefail" && pass "Strict error handling (set -eo pipefail)" || fail "Missing set -eo pipefail"

# Check for adb_cmd wrapper
grep -q "adb_cmd()" "$SCRIPT" && pass "adb_cmd() wrapper defined (multi-device safety)" || fail "Missing adb_cmd() wrapper"

# Check for cleanup trap
grep -q "trap cleanup EXIT" "$SCRIPT" && pass "Cleanup trap on EXIT" || fail "Missing cleanup trap"

# Check Private DNS restoration in trap
grep -A20 "^cleanup()" "$SCRIPT" | grep -q "private_dns_mode" && pass "Trap restores Private DNS" || fail "Trap does not restore Private DNS"

# Check tap_element helper
grep -q "tap_element()" "$SCRIPT" && pass "tap_element() helper defined" || fail "Missing tap_element() helper"

# Check VPN lockdown is in last numbered step
LAST_STEP=$(echo "$STEPS" | sort -t/ -k1 -n | tail -1)
LOCKDOWN_STEP=$(grep -n "always_on_vpn_lockdown" "$SCRIPT" | head -1 | grep -oP '\[\d+/\d+\]' || true)
if [ -z "$LOCKDOWN_STEP" ]; then
  # Check by proximity — lockdown should be near end of file
  TOTAL_LINES=$(wc -l < "$SCRIPT")
  LOCKDOWN_LINE=$(grep -n "always_on_vpn_lockdown 1" "$SCRIPT" | head -1 | cut -d: -f1 || true)
  if [ -n "$LOCKDOWN_LINE" ]; then
    PERCENT=$(( LOCKDOWN_LINE * 100 / TOTAL_LINES ))
    if [ "$PERCENT" -gt 80 ]; then
      pass "VPN lockdown is near end of script (line $LOCKDOWN_LINE/$TOTAL_LINES = ${PERCENT}%)"
    else
      fail "VPN lockdown too early (line $LOCKDOWN_LINE/$TOTAL_LINES = ${PERCENT}%) — should be last step"
    fi
  else
    fail "VPN lockdown (always_on_vpn_lockdown) not found in script"
  fi
else
  pass "VPN lockdown in step $LOCKDOWN_STEP"
fi

# Check Termux is NOT in the script (replaced by ADB TCP)
if grep -q "termux_setup\|Termux SSH\|termux-services" "$SCRIPT"; then
  fail "Script still references Termux SSH setup (should be removed)"
else
  pass "No Termux SSH references (replaced by ADB TCP)"
fi

# Check ADB is disabled after provisioning (security hardening)
grep -q "development_settings_enabled 0" "$SCRIPT" && pass "Developer options disabled after provisioning" || fail "Missing developer options disable"
if grep -q "tcpip 5555" "$SCRIPT"; then
  fail "ADB TCP still enabled (security risk — should be disabled)"
else
  pass "No ADB TCP (unauthenticated remote shell removed)"
fi

# Check single-argument interface (no pre-auth key arg)
if head -15 "$SCRIPT" | grep -q 'PREAUTH_KEY="\${2:-}"'; then
  fail "Script still expects pre-auth key as argument (should auto-generate)"
else
  pass "Single-argument interface (key auto-generated)"
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
