# CrypTAK Fleet Monitoring Deployment

**Last Updated:** 2026-03-20 11:45 EDT

## Architecture Overview

CrypTAK Fleet Monitoring comprises three layers:

```
Layer 1: Hardware (Meshtastic Nodes)
├── BRG01 (T-Beam, admin controller, serial /dev/ttyACM0)
├── TBS01, TBS02 (T-Beam Supreme S3 prototypes)
├── VHC01, TRK01 (RAK4631, T1000-E client nodes)
└── RPT01 (RAK4631 repeater)

Layer 2: Bridge & Monitoring (Unraid Docker)
├── meshtastic-serial-bridge (translates USB serial → TCP 4403)
└── meshmonitor (fleet monitoring web UI + admin console)

Layer 3: Access
├── LAN: http://192.168.50.120:8090 (MeshMonitor web UI)
├── Discord: /cryptak commands (TBD)
└── Remote: Tailscale relay via gateway (TBD)
```

## Phase 1: MeshMonitor Deployment ✅

### Prerequisites
- BRG01 (T-Beam) connected via USB on Unraid at `/dev/ttyACM0`
- Unraid with Docker & Docker Compose installed
- Network access: 192.168.50.120:8090

### Deployment Steps

**1. Enable Serial Protocol on BRG01**
```bash
ssh root@192.168.50.120 'meshtastic --port /dev/ttyACM0 \
  --set serial.enabled true \
  --set serial.echo false \
  --set serial.mode SIMPLE \
  --set serial.baud BAUD_115200'
```

**2. Create Docker Compose Configuration**
Location: `/mnt/user/appdata/meshmonitor/docker-compose.yml`

```yaml
services:
  meshtastic-serial-bridge:
    image: ghcr.io/yeraze/meshtastic-serial-bridge:latest
    container_name: meshtastic-serial-bridge
    devices:
      - /dev/ttyACM0:/dev/ttyACM0
    ports:
      - "4403:4403"
    restart: unless-stopped
    environment:
      - SERIAL_DEVICE=/dev/ttyACM0
      - BAUD_RATE=115200
      - TCP_PORT=4403

  meshmonitor:
    image: ghcr.io/yeraze/meshmonitor:latest
    container_name: meshmonitor
    ports:
      - "8090:3001"
    volumes:
      - /mnt/user/appdata/meshmonitor/data:/data
    environment:
      - MESHTASTIC_NODE_IP=meshtastic-serial-bridge
      - MESHTASTIC_NODE_PORT=4403
    restart: unless-stopped
    depends_on:
      - meshtastic-serial-bridge
```

**3. Deploy**
```bash
cd /mnt/user/appdata/meshmonitor
docker compose up -d
```

**4. Verify**
- Serial bridge logs: `docker logs meshtastic-serial-bridge | tail -5`
- MeshMonitor logs: `docker logs meshmonitor | tail -5`
- Web UI: http://192.168.50.120:8090

### Default Login
- Username: `admin`
- Password: `changeme` (⚠️ **Change on first login!**)

### Network Access

| Access Method | URL | Notes |
|---------------|-----|-------|
| **LAN** | http://192.168.50.120:8090 | Direct, no auth (change password!) |
| **Tailscale** | http://lc-desktop-mint:8090 | Via gateway tunnel (TBD) |
| **Remote** | Via Authelia proxy (TBD) | Production hardening needed |

---

## Phase 2: CrypTAK Integration (In Progress)

### What We're Adding

#### 2.1 IFF Detection Engine
- Monitor channel index for incoming packets
- Mark nodes seen on `cryptak` channel (index 1) as **friendly**
- Track affiliation per node in MeshMonitor database
- Passive detection—no special queries needed

#### 2.2 TAK-Style Affiliation Coloring
Replaces MeshMonitor's default node coloring with MIL-STD-2525 scheme:

| Color | Affiliation | Condition |
|-------|-------------|-----------|
| **Blue** | Friendly | Seen on cryptak channel + in registry |
| **Yellow** | Unknown | Public channel only, not in registry |
| **Green** | Neutral | In registry but not yet seen on cryptak |
| **Red** | Suspect | Registry name mismatch OR public key mismatch |

Frontend changes:
- Node marker SVG/CSS styling
- Affiliation logic in web UI
- Alert badges for spoofed nodes

#### 2.3 Fleet Registry Integration
- Import `nodes.yaml` as source of truth
- Match by node ID (`!435ae49c`) and public key
- Display registry metadata (role, profile, notes, GPS variant)
- Flag public key drift

#### 2.4 Spoof Detection
- Compare incoming NodeInfo against nodes.yaml
- Red alert if claimed name ≠ registered name + different public key
- Yellow alert if public key changed (likely reflashed)
- Automatic PKC key validation dashboard

#### 2.5 Admin Enhancements
- Bulk IFF channel deployment (push cryptak channel config to multiple nodes)
- PKC admin key status dashboard
- Remote admin runner UI (execute commands via MeshMonitor)

### Implementation Plan

| Phase | Task | ETA | Notes |
|-------|------|-----|-------|
| 2.1 | Database schema: add `affiliation`, `verified_public_key` fields | Now | Drizzle ORM migration |
| 2.2 | IFF packet sniffer in backend (monitor channel index) | Now | Listen to incoming RadioEvents |
| 2.3 | Frontend: TAK coloring + marker logic | 1-2 days | React component refactor |
| 2.4 | nodes.yaml loader + registry match logic | 1 day | YAML parser + fuzzy match |
| 2.5 | Spoof detection alerts + dashboard | 1 day | Vue/React modal + API endpoint |
| 2.6 | Admin bulk IFF push + PKC key dashboard | 1-2 days | New tabs in admin UI |

---

## Phase 3: Documentation & Workflow

### Documentation Structure

```
CrypTAK/
├── DEPLOYMENT.md (this file)
├── firmware/
│   ├── README.md (provisioning workflow)
│   ├── provision.sh (main provisioning)
│   ├── upgrade-iff.sh (IFF-only upgrade)
│   ├── secrets.sh (ADMIN_KEY, admin PSK)
│   ├── nodes.yaml (fleet registry)
│   └── ...firmware binaries
├── meshmonitor-integration/
│   ├── README.md (MeshMonitor fork setup)
│   ├── docker-compose.yml (deployment)
│   ├── patches/ (CrypTAK IFF + TAK coloring)
│   ├── schema-migrations/ (Drizzle ORM migrations)
│   └── public/config.json (CrypTAK settings)
└── ...rest of CrypTAK stack
```

### Deployment Workflow

**For Operators (Day-to-Day)**

1. **Monitor Fleet Health** → MeshMonitor dashboard
2. **Deploy IFF to New Nodes** → `upgrade-iff.sh` via provision.sh
3. **Check Spoofing/Trust Status** → IFF affiliation panel (blue/yellow/red)
4. **Remote Admin** → MeshMonitor admin console (PKC-secured)
5. **Firmware Updates** → provision.sh (with PKC key distribution)

**For Development (Iteration)**

1. Fork MeshMonitor repo
2. Add CrypTAK patches (IFF detection, TAK coloring, nodes.yaml import)
3. Test locally with meshtasticd virtual nodes
4. Deploy to Unraid via docker-compose
5. Iterate with live mesh feedback

---

## Phase 4: Hardening & Production (Future)

### Security Checklist

- [ ] Change MeshMonitor admin password
- [ ] Set up Authelia reverse proxy + TLS
- [ ] Enable MeshMonitor OIDC (SSO via identity provider)
- [ ] Configure PKC admin key rotation schedule
- [ ] Implement audit logging (who changed what)
- [ ] Rate-limit remote admin commands
- [ ] Validate all inputs to IFF channel config
- [ ] Enable end-to-end encryption for sensitive data

### Observability

