# fleet-status

Quick CLI tool to query the CrypTAK mesh node fleet and print a status table.

## Usage

```bash
# Auto-detect all available sources
./fleet_status.py

# Specific source only
./fleet_status.py --source mqtt
./fleet_status.py --source cli
./fleet_status.py --source meshmon

# JSON output (for scripting)
./fleet_status.py --json

# Watch mode (re-poll every 60s)
./fleet_status.py --watch
./fleet_status.py --watch 30   # every 30s

# Longer MQTT listen window
./fleet_status.py --source mqtt --mqtt-timeout 30
```

## Data Sources

| Source | What it queries | Requirements |
|--------|----------------|--------------|
| `meshmon` | MeshMonitor API at :8090 | Network access to Unraid, `MESHMON_TOKEN` |
| `cli` | `meshtastic --nodes` | meshtastic CLI installed, serial/TCP to gateway |
| `mqtt` | Mosquitto broker :1883 | `paho-mqtt`, MQTT credentials |

All sources are tried by default and fail gracefully if unavailable.

## Configuration

Set via environment variables or `firmware/secrets.sh`:

```bash
export MQTT_HOST=192.168.50.120
export MQTT_USER=meshtastic
export MQTT_PASS=<password>
export MESHMON_URL=http://192.168.50.120:8090
export MESHMON_TOKEN=<bearer-token>
export MESH_PORT=/dev/ttyACM0     # serial
export MESH_HOST=192.168.50.198   # TCP fallback
```

## Dependencies

- Python 3.10+
- `paho-mqtt` (optional, for MQTT source): `pip install paho-mqtt`
- `meshtastic` CLI (optional, for CLI source): `pip install meshtastic`
