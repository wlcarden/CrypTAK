#!/bin/bash
# CrypTAK Mesh Node Monitor v2 — Hybrid passive/active monitoring
#
# PRIMARY: Parse `meshtastic --nodes` for last-heard timestamps (zero airtime cost)
# SECONDARY: Traceroute probes for nodes that support it (rotated, one per cycle)
#
# Usage:
#   ./mesh-monitor.sh                    # Full check: passive all + active probe next in rotation
#   ./mesh-monitor.sh --passive          # Passive only (no traceroute, no airtime)
#   ./mesh-monitor.sh --dry-run          # Show what would happen
#   ./mesh-monitor.sh --status           # Show current state
#   ./mesh-monitor.sh --reset            # Clear state and start fresh
#
# Designed to run on Unraid where BRG01 is connected via /dev/ttyACM0

set -euo pipefail

# --- Config ---
MESHTASTIC_PORT="/dev/ttyACM0"
DATA_DIR="/mnt/user/appdata/tak-server/mesh-monitor"
STATE_FILE="$DATA_DIR/state.json"
LOG_FILE="$DATA_DIR/monitor.log"
TRACEROUTE_TIMEOUT=45

# Nodes to monitor
# Format: "node_id|name|type|probe_mode"
#   type: own | community
#   probe_mode: passive | active
#     passive = only check last-heard from --nodes (trackers, low-power)
#     active  = also send traceroute probes (routers, clients, base stations)
MONITOR_NODES=(
  "!a51e2838|CrypTAK Base|own|active"
  "!dce7b97d|CrypTAK-TRK01|own|passive"
  "!c6eadff0|CrypTAK-SOL01|own|active"
  "!3db00f2c|CrypTAK-SOL02|own|active"
  "!9aa4baf0|CrypTAK-VHC01|own|active"
  "!01f94ec0|Tracker Alpha|own|passive"
  "!67af5da5|BUMA|community|active"
  "!756f8df0|Meshtastic 8df0|community|passive"
)

# Thresholds (seconds)
STALE_WARN=1800      # 30 min — yellow
STALE_CRIT=3600      # 1 hour — red for active nodes
STALE_CRIT_PASSIVE=7200  # 2 hours — red for passive/tracker nodes

# --- Functions ---
timestamp() { date -u '+%Y-%m-%dT%H:%M:%SZ'; }
epoch_now() { date -u '+%s'; }
log() { echo "[$(timestamp)] $*" >> "$LOG_FILE"; }

ensure_dirs() {
  mkdir -p "$DATA_DIR"
  [ -f "$STATE_FILE" ] || echo '{"traceIndex":0,"nodes":{},"lastRun":"never"}' > "$STATE_FILE"
}

# Parse --nodes output into JSON keyed by node ID
fetch_node_table() {
  meshtastic --port "$MESHTASTIC_PORT" --nodes 2>&1 | python3 -c "
import re, json, sys
from datetime import datetime, timezone

now = datetime.now(timezone.utc)
nodes = {}

for line in sys.stdin:
    # Find node ID in line
    nid_match = re.search(r'(![\da-f]{8})', line)
    if not nid_match:
        continue
    nid = nid_match.group(1)
    
    # Split by │ delimiter
    parts = [p.strip() for p in line.split('│')]
    if len(parts) < 15:
        continue
    
    # parts layout: ['', N, User, ID, AKA, Hardware, Pubkey, Role, Lat, Lon, Alt, Battery, ChanUtil, TxUtil, SNR, Hops, Channel, Fav, LastHeard, Since, '']
    try:
        name = parts[2]
        battery = parts[11] if len(parts) > 11 else 'N/A'
        snr = parts[14] if len(parts) > 14 else 'N/A'
        hops = parts[15] if len(parts) > 15 else 'N/A'
        last_heard = parts[18] if len(parts) > 18 else 'N/A'
        since = parts[19] if len(parts) > 19 else 'N/A'
    except (IndexError, ValueError):
        continue
    
    # Parse last_heard to epoch (meshtastic CLI outputs LOCAL time)
    last_epoch = 0
    ts_match = re.search(r'(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2})', last_heard)
    if ts_match:
        try:
            # Parse as naive local time, let system handle timezone
            import time
            dt_struct = time.strptime(ts_match.group(1), '%Y-%m-%d %H:%M:%S')
            last_epoch = int(time.mktime(dt_struct))
        except:
            pass
    
    ago_secs = int(time.time()) - last_epoch if last_epoch else -1
    
    nodes[nid] = {
        'name': name,
        'battery': battery,
        'snr': snr,
        'hops': hops,
        'lastHeard': last_heard,
        'lastHeardEpoch': last_epoch,
        'agoSecs': ago_secs,
        'since': since
    }

json.dump(nodes, sys.stdout, indent=2)
"
}

