#!/usr/bin/env bash
# upgrade-iff.sh — Add CrypTAK IFF channel + admin key to existing nodes
#
# Non-destructive: only adds channel 1 "cryptak" and BRG01 admin key.
# Does NOT touch profile, position, telemetry, or other settings.
#
# Usage:
#   ./firmware/upgrade-iff.sh              # Auto-detect port
#   ./firmware/upgrade-iff.sh /dev/ttyACM0 # Specify port
#
# Requires: meshtastic CLI, firmware/secrets.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SECRETS_FILE="$SCRIPT_DIR/secrets.sh"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

ok()   { echo -e "  ${GREEN}✓${NC} $1"; }
warn() { echo -e "  ${YELLOW}!${NC} $1"; }
err()  { echo -e "  ${RED}x${NC} $1" >&2; }
die()  { err "$1"; exit 1; }

# Load secrets
if [[ -f "$SECRETS_FILE" ]]; then
    source "$SECRETS_FILE"
else
    die "firmware/secrets.sh not found. See secrets.sh.example."
fi

[[ -n "${ADMIN_CHANNEL_PSK:-}" ]] || die "ADMIN_CHANNEL_PSK not set in secrets.sh"
[[ -n "${ADMIN_KEY:-}" ]] || die "ADMIN_KEY not set in secrets.sh"

# BRG01 public key — the admin controller node (derived from ADMIN_KEY_PRIVATE)
BRG01_PUBKEY="base64:tFD1EtntZR3uHquuPAf/TvNfY5QBBVg7FlmP5Rsf1Eo="

# Port detection
if [[ -n "${1:-}" ]]; then
    PORT="$1"
else
    ports=()
    for p in /dev/ttyACM* /dev/ttyUSB*; do
        [[ -e "$p" ]] && ports+=("$p")
    done
    [[ ${#ports[@]} -gt 0 ]] || die "No serial devices found."
    if [[ ${#ports[@]} -eq 1 ]]; then
        PORT="${ports[0]}"
    else
        echo "Multiple ports found:"
        for i in "${!ports[@]}"; do echo "  [$((i+1))] ${ports[$i]}"; done
        read -rp "Select port [1]: " choice
        PORT="${ports[$(( ${choice:-1} - 1 ))]}"
    fi
fi

echo ""
echo -e "${BOLD}CrypTAK IFF Channel Upgrade${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo -e "  Port: ${CYAN}$PORT${NC}"

# Read current node info
echo ""
echo -e "${CYAN}[1/5]${NC} ${BOLD}Reading current config...${NC}"
INFO=$(meshtastic --port "$PORT" --info 2>&1) || die "Cannot connect to device on $PORT"

OWNER=$(echo "$INFO" | grep "^Owner:" | head -1)
echo "  $OWNER"

# Check if channel 1 already exists
if echo "$INFO" | grep -q "Index 1:"; then
    CH1_NAME=$(echo "$INFO" | grep "Index 1:" | grep -oP '"name":\s*"\K[^"]+' || echo "")
    if [[ "$CH1_NAME" == "cryptak" ]]; then
        ok "IFF channel 'cryptak' already exists — skipping channel setup"
        SKIP_CHANNEL=true
    elif [[ "$CH1_NAME" == "admin" ]]; then
        warn "Found legacy 'admin' channel — renaming to 'cryptak'"
        SKIP_CHANNEL=false
        RENAME_ONLY=true
    else
        die "Channel 1 already exists with name '$CH1_NAME' — manual intervention needed"
    fi
else
    SKIP_CHANNEL=false
    RENAME_ONLY=false
fi

# Step 2: Add/fix IFF channel
echo ""
echo -e "${CYAN}[2/5]${NC} ${BOLD}Configuring IFF channel...${NC}"
if [[ "$SKIP_CHANNEL" == "true" ]]; then
    ok "Already configured"
elif [[ "${RENAME_ONLY:-false}" == "true" ]]; then
    meshtastic --port "$PORT" --ch-set name cryptak --ch-index 1 >/dev/null 2>&1
    ok "Renamed admin → cryptak"
    sleep 15
else
    meshtastic --port "$PORT" --ch-add cryptak >/dev/null 2>&1
    ok "Created channel 1 'cryptak'"
    sleep 15

    meshtastic --port "$PORT" --ch-set psk "$ADMIN_CHANNEL_PSK" --ch-index 1 >/dev/null 2>&1
    ok "PSK set"
    sleep 15
fi

# Step 3: Enable admin channel routing
echo ""
echo -e "${CYAN}[3/5]${NC} ${BOLD}Enabling admin channel routing...${NC}"
meshtastic --port "$PORT" --set security.admin_channel_enabled true >/dev/null 2>&1
ok "admin_channel_enabled = true"
sleep 15

# Step 4: Add BRG01 admin key
echo ""
echo -e "${CYAN}[4/5]${NC} ${BOLD}Adding BRG01 admin key...${NC}"
# Check if already present
if echo "$INFO" | grep -q "tFD1"; then
    ok "BRG01 key already trusted"
else
    meshtastic --port "$PORT" --set security.admin_key "$BRG01_PUBKEY" >/dev/null 2>&1
    ok "BRG01 public key added to admin_key list"
fi
sleep 15

# Step 5: Reset NodeDB
echo ""
echo -e "${CYAN}[5/5]${NC} ${BOLD}Resetting NodeDB for fresh PKC exchange...${NC}"
read -rp "  Reset NodeDB? This clears the node list (rebuilds in ~20 min). [Y/n]: " confirm
if [[ "${confirm:-Y}" =~ ^[Yy] ]]; then
    meshtastic --port "$PORT" --reset-nodedb >/dev/null 2>&1
    ok "NodeDB reset"
else
    warn "Skipped — remote admin may not work until NodeDB is reset"
fi

# Verify
echo ""
sleep 10
echo -e "${CYAN}Verifying...${NC}"
VERIFY=$(meshtastic --port "$PORT" --info 2>&1) || warn "Could not verify (device may be rebooting)"

if echo "$VERIFY" | grep -q "cryptak"; then
    ok "IFF channel 'cryptak' confirmed"
else
    warn "Could not confirm IFF channel — device may still be rebooting"
fi

echo ""
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${GREEN}  IFF upgrade complete${NC}"
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo ""
