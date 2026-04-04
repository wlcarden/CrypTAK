#!/usr/bin/env bash
# provision.sh — One-command Meshtastic node provisioning for CrypTAK mesh
#
# Usage:
#   ./firmware/provision.sh [options] [node-name]
#   ./firmware/provision.sh --list
#
# Connection options (default: serial auto-detect):
#   --port <dev>      Serial port override (e.g. /dev/ttyACM1)
#   --host <ip>       WiFi/TCP — connect directly to a WiFi-enabled node
#   --dest <node-id>  Mesh admin — relay commands through local node to a remote one
#                     Combine with --host or --port to specify the local relay node.
#
# Examples:
#   ./firmware/provision.sh "CrypTAK-RPT02"
#   ./firmware/provision.sh --port /dev/ttyACM1 "CrypTAK-RPT02"
#   ./firmware/provision.sh --host 192.168.50.198 "CrypTAK-GW01"
#   ./firmware/provision.sh --host 192.168.50.198 --dest '!55c6ddbc' "CrypTAK-RPT02"
#   ./firmware/provision.sh --dest '!435ae49c' "CrypTAK-RPT03"
#
# Requires: meshtastic CLI (pip install meshtastic), python3 + PyYAML

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROFILES_DIR="$SCRIPT_DIR/profiles"
NODES_FILE="$SCRIPT_DIR/nodes.yaml"
# ADMIN_CHANNEL_PSK (and legacy ADMIN_KEY) loaded from firmware/secrets.sh (gitignored)
SECRETS_FILE="$(dirname "$0")/secrets.sh"
if [[ -f "$SECRETS_FILE" ]]; then
    # shellcheck source=secrets.sh.example
    source "$SECRETS_FILE"
else
    echo "ERROR: firmware/secrets.sh not found."
    echo "  Copy firmware/secrets.sh.example → firmware/secrets.sh"
    echo "  Set ADMIN_CHANNEL_PSK (the IFF/admin channel pre-shared key)."
    echo "  See firmware/README.md for key generation instructions."
    exit 1
fi

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

STEPS=7
step() { echo -e "\n${CYAN}[$1/$STEPS]${NC} ${BOLD}$2${NC}"; }
ok()   { echo -e "  ${GREEN}✓${NC} $1"; }
warn() { echo -e "  ${YELLOW}!${NC} $1"; }
err()  { echo -e "  ${RED}x${NC} $1" >&2; }
die()  { err "$1"; exit 1; }

# ---------------------------------------------------------------------------
# Connection state — set by parse_args(), used by mesh_cmd() and friends
# ---------------------------------------------------------------------------
CONNECTION_MODE="serial"  # serial | wifi | remote
PORT=""                   # serial port
HOST=""                   # WiFi host IP
DEST_ID=""               # remote node ID for mesh admin (--dest '!abcd1234')
# Inter-command delay: mesh admin packets need ~3s to propagate and be acknowledged;
# serial/wifi is local so 1s is fine.
CMD_DELAY=1