# Update state with passive check results
update_passive() {
  local node_table_file="$1"
  
  # Build monitor list file
  local monitor_file="$DATA_DIR/.monitor_nodes.txt"
  printf '%s\n' "${MONITOR_NODES[@]}" > "$monitor_file"
  
  python3 - "$node_table_file" "$monitor_file" "$STATE_FILE" "$STALE_WARN" "$STALE_CRIT" "$STALE_CRIT_PASSIVE" <<'PYEOF'
import json, sys
from datetime import datetime, timezone

node_table_file, monitor_file, state_file = sys.argv[1], sys.argv[2], sys.argv[3]
stale_warn, stale_crit, stale_crit_passive = int(sys.argv[4]), int(sys.argv[5]), int(sys.argv[6])
now_str = datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')

with open(node_table_file) as f:
    nodes_live = json.load(f)

with open(monitor_file) as f:
    monitor = [line.strip().split('|') for line in f if line.strip()]

with open(state_file) as f:
    state = json.load(f)

for parts in monitor:
    nid, name, ntype, probe = parts[0], parts[1], parts[2], parts[3]
    
    if nid not in state['nodes']:
        state['nodes'][nid] = {
            'name': name,
            'type': ntype,
            'probeMode': probe,
            'passiveHistory': [],
            'traceHistory': [],
        }
    
    node = state['nodes'][nid]
    live = nodes_live.get(nid, {})
    
    entry = {
        'time': now_str,
        'lastHeard': live.get('lastHeard', 'N/A'),
        'agoSecs': live.get('agoSecs', -1),
        'snr': live.get('snr', 'N/A'),
        'battery': live.get('battery', 'N/A'),
        'since': live.get('since', 'N/A'),
    }
    
    node['passiveHistory'].append(entry)
    node['passiveHistory'] = node['passiveHistory'][-48:]
    
    ago = live.get('agoSecs', -1)
    crit = stale_crit_passive if probe == 'passive' else stale_crit
    
    if ago < 0:
        node['passiveStatus'] = 'unknown'
    elif ago <= stale_warn:
        node['passiveStatus'] = 'ok'
    elif ago <= crit:
        node['passiveStatus'] = 'warn'
    else:
        node['passiveStatus'] = 'stale'
    
    node['lastPassiveCheck'] = now_str
    node['lastHeardAge'] = live.get('since', 'N/A')

state['lastRun'] = now_str

with open(state_file, 'w') as f:
    json.dump(state, f, indent=2)

# Print summary
for nid, node in sorted(state['nodes'].items(), key=lambda x: x[1]['name']):
    s = node.get('passiveStatus', '?')
    icon = {'ok': '🟢', 'warn': '🟡', 'stale': '🔴', 'unknown': '⚪'}.get(s, '❓')
    age = node.get('lastHeardAge', '?')
    bat = node['passiveHistory'][-1]['battery'] if node['passiveHistory'] else '?'
    mode = node.get('probeMode', '?')
    print(f'{icon} {node["name"]:25s} {nid}  heard:{age:>15s}  bat:{bat:>8s}  mode:{mode}')
PYEOF
}

