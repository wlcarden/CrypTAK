#!/usr/bin/env python3
"""CrypTAK Fleet Status — Query mesh node fleet from all available sources.

Reads the node registry (firmware/nodes.yaml) and queries up to three data
sources for live status, merging results into a single fleet table:

  1. MeshMonitor API  (fastest — pre-aggregated, requires auth)
  2. Meshtastic CLI   (meshtastic --nodes — requires serial/TCP to a gateway)
  3. MQTT             (subscribe briefly to Mosquitto for fresh telemetry)

Usage:
  ./fleet_status.py                       # Auto-detect sources, print table
  ./fleet_status.py --source mqtt         # MQTT only
  ./fleet_status.py --source cli          # Meshtastic CLI only
  ./fleet_status.py --source meshmon      # MeshMonitor API only
  ./fleet_status.py --json                # Output as JSON
  ./fleet_status.py --watch               # Re-poll every 60s

Environment / config:
  MQTT_HOST       Mosquitto broker   (default: 192.168.50.120)
  MQTT_PORT       Mosquitto port     (default: 1883)
  MQTT_USER       Mosquitto user     (default: meshtastic)
  MQTT_PASS       Mosquitto password (reads from secrets if unset)
  MESHMON_URL     MeshMonitor base   (default: http://192.168.50.120:8090)
  MESHMON_TOKEN   MeshMonitor bearer token
  MESH_PORT       Meshtastic serial  (default: /dev/ttyACM0)
  MESH_HOST       Meshtastic TCP     (fallback if no serial)
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

# ---------------------------------------------------------------------------
# Node registry
# ---------------------------------------------------------------------------

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent.parent
NODES_YAML = REPO_ROOT / "firmware" / "nodes.yaml"


def load_registry(path: Path = NODES_YAML) -> dict:
    """Load firmware/nodes.yaml into {hex_id: {...}} keyed by bare hex ID."""
    # Minimal YAML parser — nodes.yaml is simple enough to avoid PyYAML dep
    registry = {}
    current_name = None
    current = {}
    with open(path) as f:
        for line in f:
            stripped = line.rstrip()
            # Top-level key like "  CrypTAK-BRG01:"
            m = re.match(r"  (\S+):", stripped)
            if m and not stripped.lstrip().startswith("- "):
                if current_name and current.get("id"):
                    nid = current["id"].strip('"').lstrip("!")
                    current["registry_name"] = current_name
                    registry[nid] = current
                current_name = m.group(1)
                current = {}
                continue
            # Key-value like "    id: \"!55c6ddbc\""
            kv = re.match(r"\s{4}(\w+):\s*(.*)", stripped)
            if kv:
                key = kv.group(1)
                val = kv.group(2).strip().strip('"')
                if val.lower() == "true":
                    val = True
                elif val.lower() == "false":
                    val = False
                else:
                    try:
                        val = float(val) if "." in val else int(val)
                    except ValueError:
                        pass
                current[key] = val
    # Flush last node
    if current_name and current.get("id"):
        nid = current["id"].strip('"').lstrip("!")
        current["registry_name"] = current_name
        registry[nid] = current
    return registry


# ---------------------------------------------------------------------------
# Fleet record — merged from all sources
# ---------------------------------------------------------------------------

def new_fleet_record(nid: str, reg: dict) -> dict:
    """Create a blank fleet record seeded from registry data."""
    return {
        "node_id": nid,
        "name": reg.get("registry_name", f"!{nid}"),
        "short_name": reg.get("short_name", ""),
        "profile": reg.get("profile", ""),
        "cot_type": reg.get("cot_type", ""),
        "hw_model": "",
        "role": "",
        "battery_pct": None,
        "voltage": None,
        "channel_util": None,
        "air_util_tx": None,
        "uptime_s": None,
        "snr": None,
        "hops": None,
        "lat": reg.get("latitude"),
        "lon": reg.get("longitude"),
        "alt": reg.get("altitude"),
        "last_heard_epoch": None,
        "last_heard_ago_s": None,
        "source": [],
        "status": "UNKNOWN",
    }


def compute_status(rec: dict) -> str:
    """Derive status from last_heard_ago_s and battery."""
    ago = rec.get("last_heard_ago_s")
    if ago is None or ago < 0:
        return "UNKNOWN"
    if ago <= 1800:
        status = "ONLINE"
    elif ago <= 7200:
        status = "STALE"
    else:
        status = "OFFLINE"
    # Battery warnings override
    v = rec.get("voltage")
    if v is not None and v > 0:
        if v < 3.3:
            status += " BATT-CRIT"
        elif v < 3.5:
            status += " BATT-LOW"
    return status


# ---------------------------------------------------------------------------
# Source 1: MeshMonitor API
# ---------------------------------------------------------------------------

def query_meshmonitor(fleet: dict, url: str, token: str) -> bool:
    """Query MeshMonitor /api/v1/nodes and merge into fleet records."""
    try:
        import urllib.request
        import urllib.error
        req = urllib.request.Request(
            f"{url.rstrip('/')}/api/v1/nodes",
            headers={"Authorization": f"Bearer {token}"} if token else {},
        )
        with urllib.request.urlopen(req, timeout=5) as resp:
            data = json.loads(resp.read())
    except Exception as e:
        print(f"  [meshmon] unavailable: {e}", file=sys.stderr)
        return False

    nodes_list = data if isinstance(data, list) else data.get("nodes", data.get("data", []))
    now = time.time()
    matched = 0

    for node in nodes_list:
        # MeshMonitor may use decimal or hex IDs
        raw_id = str(node.get("id", node.get("node_id", node.get("nodeId", ""))))
        # Normalize to bare hex
        nid = raw_id.lstrip("!").lstrip("0x")
        if len(nid) <= 8:
            try:
                nid = format(int(nid), "08x") if nid.isdigit() else nid
            except ValueError:
                pass
        nid = nid.lower()

        if nid not in fleet:
            continue
        matched += 1
        rec = fleet[nid]

        rec["hw_model"] = node.get("hardware", node.get("hwModel", rec["hw_model"]))
        rec["role"] = node.get("role", rec["role"])
        rec["battery_pct"] = _first(node, "batteryLevel", "battery", "battery_pct") or rec["battery_pct"]
        rec["voltage"] = _first(node, "voltage") or rec["voltage"]
        rec["snr"] = _first(node, "snr") or rec["snr"]
        rec["hops"] = _first(node, "hopsAway", "hops") or rec["hops"]

        last = _first(node, "lastHeard", "last_heard", "lastSeen")
        if last:
            epoch = _parse_timestamp(last)
            if epoch and (rec["last_heard_epoch"] is None or epoch > rec["last_heard_epoch"]):
                rec["last_heard_epoch"] = epoch
                rec["last_heard_ago_s"] = int(now - epoch)

        if "meshmon" not in rec["source"]:
            rec["source"].append("meshmon")

    print(f"  [meshmon] matched {matched}/{len(fleet)} nodes", file=sys.stderr)
    return matched > 0


# ---------------------------------------------------------------------------
# Source 2: Meshtastic CLI (meshtastic --nodes)
# ---------------------------------------------------------------------------

def query_meshtastic_cli(fleet: dict, port: str = None, host: str = None) -> bool:
    """Run meshtastic --nodes and parse the table output."""
    cmd = ["meshtastic"]
    if port and os.path.exists(port):
        cmd += ["--port", port]
    elif host:
        cmd += ["--host", host]
    else:
        # Try default serial
        if os.path.exists("/dev/ttyACM0"):
            cmd += ["--port", "/dev/ttyACM0"]
        else:
            print("  [cli] no serial port or host configured", file=sys.stderr)
            return False

    try:
        result = subprocess.run(
            cmd + ["--nodes"],
            capture_output=True, text=True, timeout=30
        )
        output = result.stdout + result.stderr
    except FileNotFoundError:
        print("  [cli] meshtastic command not found", file=sys.stderr)
        return False
    except subprocess.TimeoutExpired:
        print("  [cli] timed out after 30s", file=sys.stderr)
        return False

    now = time.time()
    matched = 0

    for line in output.splitlines():
        # Find node ID pattern !xxxxxxxx
        nid_match = re.search(r"(![\da-f]{8})", line, re.IGNORECASE)
        if not nid_match:
            continue
        nid = nid_match.group(1).lstrip("!").lower()

        # Split on box-drawing delimiter │
        parts = [p.strip() for p in line.split("│")]
        if len(parts) < 15:
            continue

        if nid not in fleet:
            continue
        matched += 1
        rec = fleet[nid]

        try:
            # Column layout from meshtastic 2.x CLI:
            # ['', N, User, ID, AKA, Hardware, Pubkey, Role, Lat, Lon, Alt,
            #  Battery, ChanUtil, TxUtil, SNR, Hops, Channel, Fav, LastHeard, Since, '']
            rec["hw_model"] = parts[5] or rec["hw_model"]
            rec["role"] = parts[7] or rec["role"]

            lat = _safe_float(parts[8])
            lon = _safe_float(parts[9])
            if lat and lon:
                rec["lat"] = lat
                rec["lon"] = lon
            alt = _safe_float(parts[10])
            if alt is not None:
                rec["alt"] = alt

            batt = parts[11].strip().rstrip("%")
            if batt and batt not in ("N/A", ""):
                rec["battery_pct"] = _safe_float(batt)

            cu = _safe_float(parts[12].rstrip("%"))
            if cu is not None:
                rec["channel_util"] = cu

            tx = _safe_float(parts[13].rstrip("%"))
            if tx is not None:
                rec["air_util_tx"] = tx

            snr = _safe_float(parts[14])
            if snr is not None:
                rec["snr"] = snr

            hops = parts[15].strip()
            if hops and hops not in ("N/A", ""):
                rec["hops"] = int(hops) if hops.isdigit() else hops

            # Parse last heard timestamp
            last_heard = parts[18] if len(parts) > 18 else ""
            ts_match = re.search(r"(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2})", last_heard)
            if ts_match:
                try:
                    import calendar
                    dt_struct = time.strptime(ts_match.group(1), "%Y-%m-%d %H:%M:%S")
                    epoch = time.mktime(dt_struct)
                    if rec["last_heard_epoch"] is None or epoch > rec["last_heard_epoch"]:
                        rec["last_heard_epoch"] = epoch
                        rec["last_heard_ago_s"] = int(now - epoch)
                except (ValueError, OverflowError):
                    pass
        except (IndexError, ValueError):
            continue

        if "cli" not in rec["source"]:
            rec["source"].append("cli")

    print(f"  [cli] matched {matched}/{len(fleet)} nodes", file=sys.stderr)
    return matched > 0


# ---------------------------------------------------------------------------
# Source 3: MQTT (brief subscribe for fresh telemetry)
# ---------------------------------------------------------------------------

def query_mqtt(fleet: dict, host: str, port: int, user: str, password: str,
               topic: str = "msh/US/2/2/json/LongFast/#", timeout: float = 10) -> bool:
    """Subscribe to MQTT briefly and collect any messages that arrive."""
    try:
        import paho.mqtt.client as mqtt
    except ImportError:
        print("  [mqtt] paho-mqtt not installed (pip install paho-mqtt)", file=sys.stderr)
        return False

    now = time.time()
    messages = []

    def on_message(_client, _userdata, msg):
        try:
            payload = json.loads(msg.payload.decode())
            messages.append(payload)
        except (json.JSONDecodeError, UnicodeDecodeError):
            pass

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2) if hasattr(mqtt, "CallbackAPIVersion") else mqtt.Client()
    client.username_pw_set(user, password)
    client.on_message = on_message

    try:
        client.connect(host, port, keepalive=15)
        client.subscribe(topic)
        client.loop_start()
        time.sleep(timeout)
        client.loop_stop()
        client.disconnect()
    except Exception as e:
        print(f"  [mqtt] connection failed: {e}", file=sys.stderr)
        return False

    matched = 0
    for msg in messages:
        sender = str(msg.get("sender", msg.get("from", ""))).lstrip("!")
        # Handle decimal sender IDs
        if sender.isdigit():
            sender = format(int(sender), "08x")
        sender = sender.lower()

        if sender not in fleet:
            continue
        matched += 1
        rec = fleet[sender]
        msg_type = msg.get("type", "")

        if msg_type == "telemetry":
            payload = msg.get("payload", {})
            rec["battery_pct"] = payload.get("battery_level", rec["battery_pct"])
            rec["voltage"] = payload.get("voltage", rec["voltage"])
            rec["channel_util"] = payload.get("channel_utilization", rec["channel_util"])
            rec["air_util_tx"] = payload.get("air_util_tx", rec["air_util_tx"])
            rec["uptime_s"] = payload.get("uptime_seconds", rec["uptime_s"])
            rec["last_heard_epoch"] = now
            rec["last_heard_ago_s"] = 0

        elif msg_type == "position":
            payload = msg.get("payload", {})
            lat_i = payload.get("latitude_i")
            lon_i = payload.get("longitude_i")
            if lat_i and lon_i:
                rec["lat"] = lat_i / 1e7
                rec["lon"] = lon_i / 1e7
            rec["alt"] = payload.get("altitude", rec["alt"])
            rec["last_heard_epoch"] = now
            rec["last_heard_ago_s"] = 0

        elif msg_type == "nodeinfo":
            payload = msg.get("payload", {})
            rec["hw_model"] = payload.get("hardware", rec["hw_model"])

        if "mqtt" not in rec["source"]:
            rec["source"].append("mqtt")

    print(f"  [mqtt] received {len(messages)} msgs, matched {matched}/{len(fleet)} nodes", file=sys.stderr)
    return matched > 0


# ---------------------------------------------------------------------------
# Output formatting
# ---------------------------------------------------------------------------

COLORS = {
    "ONLINE": "\033[92m",   # green
    "STALE": "\033[93m",    # yellow
    "OFFLINE": "\033[91m",  # red
    "UNKNOWN": "\033[90m",  # gray
    "BATT": "\033[91m",     # red for battery warnings
    "RESET": "\033[0m",
}


def format_ago(secs) -> str:
    """Human-readable time-ago string."""
    if secs is None or secs < 0:
        return "never"
    if secs < 60:
        return f"{int(secs)}s"
    if secs < 3600:
        return f"{int(secs / 60)}m"
    if secs < 86400:
        h = int(secs / 3600)
        m = int((secs % 3600) / 60)
        return f"{h}h{m:02d}m"
    d = int(secs / 86400)
    h = int((secs % 86400) / 3600)
    return f"{d}d{h}h"


def format_uptime(secs) -> str:
    if secs is None:
        return ""
    d = int(secs / 86400)
    h = int((secs % 86400) / 3600)
    if d > 0:
        return f"{d}d{h}h"
    m = int((secs % 3600) / 60)
    return f"{h}h{m:02d}m"


def format_battery(pct, voltage) -> str:
    parts = []
    if pct is not None:
        parts.append(f"{int(pct)}%")
    if voltage is not None:
        parts.append(f"{voltage:.2f}V")
    return " ".join(parts) if parts else ""


def print_table(fleet: dict, use_color: bool = True):
    """Print a formatted fleet status table."""
    c = COLORS if use_color else {k: "" for k in COLORS}
    now_str = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")

    # Sort: online first, then stale, then offline, then unknown
    order = {"ONLINE": 0, "STALE": 1, "OFFLINE": 2, "UNKNOWN": 3}
    sorted_fleet = sorted(
        fleet.values(),
        key=lambda r: (order.get(r["status"].split()[0], 9), r["name"]),
    )

    print(f"\n{'='*90}")
    print(f"  CrypTAK Fleet Status — {now_str}")
    print(f"  Sources: {', '.join(s for rec in sorted_fleet for s in rec['source']) or 'none'}")
    print(f"{'='*90}")
    print(f"  {'Name':<18} {'ID':<11} {'Profile':<10} {'Status':<18} {'Battery':<12} {'Last Heard':<10} {'SNR':>5} {'Hops':>4}")
    print(f"  {'-'*17} {'-'*10} {'-'*9} {'-'*17} {'-'*11} {'-'*9} {'-'*5} {'-'*4}")

    for rec in sorted_fleet:
        status = rec["status"]
        base_status = status.split()[0]
        sc = c.get(base_status, "")
        if "BATT" in status:
            sc = c["BATT"]

        battery = format_battery(rec["battery_pct"], rec["voltage"])
        ago = format_ago(rec["last_heard_ago_s"])
        snr = f"{rec['snr']:.1f}" if rec["snr"] is not None else ""
        hops = str(rec["hops"]) if rec["hops"] is not None else ""

        print(
            f"  {rec['name']:<18} !{rec['node_id']:<10} {rec['profile']:<10} "
            f"{sc}{status:<18}{c['RESET']} {battery:<12} {ago:<10} {snr:>5} {hops:>4}"
        )

    # Summary line
    statuses = [r["status"].split()[0] for r in sorted_fleet]
    n_online = statuses.count("ONLINE")
    n_stale = statuses.count("STALE")
    n_offline = statuses.count("OFFLINE")
    n_unknown = statuses.count("UNKNOWN")
    print(f"\n  {c['ONLINE']}● {n_online} online{c['RESET']}  "
          f"{c['STALE']}● {n_stale} stale{c['RESET']}  "
          f"{c['OFFLINE']}● {n_offline} offline{c['RESET']}  "
          f"{c['UNKNOWN']}● {n_unknown} unknown{c['RESET']}  "
          f"({len(sorted_fleet)} total)")
    print()


def print_json(fleet: dict):
    """Print fleet as JSON."""
    out = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "nodes": list(fleet.values()),
    }
    print(json.dumps(out, indent=2, default=str))


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _first(d: dict, *keys):
    for k in keys:
        v = d.get(k)
        if v is not None and v != "" and v != "N/A":
            return v
    return None


def _safe_float(s) -> float | None:
    if s is None:
        return None
    try:
        return float(str(s).strip())
    except (ValueError, TypeError):
        return None


def _parse_timestamp(val) -> float | None:
    """Parse various timestamp formats to epoch."""
    if isinstance(val, (int, float)):
        # Already epoch (or epoch-ish)
        if val > 1e9:
            return float(val)
        return None
    if isinstance(val, str):
        for fmt in ("%Y-%m-%dT%H:%M:%S.%fZ", "%Y-%m-%dT%H:%M:%SZ",
                     "%Y-%m-%d %H:%M:%S", "%Y-%m-%dT%H:%M:%S%z"):
            try:
                dt = datetime.strptime(val, fmt)
                if dt.tzinfo is None:
                    dt = dt.replace(tzinfo=timezone.utc)
                return dt.timestamp()
            except ValueError:
                continue
    return None


def load_mqtt_password() -> str:
    """Try to load MQTT password from secrets.sh if not in env."""
    pw = os.environ.get("MQTT_PASS", "")
    if pw:
        return pw
    # Try firmware/secrets.sh
    secrets = REPO_ROOT / "firmware" / "secrets.sh"
    if secrets.exists():
        with open(secrets) as f:
            for line in f:
                m = re.match(r'export\s+MQTT_PASS(?:WORD)?[=]"?([^"]+)"?', line)
                if m:
                    return m.group(1)
    return ""


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="CrypTAK Fleet Status — query mesh node fleet",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--source", "-s", action="append",
        choices=["meshmon", "cli", "mqtt", "all"],
        help="Data source(s) to query (default: auto-detect, try all)",
    )
    parser.add_argument("--json", "-j", action="store_true", help="Output as JSON")
    parser.add_argument("--no-color", action="store_true", help="Disable color output")
    parser.add_argument(
        "--watch", "-w", type=int, nargs="?", const=60, metavar="SECS",
        help="Re-poll every N seconds (default: 60)",
    )
    parser.add_argument(
        "--mqtt-timeout", type=float, default=10,
        help="Seconds to listen on MQTT (default: 10)",
    )
    parser.add_argument(
        "--nodes-yaml", type=Path, default=NODES_YAML,
        help=f"Path to nodes.yaml (default: {NODES_YAML})",
    )
    args = parser.parse_args()

    # Resolve sources
    sources = set()
    if args.source:
        for s in args.source:
            if s == "all":
                sources = {"meshmon", "cli", "mqtt"}
            else:
                sources.add(s)
    else:
        sources = {"meshmon", "cli", "mqtt"}  # Try all, each fails gracefully

    # Load registry
    try:
        registry = load_registry(args.nodes_yaml)
    except FileNotFoundError:
        print(f"Error: nodes.yaml not found at {args.nodes_yaml}", file=sys.stderr)
        sys.exit(1)

    print(f"Loaded {len(registry)} nodes from registry", file=sys.stderr)

    while True:
        # Build fleet from registry
        fleet = {nid: new_fleet_record(nid, reg) for nid, reg in registry.items()}

        # Query sources
        if "meshmon" in sources:
            url = os.environ.get("MESHMON_URL", "http://192.168.50.120:8090")
            token = os.environ.get("MESHMON_TOKEN", "")
            query_meshmonitor(fleet, url, token)

        if "cli" in sources:
            port = os.environ.get("MESH_PORT", "/dev/ttyACM0")
            host = os.environ.get("MESH_HOST", "")
            query_meshtastic_cli(fleet, port=port, host=host or None)

        if "mqtt" in sources:
            mqtt_host = os.environ.get("MQTT_HOST", "192.168.50.120")
            mqtt_port = int(os.environ.get("MQTT_PORT", "1883"))
            mqtt_user = os.environ.get("MQTT_USER", "meshtastic")
            mqtt_pass = load_mqtt_password()
            if mqtt_pass:
                query_mqtt(fleet, mqtt_host, mqtt_port, mqtt_user, mqtt_pass,
                           timeout=args.mqtt_timeout)
            else:
                print("  [mqtt] no password configured (set MQTT_PASS or check firmware/secrets.sh)",
                      file=sys.stderr)

        # Compute status for all nodes
        for rec in fleet.values():
            rec["status"] = compute_status(rec)

        # Output
        if args.json:
            print_json(fleet)
        else:
            print_table(fleet, use_color=not args.no_color)

        if args.watch:
            try:
                time.sleep(args.watch)
            except KeyboardInterrupt:
                break
        else:
            break


if __name__ == "__main__":
    main()
