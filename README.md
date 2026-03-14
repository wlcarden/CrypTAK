<p align="center">
  <img src="logo.png" alt="CrypTAK" width="120">
</p>

# CrypTAK

Privacy-focused situational awareness over LoRa mesh radio. CrypTAK bridges
[Meshtastic](https://meshtastic.org/) mesh networks to
[FreeTAKServer](https://github.com/FreeTAKTeam/FreeTAKServer) with AES-256-GCM
content encryption, a real-time WebMap, automated incident detection, and a
field-deployable command unit — all self-hosted, no cloud dependencies.

CrypTAK traffic travels as standard Meshtastic packets relayed by any node on
the channel, including community infrastructure. The AES-256-GCM layer ensures
message content stays confidential even through nodes you don't control.

Remote access uses a self-hosted WireGuard VPN
([headscale](https://headscale.net/)) with OIDC authentication
([Authelia](https://www.authelia.com/)), so the server is never exposed
directly to the internet.

> **Privacy scope:** Encryption hides message content. It does not hide that
> transmissions are occurring, when, or from where. RF metadata (timing, signal
> strength, triangulation) is observable to anyone monitoring the spectrum.

---

## Architecture

```
                      ┌──────────────────────────────────────────────────────┐
                      │                   Home Server (Unraid)               │
                      │                                                      │
                      │  ┌─────────────┐  ┌──────────┐  ┌───────────────┐   │
                      │  │ FreeTAK     │  │ Node-RED  │  │ Mesh Relay    │   │
                      │  │ Server      │  │ WebMap    │  │ (relay.py)    │   │
                      │  │ :8087 CoT   │  │ :1880     │  │               │   │
                      │  └──────┬──────┘  └─────┬─────┘  └───────┬───────┘   │
                      │         │               │                │           │
                      │         └───────┬───────┘                │           │
                      │                 │ taknet (Docker)        │           │
                      │  ┌──────────┐  ┌┴─────────┐  ┌──────────┴────────┐  │
                      │  │ Headscale│  │ Mosquitto │  │ T-Beam Bridge     │  │
                      │  │ VPN      │  │ MQTT      │  │ (USB serial)      │  │
                      │  │ :9443    │  │ :1883     │  │                   │  │
                      │  └──────────┘  └──────────┘  └─────────┬─────────┘  │
                      └──────────────────────────────────────────┼───────────┘
                                                                 │
                             ┌────────── LoRa 915 MHz ───────────┤
                             │                                   │
                      ┌──────┴──────┐                     ┌──────┴──────┐
                      │ Solar Relay │◄── LoRa ──►         │ Base Station│
                      │ (SOL01)     │            │        │ (BSE01)     │
                      └─────────────┘     ┌──────┴──────┐ └─────────────┘
                                          │ Vehicle     │
┌─────────────────┐                       │ (VHC01)     │
│  Android Device │──BT/USB──►Meshtastic  └─────────────┘
│  ATAK-CIV +     │  radio node
│  CrypTAK Plugin │
│  (AES-256-GCM)  │──WiFi/VPN──► FreeTAKServer :8087
└─────────────────┘
```

**Encryption boundary:** The CrypTAK plugin encrypts all ATAK payloads (chat,
PLI, CoT events) with AES-256-GCM before they leave the phone. The server
relays ciphertext — it never sees plaintext. Decryption happens only on devices
that hold the shared key.

**Data paths (both active simultaneously):**

- **WiFi/VPN:** ATAK devices connect directly to FreeTAKServer over TCP for
  full-rate CoT exchange with server persistence.
- **LoRa mesh:** When WiFi is unavailable, CoT events are encrypted and sent
  over the Meshtastic mesh. The mesh relay service on the server converts mesh
  positions to CoT and injects them into FTS, so mesh-only nodes appear on
  every connected client's map.
- **Detection alerts:** GPIO sensor events from mesh nodes are forwarded as
  CoT alarm markers with position and alert text.

---

## Repository Layout

```
CrypTAK/
├── plugin/                    ATAK plugin — AES-256-GCM encryption + Meshtastic bridge
├── server/                    Self-hosted server stack
│   ├── docker-compose.yml     Full Unraid deployment (10 services)
│   ├── docker-compose.field.yml  Field unit (FTS + Mumble + HaLow bridge)
│   ├── mesh-relay/            Meshtastic → FTS relay service (relay.py)
│   ├── incident-tracker/      Automated incident monitoring (RSS, NWS, USGS, etc.)
│   ├── halow-bridge/          Field-to-home CoT bridge with offline buffering
│   ├── nodered/               Node-RED WebMap + mesh panel + CoT pipeline
│   ├── field-services/        systemd units for reTerminal (GPS bridge, power button)
│   ├── scripts/               Server-side scripts (GPS-MQTT bridge, power handler)
│   ├── fts-patches/           FTS 2.2.1 bug patches (5 files)
│   ├── headscale/             VPN config (nginx, ACLs)
│   └── authelia/              OIDC authentication config
├── firmware/                  Meshtastic node profiles and provisioning
│   ├── profiles/              Role-based configs (bridge, relay, tracker, field, vehicle)
│   ├── nodes.yaml             Node registry
│   └── provision.sh           Auto-provisioning script
├── docs/                      Deployment, architecture, hardware, security
└── scripts/                   Dev tools (install, build, SDK setup, field Pi setup)
```

---

## Hardware Requirements

### Home Server

Any Docker-capable host with 4+ GB RAM. Tested on Unraid (192.168.50.120).

- Docker + Docker Compose
- USB port for T-Beam bridge node (serial connection)
- Network access for ATAK clients (WiFi/LAN or VPN)

### Meshtastic Nodes

At minimum, one bridge node connected to the server. Additional nodes extend
mesh coverage. See [docs/node-types.md](docs/node-types.md) for role details.

| Role               | Recommended Hardware                     | Purpose                    |
| ------------------ | ---------------------------------------- | -------------------------- |
| Bridge (BRG)       | LilyGo T-Beam / RAK WisMesh WiFi Gateway | Server ↔ mesh gateway      |
| Base Station (BSE) | Seeed SenseCAP Solar P1-Pro              | Fixed relay, solar powered |
| Solar Relay (SOL)  | RAK WisMesh Repeater Mini                | Outdoor mesh repeater      |
| Vehicle (VHC)      | LilyGo T-Beam Supreme                    | Mobile repeater + GPS      |
| Tracker (TRK)      | Seeed Card Tracker T1000-E               | GPS asset tracking         |

See [docs/hardware-builds.md](docs/hardware-builds.md) for assembly and
flashing procedures.

### Field Unit (optional)

Seeed reTerminal (CM4) with E10-1 expansion board, or Raspberry Pi 4B. Runs a
lightweight FTS + Mumble stack for offline field operations. See
[docs/field-unit.md](docs/field-unit.md).

### ATAK Devices

Android phone or tablet running [ATAK-CIV](https://www.tak.gov/) 5.5.1+ with
the CrypTAK plugin.

---

## Node Types

CrypTAK uses a profile-based provisioning system. Each node gets a role from
`firmware/profiles/`:

| Profile           | Meshtastic Role | GPS                  | Use Case                             |
| ----------------- | --------------- | -------------------- | ------------------------------------ |
| `bridge`          | CLIENT          | On                   | T-Beam server bridge (serial + MQTT) |
| `relay`           | ROUTER          | Off (fixed position) | Solar scatter relay, base station    |
| `tracker`         | TRACKER         | On (15s updates)     | Vehicle/person GPS tracking          |
| `field`           | CLIENT          | On                   | General field node                   |
| `vehicle`         | ROUTER_CLIENT   | On                   | Mobile repeater (vehicle-mounted)    |
| `prototype-solar` | ROUTER          | Off                  | Enhanced telemetry (probe sensors)   |

Provision a node with one command:

```bash
./firmware/provision.sh "CrypTAK-BSE01"
```

See [docs/node-types.md](docs/node-types.md) for the full reference and
[firmware/README.md](firmware/README.md) for provisioning details.

---

## Quick Start

### 1. Server

```bash
cd server/
cp .env.example .env
# Edit .env: set UNRAID_IP, generate secrets for all FTS_* variables
docker compose up -d freetakserver    # start FTS first (needs ~60s to init)
docker compose up -d                  # start remaining services
```

### 2. Meshtastic Nodes

```bash
# Provision a registered node (plug in USB, run one command)
./firmware/provision.sh "CrypTAK-BRG01"
```

See [firmware/README.md](firmware/README.md) for per-device procedures.

### 3. ATAK Plugin

> **Note:** CrypTAK Plugin replaces the upstream
> [Meshtastic ATAK Plugin](https://github.com/meshtastic/ATAK-Plugin). They
> share the same package name and cannot coexist.

```bash
cd plugin/
# Place ATAK Plugin SDK files in plugin/sdk/ first
./gradlew assembleCivDebug
../scripts/install-plugin.sh
```

See [plugin/README.md](plugin/README.md) for encryption setup and key
distribution.

### 4. Mesh Relay (optional)

Bridges mesh node positions to the TAK map. Enable with:

```bash
docker compose --profile mesh-relay up -d
```

Configure `FRIENDLY_NODES` and `TRACKER_NODES` in `.env` to control marker
affiliation (blue vs. green vs. orange on the map).

### 5. Incident Tracker (optional)

Polls RSS/NWS/USGS/Waze/etc., filters by area and keywords, injects CoT
markers into FTS:

```bash
cd server/incident-tracker/
cp config.yaml config.local.yaml
# Edit config.local.yaml — set geo_filter, enable sources
docker compose --profile incident-tracker up -d
```

See [server/incident-tracker/README.md](server/incident-tracker/README.md).

### 6. Field Unit (optional)

Deploy a portable FTS + voice server on a reTerminal or Pi 4:

```bash
cd server/
cp .env.field.example .env.field
# Edit .env.field — change all passwords
docker compose -f docker-compose.field.yml --env-file .env.field up -d
```

See [docs/field-unit.md](docs/field-unit.md) and
[docs/deployment-runbook.md](docs/deployment-runbook.md).

---

## Key Distribution

The shared AES-256 key is provisioned to each device via one of:

- **QR code** — display on one device, scan on another (in-app camera scanner)
- **Data Package** — export a `.zip` to share via file transfer or side-channel
- **Manual entry** — paste a Base64-encoded key directly

All methods are accessible from the plugin's encryption settings screen.

---

## Documentation

| Document                                                     | Description                             |
| ------------------------------------------------------------ | --------------------------------------- |
| [docs/architecture.md](docs/architecture.md)                 | System architecture and data flows      |
| [docs/node-types.md](docs/node-types.md)                     | Node roles, hardware, provisioning      |
| [docs/field-unit.md](docs/field-unit.md)                     | reTerminal field unit setup             |
| [docs/deployment-runbook.md](docs/deployment-runbook.md)     | 17-step deployment checklist            |
| [docs/hardware-builds.md](docs/hardware-builds.md)           | Device assembly and flashing            |
| [docs/network-architecture.md](docs/network-architecture.md) | Network topology and encryption layers  |
| [docs/halow-field-kit.md](docs/halow-field-kit.md)           | HaLow 802.11ah field kit (experimental) |
| [docs/security.md](docs/security.md)                         | Security model and hardening guide      |
| [CHANGELOG.md](CHANGELOG.md)                                 | Version history                         |
| [CONTRIBUTING.md](CONTRIBUTING.md)                           | Contributor guide                       |

---

## Prerequisites

| Requirement                       | Source                                                                                                 |
| --------------------------------- | ------------------------------------------------------------------------------------------------------ |
| ATAK-CIV APK                      | [tak.gov](https://www.tak.gov/) or [ATAK.app](https://www.atak.app/)                                   |
| ATAK Plugin SDK (`pluginsdk.zip`) | [TAK Product Center GitHub](https://github.com/TAK-Product-Center/atak-civ) — extract to `plugin/sdk/` |
| Android SDK                       | Standard Android development install                                                                   |
| Docker + Docker Compose           | For the server stack                                                                                   |
| Meshtastic hardware               | RAK WisBlock, LilyGo T-Beam, or Seeed SenseCAP — 868/915 MHz                                           |
| Python 3.10+                      | For `meshtastic` CLI and relay service                                                                 |

The ATAK SDK is subject to TAK Product Center terms and is not included in this
repository.

---

## Components

| Component                                                     | Role                         | License                    |
| ------------------------------------------------------------- | ---------------------------- | -------------------------- |
| [Meshtastic](https://meshtastic.org/)                         | LoRa mesh radio firmware     | Apache 2.0                 |
| [ATAK-CIV](https://www.tak.gov/)                              | Tactical awareness platform  | TAK Product Center terms   |
| [FreeTAKServer](https://github.com/FreeTAKTeam/FreeTAKServer) | CoT relay server             | Eclipse Public License 2.0 |
| [headscale](https://headscale.net/)                           | Self-hosted WireGuard VPN    | BSD 3-Clause               |
| [Authelia](https://www.authelia.com/)                         | OIDC authentication          | Apache 2.0                 |
| [Node-RED](https://nodered.org/)                              | WebMap + CoT pipeline        | Apache 2.0                 |
| [Mosquitto](https://mosquitto.org/)                           | MQTT broker                  | Eclipse Public License 2.0 |
| [Mumble](https://www.mumble.info/)                            | Encrypted voice (field unit) | BSD 3-Clause               |

---

## License

Apache 2.0 — see [LICENSE](LICENSE).

---

## Export Notice

This project implements AES-256 encryption. Encryption software may be subject
to U.S. Export Administration Regulations (EAR). Open-source encryption is
generally authorized under EAR §740.13(e). Review applicable regulations before
distributing outside the United States.
