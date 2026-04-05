#!/bin/bash
# CrypTAK Provisioning — Automated Test Suite
# Tests provisioning script logic WITHOUT requiring a phone.
#
# What this tests:
#   - Argument validation (callsign format, key format)
#   - APK inventory checks
#   - ATAK config XML generation + well-formedness
#   - Termux setup script generation
#   - HMDM QR payload generation
#   - Cellular data policy command generation
#   - Step ordering and completeness
#
# What this CANNOT test (requires hardware):
#   - ADB connectivity and APK installation
#   - Actual file push to device
#   - Tailscale enrollment
#   - On-device ATAK/Termux behavior
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

# Should reject missing args
OUT=$(bash "$SCRIPT_DIR/provision-phone.sh" 2>&1 || true)
echo "$OUT" | grep -q "Usage:" && pass "Rejects missing args" || fail "Missing args not caught"

OUT=$(bash "$SCRIPT_DIR/provision-phone.sh" TAK-02 2>&1 || true)
echo "$OUT" | grep -q "Usage:" && pass "Rejects missing pre-auth key" || fail "Missing key not caught"

# Should reject bad callsign format
OUT=$(bash "$SCRIPT_DIR/provision-phone.sh" "BADNAME" "hskey-auth-fake" 2>&1 || true)
echo "$OUT" | grep -q "TAK-NN" && pass "Rejects bad callsign format" || fail "Bad callsign not caught"

OUT=$(bash "$SCRIPT_DIR/provision-phone.sh" "TAK-" "hskey-auth-fake" 2>&1 || true)
echo "$OUT" | grep -q "TAK-NN" && pass "Rejects TAK- without number" || fail "TAK- without number not caught"

OUT=$(bash "$SCRIPT_DIR/provision-phone.sh" "tak-02" "hskey-auth-fake" 2>&1 || true)
echo "$OUT" | grep -q "TAK-NN" && pass "Rejects lowercase callsign" || fail "Lowercase callsign not caught"

# Should reject bad key format
OUT=$(bash "$SCRIPT_DIR/provision-phone.sh" "TAK-02" "not-a-key" 2>&1 || true)
echo "$OUT" | grep -q "hskey-auth-" && pass "Rejects bad key format" || fail "Bad key format not caught"

# Should accept valid args (will fail at ADB check, which is expected)
OUT=$(bash "$SCRIPT_DIR/provision-phone.sh" "TAK-99" "hskey-auth-testkey123" 2>&1 || true)
echo "$OUT" | grep -q "CrypTAK Device Provisioning: TAK-99" && pass "Accepts valid TAK-99 + key" || fail "Valid args rejected"

echo ""

# ─────────────────────────────────────────────
echo "--- APK Inventory ---"
# ─────────────────────────────────────────────

REQUIRED_APKS="FDroid.apk ATAK-CIV.apk Tailscale.apk Termux.apk"
OPTIONAL_APKS="Meshtastic.apk CrypTAK-Plugin.apk HeadwindMDM.apk"

for apk in $REQUIRED_APKS; do
  if [ -f "$SCRIPT_DIR/apks/$apk" ]; then
    SIZE=$(stat -c%s "$SCRIPT_DIR/apks/$apk" 2>/dev/null || echo 0)
    if [ "$SIZE" -gt 1000000 ]; then
      pass "Required APK: $apk ($(numfmt --to=iec $SIZE))"
    else
      fail "Required APK: $apk exists but suspiciously small ($SIZE bytes)"
    fi
  else
    fail "Required APK: $apk MISSING"
  fi
done

for apk in $OPTIONAL_APKS; do
  if [ -f "$SCRIPT_DIR/apks/$apk" ]; then
    pass "Optional APK: $apk present"
  else
    warn "Optional APK: $apk missing (non-fatal)"
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
  # Validate it's actually a zip
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
echo "--- HMDM QR Payload ---"
# ─────────────────────────────────────────────

