#!/usr/bin/env bash
# setup-field-pi.sh — Configure a Raspberry Pi 4 as a CrypTAK field server.
#
# Run on a fresh Pi OS Lite 64-bit (Bookworm) install over SSH.
# Requires internet for package installation.
#
# Usage:
#   scp -r server/ scripts/ pi@<pi-ip>:/tmp/cryptak-setup/
#   ssh pi@<pi-ip>
#   sudo bash /tmp/cryptak-setup/scripts/setup-field-pi.sh
#
# What this does:
#   1. Install system packages (hostapd, dnsmasq, Docker, gpsd)
#   2. Configure udev rules for USB Wi-Fi dongle naming
#   3. Configure hostapd (2.4 GHz AP on RT5370 dongle)
#   4. Configure dnsmasq (DHCP for AP clients)
#   5. Configure network interfaces (static IP on AP, DHCP on WAN)
#   6. Install Tailscale (Headscale VPN client)
#   7. Deploy CrypTAK field server stack (FTS + Mumble + halow-bridge)
#   8. Enable services on boot
#
# After running:
#   - Edit /opt/cryptak/.env.field (change all passwords + set REMOTE_FTS_HOST)
#   - Run: tailscale up --login-server https://vpn.thousand-pikes.com --hostname tak-field
#   - Reboot and verify AP + FTS + Mumble come up

set -euo pipefail

# --- Configuration ---

AP_SSID="${AP_SSID:-CrypTAK-Field}"
AP_PASSPHRASE="${AP_PASSPHRASE:-changeme123}"
AP_IP="192.168.73.1"
AP_DHCP_START="192.168.73.10"
AP_DHCP_END="192.168.73.50"
AP_CHANNEL="${AP_CHANNEL:-6}"
INSTALL_DIR="/opt/cryptak"
HEADSCALE_URL="${HEADSCALE_URL:-https://vpn.thousand-pikes.com}"

# --- Color output ---

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[+]${NC} $*"; }
warn()  { echo -e "${YELLOW}[!]${NC} $*"; }
error() { echo -e "${RED}[x]${NC} $*" >&2; }

# --- Preflight checks ---

if [[ $EUID -ne 0 ]]; then
    error "Must run as root (sudo)"
    exit 1
fi

if ! grep -qi "raspberry\|aarch64" /proc/cpuinfo 2>/dev/null && \
   [[ "$(uname -m)" != "aarch64" ]]; then
    warn "Not running on a Raspberry Pi (detected $(uname -m))."
    warn "Continuing anyway — some hardware steps may fail."
fi

# --- Phase 1: System packages ---

info "Updating package lists..."
apt-get update -qq

info "Installing system packages..."
apt-get install -y -qq \
    hostapd \
    dnsmasq \
    gpsd \
    gpsd-clients \
    iptables \
    jq \
    curl \
    git \
    > /dev/null

# --- Phase 2: Docker ---

if command -v docker &>/dev/null; then
    info "Docker already installed: $(docker --version)"
else
    info "Installing Docker..."
    curl -fsSL https://get.docker.com | sh
    # Add the pi user to docker group (assumes default 'pi' or first user)
    PI_USER=$(getent passwd 1000 | cut -d: -f1)
    if [[ -n "$PI_USER" ]]; then
        usermod -aG docker "$PI_USER"
        info "Added $PI_USER to docker group"
    fi
fi

# --- Phase 3: Tailscale ---

if command -v tailscale &>/dev/null; then
    info "Tailscale already installed: $(tailscale version 2>/dev/null || echo 'unknown')"
else
    info "Installing Tailscale..."
    curl -fsSL https://tailscale.com/install.sh | sh
fi

# --- Phase 4: udev rules for USB dongles ---

UDEV_FILE="/etc/udev/rules.d/70-wifi-dongles.rules"
info "Writing udev rules to $UDEV_FILE"

cat > "$UDEV_FILE" << 'UDEV'
# CrypTAK Field Pi — persistent Wi-Fi dongle naming
#
# RT5370 (AP dongle) → wlan_ap
# MT7601U (WAN dongle) → wlan_wan
#
# If your dongle has different USB IDs, update with: lsusb | grep -i wireless
# Then: udevadm info -a /sys/class/net/wlanX | grep idVendor