- [ ] Prometheus metrics export (node health, message count, latency)
- [ ] Loki logs aggregation (MeshMonitor + serial bridge)
- [ ] Grafana dashboards (fleet overview, anomaly detection)
- [ ] Discord alerts (critical node down, spoofing detected)

### Scaling

- [ ] Multi-bridge support (if > 1 Meshtastic device on Unraid)
- [ ] SQL database backend (PostgreSQL instead of SQLite)
- [ ] High-availability mode (redundant bridges, auto-failover)

---

## Troubleshooting

### MeshMonitor Can't Connect to Serial Bridge

**Symptom:** `docker logs meshmonitor | grep ECONNREFUSED`

**Fix:**
```bash
# 1. Verify bridge is running
docker logs meshtastic-serial-bridge | grep "Listening on"

# 2. Check BRG01 is on serial
ls -la /dev/ttyACM0

# 3. Restart both containers
docker compose restart
```

### Serial Bridge Sees No Data

**Symptom:** `docker logs meshtastic-serial-bridge | grep "no data\|timeout"`

**Fix:**
```bash
# 1. Verify BRG01 has serial enabled
ssh root@192.168.50.120 'meshtastic --port /dev/ttyACM0 --get serial'

# 2. Check BRG01 is responsive
ssh root@192.168.50.120 'meshtastic --port /dev/ttyACM0 --info' | head -5

# 3. Power-cycle BRG01 (disconnect USB, wait 10s, reconnect)
```

### MeshMonitor UI Shows No Nodes

**Symptom:** Map is empty, node list shows 0 devices

**Fix:**
```bash
# 1. Verify mesh is active (at least one other node nearby)
# 2. Check MeshMonitor logs for RadioEvent parsing errors
docker logs meshmonitor 2>&1 | grep -i "radio\|event\|node"

# 3. Wait 30s for NodeDB sync (first connection discovery takes time)
# 4. Refresh browser (hard refresh: Ctrl+Shift+R)
```

### IFF Channel Not Provisioning to a Node

**Symptom:** Node updated but cryptak channel missing

**Fix:**
- Verify T-Beam S3 delay (25+ seconds between channel commands)
- Check provision.sh has proper sleep commands
- Run with bash -x for debug: `bash -x upgrade-iff.sh <node-id>`

---

## Exact Identifiers

### Current Deployment

| Component | Address/Port | Status |
|-----------|-------------|--------|
| BRG01 (serial) | /dev/ttyACM0 @ 115200 baud | ✅ Active |
| Serial Bridge | localhost:4403 (Docker) | ✅ Connected |
| MeshMonitor UI | 192.168.50.120:8090 | ✅ Serving |
| Default Login | admin / changeme | ⚠️ **Change!** |

### Networking

| Host | IP | Role |
|------|----|----|
| LC-Desktop-Mint | 192.168.50.55 | Development machine |
| Unraid NAS | 192.168.50.120 | Docker host + MeshMonitor |
| BRG01 WiFi | 192.168.50.79 | Meshtastic HTTP API (unused for now) |
| Tailscale Relay | 100.101.255.78 | Remote access (TBD) |

### Docker Containers

```bash
# List running services
ssh root@192.168.50.120 'docker ps -f name=mesh'

# Logs
ssh root@192.168.50.120 'docker compose -f /mnt/user/appdata/meshmonitor/docker-compose.yml logs -f'
```

---

## Next Steps

1. **Test MeshMonitor UI** → Verify all 6 nodes visible on map
2. **Create fork of MeshMonitor** → github.com/wlcarden/meshmonitor-cryptak
3. **Implement Phase 2** → IFF detection + TAK coloring
4. **Deploy to Discord** → `/mesh fleet`, `/mesh admin`, `/mesh spoof-check` commands
5. **Integrate nodes.yaml** → Autoload fleet registry on startup
6. **Harden security** → Change password, enable auth, TLS setup

---

**Branch:** main  
**Last Commit:** meshmonitor deployment phase 1 complete  
**Maintainer:** Kit (wlcarden)
