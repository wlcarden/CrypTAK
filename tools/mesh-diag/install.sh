#!/usr/bin/env bash
# install.sh — Deploy mesh-diag to reTerminal
# Run on the reTerminal: sudo ./install.sh
set -euo pipefail

INSTALL_DIR="/opt/mesh-diag"
REPO_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
SERVICE_NAME="mesh-diag"
USER="pi"

echo "=== mesh-diag installer ==="

# 1. Install system deps
echo "[1/6] Installing system packages..."
apt-get update -qq
apt-get install -y -qq python3-venv chromium udev > /dev/null 2>&1 || true

# 2. Copy app to /opt
echo "[2/6] Deploying to $INSTALL_DIR..."
mkdir -p "$INSTALL_DIR"
cp -r "$(dirname "$0")/server" "$INSTALL_DIR/"
cp -r "$(dirname "$0")/ui" "$INSTALL_DIR/"
mkdir -p "$INSTALL_DIR/backups" "$INSTALL_DIR/logs" "$INSTALL_DIR/profiles"

# Copy fleet profile if available
if [ -f "$REPO_DIR/firmware/nodes.yaml" ]; then
    cp "$REPO_DIR/firmware/nodes.yaml" "$INSTALL_DIR/profiles/cryptak.yaml"
fi

chown -R "$USER:$USER" "$INSTALL_DIR"

# 3. Create venv and install deps
echo "[3/6] Setting up Python venv..."
if [ ! -d "$INSTALL_DIR/.venv" ]; then
    sudo -u "$USER" python3 -m venv "$INSTALL_DIR/.venv"
fi
sudo -u "$USER" "$INSTALL_DIR/.venv/bin/pip" install -q \
    meshtastic fastapi 'uvicorn[standard]' pydantic

# 4. udev rule for serial devices (allow pi user access)
echo "[4/6] Configuring udev rules..."
cat > /etc/udev/rules.d/99-meshtastic.rules << 'EOF'
# Allow pi user access to Meshtastic serial devices
SUBSYSTEM=="tty", ATTRS{idVendor}=="239a", MODE="0666"
SUBSYSTEM=="tty", ATTRS{idVendor}=="10c4", MODE="0666"
SUBSYSTEM=="tty", ATTRS{idVendor}=="1a86", MODE="0666"
SUBSYSTEM=="tty", ATTRS{idVendor}=="303a", MODE="0666"
SUBSYSTEM=="usb", ATTRS{idVendor}=="239a", MODE="0666"
SUBSYSTEM=="usb", ATTRS{idVendor}=="10c4", MODE="0666"
EOF
udevadm control --reload-rules

# 5. systemd service
echo "[5/6] Installing systemd service..."
cat > /etc/systemd/system/mesh-diag.service << EOF
[Unit]
Description=Meshtastic Field Diagnostic Tool
After=network.target

[Service]
Type=simple
User=$USER
Group=$USER
WorkingDirectory=$INSTALL_DIR
ExecStart=$INSTALL_DIR/.venv/bin/python -m uvicorn server.app:app --host 0.0.0.0 --port 8100
Restart=always
RestartSec=5
Environment=PYTHONUNBUFFERED=1

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable mesh-diag
systemctl restart mesh-diag

# 6. Chromium kiosk autostart
echo "[6/6] Setting up kiosk mode..."
AUTOSTART_DIR="/home/$USER/.config/autostart"
mkdir -p "$AUTOSTART_DIR"
cat > "$AUTOSTART_DIR/mesh-diag-kiosk.desktop" << 'EOF'
[Desktop Entry]
Type=Application
Name=mesh-diag Kiosk
Comment=Meshtastic Field Diagnostic Tool
Exec=bash -c 'sleep 5 && chromium --kiosk --noerrdialogs --disable-infobars --disable-session-crashed-bubble --no-first-run --start-fullscreen http://localhost:8100'
X-GNOME-Autostart-enabled=true
EOF
chown -R "$USER:$USER" "$AUTOSTART_DIR"

echo ""
echo "=== mesh-diag installed ==="
echo "  Service: systemctl status mesh-diag"
echo "  URL:     http://$(hostname -I | awk '{print $1}'):8100"
echo "  Kiosk:   will launch on next desktop login"
echo ""
echo "To start kiosk now:"
echo "  chromium --kiosk http://localhost:8100"