# Ralink RT5370
SUBSYSTEM=="net", ACTION=="add", ATTRS{idVendor}=="148f", ATTRS{idProduct}=="5370", NAME="wlan_ap"

# Mediatek MT7601U
SUBSYSTEM=="net", ACTION=="add", ATTRS{idVendor}=="0e8d", ATTRS{idProduct}=="7601", NAME="wlan_wan"
UDEV

udevadm control --reload-rules
info "udev rules loaded (will take effect on next dongle plug/reboot)"

# --- Phase 5: hostapd ---

info "Configuring hostapd..."

cat > /etc/hostapd/hostapd.conf << HOSTAPD
# CrypTAK Field AP — 2.4 GHz on RT5370 dongle
interface=wlan_ap
driver=nl80211
ssid=${AP_SSID}
hw_mode=g
channel=${AP_CHANNEL}
wmm_enabled=0
macaddr_acl=0
auth_algs=1
wpa=2
wpa_passphrase=${AP_PASSPHRASE}
wpa_key_mgmt=WPA-PSK
rsn_pairwise=CCMP
# Max clients — ATAK team size
max_num_sta=15
HOSTAPD

# Point hostapd at our config
if grep -q "^#DAEMON_CONF" /etc/default/hostapd 2>/dev/null; then
    sed -i 's|^#DAEMON_CONF=.*|DAEMON_CONF="/etc/hostapd/hostapd.conf"|' /etc/default/hostapd
elif ! grep -q "DAEMON_CONF" /etc/default/hostapd 2>/dev/null; then
    echo 'DAEMON_CONF="/etc/hostapd/hostapd.conf"' >> /etc/default/hostapd
fi

systemctl unmask hostapd
systemctl enable hostapd

# --- Phase 6: dnsmasq ---

info "Configuring dnsmasq..."

# Prevent dnsmasq from conflicting with systemd-resolved
cat > /etc/dnsmasq.d/cryptak-ap.conf << DNSMASQ
# CrypTAK Field AP — DHCP server for wlan_ap
interface=wlan_ap
bind-interfaces
dhcp-range=${AP_DHCP_START},${AP_DHCP_END},255.255.255.0,12h
# Upstream DNS — use Pi's own resolver for connected clients
server=8.8.8.8
server=1.1.1.1
DNSMASQ

systemctl enable dnsmasq

# --- Phase 7: Network interface config ---

info "Configuring static IP for AP interface..."

# Use dhcpcd (Pi OS default) to set static IP on wlan_ap
DHCPCD_CONF="/etc/dhcpcd.conf"
if ! grep -q "interface wlan_ap" "$DHCPCD_CONF" 2>/dev/null; then
    cat >> "$DHCPCD_CONF" << DHCPCD

# CrypTAK Field AP — static IP
interface wlan_ap
    static ip_address=${AP_IP}/24
    nohook wpa_supplicant
DHCPCD
    info "Added wlan_ap static IP to dhcpcd.conf"
else
    warn "wlan_ap already configured in dhcpcd.conf — skipping"
fi

# --- Phase 8: IP forwarding + NAT ---
# Allow AP clients to reach the internet when WAN dongle is connected

info "Enabling IP forwarding..."
sed -i 's/^#net.ipv4.ip_forward=1/net.ipv4.ip_forward=1/' /etc/sysctl.conf
sysctl -w net.ipv4.ip_forward=1 > /dev/null

# NAT from AP subnet to WAN interface (wlan_wan or eth0)
# Using iptables-persistent would be cleaner, but this works on first boot
IPTABLES_SCRIPT="/etc/network/if-up.d/cryptak-nat"
cat > "$IPTABLES_SCRIPT" << 'NAT'
#!/bin/sh
# CrypTAK NAT — forward AP clients to WAN
# Runs on interface up; safe to run multiple times (flush first)

iptables -t nat -F POSTROUTING 2>/dev/null || true

# NAT to whichever WAN interface is up
for iface in wlan_wan eth0; do
    if ip link show "$iface" up 2>/dev/null | grep -q "state UP"; then
        iptables -t nat -A POSTROUTING -o "$iface" -j MASQUERADE
        break
    fi
done
NAT
chmod +x "$IPTABLES_SCRIPT"

# --- Phase 9: gpsd ---

