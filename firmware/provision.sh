#!/usr/bin/env bash
# provision.sh — One-command Meshtastic node provisioning for CrypTAK mesh
#
# Usage:
#   ./firmware/provision.sh "CrypTAK Base"     # Provision from registry
#   ./firmware/provision.sh                     # Interactive mode
#   ./firmware/provision.sh --list              # List registered nodes
#
# Requires: meshtastic CLI (pip install meshtastic), python3 + PyYAML

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROFILES_DIR="$SCRIPT_DIR/profiles"
NODES_FILE="$SCRIPT_DIR/nodes.yaml"
ADMIN_KEY="base64:FVmX/5EbFDNF8D1IB5rT6UaDil6dacMR9vpjOqoy0Eo="

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

STEPS=6
step() { echo -e "\n${CYAN}[$1/$STEPS]${NC} ${BOLD}$2${NC}"; }
ok()   { echo -e "  ${GREEN}✓${NC} $1"; }
warn() { echo -e "  ${YELLOW}!${NC} $1"; }
err()  { echo -e "  ${RED}x${NC} $1" >&2; }
die()  { err "$1"; exit 1; }

# Run a meshtastic CLI command, retrying once on serial errors
mesh_cmd() {
    local output
    if output=$(meshtastic --port "$PORT" "$@" 2>&1); then
        return 0
    fi
    # Serial ports can be flaky after rapid reconnects — wait and retry
    sleep 2
    meshtastic --port "$PORT" "$@" 2>&1
}