# Active traceroute probe — one node per call
probe_traceroute() {
  local node_id="$1" name="$2"
  log "TRACEROUTE $name ($node_id)"
  
  local output exit_code
  output=$(timeout "$TRACEROUTE_TIMEOUT" meshtastic --port "$MESHTASTIC_PORT" --traceroute "$node_id" 2>&1)
  exit_code=$?
  
  local status="timeout" detail="No response within ${TRACEROUTE_TIMEOUT}s" route=""
  
  if [ "$exit_code" -eq 0 ] && echo "$output" | grep -qi "Route traced"; then
    status="alive"
    route=$(echo "$output" | grep -E "^!" | head -4 | tr '\n' ' → ' | sed 's/ → $//')
    [ -z "$route" ] && route=$(echo "$output" | grep -E "\-\->" | head -2 | tr '\n' ' | ' | sed 's/ | $//')
    detail="$route"
    log "  ALIVE: $detail"
  else
    log "  TIMEOUT/FAIL (exit=$exit_code)"
  fi
  
  # Update state
  python3 -c "
import json

with open('$STATE_FILE') as f:
    state = json.load(f)

nid = '$node_id'
if nid in state['nodes']:
    node = state['nodes'][nid]
    node['traceHistory'].append({
        'time': '$(timestamp)',
        'status': '$status',
        'detail': $(python3 -c "import json; print(json.dumps('''$detail'''))")
    })
    node['traceHistory'] = node['traceHistory'][-24:]
    
    # Trace stats
    recent = node['traceHistory']
    alive = sum(1 for h in recent if h['status'] == 'alive')
    node['traceAvailability'] = f'{alive}/{len(recent)}'
    node['lastTraceOK'] = next((h['time'] for h in reversed(recent) if h['status'] == 'alive'), 'never')

with open('$STATE_FILE', 'w') as f:
    json.dump(state, f, indent=2)
" 2>/dev/null
  
  echo "$status"
}

