# Meshtastic Firmware and Device Configuration

## Quick Start — Provisioning a Node

```bash
# List registered nodes
./firmware/provision.sh --list

# Provision a registered node (plug in USB, run one command)
./firmware/provision.sh "CrypTAK Base"

# Provision a new node interactively
./firmware/provision.sh
```

The provisioning script handles everything: profile config, channel settings, neighbor info, admin keys, owner name, and fixed position.

## Prerequisites

```bash
pip install meshtastic
```

Alternatively, use the [Meshtastic Android app](https://play.google.com/store/apps/details?id=com.geeksville.mesh) for configuration over Bluetooth.

## Profiles

Role-based configuration templates in `profiles/`:

| Profile   | Role    | GPS                  | Use Case                                   |
| --------- | ------- | -------------------- | ------------------------------------------ |
| `relay`   | ROUTER  | Off (fixed position) | Solar scatter relays, base stations        |
| `tracker` | TRACKER | On (15s updates)     | Vehicle/person tracking, battery optimized |
| `bridge`  | CLIENT  | On                   | T-Beam TAK server bridge (serial enabled)  |
| `field`   | CLIENT  | On                   | General field node                         |

## Node Registry

`nodes.yaml` maps node names to their profile and identity. The provisioning script looks up nodes by name and applies the correct configuration. New nodes can be added interactively or by editing the file directly.

```yaml
nodes:
  CrypTAK Relay North:
    profile: relay
    short_name: RLN
    latitude: 38.8455
    longitude: -77.2901
    altitude: 50
```

## Hardware

| Device                   | Firmware                              | Notes                        |
| ------------------------ | ------------------------------------- | ---------------------------- |
| RAK19007 + RAK4631       | `firmware-rak4631-2.7.15.567b8ea.uf2` | Standard relay/tracker board |
| RAK WisBlock 5005 + 4630 | `firmware-rak4631-2.7.15.567b8ea.uf2` | Older board, same firmware   |
| LilyGo T-Beam            | ESP32 — use Web Flasher               | TAK bridge node              |

Both RAK boards use the same RAK4631 core module and firmware. The provisioning script auto-detects hardware.

## Flashing Firmware

### RAK WisBlock (nRF52840)

1. Connect via USB.
2. Double-press the reset button. A USB drive named `RAK4631` appears.
3. Drag and drop `firmware-rak4631-2.7.15.567b8ea.uf2` onto the drive.
4. The device reboots automatically.

### LilyGo T-Beam (ESP32)

1. Connect via USB.
2. Open https://flasher.meshtastic.org in Chrome.
3. Select board variant `TBEAM`.
4. Follow on-screen prompts.

## What Provisioning Does

The `provision.sh` script runs these steps automatically:

1. **Detect** serial port and hardware
2. **Apply profile** via `meshtastic --configure`
3. **Channel config** — position precision 32 (prevents ~5km coordinate truncation)
4. **NeighborInfo** — enables mesh topology visualization on the WebMap
5. **Admin key** — trusts the T-Beam bridge for remote admin over mesh (PKC)
6. **Owner name** — sets the node's display name and 4-char short name
7. **Fixed position** — sets coordinates for relay nodes
8. **Verify** — reads back config to confirm

### Manual Post-Configure (if not using provision.sh)

```bash
# Full position precision (all devices)
meshtastic --port /dev/ttyACM0 --ch-set module_settings.position_precision 32 --ch-index 0

# NeighborInfo module (all devices)
meshtastic --port /dev/ttyACM0 --set neighbor_info.enabled true

# Admin key (all except bridge)
meshtastic --port /dev/ttyACM0 --set security.admin_key "base64:FVmX/5EbFDNF8D1IB5rT6UaDil6dacMR9vpjOqoy0Eo="
```

## Remote Administration

Once provisioned, the T-Beam bridge can remotely manage any node in the mesh:

```bash
# Reboot a remote node
meshtastic --host 192.168.50.198 --dest a51e2838 --reboot

# Change a remote node's setting
meshtastic --host 192.168.50.198 --dest a51e2838 --set device.role CLIENT
```

The `--dest` flag targets a node by ID (without the `!` prefix). Remote admin uses Meshtastic PKC — the target verifies the sender's public key against its `security.admin_key`.

## Channel Strategy

All devices operate on the **default public LongFast channel** (PSK `AQ==`):

- Our ROUTER nodes relay traffic for the broader Meshtastic community
- We benefit from other users' relay infrastructure
- Any Meshtastic user in range can see our position broadcasts

TAK payloads (chat, markers, routes) are protected by the **ATAK plugin's AES-256-GCM encryption**, not the mesh channel. This provides end-to-end encryption while keeping the mesh open.

## Encryption Layers

| Layer                     | Key                | Protects                             | Scope             |
| ------------------------- | ------------------ | ------------------------------------ | ----------------- |
| LoRa channel (Meshtastic) | Default PSK `AQ==` | Link-layer — public                  | All mesh traffic  |
| App-layer (ATAK plugin)   | AES-256-GCM key    | TAK payloads — chat, markers, routes | Team devices only |
