# Node Types

Reference for CrypTAK Meshtastic node roles, hardware, and provisioning.

---

## Profiles

Each node is assigned a profile from `firmware/profiles/`. The provisioning
script applies the profile, sets channel config, enables NeighborInfo, and
configures the node identity.

| Profile           | Meshtastic Role | GPS         | Tx Power | Position Broadcast    | Telemetry Interval |
| ----------------- | --------------- | ----------- | -------- | --------------------- | ------------------ |
| `bridge`          | CLIENT          | On          | 30 dBm   | 300s (smart)          | 60s                |
| `relay`           | ROUTER          | Off (fixed) | 30 dBm   | 300s                  | 60s                |
| `tracker`         | TRACKER         | On (15s)    | 30 dBm   | 60s (smart, 30s min)  | 60s                |
| `field`           | CLIENT          | On (30s)    | 30 dBm   | 120s (smart, 60s min) | 60s                |
| `vehicle`         | ROUTER_CLIENT   | On (15s)    | 30 dBm   | 60s (smart, 30s min)  | 60s                |
| `prototype-solar` | ROUTER          | Off         | 30 dBm   | 300s                  | 60s                |

### Bridge (`bridge.yaml`)

The bridge node connects the mesh network to the server. It has two connection
paths: USB serial (preferred — no WiFi dependency, no idle timeout) and TCP
over WiFi (fallback). The relay service (`relay.py`) connects to the bridge to
receive position packets from the entire mesh.

**Hardware:** LilyGo T-Beam (ESP32, with GPS and WiFi)

**Configuration highlights:**

- CLIENT role (does not relay for other nodes — server-side relay handles this)
- Serial enabled for direct USB connection to server
- MQTT bridge enabled (uplink + downlink on channel 0)
- Smart positioning on (broadcasts when position changes)
- Power saving off (always connected)

### Relay (`relay.yaml`)

Fixed-position mesh relay nodes. Deployed at elevated locations for maximum
coverage. Do not have GPS enabled — position is set during provisioning and
stored as a fixed coordinate.

**Hardware:** Seeed SenseCAP Solar P1-Pro, RAK WisMesh Repeater Mini, or
legacy RAK WisBlock (RAK4631)

**Configuration highlights:**

- ROUTER role (actively forwards packets for other nodes)
- Smart positioning off (never broadcasts — position is fixed)
- Position broadcast 300s (for nodedb updates only)
- GPS off (coordinates set in `nodes.yaml`)

### Tracker (`tracker.yaml`)

GPS asset tracking nodes. Aggressively report position for real-time tracking
on the TAK map. Appear as suspect/orange markers by default (controllable from
WebMap).

**Hardware:** Seeed Card Tracker T1000-E, or any GPS-equipped Meshtastic node

**Configuration highlights:**

- TRACKER role (battery optimized, position-focused)
- GPS update every 15s
- Position broadcast every 60s, smart minimum 30s
- Power saving off

### Field (`field.yaml`)

General-purpose field nodes carried by team members. Balanced between position
update frequency and battery life.

**Hardware:** Any Meshtastic node with GPS

**Configuration highlights:**

- CLIENT role
- GPS update every 30s
- Position broadcast every 120s, smart minimum 60s

### Vehicle (`vehicle.yaml`)

Mobile repeater nodes mounted in vehicles. Combines ROUTER_CLIENT role
(forwards for others while also originating traffic) with aggressive GPS
tracking.

**Hardware:** LilyGo T-Beam Supreme (ESP32-S3, GPS, WiFi, 18650 battery)

**Configuration highlights:**

- ROUTER_CLIENT role (relay + originate)
- Same GPS/position settings as tracker (15s GPS, 60s broadcast)
- Power saving off (vehicle power assumed)

### Prototype Solar (`prototype-solar.yaml`)

Experimental profile with enhanced telemetry for testing solar charging and
environmental monitoring hardware.

**Configuration highlights:**

- ROUTER role
- GPS off
- Environmental measurement enabled (probe sensors)
- Power measurement enabled

---

## Node Registry