# ---------------------------------------------------------------------------
# Argument parsing
# Handles: --port, --host, --dest, --list, positional node name
# Sets NODE_ARG global for the positional node name.
# Must be called directly (not via command substitution) to preserve globals.
# ---------------------------------------------------------------------------
NODE_ARG=""
parse_args() {
    local explicit_port=""
    local node_arg=""

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --list)
                list_nodes
                exit 0
                ;;
            --port)
                [[ $# -lt 2 ]] && die "--port requires an argument"
                explicit_port="$2"
                shift 2
                ;;
            --host)
                [[ $# -lt 2 ]] && die "--host requires an argument"
                HOST="$2"
                shift 2
                ;;
            --dest)
                [[ $# -lt 2 ]] && die "--dest requires an argument"
                DEST_ID="$2"
                shift 2
                ;;
            -*)
                die "Unknown option: $1"
                ;;
            *)
                node_arg="$1"
                shift
                ;;
        esac
    done

    # Resolve connection mode from flag combination
    if [[ -n "$DEST_ID" ]]; then
        CONNECTION_MODE="remote"
        CMD_DELAY=8  # mesh admin packets need time to route, ack, and persist
        [[ -n "$explicit_port" ]] && PORT="$explicit_port"
        # PORT may still be empty — detect_port will run in main() if so
    elif [[ -n "$HOST" ]]; then
        CONNECTION_MODE="wifi"
    else
        CONNECTION_MODE="serial"
        [[ -n "$explicit_port" ]] && PORT="$explicit_port"
    fi

    NODE_ARG="$node_arg"
}

# ---------------------------------------------------------------------------
# meshtastic command wrapper
# Builds connection flags based on CONNECTION_MODE and DEST_ID.
#
# Serial:  meshtastic --port $PORT [--dest $DEST_ID] "$@"
# WiFi:    meshtastic --host $HOST [--dest $DEST_ID] "$@"
# Remote:  same as serial or wifi, but always with --dest $DEST_ID
#
# Retry: serial mode only — serial ports can be flaky after rapid reconnects.
# Remote mode does NOT retry; double-applying channel ops or reboots is harmful.
# ---------------------------------------------------------------------------
mesh_cmd() {
    local base_flags=()
    case "$CONNECTION_MODE" in
        serial) base_flags=(--port "$PORT") ;;
        wifi)   base_flags=(--host "$HOST") ;;
        remote)
            if [[ -n "$HOST" ]]; then
                base_flags=(--host "$HOST" --dest "$DEST_ID")
            else
                base_flags=(--port "$PORT" --dest "$DEST_ID")
            fi
            ;;
    esac

    local output
    if output=$(meshtastic "${base_flags[@]}" "$@" 2>&1); then
        echo "$output"
        return 0
    fi

    if [[ "$CONNECTION_MODE" == "serial" ]]; then
        sleep 2
        meshtastic "${base_flags[@]}" "$@" 2>&1
    else
        echo "$output" >&2
        return 1
    fi
}