info "Configuring gpsd..."
cat > /etc/default/gpsd << GPSD
# CrypTAK Field Pi — USB GPS dongle
START_DAEMON="true"
USBAUTO="true"
DEVICES="/dev/ttyACM0"
GPSD_OPTIONS="-n"
GPSD_SOCKET="/var/run/gpsd.sock"
GPSD
systemctl enable gpsd

# --- Phase 10: Deploy CrypTAK field stack ---

info "Deploying CrypTAK field stack to $INSTALL_DIR..."
mkdir -p "$INSTALL_DIR"

# Copy compose file and halow-bridge source
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -f "$SCRIPT_DIR/server/docker-compose.field.yml" ]]; then
    cp "$SCRIPT_DIR/server/docker-compose.field.yml" "$INSTALL_DIR/docker-compose.yml"
    cp -r "$SCRIPT_DIR/server/halow-bridge" "$INSTALL_DIR/halow-bridge"
    if [[ -f "$SCRIPT_DIR/server/.env.field.example" ]]; then
        cp "$SCRIPT_DIR/server/.env.field.example" "$INSTALL_DIR/.env.field.example"
    fi
    info "Copied compose file + halow-bridge to $INSTALL_DIR"
else
    warn "Could not find server/docker-compose.field.yml relative to script."
    warn "Copy files manually to $INSTALL_DIR"
fi

# Create .env.field if it doesn't exist
if [[ ! -f "$INSTALL_DIR/.env.field" ]]; then
    if [[ -f "$INSTALL_DIR/.env.field.example" ]]; then
        cp "$INSTALL_DIR/.env.field.example" "$INSTALL_DIR/.env.field"
        warn "Created $INSTALL_DIR/.env.field from example — EDIT THIS before starting!"
    fi
fi

# Pull Docker images (build halow-bridge locally)
info "Pulling Docker images (this may take a few minutes on Pi)..."
cd "$INSTALL_DIR"
if [[ -f docker-compose.yml ]]; then
    docker compose --env-file .env.field pull freetakserver mumble 2>/dev/null || \
        warn "Docker pull failed — will retry on first 'docker compose up'"
    if [[ -d halow-bridge ]]; then
        docker compose --env-file .env.field build halow-bridge 2>/dev/null || \
            warn "halow-bridge build failed — will retry on first 'docker compose up'"
    fi
fi

# --- Phase 11: Systemd service for field stack ---

info "Creating systemd service for field stack..."
cat > /etc/systemd/system/cryptak-field.service << SYSTEMD
[Unit]
Description=CrypTAK Field Server (FTS + Mumble + HaLow Bridge)
After=docker.service network-online.target
Requires=docker.service
Wants=network-online.target

[Service]
Type=oneshot
RemainAfterExit=yes
WorkingDirectory=${INSTALL_DIR}
ExecStart=/usr/bin/docker compose --env-file .env.field up -d
ExecStop=/usr/bin/docker compose --env-file .env.field down
TimeoutStartSec=120

[Install]
WantedBy=multi-user.target
SYSTEMD

systemctl daemon-reload
systemctl enable cryptak-field.service
info "cryptak-field.service enabled (starts on boot)"

# --- Done ---

echo ""
echo "============================================"
info "CrypTAK Field Pi setup complete!"
echo "============================================"
echo ""
echo "Remaining manual steps:"
echo ""
echo "  1. Edit passwords and remote FTS host:"
echo "     nano $INSTALL_DIR/.env.field"
echo ""
echo "  2. Change AP passphrase (currently: $AP_PASSPHRASE):"
echo "     nano /etc/hostapd/hostapd.conf"
echo ""
echo "  3. Enroll in Headscale VPN:"
echo "     sudo tailscale up --login-server $HEADSCALE_URL --hostname tak-field"
echo ""
echo "  4. Verify USB dongle IDs match udev rules:"
echo "     lsusb  (check vendor:product IDs)"
echo "     If different, edit $UDEV_FILE"
echo ""
echo "  5. Reboot and verify:"
echo "     sudo reboot"
echo "     # After reboot, from another device:"
echo "     # - Connect to '$AP_SSID' WiFi"
echo "     # - ATAK: add server 192.168.73.1:8087 TCP"
echo "     # - Mumble: connect to 192.168.73.1:64738"
echo ""