# ---------------------------------------------------------------------------
# Port detection
# ---------------------------------------------------------------------------
detect_port() {
    local ports=()
    for p in /dev/ttyACM* /dev/ttyUSB*; do
        [[ -e "$p" ]] && ports+=("$p")
    done

    if [[ ${#ports[@]} -eq 0 ]]; then
        die "No serial devices found. Is the node plugged in?"
    elif [[ ${#ports[@]} -eq 1 ]]; then
        PORT="${ports[0]}"
    else
        echo "  Multiple serial devices found:"
        for i in "${!ports[@]}"; do
            echo "    [$((i+1))] ${ports[$i]}"
        done
        read -rp "  Select port [1]: " choice
        PORT="${ports[$(( ${choice:-1} - 1 ))]}"
    fi

    # Ensure we can read/write the port
    if [[ ! -r "$PORT" || ! -w "$PORT" ]]; then
        warn "Fixing permissions on $PORT"
        sudo chmod a+rw "$PORT"
    fi

    ok "Port: $PORT"
}

# ---------------------------------------------------------------------------
# Hardware detection
# ---------------------------------------------------------------------------
detect_hardware() {
    local info
    info=$(mesh_cmd --info) || die "Failed to connect to device on $PORT"

    # Parse hwModel from Metadata JSON line (e.g. "hwModel": "RAK4631")
    HARDWARE=$(echo "$info" | grep 'Metadata:' | grep -oP '"hwModel":\s*"?\K[A-Za-z0-9_]+' | head -1 || true)
    if [[ -z "$HARDWARE" ]]; then
        # Fallback: parse from My info or any hwModel occurrence
        HARDWARE=$(echo "$info" | grep -oP '"hwModel":\s*"?\K[A-Za-z0-9_]+' | head -1 || echo "UNKNOWN")
    fi

    case "$HARDWARE" in
        RAK4631)      HW_FAMILY="rak" ;;
        TBEAM*)       HW_FAMILY="tbeam" ;;
        TLORA*|LORA*) HW_FAMILY="esp32" ;;
        *)            HW_FAMILY="unknown" ;;
    esac

    ok "Hardware: $HARDWARE ($HW_FAMILY)"
}

# ---------------------------------------------------------------------------
# Node registry helpers (python3 + PyYAML)
# ---------------------------------------------------------------------------
lookup_node() {
    python3.12 -c "
import yaml, json, sys
with open(sys.argv[1]) as f:
    data = yaml.safe_load(f)
node = data.get('nodes', {}).get(sys.argv[2])
if node:
    json.dump(node, sys.stdout)
else:
    sys.exit(1)
" "$NODES_FILE" "$1" 2>/dev/null
}

get_field() {
    python3.12 -c "
import json, sys
d = json.loads(sys.argv[1])
v = d.get(sys.argv[2])
print('' if v is None else v)
" "$1" "$2"
}

list_nodes() {
    echo -e "\n${BOLD}Registered nodes:${NC}\n"
    python3.12 -c "
import yaml, sys
with open(sys.argv[1]) as f:
    data = yaml.safe_load(f)
nodes = data.get('nodes', {})
print(f'  {\"Name\":<24} {\"Profile\":<10} {\"Short\":<6} Position')
print(f'  {\"─\"*24} {\"─\"*10} {\"─\"*6} {\"─\"*24}')
for name, cfg in nodes.items():
    profile = cfg.get('profile', '?')
    short = cfg.get('short_name', '')
    lat = cfg.get('latitude')
    lon = cfg.get('longitude')
    pos = f'{lat}, {lon}' if lat else 'GPS'
    print(f'  {name:<24} {profile:<10} {short:<6} {pos}')
" "$NODES_FILE"
    echo ""
}

save_node() {
    python3.12 -c "
import yaml, sys

name, profile, short = sys.argv[1], sys.argv[2], sys.argv[3]
hw = sys.argv[4]
lat, lon, alt = sys.argv[5], sys.argv[6], sys.argv[7]
path = sys.argv[8]

with open(path) as f:
    data = yaml.safe_load(f) or {}

if 'nodes' not in data:
    data['nodes'] = {}

node = {'profile': profile, 'short_name': short}
if hw == 'false':
    node['gps'] = False
if lat:
    node['latitude'] = float(lat)
if lon:
    node['longitude'] = float(lon)
if alt:
    node['altitude'] = int(alt)

data['nodes'][name] = node

with open(path, 'w') as f:
    yaml.dump(data, f, default_flow_style=False, sort_keys=False, allow_unicode=True)
" "$NODE_NAME" "$PROFILE" "$SHORT_NAME" "$HAS_GPS" "$LAT" "$LON" "$ALT" "$NODES_FILE"
    ok "Saved '$NODE_NAME' to nodes.yaml"
}

# ---------------------------------------------------------------------------
# Interactive mode
# ---------------------------------------------------------------------------
interactive_setup() {
    echo ""
    echo -e "  ${BOLD}No node name provided — interactive mode${NC}"
    echo ""
    echo "  Available profiles:"
    echo "    [1] base     — ROUTER, fixed emplacement (roof, tower)"
    echo "    [2] relay    — ROUTER, deployable solar relay"
    echo "    [3] vehicle  — ROUTER_CLIENT, mobile repeater (car-mounted)"
    echo "    [4] tracker  — TRACKER, GPS, battery-optimized mobile node"
    echo "    [5] bridge   — CLIENT, serial-enabled TAK server bridge"
    echo "    [6] field    — CLIENT, GPS, general field node"
    echo ""
    read -rp "  Profile [2]: " pchoice
    case "${pchoice:-2}" in
        1) PROFILE="relay"; IS_BASE="true" ;;
        2) PROFILE="relay"; IS_BASE="false" ;;
        3) PROFILE="vehicle"; IS_BASE="false" ;;
        4) PROFILE="tracker"; IS_BASE="false" ;;
        5) PROFILE="bridge"; IS_BASE="false" ;;
        6) PROFILE="field"; IS_BASE="false" ;;
        *) die "Invalid profile selection" ;;
    esac

    # Auto-generate name from profile + next available number
    local role_prefix short_prefix
    if [[ "${IS_BASE:-false}" == "true" ]]; then
        role_prefix="BSE"; short_prefix="CB"
    else
        case "$PROFILE" in
            relay)   role_prefix="RLY"; short_prefix="CR" ;;
            vehicle) role_prefix="VHC"; short_prefix="CV" ;;
            tracker) role_prefix="TRK"; short_prefix="CT" ;;
            bridge)  role_prefix="BRG"; short_prefix="CG" ;;
            field)   role_prefix="FLD"; short_prefix="CF" ;;
        esac
    fi

    # Find next available number for this role prefix
    local next_num
    next_num=$(python3.12 -c "
import yaml, sys, re
with open(sys.argv[1]) as f:
    data = yaml.safe_load(f) or {}
nodes = data.get('nodes', {})
nums = [int(m.group(1)) for name in nodes
        if (m := re.search(r'${role_prefix}(\d+)$', name))]
print(f'{max(nums)+1:02d}' if nums else '01')
" "$NODES_FILE")

    local default_name="CrypTAK-${role_prefix}${next_num}"
    local default_short="${short_prefix}${next_num}"
    read -rp "  Node name [$default_name]: " NODE_NAME
    NODE_NAME="${NODE_NAME:-$default_name}"

    read -rp "  Short name [$default_short]: " SHORT_NAME
    SHORT_NAME="${SHORT_NAME:-$default_short}"

    # GPS or fixed position
    LAT="" LON="" ALT=""
    echo ""
    read -rp "  Does this board have a GPS module? [Y/n]: " gps_choice
    if [[ "${gps_choice:-Y}" =~ ^[Nn] ]]; then
        HAS_GPS="false"
        echo "  Position can be set now or later via remote admin."
        read -rp "  Latitude (blank to skip): " LAT
        read -rp "  Longitude (blank to skip): " LON
        if [[ -n "$LAT" && -n "$LON" ]]; then
            read -rp "  Altitude in meters (blank to skip): " ALT
        fi
    else
        HAS_GPS="true"
    fi

    echo ""
    read -rp "  Save to nodes.yaml for future reprovisioning? [Y/n]: " save
    if [[ "${save:-Y}" =~ ^[Yy] ]]; then
        save_node
    fi
}