# ---------------------------------------------------------------------------
# Port detection (serial and remote-via-serial modes only)
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
# Skipped in mesh admin (remote) mode — --info over mesh is unreliable and
# slow; the node is already registered in nodes.yaml, so profile compatibility
# warnings are the user's responsibility.
# ---------------------------------------------------------------------------
detect_hardware() {
    if [[ "$CONNECTION_MODE" == "remote" ]]; then
        HARDWARE="UNKNOWN"
        HW_FAMILY="unknown"
        warn "Hardware detection skipped in mesh admin mode"
        return
    fi

    local info
    info=$(mesh_cmd --info) || die "Failed to connect to device on ${PORT:-$HOST}"

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
# Profile application
# Direct modes (serial/wifi): --configure sends the full profile YAML in one shot.
# Mesh admin (remote): --configure is not supported over the mesh admin channel.
#   Instead, group settings by config section and send one meshtastic call per
#   section. The CLI batches same-section --set flags into a single admin packet,
#   so 18 settings across 6 sections = 6 round-trips, not 18.
#   One delay between sections covers any firmware reboot triggered by the update.
# ---------------------------------------------------------------------------
apply_profile() {
    local profile_file="$1"

    if [[ "$CONNECTION_MODE" != "remote" ]]; then
        mesh_cmd --configure "$profile_file" | tail -5
        ok "Profile applied via --configure"
        return
    fi

    warn "Mesh admin mode: grouping settings by section (one admin packet per section)"

    # Python emits one line per config section: "section\t--set s.k1 v1\t--set s.k2 v2\t..."
    local sections
    sections=$(python3.12 -c "
import yaml, sys
from collections import OrderedDict
with open(sys.argv[1]) as f:
    data = yaml.safe_load(f)
config = data.get('config', {})
for section, vals in config.items():
    if not isinstance(vals, dict):
        continue
    flags = []
    for key, value in vals.items():
        v = 'true' if value is True else ('false' if value is False else str(value))
        flags.append(f'--set {section}.{key} {v}')
    if flags:
        print(section + '\t' + '\t'.join(flags))
" "$profile_file")

    local count=0
    while IFS=$'\t' read -r section rest; do
        # Split the tab-delimited flags into an array and call mesh_cmd once
        local flags=()
        while IFS= read -r -d $'\t' flag; do
            [[ -n "$flag" ]] && flags+=($flag)
        done <<< "${rest}"$'\t'
        if mesh_cmd "${flags[@]}" >/dev/null 2>&1; then
            ok "  $section: $(( ${#flags[@]} / 3 )) settings applied"
        else
            warn "  $section: send failed (node may be rebooting) — will retry in next reprovision"
        fi
        # Device section contains role — firmware reboots after this. Wait longer.
        if [[ "$section" == "device" && "$CONNECTION_MODE" == "remote" ]]; then
            ok "  Waiting for node reboot after device config (20s)..."
            sleep 20
        else
            sleep "$CMD_DELAY"
        fi
        (( count++ )) || true
    done <<< "$sections"

    ok "Profile applied ($count sections via mesh admin)"
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
    read -rp "  Does this board have a GPS module? [Y/n/auto]: " gps_choice
    if [[ "${gps_choice:-auto}" =~ ^[Aa] || -z "$gps_choice" ]]; then
        HAS_GPS="probe"
    elif [[ "${gps_choice}" =~ ^[Nn] ]]; then
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
# GPS hardware probe
# Skipped in mesh admin mode — hardware model is unknown without --info.
# Falls back to GPS=enabled (safe default for all current CrypTAK hardware).
# ---------------------------------------------------------------------------
detect_gps() {
    if [[ "$CONNECTION_MODE" == "remote" ]]; then
        warn "GPS probe skipped in mesh admin mode — assuming GPS present"
        HAS_GPS="true"
        return
    fi

    warn "GPS not specified in nodes.yaml — checking hardware capability..."

    # GPS-capable hardware families (boards that ship with or support GPS modules)
    # RAK4631 on WisBlock base with GPS slot, T-Beam (built-in GPS), T-Echo, etc.
    # Conservative: assume GPS-capable unless we know the board can't have one.
    local no_gps_models="PORTDUINO|ANDROID_SIM|HELTEC_V3|HELTEC_V2|HELTEC_V1|HELTEC_WSL_V3|HELTEC_HT62|STATION_G1|STATION_G2|NRF52_UNKNOWN|RP2040_LORA|RPI_PICO|RPI_PICO2"

    if echo "$HARDWARE" | grep -qEi "$no_gps_models"; then
        HAS_GPS="false"
        ok "GPS: hardware model $HARDWARE — no GPS expected"
    else
        HAS_GPS="true"
        ok "GPS: hardware model $HARDWARE — GPS assumed present"
    fi
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
    echo ""
    echo -e "${BOLD}CrypTAK Mesh Node Provisioning${NC}"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    parse_args "$@"
    local node_arg="$NODE_ARG"

    # Step 1: Establish connection
    step 1 "Establishing connection..."
    case "$CONNECTION_MODE" in
        serial)
            [[ -z "$PORT" ]] && detect_port || ok "Port: $PORT"
            ;;
        wifi)
            ok "WiFi: $HOST (TCP)"
            ;;
        remote)
            # Need a local node to relay admin packets through
            if [[ -n "$HOST" ]]; then
                ok "Local relay: $HOST (WiFi)"
            else
                [[ -z "$PORT" ]] && detect_port
                ok "Local relay: $PORT (serial)"
            fi
            ok "Remote target: $DEST_ID (mesh admin)"
            warn "Mesh admin: --configure not supported; profile applied as individual --set calls"
            ;;
    esac

    # Step 2: Detect hardware
    step 2 "Identifying hardware..."
    detect_hardware

    # Step 3: Load node configuration
    step 3 "Loading node configuration..."
    if [[ -n "$node_arg" ]]; then
        NODE_NAME="$node_arg"
        local node_json
        node_json=$(lookup_node "$NODE_NAME") || die "Node '$NODE_NAME' not found in nodes.yaml\n  Run without args for interactive mode, or --list to see registered nodes."

        PROFILE=$(get_field "$node_json" "profile")
        SHORT_NAME=$(get_field "$node_json" "short_name")
        HAS_GPS=$(get_field "$node_json" "gps")
        LAT=$(get_field "$node_json" "latitude")
        LON=$(get_field "$node_json" "longitude")
        ALT=$(get_field "$node_json" "altitude")

        # false = confirmed no GPS; empty = unknown (will probe in step 5); else = confirmed present
        if [[ "$HAS_GPS" == "False" || "$HAS_GPS" == "false" ]]; then
            HAS_GPS="false"
        elif [[ -z "$HAS_GPS" ]]; then
            HAS_GPS="probe"
        else
            HAS_GPS="true"
        fi

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

    # Warn on hardware/profile mismatch (skipped in remote mode — HARDWARE is UNKNOWN)
    if [[ "$CONNECTION_MODE" != "remote" ]]; then
        if [[ "$PROFILE" == "bridge" && "$HW_FAMILY" != "tbeam" ]]; then
            warn "Bridge profile is designed for T-Beam (detected: $HARDWARE)"
            read -rp "  Continue anyway? [y/N]: " cont
            [[ "${cont:-N}" =~ ^[Yy] ]] || exit 1
        fi
        if [[ "$PROFILE" == "tracker" && "$HW_FAMILY" == "unknown" ]]; then
            warn "Tracker profile requires GPS hardware — verify this board has a GPS module"
        fi
    fi

    # Step 4: Apply profile
    step 4 "Applying $PROFILE profile..."
    apply_profile "$profile_file"
    # Remote: node may reboot after a role/config change — wait for it to come back
    if [[ "$CONNECTION_MODE" == "remote" ]]; then
        ok "Waiting for node to stabilize after config (20s)..."
        sleep 20
    else
        sleep "$CMD_DELAY"
    fi

    # Step 5: Post-configure settings
    step 5 "Applying post-configure settings..."

    # Channel: full position precision
    mesh_cmd --ch-set module_settings.position_precision 32 --ch-index 0 >/dev/null 2>&1 || true
    ok "Position precision: 32 (full)"
    sleep "$CMD_DELAY"

    # Gateway: enable MQTT uplink on channel 0 (LongFast) so all mesh traffic is published.
    # Other profiles leave this off — only the MQTT bridge node needs to forward to mosquitto.
    if [[ "$PROFILE" == "gateway" ]]; then
        mesh_cmd --ch-set uplink_enabled true --ch-index 0 >/dev/null 2>&1 || true
        ok "MQTT uplink: enabled on channel 0 (LongFast)"
        sleep "$CMD_DELAY"
    fi

    # Module: NeighborInfo for topology overlay
    mesh_cmd --set neighbor_info.enabled true >/dev/null 2>&1
    ok "NeighborInfo: enabled"
    sleep "$CMD_DELAY"

    # Security: set PKC admin key to BRG01 (workbench admin device, not a field node).
    # ADMIN_KEY in secrets.sh must be BRG01's public key — get it after provisioning
    # BRG01 via: meshtastic --host <ip> --info | grep publicKey
    # Admin channel PSK (cryptak channel) is also enabled as belt-and-suspenders.
    # Never use a field node's key here — authority must stay at base.
    if [[ "$PROFILE" != "bridge" && -n "${ADMIN_KEY:-}" ]]; then
        # security.admin_key ACK is unreliable in mesh admin mode — the packet fires
        # but the node doesn't echo back a ConfigComplete, causing the CLI to hang.
        # Run with a 30s timeout; if it times out, warn and continue.
        # Verify with --get security when physically connected.
        local admin_cmd=()
        if [[ -n "$HOST" ]]; then
            admin_cmd=(meshtastic --host "$HOST" --dest "$DEST_ID" --set security.admin_key "$ADMIN_KEY")
        else
            admin_cmd=(meshtastic --port "$PORT" --dest "$DEST_ID" --set security.admin_key "$ADMIN_KEY")
        fi
        if timeout 30 "${admin_cmd[@]}" >/dev/null 2>&1; then
            ok "Admin key: BRG01 PKC set"
        else
            warn "Admin key: timed out waiting for ACK (packet may have fired — verify physically)"
        fi
    elif [[ "$PROFILE" == "bridge" ]]; then
        ok "Admin key: skipped (this is the admin device)"
    else
        warn "ADMIN_KEY not set in secrets.sh — skipping PKC admin key"
    fi
    sleep "$CMD_DELAY"

    # Identity: owner name
    mesh_cmd --set-owner "$NODE_NAME" --set-owner-short "$SHORT_NAME" >/dev/null 2>&1
    ok "Owner: $NODE_NAME ($SHORT_NAME)"
    sleep "$CMD_DELAY"

    # Admin channel: add channel 1 "cryptak" with shared PSK for remote management
    # ESP32-S3 boards need longer delays between channel writes (25s+).
    # Mesh admin mode inherits the same delays — channel ops are slow regardless.
    if [[ -n "${ADMIN_CHANNEL_PSK:-}" ]]; then
        mesh_cmd --ch-add cryptak >/dev/null 2>&1 || true  # no-op if channel already exists
        ok "IFF channel 'cryptak': created or already present (index 1)"
        sleep 15

        mesh_cmd --ch-set psk "$ADMIN_CHANNEL_PSK" --ch-index 1 >/dev/null 2>&1 || true
        ok "IFF channel 'cryptak': PSK set (or already correct)"
        sleep 15

        mesh_cmd --set security.admin_channel_enabled true >/dev/null 2>&1 || true
        ok "IFF channel: admin routing enabled"
        sleep 10
    else
        warn "IFF channel: ADMIN_CHANNEL_PSK not set in secrets.sh — skipping"
    fi

    # GPS: probe if not explicitly set, override if confirmed absent
    if [[ "$HAS_GPS" == "probe" ]]; then
        detect_gps
        sleep "$CMD_DELAY"
    fi

    if [[ "$HAS_GPS" == "false" ]]; then
        mesh_cmd --set position.gps_enabled false --set position.gps_mode NOT_PRESENT --set position.fixed_position true >/dev/null 2>&1
        ok "GPS: disabled (no hardware)"
        sleep "$CMD_DELAY"

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

    # Step 7: Verify
    step 7 "Verifying configuration..."
    if [[ "$CONNECTION_MODE" == "remote" ]]; then
        warn "Verification skipped in mesh admin mode"
        warn "Confirm with: meshtastic --info (connected to the node directly or via nodedb)"
    else
        sleep 2
        local info
        info=$(mesh_cmd --info 2>&1) || warn "Could not read back config (device may be rebooting)"
    fi

    echo ""
    echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${GREEN}  Provisioned: ${BOLD}$NODE_NAME${NC}"
    echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo "  Hardware:  $HARDWARE"
    echo "  Profile:   $PROFILE ($(role_label "$PROFILE"))"
    echo "  Mode:      $CONNECTION_MODE"
    echo "  Channel:   LongFast (public) | Hop limit: 3"
    if [[ "$HAS_GPS" == "false" && -n "$LAT" && -n "$LON" ]]; then
        echo "  Position:  Fixed — $LAT, $LON${ALT:+ (${ALT}m)}"
    elif [[ "$HAS_GPS" == "false" ]]; then
        echo "  Position:  Fixed — NOT SET (reprovision with coordinates)"
    else
        echo "  Position:  GPS (hardware confirmed)"
    fi
    if [[ "$PROFILE" == "bridge" ]]; then
        echo "  Admin:     This is the admin device (BRG01)"
    else
        echo "  Admin:     BRG01 PKC + cryptak channel PSK"
    fi
    if [[ -n "${ADMIN_CHANNEL_PSK:-}" ]]; then
        echo "  IFF Ch:    Channel 1 (cryptak) — fleet IFF + remote admin"
    else
        echo "  IFF Ch:    NOT CONFIGURED (add ADMIN_CHANNEL_PSK to secrets.sh)"
    fi
    if [[ "$CONNECTION_MODE" == "remote" ]]; then
        echo ""
        echo -e "  ${YELLOW}NOTE:${NC} Mesh admin changes propagate asynchronously."
        echo "  Allow 30–60s before verifying, then check with meshtastic --info."
    fi
    echo ""
}

main "$@"
