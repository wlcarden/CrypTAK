#!/bin/bash
# CrypTAK Mission Device Provisioning Script
# Usage: ./provision-phone.sh <CALLSIGN> <PREAUTH_KEY>
# Example: ./provision-phone.sh TAK-02 hskey-auth-LBHNqGbNGYBA-...
set -euo pipefail

DEVICE_ID="${1:-}"
PREAUTH_KEY="${2:-}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APK_DIR="$SCRIPT_DIR/apks"

if [ -z "$DEVICE_ID" ] || [ -z "$PREAUTH_KEY" ]; then
  echo "Usage: $0 <CALLSIGN> <PREAUTH_KEY>"
  echo "Example: $0 TAK-02 hskey-auth-LBHNqGbNGYBA-srCQiz4lg_..."
  echo ""
  echo "Generate a key on Unraid:"
  echo "  ssh unraid \"docker exec headscale headscale preauthkeys create --user 1 --expiration 72h\""
  exit 1
fi

if [[ ! "$DEVICE_ID" =~ ^TAK-[0-9]+$ ]]; then
  echo "ERROR: Callsign must match TAK-NN format (e.g. TAK-02)"
  exit 1
fi

if [[ ! "$PREAUTH_KEY" =~ ^hskey-auth- ]]; then
  echo "ERROR: Pre-auth key must start with 'hskey-auth-'"
  exit 1
fi

echo "========================================="
echo "CrypTAK Device Provisioning: $DEVICE_ID"
echo "========================================="

# --- Check APKs exist ---
# Only Tailscale + HMDM are installed via ADB (bootstrap).
# All other apps (ATAK, Meshtastic, Termux, F-Droid, CrypTAK Plugin)
# are pushed by HMDM after enrollment.
MISSING=0
for apk in Tailscale.apk HeadwindMDM.apk; do
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

# --- Pre-flight device check ---
echo ""
echo "--- Pre-flight device check ---"

# Check for stale ATAK database (blocks fresh setup with old passphrase)
if adb shell "ls /sdcard/atak/Databases/" &>/dev/null; then
  echo "  WARNING: Stale ATAK database found — wiping to avoid passphrase lockout"
  adb shell "rm -rf /sdcard/atak/Databases" 2>/dev/null
fi

# Check for pre-installed ATAK with mismatched signatures
INSTALLED_ATAK=$(adb shell pm list packages com.atakmap.app.civ 2>/dev/null | grep -c "package:" || true)
if [ "$INSTALLED_ATAK" -gt 0 ]; then
  echo "  Existing ATAK found — uninstalling to avoid signature conflicts"
  adb shell pm list packages 2>/dev/null | grep "com.atakmap" | sed 's/package://' | while read -r pkg; do
    adb uninstall "$pkg" 2>/dev/null && echo "    Removed: $pkg"
  done
fi

# Ensure Termux has storage access (GrapheneOS requires explicit grant)
echo "  NOTE: After Termux install, run 'termux-setup-storage' on device before executing setup script"

echo "  Pre-flight checks passed"

# --- Install bootstrap apps (Tailscale + HMDM only) ---
# All other apps are pushed by HMDM after enrollment.
echo ""
echo "[1/6] Installing Tailscale..."
if ! adb install -r "$APK_DIR/Tailscale.apk"; then
  echo "ERROR: Tailscale install failed"
  exit 1
fi

echo "[2/6] Installing Headwind MDM..."
if ! adb install -r "$APK_DIR/HeadwindMDM.apk"; then
  echo "ERROR: Headwind MDM install failed"
  exit 1
fi

# Configure data policy — WiFi-only for large downloads, cellular only for ops traffic
echo "[3/6] Configuring cellular data policy..."

# Enable Data Saver — blocks background cellular for unlisted apps (covers F-Droid updates, etc.)
if ! adb shell settings put global data_saver_enabled 1; then
  echo "ERROR: Failed to enable Data Saver"
  exit 1
fi

# Whitelist apps that need cellular (Tailscale now, others after HMDM installs them)
for PKG in com.tailscale.ipn com.atakmap.app.civ com.termux com.geeksville.mesh; do
  PKG_UID=$(adb shell pm list packages -U "$PKG" 2>/dev/null | grep -oP 'uid:\K[0-9]+' || true)
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
FDROID_UID=$(adb shell pm list packages -U org.fdroid.fdroid 2>/dev/null | grep -oP 'uid:\K[0-9]+' || true)
if [ -n "$FDROID_UID" ]; then
  adb shell cmd netpolicy set restrict-background "$FDROID_UID" true
  echo "  Restricted cellular: F-Droid (uid $FDROID_UID)"
fi

echo "  Data Saver enabled — large downloads WiFi-only"

# Push ATAK preferences (pre-staged for when HMDM installs ATAK)
echo "[4/6] Pushing ATAK server configuration..."
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
echo "Pushing Termux SSH setup script..."
cat > /tmp/termux_setup.sh << 'TERMUXEOF'
#!/data/data/com.termux/files/usr/bin/bash
# CrypTAK Termux SSH setup — run inside Termux
# Uses $HOME explicitly (~ may not expand correctly via ADB input)

echo "=== CrypTAK Termux Setup ==="

# Install packages (requires internet — run BEFORE VPN lockdown)
echo "[1/4] Installing packages..."
if ! pkg install -y openssh termux-services 2>&1; then
  echo "WARNING: pkg install failed — check internet connectivity"
  echo "VPN kill-switch may be blocking Termux mirror access"
  echo "Disable VPN lockdown, run this script, then re-enable"
  exit 1
