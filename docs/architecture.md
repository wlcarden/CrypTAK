# System Architecture

## Overview

CrypTAK is a self-hosted tactical awareness system that bridges Meshtastic LoRa
mesh networks to ATAK (Android Team Awareness Kit) via FreeTAKServer. The system
operates in two deployment modes: a full home server stack (Unraid) and a
portable field unit (reTerminal or Raspberry Pi 4).

---

## Home Server Stack

Ten Docker services on a single Unraid host, connected via the `taknet` bridge
network.

```
Internet / LAN
     │
     ├── :8087  ── FreeTAKServer ──── CoT relay (ATAK client connections)
     ├── :8089  ── FreeTAKServer ──── CoT SSL
     ├── :8080  ── FreeTAKServer ──── Data Package HTTP
     ├── :8443  ── FreeTAKServer ──── Data Package HTTPS
     ├── :1880  ── Node-RED ───────── WebMap UI (/tak-map)
     ├── :1883  ── Mosquitto ──────── MQTT (mesh bridge, auth required)
     ├── :8082  ── nginx ──────────── Headscale control plane (Cloudflare)
     ├── :9443  ── nginx ──────────── Direct TLS (VPN enrollment)
     │
     └── localhost only:
         ├── :5000  ── FTS-UI ──────── Admin panel
         ├── :19023 ── FreeTAKServer ── REST API
         ├── :8083  ── headscale-ui ── VPN admin
         └── :9090  ── headscale ───── Prometheus metrics
```

### Service Roles

| Service              | Image                                      | Purpose                                     |
| -------------------- | ------------------------------------------ | ------------------------------------------- |
| **freetakserver**    | ghcr.io/freetakteam/freetakserver          | CoT relay, client management, data packages |
| **freetakserver-ui** | ghcr.io/freetakteam/ui                     | Admin web panel (user management, API key)  |
| **nodered**          | nodered/node-red:4.1                       | WebMap, mesh panel, CoT processing pipeline |
| **mosquitto**        | eclipse-mosquitto:2                        | MQTT broker for Meshtastic MQTT bridge      |
| **mesh-relay**       | Built from `./mesh-relay`                  | Meshtastic serial/TCP → FTS CoT bridge      |
| **authelia**         | ghcr.io/authelia/authelia:4.39             | OIDC authentication for VPN enrollment      |
| **headscale-nginx**  | ghcr.io/nginxinc/nginx-unprivileged:alpine | Reverse proxy (TLS, OIDC routing)           |
| **headscale**        | ghcr.io/juanfont/headscale:0.28            | WireGuard VPN coordination server           |
| **headscale-ui**     | ghcr.io/gurucomputing/headscale-ui         | VPN admin UI                                |
| **github-runner**    | myoung34/github-runner                     | CI/CD self-hosted runner                    |
| **incident-tracker** | Built from `./incident-tracker`            | Event monitoring + CoT injection (optional) |

### Data Flow — Mesh Position to TAK Map

```
Meshtastic node broadcasts position
         │
         ▼
T-Beam bridge (USB serial to Unraid)
         │
         ▼
mesh-relay (relay.py)
  ├── on_position(): extract lat/lon/alt/telemetry from packet
  ├── _seed_from_nodedb(): poll cached positions every 120s
  │   (firmware suppresses duplicate positions — nodedb compensates)
  ├── build_pli(): generate CoT XML with affiliation + telemetry tags
  └── FtsClient.send(): TCP to FTS :8087
         │
         ▼
FreeTAKServer relays CoT to all connected clients
  ├── ATAK devices (TCP :8087)
  └── Node-RED (TCP subscription)
         │
         ▼
Node-RED cot-maps.js
  ├── parseCotToMarker(): CoT XML → Leaflet marker
  ├── Mesh telemetry → sidebar panel
  ├── NeighborInfo → topology polylines
  └── worldmap node → browser (/tak-map)
```

### Data Flow — Detection Sensor Alert

```
Meshtastic node GPIO triggers DETECTION_SENSOR_APP
         │
         ▼
mesh-relay on_detection()
  ├── Look up node's last known position
  ├── build_detection_alert(): CoT alarm marker (red, 5-min stale)
  └── FtsClient.send() → FTS
         │
         ▼
ATAK clients + WebMap show red alarm marker with alert text
```

---

## Field Server Stack

Three Docker services on a reTerminal (CM4) or Raspberry Pi 4, plus three
native systemd services for hardware integration.

```
ATAK Phones                    reTerminal / Pi 4
     │                         ┌──────────────────────────────┐
     │ WiFi AP (192.168.73.1)  │  Docker (fieldnet):          │
     ├─────────────────────────┤    freetakserver (:8087)     │
     │                         │    mumble (:64738)           │
     │                         │    halow-bridge              │
     │                         │                              │
     │                         │  systemd services:           │
     │                         │    ec25-gnss (AT+QGPS=1)    │
     │                         │    gps-mqtt-bridge           │
     │                         │    power-button              │
     │                         └──────────────┬───────────────┘
     │                                        │
     │                                        │ Tailscale VPN
     │                                        │ (when internet available)
     │                                        ▼
     │                                   Home Unraid FTS
```