# ---------------------------------------------------------------------------
# Profile role label
# ---------------------------------------------------------------------------
role_label() {
    case "$1" in
        relay)   echo "ROUTER" ;;
        vehicle) echo "ROUTER_CLIENT" ;;
        tracker) echo "TRACKER" ;;
        bridge)  echo "CLIENT (bridge)" ;;
        field)   echo "CLIENT" ;;
        *)       echo "$1" ;;
    esac
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
    echo ""
    echo -e "${BOLD}CrypTAK Mesh Node Provisioning${NC}"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    # --list mode
    if [[ "${1:-}" == "--list" ]]; then
        list_nodes
        exit 0
    fi

    # Step 1: Detect port
    step 1 "Detecting device..."
    detect_port

    # Step 2: Detect hardware
    step 2 "Identifying hardware..."
    detect_hardware

    # Step 3: Load node configuration
    step 3 "Loading node configuration..."
    if [[ -n "${1:-}" ]]; then
        NODE_NAME="$1"
        local node_json
        node_json=$(lookup_node "$NODE_NAME") || die "Node '$NODE_NAME' not found in nodes.yaml\n  Run without args for interactive mode, or --list to see registered nodes."

        PROFILE=$(get_field "$node_json" "profile")
        SHORT_NAME=$(get_field "$node_json" "short_name")
        HAS_GPS=$(get_field "$node_json" "gps")
        LAT=$(get_field "$node_json" "latitude")
        LON=$(get_field "$node_json" "longitude")
        ALT=$(get_field "$node_json" "altitude")

        # Default to GPS enabled unless explicitly set to false
        [[ "$HAS_GPS" == "False" || "$HAS_GPS" == "false" ]] && HAS_GPS="false" || HAS_GPS="true"

        [[ -z "$PROFILE" ]] && die "Node '$NODE_NAME' is missing the 'profile' field in nodes.yaml"

        # Auto-generate short name if not in registry (extract last 4 chars of convention name)
        if [[ -z "$SHORT_NAME" ]]; then
            case "$PROFILE" in
                relay)   SHORT_PREFIX="CR" ;;
                vehicle) SHORT_PREFIX="CV" ;;
                tracker) SHORT_PREFIX="CT" ;;
                bridge)  SHORT_PREFIX="CG" ;;
                field)   SHORT_PREFIX="CF" ;;
                *)       SHORT_PREFIX="CX" ;;
            esac
            local num
            num=$(echo "$NODE_NAME" | grep -oP '\d+$' || echo "01")
            SHORT_NAME="${SHORT_PREFIX}${num}"
        fi

        ok "Node: $NODE_NAME"
        ok "Profile: $PROFILE ($(role_label "$PROFILE"))"
    else
        interactive_setup
        ok "Node: $NODE_NAME"
        ok "Profile: $PROFILE ($(role_label "$PROFILE"))"
    fi

    # Validate profile file exists
    local profile_file="$PROFILES_DIR/$PROFILE.yaml"
    [[ -f "$profile_file" ]] || die "Profile not found: $profile_file"

    # Warn on hardware/profile mismatch
    if [[ "$PROFILE" == "bridge" && "$HW_FAMILY" != "tbeam" ]]; then
        warn "Bridge profile is designed for T-Beam (detected: $HARDWARE)"
        read -rp "  Continue anyway? [y/N]: " cont
        [[ "${cont:-N}" =~ ^[Yy] ]] || exit 1
    fi
    if [[ "$PROFILE" == "tracker" && "$HW_FAMILY" == "unknown" ]]; then
        warn "Tracker profile requires GPS hardware — verify this board has a GPS module"
    fi

    # Step 4: Apply profile
    step 4 "Applying $PROFILE profile..."
    mesh_cmd --configure "$profile_file" | tail -5
    ok "Profile applied"
    sleep 1

    # Step 5: Post-configure settings
    step 5 "Applying post-configure settings..."

    # Channel: full position precision
    mesh_cmd --ch-set module_settings.position_precision 32 --ch-index 0 >/dev/null 2>&1
    ok "Position precision: 32 (full)"
    sleep 1

    # Module: NeighborInfo for topology overlay
    mesh_cmd --set neighbor_info.enabled true >/dev/null 2>&1
    ok "NeighborInfo: enabled"
    sleep 1

    # Security: admin key (all nodes except bridge — bridge IS the admin)
    if [[ "$PROFILE" != "bridge" ]]; then
        mesh_cmd --set security.admin_key "$ADMIN_KEY" >/dev/null 2>&1
        ok "Admin key: T-Beam PKC trusted"
    else
        ok "Admin key: skipped (this is the admin node)"
    fi
    sleep 1

    # Identity: owner name
    mesh_cmd --set-owner "$NODE_NAME" --set-owner-short "$SHORT_NAME" >/dev/null 2>&1
    ok "Owner: $NODE_NAME ($SHORT_NAME)"
    sleep 1

    # GPS override for no-GPS boards
    if [[ "$HAS_GPS" == "false" ]]; then
        mesh_cmd --set position.gps_enabled false --set position.gps_mode NOT_PRESENT --set position.fixed_position true >/dev/null 2>&1
        ok "GPS: disabled (no hardware)"
        sleep 1

        if [[ -n "$LAT" && -n "$LON" ]]; then
            local pos_args=(--setlat "$LAT" --setlon "$LON")
            [[ -n "$ALT" ]] && pos_args+=(--setalt "$ALT")
            mesh_cmd "${pos_args[@]}" >/dev/null 2>&1
            ok "Position: $LAT, $LON${ALT:+ (${ALT}m)}"
        else
            warn "No coordinates set — add latitude/longitude to nodes.yaml and reprovision"
        fi
    else
        ok "GPS: enabled"
    fi

    # Step 6: Verify
    step 6 "Verifying configuration..."
    sleep 2
    local info
    info=$(mesh_cmd --info 2>&1) || warn "Could not read back config (device may be rebooting)"

    echo ""
    echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${GREEN}  Provisioned: ${BOLD}$NODE_NAME${NC}"
    echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo "  Hardware:  $HARDWARE"
    echo "  Profile:   $PROFILE ($(role_label "$PROFILE"))"
    echo "  Channel:   LongFast (public) | Hop limit: 3"
    if [[ "$HAS_GPS" == "false" && -n "$LAT" && -n "$LON" ]]; then
        echo "  Position:  Fixed — $LAT, $LON${ALT:+ (${ALT}m)}"
    elif [[ "$HAS_GPS" == "false" ]]; then
        echo "  Position:  Fixed — NOT SET (reprovision with coordinates)"
    else
        echo "  Position:  GPS"
    fi
    if [[ "$PROFILE" == "bridge" ]]; then
        echo "  Admin:     This is the admin node"
    else
        echo "  Admin:     T-Beam PKC remote admin enabled"
    fi
    echo ""
}

main "$@"