`firmware/nodes.yaml` maps node names to their profile and identity:

```yaml
nodes:
  CrypTAK-BRG01: # T-Beam bridge
    profile: bridge
    short_name: CG01

  CrypTAK-BSE01: # Base station (fixed position)
    profile: relay
    short_name: CB01
    gps: false
    latitude: 38.84191827
    longitude: -77.29344941
    altitude: 155

  CrypTAK-SOL01: # Solar relay
    profile: relay
    short_name: CS01

  CrypTAK-VHC01: # Vehicle node
    profile: vehicle
    short_name: CV01

  CrypTAK-TRK01: # GPS tracker
    profile: tracker
    short_name: CT01

  CrypTAK-SOL02: # Prototype solar (enhanced telemetry)
    profile: prototype-solar
    short_name: CS02
```

---

## Provisioning

### Automated (recommended)

```bash
# List all registered nodes
./firmware/provision.sh --list

# Provision by name (auto-detects serial port and hardware)
./firmware/provision.sh "CrypTAK-BSE01"

# Interactive provisioning for a new node
./firmware/provision.sh
```

The script performs these steps:

1. Detect serial port and hardware type
2. Apply profile via `meshtastic --configure`
3. Set channel position precision to 32 (prevents ~5km coordinate truncation)
4. Enable NeighborInfo module (mesh topology visualization)
5. Set admin key (trusts T-Beam bridge for remote admin over mesh)
6. Set owner name and 4-char short name
7. Set fixed position (for relay/base nodes with coordinates in `nodes.yaml`)
8. Verify config readback

### Manual

If not using `provision.sh`, these settings must be applied to every node:

```bash
# Position precision (prevents 5km truncation — CRITICAL for TAK)
meshtastic --port /dev/ttyACM0 \
  --ch-set module_settings.position_precision 32 --ch-index 0

# NeighborInfo (enables topology visualization on WebMap)
meshtastic --port /dev/ttyACM0 --set neighbor_info.enabled true

# Admin key (enables remote management from T-Beam bridge)
meshtastic --port /dev/ttyACM0 \
  --set security.admin_key "base64:FVmX/5EbFDNF8D1IB5rT6UaDil6dacMR9vpjOqoy0Eo="
```

---

## TAK Map Appearance

How nodes appear on ATAK clients and the WebMap depends on their classification
in the relay service:

| Classification  | CoT Type    | Map Color     | Configured By                  |
| --------------- | ----------- | ------------- | ------------------------------ |
| Friendly        | `a-f-G-E-S` | Blue (Cyan)   | `FRIENDLY_NODES` env var       |
| Neutral         | `a-n-G-E-S` | Green         | Default for unknown nodes      |
| Tracker/Suspect | `a-s-G-O-E` | Orange/Yellow | `TRACKER_NODES` env var        |
| Detection Alert | `a-u-G`     | Red           | Automatic (GPIO sensor events) |

The relay service (`relay.py`) adds telemetry as a `__meshTelemetry` CoT detail
tag containing battery voltage, channel utilization, air utilization TX,
uptime, SNR, and hop count. Node-RED reads these to populate the mesh panel on
the WebMap.

---

## Remote Administration

Once provisioned, the T-Beam bridge can manage any node in the mesh remotely
using Meshtastic PKC (Public Key Cryptography):

```bash
# Reboot a remote node
meshtastic --host 192.168.50.198 --dest a51e2838 --reboot

# Change a remote setting
meshtastic --host 192.168.50.198 --dest a51e2838 --set device.role CLIENT
```

The `--dest` flag targets by node ID (without `!` prefix). The target verifies
the sender's public key against its `security.admin_key`.

---

## Channel Strategy

All nodes operate on the **default public LongFast channel** (PSK `AQ==`):

- ROUTER nodes relay for the broader Meshtastic community
- CrypTAK benefits from community relay infrastructure
- Position broadcasts are visible to any Meshtastic user in range
- TAK payload confidentiality is provided by the ATAK plugin's AES-256-GCM
  encryption, not the mesh channel

This is an intentional design choice — the mesh is open, the payload is
encrypted.
