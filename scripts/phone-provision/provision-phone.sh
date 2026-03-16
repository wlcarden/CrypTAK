#!/bin/bash
# CrypTAK Mission Device Provisioning Script
# Usage: ./provision-phone.sh TAK-01 | TAK-02 | TAK-03
set -euo pipefail

DEVICE_ID="${1:-}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK_DIR="$SCRIPT_DIR/apks"

if [ -z "$DEVICE_ID" ]; then
  echo "Usage: $0 TAK-01 | TAK-02 | TAK-03"
  exit 1
fi

echo "========================================="
echo "CrypTAK Device Provisioning: $DEVICE_ID"
echo "========================================="

# --- Check APKs exist ---
MISSING=0
for apk in FDroid.apk ATAK-CIV.apk Tailscale.apk Termux.apk; do
  if [ ! -f "$APK_DIR/$apk" ]; then
    echo "MISSING APK: $APK_DIR/$apk"
    MISSING=1
  fi
done
[ "$MISSING" -eq 0 ] || { echo "Download missing APKs first (see README.md)"; exit 1; }

# --- Check ADB connection ---
echo "Checking ADB connection..."
if ! adb devices | grep -q "device$"; then
  echo "ERROR: No device found via ADB. Enable USB debugging and connect USB-C."
  exit 1
fi
DEVICE_MODEL=$(adb shell getprop ro.product.model 2>/dev/null | tr -d '\r')
echo "Connected: $DEVICE_MODEL"

# --- Install apps ---
echo ""
echo "[1/5] Installing F-Droid..."
if ! adb install -r "$APK_DIR/FDroid.apk"; then
  echo "ERROR: F-Droid install failed"
  exit 1
fi

echo "[2/5] Installing ATAK-CIV..."
if ! adb install -r "$APK_DIR/ATAK-CIV.apk"; then
  echo "ERROR: ATAK-CIV install failed"
  exit 1
fi

# Install Tailscale if APK present
echo "[3/5] Installing Tailscale..."
if [ ! -f "$APK_DIR/Tailscale.apk" ]; then
  echo "ERROR: Tailscale.apk not found at $APK_DIR/Tailscale.apk"
  exit 1
fi
if ! adb install -r "$APK_DIR/Tailscale.apk"; then
  echo "ERROR: Tailscale install failed"
  exit 1
fi

# Configure data policy — WiFi-only for large downloads, cellular only for ops traffic
echo "[4/5] Configuring cellular data policy..."

# Enable Data Saver — blocks background cellular for unlisted apps (covers F-Droid updates, etc.)
if ! adb shell settings put global data_saver_enabled 1; then
  echo "ERROR: Failed to enable Data Saver"
  exit 1
fi

# Whitelist apps that legitimately need cellular: Tailscale (VPN), ATAK (CoT), Termux (SSH)
for PKG in com.tailscale.ipn com.atakmap.app.civ com.termux; do
  PKG_UID=$(adb shell pm list packages -U "$PKG" 2>/dev/null | grep -oP 'uid:\K[0-9]+')
  if [ -n "$PKG_UID" ]; then
    if ! adb shell cmd netpolicy add restrict-background-whitelist "$PKG_UID"; then
      echo "  WARNING: Failed to whitelist $PKG — apply manually"
    else
      echo "  Whitelisted cellular: $PKG (uid $PKG_UID)"
    fi
  else
    echo "  WARNING: $PKG not installed — skipping whitelist (install app first)"
  fi
done

# Explicitly restrict F-Droid from background cellular (belt + suspenders)
FDROID_UID=$(adb shell pm list packages -U org.fdroid.fdroid 2>/dev/null | grep -oP 'uid:\K[0-9]+')
if [ -n "$FDROID_UID" ]; then
  adb shell cmd netpolicy set restrict-background "$FDROID_UID" true
  echo "  Restricted cellular: F-Droid (uid $FDROID_UID)"
fi

echo "  Data Saver enabled — large downloads WiFi-only"

# Push ATAK preferences
echo "[5/6] Pushing ATAK server configuration..."
adb shell mkdir -p /sdcard/atak/tools 2>/dev/null || true
cat > /tmp/atak_servers.pref << PREFEOF
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
if ! adb push /tmp/atak_servers.pref /sdcard/atak/servers.pref; then
  echo "ERROR: Failed to push ATAK server config"
  exit 1
fi
rm /tmp/atak_servers.pref

# Push Termux setup script
echo "[6/6] Pushing Termux SSH setup script..."
cat > /tmp/termux_setup.sh << 'TERMUXEOF'
#!/data/data/com.termux/files/usr/bin/bash
# Run this inside Termux after opening it