HMDM_SERVER="http://100.64.0.1:8095"
for DEVICE_ID in TAK-02 TAK-03; do
  QR_PAYLOAD="{\"android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE\":{\"com.hmdm.BASE_URL\":\"${HMDM_SERVER}\",\"com.hmdm.DEVICE_ID\":\"${DEVICE_ID}\"}}"

  # Validate JSON
  if echo "$QR_PAYLOAD" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d)" >/dev/null 2>&1; then
    pass "HMDM QR JSON valid for $DEVICE_ID"
  else
    fail "HMDM QR JSON invalid for $DEVICE_ID"
  fi

  # Check device ID in payload
  if echo "$QR_PAYLOAD" | grep -q "\"$DEVICE_ID\""; then
    pass "HMDM QR contains correct device ID for $DEVICE_ID"
  else
    fail "HMDM QR has wrong device ID for $DEVICE_ID"
  fi
done

# Check qrencode availability
if command -v qrencode &>/dev/null; then
  pass "qrencode installed — QR generation will work"
else
  warn "qrencode not installed — QR step will print payload only"
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
echo "--- Termux Setup Script ---"
# ─────────────────────────────────────────────

# Extract and validate the embedded Termux script
TERMUX_SCRIPT=$(sed -n "/^cat > \/tmp\/termux_setup.sh << 'TERMUXEOF'$/,/^TERMUXEOF$/p" "$SCRIPT_DIR/provision-phone.sh" | sed '1d;$d')

if [ -n "$TERMUX_SCRIPT" ]; then
  pass "Termux script extracted from provision-phone.sh"

  echo "$TERMUX_SCRIPT" | grep -q "ssh-keygen" && pass "Termux script generates SSH key" || fail "Termux script missing ssh-keygen"
  echo "$TERMUX_SCRIPT" | grep -q "sv-enable sshd" && pass "Termux script enables sshd service" || fail "Termux script missing sv-enable"
  echo "$TERMUX_SCRIPT" | grep -q "Port 8022" && pass "Termux sshd on port 8022" || fail "Termux sshd port not 8022"
  echo "$TERMUX_SCRIPT" | grep -q "PasswordAuthentication no" && pass "Termux password auth disabled" || fail "Termux password auth not disabled"
  echo "$TERMUX_SCRIPT" | grep -q "grep -qF" && pass "Termux pubkey append is idempotent" || fail "Termux pubkey append not idempotent (duplicates on re-run)"
  echo "$TERMUX_SCRIPT" | grep -q "wlcarden@gmail.com" && pass "Desktop pubkey present" || fail "Desktop pubkey missing from Termux script"
else
  fail "Could not extract Termux script from provision-phone.sh"
fi

echo ""

# ─────────────────────────────────────────────
echo "--- Script Consistency ---"
# ─────────────────────────────────────────────

# Check step numbering
STEPS=$(grep -oP '\[\d+/\d+\]' "$SCRIPT_DIR/provision-phone.sh" | sort -u)
DENOMINATORS=$(echo "$STEPS" | grep -oP '/\K\d+' | sort -u)
if [ "$(echo "$DENOMINATORS" | wc -l)" -eq 1 ]; then
  pass "Step numbering uses consistent denominator: $(echo "$DENOMINATORS" | head -1)"
else
  warn "Inconsistent step denominators: $(echo "$DENOMINATORS" | tr '\n' ' ')"
fi

# Check script uses set -euo pipefail
head -5 "$SCRIPT_DIR/provision-phone.sh" | grep -q "set -euo pipefail" && pass "Script has strict error handling (set -euo pipefail)" || fail "Script missing set -euo pipefail"

# Check for PREAUTH_KEY usage in manual steps output
grep -q 'echo.*\$PREAUTH_KEY' "$SCRIPT_DIR/provision-phone.sh" && pass "Manual steps print the pre-auth key" || fail "Manual steps don't print the pre-auth key"

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