fi

# Generate SSH key
echo "[2/4] Configuring SSH..."
mkdir -p "$HOME/.ssh"
[ -f "$HOME/.ssh/id_ed25519" ] || ssh-keygen -t ed25519 -f "$HOME/.ssh/id_ed25519" -N ""

# Configure sshd
cat > "$PREFIX/etc/ssh/sshd_config" << 'SSHDEOF'
Port 8022
ListenAddress 0.0.0.0
PubkeyAuthentication yes
PasswordAuthentication no
AuthorizedKeysFile .ssh/authorized_keys
SSHDEOF

# Add desktop's public key (idempotent)
echo "[3/4] Adding desktop public key..."
DESKTOP_PUBKEY="ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIHzLTikGXhZQfdkyXelIvALwZCugO7PH0xaQbh8D9Kig wlcarden@gmail.com"
grep -qF "$DESKTOP_PUBKEY" "$HOME/.ssh/authorized_keys" 2>/dev/null || echo "$DESKTOP_PUBKEY" >> "$HOME/.ssh/authorized_keys"
chmod 600 "$HOME/.ssh/authorized_keys"

# Start sshd — direct start first (always works), then sv-enable for persistence
# sv-enable requires a Termux restart after installing termux-services, so it may
# fail on first run. That's OK — sshd is already running from the direct start.
echo "[4/4] Starting SSH server..."
sshd 2>/dev/null && echo "  sshd started" || echo "  WARNING: sshd start failed"
sv-enable sshd 2>/dev/null && echo "  sshd persistence enabled" || echo "  NOTE: sv-enable failed (restart Termux to fix — sshd is running anyway)"

echo ""
echo "===== THIS PHONE'S PUBLIC KEY ====="
cat "$HOME/.ssh/id_ed25519.pub"
echo "===================================="
echo "SSH on port 8022"
echo "=== Setup complete ==="
TERMUXEOF
if ! adb push /tmp/termux_setup.sh /sdcard/termux_setup.sh; then
  echo "ERROR: Failed to push Termux setup script"
  exit 1
fi
rm /tmp/termux_setup.sh

echo ""
echo "========================================="
# Push ATAK offline maps (pre-staged for when HMDM installs ATAK)
echo "[5/6] Pushing ATAK offline map sources..."
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

echo "Pushing NoVA offline tile cache..."
if [ -f "$SCRIPT_DIR/atak-maps/nova-streets.sqlite" ]; then
  if ! adb push "$SCRIPT_DIR/atak-maps/nova-streets.sqlite" "/sdcard/atak/imagery/nova-streets.sqlite"; then
    echo "ERROR: Failed to push offline map cache"
    exit 1
  fi
  echo "  NoVA streets map pushed (250MB)"
else
  echo "  WARNING: nova-streets.sqlite not found — skip offline map push"
fi

# HMDM already installed in step 2

echo "[6/6] Ensuring HMDM wallpaper is on server..."
if [ -f "$SCRIPT_DIR/cryptak-wallpaper.png" ]; then
  scp "$SCRIPT_DIR/cryptak-wallpaper.png" unraid:/mnt/user/appdata/tak-server/mdm/volumes/files/cryptak-wallpaper.png 2>/dev/null && \
    echo "  Wallpaper uploaded to HMDM server" || \
    echo "  WARNING: Failed to upload wallpaper (may already exist)"
else
  echo "  WARNING: cryptak-wallpaper.png not found — run generate-wallpaper.py first"
fi

echo "Generating HMDM enrollment QR code..."
HMDM_SERVER="http://100.64.0.1:8095"
QR_PAYLOAD="{\"android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE\":{\"com.hmdm.BASE_URL\":\"${HMDM_SERVER}\",\"com.hmdm.DEVICE_ID\":\"${DEVICE_ID}\"}}"
QR_FILE="/tmp/hmdm_enroll_${DEVICE_ID}.png"
if command -v qrencode &>/dev/null; then
  echo "$QR_PAYLOAD" | qrencode -o "$QR_FILE" -s 10 -m 2
  echo "  QR saved: $QR_FILE"
  echo "  Payload: $QR_PAYLOAD"
else
  echo "  WARNING: qrencode not installed (apt install qrencode)"
  echo "  Manual QR payload: $QR_PAYLOAD"
fi

echo ""
echo "========================================="
echo "BOOTSTRAP COMPLETE for $DEVICE_ID"
echo "========================================="
echo ""
echo "NEXT STEPS (run in order):"
echo ""
echo "1. TAILSCALE ENROLLMENT (automated):"
echo "   Connect phone to cellular/hotspot (NOT home WiFi), then:"
echo "   ./enroll-tailscale.sh $PREAUTH_KEY"
echo ""
echo "2. POST-PROVISIONING (automated):"
echo "   Switch phone to home WiFi, then:"
echo "   ./post-provision.sh $DEVICE_ID"
echo "   (VPN lockdown, Termux SSH, HMDM enrollment → pushes all apps)"
echo ""
echo "3. ATAK FIRST LAUNCH (manual):"
echo "   Open ATAK → set encryption passphrase"
echo "   Verify callsign = $DEVICE_ID, team = Cyan"
echo "   Verify server: CrypTAK-Home (100.64.0.1:8087)"
echo ""
echo "4. SECURITY: Settings → Developer Options → USB Debugging → OFF"
echo ""