pkg update -y
pkg install -y openssh termux-services termux-boot

# Generate SSH key
[ -f ~/.ssh/id_ed25519 ] || ssh-keygen -t ed25519 -f ~/.ssh/id_ed25519 -N ""

# Configure sshd
mkdir -p ~/.ssh
cat > $PREFIX/etc/ssh/sshd_config << 'SSHDEOF'
Port 8022
ListenAddress 0.0.0.0
PubkeyAuthentication yes
PasswordAuthentication no
AuthorizedKeysFile .ssh/authorized_keys
SSHDEOF

# Add desktop's public key
# REPLACE THIS with the actual desktop public key:
DESKTOP_PUBKEY="ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHzLTikGXhZQfdkyXelIvALwZCugO7PH0xaQbh8D9Kig wlcarden@gmail.com"
echo "$DESKTOP_PUBKEY" >> ~/.ssh/authorized_keys
chmod 600 ~/.ssh/authorized_keys

# Enable sshd as persistent service
sv-enable sshd

# Print phone's public key for desktop to add
echo ""
echo "===== THIS PHONE'S PUBLIC KEY (send to admin) ====="
cat ~/.ssh/id_ed25519.pub
echo "==================================================="
echo ""
echo "SSH server running on port 8022"
echo "Connect with: ssh -p 8022 \$(whoami)@<tailscale-ip>"
TERMUXEOF
if ! adb push /tmp/termux_setup.sh /sdcard/termux_setup.sh; then
  echo "ERROR: Failed to push Termux setup script"
  exit 1
fi
chmod +x /tmp/termux_setup.sh
rm /tmp/termux_setup.sh

echo ""
echo "========================================="
# Push ATAK offline maps
echo "[6a] Pushing ATAK offline map sources..."
adb shell mkdir -p /sdcard/atak/imagery
for XML in "$SCRIPT_DIR/atak-maps/"*.xml; do
  [ -f "$XML" ] || continue
  NAME=$(basename "$XML")
  if ! adb push "$XML" "/sdcard/atak/imagery/$NAME" 2>/dev/null; then
    echo "  WARNING: Failed to push $NAME"
  else
    echo "  Pushed map source: $NAME"
  fi
done

echo "[6b] Pushing NoVA offline tile cache..."
if [ -f "$SCRIPT_DIR/atak-maps/nova-streets.sqlite" ]; then
  if ! adb push "$SCRIPT_DIR/atak-maps/nova-streets.sqlite" "/sdcard/atak/imagery/nova-streets.sqlite"; then
    echo "ERROR: Failed to push offline map cache"
    exit 1
  fi
  echo "  NoVA streets map pushed (250MB)"
else
  echo "  WARNING: nova-streets.sqlite not found — skip offline map push"
fi

echo "AUTOMATED STEPS COMPLETE for $DEVICE_ID"
echo "========================================="
echo ""
echo "MANUAL STEPS REMAINING:"
echo ""
echo "1. SYSTEM UPDATES → WiFi only:"
echo "   Settings → System → System Update → Download updates → Wi-Fi only"
echo ""
echo "2. TAILSCALE: Open Tailscale app → 'Log in with auth key'"
echo "   Key for $DEVICE_ID:"
case "$DEVICE_ID" in
  TAK-01) echo "   hskey-auth--Za8CoDgmdob-M5Pziy1Yxxi7z-P1d9w_O9kEuzcgbAr_DDMCde8eLTaKWtkYbkvZFzjNQq8hJ-FW" ;;
  TAK-02) echo "   hskey-auth-10qyNNg4aRQD-OzNUAtUAypNfbkhmR54gM-X-YHaHPqAylDkIUWesYvg_rHx04J54oyV0-75tn8nr" ;;
  TAK-03) echo "   hskey-auth-xF3iS1g1sl8p-W-0SIM_OUvVpxnsWh6wybyezBJcF6F_X191qvA74yDY57PdPjiQdxvpYnGUaXaZ3" ;;
esac
echo ""
echo "3. VPN LOCK: Settings → Network → VPN → Tailscale → Always-on + Block without VPN"
echo ""
echo "4. TERMUX: Open Termux → run: bash /sdcard/termux_setup.sh"
echo ""
echo "5. ATAK: Open ATAK → Server preferences should be pre-loaded"
echo "   Verify callsign = $DEVICE_ID, team = Cyan"
echo ""
echo "6. SECURITY: Settings → Developer Options → USB Debugging → OFF"
echo ""