### Field Data Flow

1. ATAK phones connect to reTerminal's WiFi AP (hostapd, 192.168.73.1)
2. ATAK clients send CoT to local FTS on :8087
3. EC25-EU modem provides GNSS fix → gpsd → gps-mqtt-bridge → MQTT
4. Node-RED field flows subscribe to `cryptak/field/gps/tpv`, build CoT PLI
   for the field unit itself (callsign CRYPTAK-TERM01)
5. halow-bridge reads CoT from local FTS, buffers in ring buffer
6. When Tailscale VPN is up, halow-bridge forwards buffered + live CoT to
   home Unraid FTS
7. When VPN drops, halow-bridge accumulates events (max 5000) for later sync

---

## Encryption Layers

Traffic is protected at three independent layers:

| Layer        | Algorithm                     | Key                                 | Scope                          |
| ------------ | ----------------------------- | ----------------------------------- | ------------------------------ |
| LoRa channel | AES-256-CTR                   | Channel PSK (`AQ==` public default) | All mesh traffic (RF link)     |
| App-layer    | AES-256-GCM                   | Passphrase-derived (SHA-256)        | TAK payloads only (end-to-end) |
| VPN          | WireGuard (ChaCha20-Poly1305) | Per-device key pair                 | Server ↔ phone (remote access) |

The app-layer encryption ensures CoT payloads remain confidential even if the
mesh channel key is compromised or the mesh is open (as it is by default). The
VPN layer protects remote server access over untrusted networks.

---

## VPN Architecture

```
Phone (Tailscale app)
  │
  ├── LAN enrollment: http://192.168.50.120:8082
  │   └── nginx → headscale
  │
  ├── Remote enrollment (direct TLS):
  │   └── vpn.thousand-pikes.com:443 → router NAT → nginx:9443 → headscale
  │
  ├── Remote enrollment (Cloudflare fallback):
  │   └── tak.thousand-pikes.com → Cloudflare → nginx:8082 → headscale
  │   (ts2021 noise handshake fails through Cloudflare — use direct TLS)
  │
  └── After enrollment:
      └── WireGuard P2P tunnel → Unraid host → FTS ports (8087, 8089, 1880)
```

OIDC enrollment flow: Tailscale app → nginx `/auth/*` → Authelia login →
Authelia → headscale OIDC callback → device enrolled.

---

## CI/CD

GitHub Actions self-hosted runner on Unraid deploys on push to `server/**`:

```
Push to main (server/** changes)
  │
  ▼
GitHub Actions (self-hosted runner on Unraid)
  ├── rsync server/ → /mnt/user/appdata/tak-server/
  │   (excludes .env, local configs, auth secrets)
  ├── docker compose build (incident-tracker, mesh-relay)
  ├── docker compose pull + up -d (excludes github-runner)
  ├── Deploy Node-RED flows via /flows API
  ├── Restart Node-RED (picks up cot-maps.js changes)
  └── Verify core services running
```

The runner container is excluded from self-deploy to avoid killing its own
process mid-job.

---

## Key Design Decisions

### Why CoT over TCP (not UDP multicast)?

FTS uses TCP for reliable delivery and client state tracking. UDP multicast
(239.2.3.1:6969) works for peer-to-peer ATAK on a LAN but doesn't provide
server persistence or cross-network relay. The mesh relay and halow-bridge both
use TCP connections to FTS.

### Why external modules for Node-RED logic?

Node-RED function node closures capture code at socket creation time. Deploying
new flows via the API updates stored code but does NOT re-create active
sockets, so old callback closures persist until container restart. Moving logic
to `cot-maps.js` (loaded via `functionGlobalContext`) ensures
`maps.parseCotToMarker()` always calls current module code. See MEMORY.md for
the full close-handler race condition analysis.

### Why nodedb seeding every 120s?

Meshtastic firmware suppresses duplicate position packets to the serial/TCP
API. Fixed-position nodes (relays, base stations) broadcast position once at
boot, then never again unless coordinates change. Without periodic nodedb
re-reading, these nodes' TAK markers would go stale after `STALE_MINUTES` and
disappear from the map. The 120s poll interval keeps markers alive while
respecting the 30-minute stale timeout.

### Why public LongFast channel?

Operating on the default Meshtastic channel (PSK `AQ==`) means CrypTAK nodes
relay traffic for the broader community, and benefit from community relay
infrastructure in return. TAK payload confidentiality comes from the app-layer
AES-256-GCM encryption, not the mesh channel.