# Get next active-probe node index
get_next_trace_index() {
  local last_idx
  last_idx=$(python3 -c "import json; print(json.load(open('$STATE_FILE')).get('traceIndex', 0))" 2>/dev/null || echo 0)
  
  # Find next node with probe_mode=active
  local total=${#MONITOR_NODES[@]}
  local checked=0
  local idx=$(( (last_idx + 1) % total ))
  
  while [ $checked -lt $total ]; do
    IFS='|' read -r _id _name _type probe_mode <<< "${MONITOR_NODES[$idx]}"
    if [ "$probe_mode" = "active" ]; then
      echo "$idx"
      return
    fi
    idx=$(( (idx + 1) % total ))
    checked=$((checked + 1))
  done
  
  echo "-1"  # No active nodes
}

update_trace_index() {
  local idx="$1"
  python3 -c "
import json
with open('$STATE_FILE') as f:
    state = json.load(f)
state['traceIndex'] = $idx
with open('$STATE_FILE', 'w') as f:
    json.dump(state, f, indent=2)
" 2>/dev/null
}

show_status() {
  [ -f "$STATE_FILE" ] || { echo "No state file. Run a check first."; exit 0; }
  
  python3 -c "
import json

with open('$STATE_FILE') as f:
    state = json.load(f)

print('╔══════════════════════════════════════════════════════════════════════════╗')
print('║                    CrypTAK Mesh Monitor Status                         ║')
print('╠══════════════════════════════════════════════════════════════════════════╣')
print(f'║ Last run: {state.get(\"lastRun\", \"never\"):>61s} ║')
print('╠══════════════════════════════════════════════════════════════════════════╣')

for nid, node in sorted(state.get('nodes', {}).items(), key=lambda x: x[1]['name']):
    ps = node.get('passiveStatus', '?')
    icon = {'ok': '🟢', 'warn': '🟡', 'stale': '🔴', 'unknown': '⚪'}.get(ps, '❓')
    name = node['name']
    age = node.get('lastHeardAge', '?')
    mode = node.get('probeMode', '?')
    ntype = node.get('type', '?')
    
    # Battery from last passive check
    bat = '?'
    if node.get('passiveHistory'):
        bat = node['passiveHistory'][-1].get('battery', '?')
    
    # Trace info (only for active nodes)
    trace = ''
    if mode == 'active' and node.get('traceHistory'):
        tavail = node.get('traceAvailability', '?')
        tlast = node.get('lastTraceOK', 'never')
        trace = f'trace:{tavail} lastOK:{tlast}'
    
    line = f'{icon} {name:22s} [{ntype:9s}] heard:{age:>14s} bat:{bat:>7s}'
    print(f'║ {line:71s}║')
    if trace:
        print(f'║   └─ {trace:66s}║')

print('╚══════════════════════════════════════════════════════════════════════════╝')
"
}

show_dry_run() {
  local next_idx
  next_idx=$(get_next_trace_index)
  
  echo "=== DRY RUN ==="
  echo ""
  echo "Step 1: PASSIVE CHECK (all nodes, zero airtime)"
  echo "  → Run 'meshtastic --nodes' and parse last-heard timestamps"
  echo ""
  echo "Step 2: ACTIVE PROBE (one node per cycle)"
  
  if [ "$next_idx" -eq -1 ]; then
    echo "  → No active-probe nodes configured"
  else
    IFS='|' read -r nid nm tp pm <<< "${MONITOR_NODES[$next_idx]}"
    echo "  → Traceroute to: $nm ($nid) [$tp]"
  fi
  
  echo ""
  echo "Node roster:"
  for i in "${!MONITOR_NODES[@]}"; do
    IFS='|' read -r nid nm tp pm <<< "${MONITOR_NODES[$i]}"
    marker=""
    [ "$i" -eq "$next_idx" ] && marker=" ← NEXT TRACE"
    echo "  [$i] $nm ($nid) [$tp] mode=$pm$marker"
  done
  
  # Count active nodes for cycle time calc
  local active_count=0
  for entry in "${MONITOR_NODES[@]}"; do
    IFS='|' read -r _ _ _ pm <<< "$entry"
    [ "$pm" = "active" ] && active_count=$((active_count + 1))
  done
  
  echo ""
  echo "Active probe cycle: $active_count nodes × 30 min = $((active_count * 30)) min full rotation"
  echo "Passive checks: all ${#MONITOR_NODES[@]} nodes every cycle (free)"
}

# --- Main ---
ensure_dirs

case "${1:-}" in
  --status)
    show_status
    exit 0
    ;;
  --dry-run)
    show_dry_run
    exit 0
    ;;
  --passive)
    log "=== PASSIVE CHECK ==="
    ntf="$DATA_DIR/.node_table.json"
    fetch_node_table > "$ntf"
    update_passive "$ntf"
    ;;
  --reset)
    echo '{"traceIndex":0,"nodes":{},"lastRun":"never"}' > "$STATE_FILE"
    echo "State reset."
    exit 0
    ;;
  *)
    # Default: passive check all + traceroute next active node
    log "=== HYBRID CHECK ==="
    
    # Step 1: Passive
    log "Passive scan..."
    ntf="$DATA_DIR/.node_table.json"
    fetch_node_table > "$ntf"
    echo "=== Passive Check (all nodes) ==="
    update_passive "$ntf"
    
    # Step 2: Active traceroute (next in rotation)
    next_idx=$(get_next_trace_index)
    if [ "$next_idx" -ne -1 ]; then
      IFS='|' read -r node_id name type probe_mode <<< "${MONITOR_NODES[$next_idx]}"
      echo ""
      echo "=== Active Probe: $name ($node_id) ==="
      result=$(probe_traceroute "$node_id" "$name")
      update_trace_index "$next_idx"
      
      icon="🟢"
      [ "$result" = "timeout" ] && icon="🔴"
      echo "  $icon Traceroute: $result"
    else
      echo ""
      echo "No active-probe nodes configured."
    fi
    
    log "=== CHECK COMPLETE ==="
    ;;
esac
