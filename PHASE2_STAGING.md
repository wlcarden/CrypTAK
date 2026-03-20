# Phase 2 Staging: CrypTAK Fleet Monitor

**Status:** Planned, not started. Pick up after Leighton returns from travel (~late March / early April 2026).  
**Phase 1:** Complete ✅ — MeshMonitor running at http://192.168.50.120:8090

---

## Pre-Flight Checklist (Before Starting Phase 2)

1. **Verify Phase 1 still running:**
   ```bash
   ssh root@192.168.50.120 'docker ps -f name=mesh --format "table {{.Names}}\t{{.Status}}"'
   ```
   Expected: `meshmonitor` and `meshtastic-serial-bridge` both Up.

2. **Verify BRG01 still on /dev/ttyACM0:**
   ```bash
   ssh root@192.168.50.120 'ls -la /dev/ttyACM0'
   ```

3. **Check MeshMonitor is connected:**
   ```bash
   ssh root@192.168.50.120 'docker logs meshmonitor 2>&1 | tail -5'
   ```

4. **Verify web UI accessible:** http://192.168.50.120:8090 (admin / changeme — should be changed by now)

---

## Step 1: Fork MeshMonitor

```bash
# On LC-Desktop-Mint
cd ~/Desktop/CrypTAK
gh repo fork Yeraze/meshmonitor --clone=true --remote=true --fork-name meshmonitor-cryptak
cd meshmonitor-cryptak

# Set up remotes
git remote rename origin upstream
git remote add origin git@github.com:wlcarden/meshmonitor-cryptak.git
git push -u origin main

# Create develop branch
git checkout -b develop
git push -u origin develop
```

**Don't do this yet** — just fork when ready to start coding.

---

## Step 2: Understand MeshMonitor's Codebase

Key files to read first:

```
server/src/
├── index.ts                    # Entry point, Express setup
├── services/
│   ├── meshtasticService.ts    # Core: TCP connection, protobuf handling
│   └── ...
├── db/
│   ├── schema.ts               # Drizzle ORM schema (nodes, messages, channels, etc.)
│   └── migrations/             # SQL migrations
└── api/
    └── routes/                 # REST API endpoints

web/src/
├── components/
│   ├── Map.tsx                 # Leaflet map with node markers ← TAK coloring goes here
│   ├── NodeList.tsx            # Sidebar node list
│   └── ...
├── pages/
│   ├── Dashboard.tsx           # Main dashboard
│   └── ...
└── styles/                     # CSS
```

**Critical understanding needed:**
- How `meshtasticService.ts` processes incoming protobuf packets
- How nodes are stored in the DB (`schema.ts`)
- How the map renders node markers (`Map.tsx`)
- How the admin commands tab works (for bulk IFF deployment later)

---

## Step 3: IFF Detection (Backend)

### 3a. Database Schema Extension

Add to `schema.ts` (or create a migration):

```sql
-- New columns on nodes table
ALTER TABLE nodes ADD COLUMN affiliation TEXT DEFAULT 'unknown';
-- Values: 'friendly', 'unknown', 'neutral', 'suspect'

ALTER TABLE nodes ADD COLUMN registry_public_key TEXT;
-- From nodes.yaml, for spoof detection

ALTER TABLE nodes ADD COLUMN last_iff_seen INTEGER;
-- Timestamp of last packet seen on cryptak channel

ALTER TABLE nodes ADD COLUMN iff_channel_index INTEGER;
-- Which channel index the IFF traffic was on (should be 1)
```

New table:
```sql
CREATE TABLE fleet_registry (
  node_id TEXT PRIMARY KEY,       -- e.g. '!435ae49c'
  long_name TEXT,                 -- e.g. 'CrypTAK-TBS02'
  short_name TEXT,                -- e.g. 'TB02'
  role TEXT,                      -- e.g. 'client', 'router', 'tracker'
  public_key TEXT,                -- base64 public key from nodes.yaml
  hardware TEXT,                  -- e.g. 'T-Beam Supreme S3'
  gps_variant TEXT,               -- e.g. 'ublox', 'l76k'
  notes TEXT,
  iff_status TEXT DEFAULT 'pending', -- 'active', 'pending', 'burned_out', 'disassembled'
  created_at INTEGER,
  updated_at INTEGER
);
```

### 3b. IFF Detection Logic

Where to hook in: `meshtasticService.ts` — find where incoming `MeshPacket` is processed.

The packet has a `channel` field (integer, 0-indexed). If `channel === 1` (cryptak), mark the source node as `friendly`.

```typescript
// Pseudocode — find the actual packet handler in meshtasticService.ts
function onMeshPacket(packet: MeshPacket) {
  const sourceNodeId = packet.from;
  const channelIndex = packet.channel;
  
  if (channelIndex === 1) { // cryptak channel
    db.update(nodes)
      .set({
        affiliation: 'friendly',
        last_iff_seen: Date.now(),
        iff_channel_index: channelIndex,
      })
      .where(eq(nodes.nodeNum, sourceNodeId));
  }
}
```

### 3c. Registry Loader

On startup (or via API trigger), load `nodes.yaml` and populate `fleet_registry` table:

```typescript
import yaml from 'js-yaml';

async function loadRegistry(yamlPath: string) {
  const content = fs.readFileSync(yamlPath, 'utf8');
  const registry = yaml.load(content) as any;
  
  for (const [id, node] of Object.entries(registry.nodes)) {
    await db.insert(fleet_registry).values({
      node_id: id,
      long_name: node.name,
      short_name: node.short_name,
      role: node.role,
      public_key: node.public_key,
      hardware: node.hardware,
      gps_variant: node.gps_variant,
      notes: node.notes,
      iff_status: node.iff_status,
    }).onConflictDoUpdate({
      target: fleet_registry.node_id,
      set: { /* update fields */ },
    });
  }
}
```

Mount `nodes.yaml` into the container:
```yaml
# docker-compose.yml addition
volumes:
  - /mnt/user/appdata/meshmonitor/data:/data
  - /home/wlcarden/Desktop/CrypTAK/firmware/nodes.yaml:/config/nodes.yaml:ro
```

### 3d. Affiliation Resolution Logic

```
For each node visible on the mesh:
1. Is it in fleet_registry?
   - No → affiliation = 'unknown' (yellow)
   - Yes → continue
2. Has it been seen on cryptak channel (index 1)?
   - No → affiliation = 'neutral' (green) — registered but not yet confirmed
   - Yes → continue
3. Does its public key match the registry entry?
   - Yes → affiliation = 'friendly' (blue)
   - No → affiliation = 'suspect' (red) — SPOOF ALERT
```

---

## Step 4: TAK Coloring (Frontend)

### 4a. Color Constants

```css
:root {
  --tak-friendly: #4A90D9;    /* Blue — confirmed IFF */
  --tak-unknown: #FFD700;     /* Yellow — not in registry */
  --tak-neutral: #32CD32;     /* Green — registered, unconfirmed */
  --tak-suspect: #FF4444;     /* Red — spoof alert */
}
```

### 4b. Map Marker Modification

Find `Map.tsx` → node marker rendering. Replace default marker color with affiliation-based color.

The existing MeshMonitor likely uses Leaflet markers. We need to:
1. Add `affiliation` to the node data fetched by the frontend
2. Pass affiliation to the marker component
3. Style marker based on affiliation

### 4c. Node List Enhancement

Add affiliation badge next to each node name in the sidebar list:
- 🔵 Friendly
- 🟡 Unknown
- 🟢 Neutral
- 🔴 Suspect

---

## Step 5: Spoof Detection & Alerts

### 5a. Backend Alert Service

When a node's observed public key doesn't match its registry entry:

```typescript
async function checkForSpoof(nodeId: string, observedPublicKey: string) {
  const registryEntry = await db.query.fleet_registry.findFirst({
    where: eq(fleet_registry.node_id, nodeId),
  });
  
  if (!registryEntry) return; // Not in registry, nothing to spoof
  
  if (registryEntry.public_key && registryEntry.public_key !== observedPublicKey) {
    // SPOOF DETECTED
    await db.update(nodes).set({ affiliation: 'suspect' }).where(eq(nodes.nodeId, nodeId));
    
    // TODO: Send Discord alert via webhook
    // POST to Discord webhook with embed: "⚠️ SPOOF ALERT: Node {nodeId} claims to be {registryEntry.long_name} but has wrong public key"
  }
}
```

### 5b. Discord Webhook Integration

Add env var `DISCORD_WEBHOOK_URL` to docker-compose.yml:
```yaml
environment:
  - DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/...
```

Use for critical alerts only (spoof detection, node down after extended absence).

---

## Step 6: API Endpoints

New REST endpoints under `/api/cryptak/`:

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/cryptak/iff/status` | All node affiliations summary |
| GET | `/api/cryptak/iff/node/:id` | Single node IFF detail |
| GET | `/api/cryptak/registry` | Fleet registry entries |
| POST | `/api/cryptak/registry/reload` | Reload nodes.yaml |
| GET | `/api/cryptak/spoof/alerts` | Recent spoof alerts |
| POST | `/api/cryptak/admin/deploy-iff` | Bulk IFF channel push (Phase 2.5) |

---

## Step 7: Docker Deployment Update

Final docker-compose.yml:
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
    image: ghcr.io/wlcarden/meshmonitor-cryptak:latest  # Our fork
    container_name: meshmonitor
    ports:
      - "8090:3001"
    volumes:
      - /mnt/user/appdata/meshmonitor/data:/data
      - /home/wlcarden/Desktop/CrypTAK/firmware/nodes.yaml:/config/nodes.yaml:ro
    environment:
      - MESHTASTIC_NODE_IP=meshtastic-serial-bridge
      - MESHTASTIC_NODE_PORT=4403
      - ALLOWED_ORIGINS=http://192.168.50.120:8090,http://localhost:8090,http://localhost:3001
      - CRYPTAK_REGISTRY_PATH=/config/nodes.yaml
      - CRYPTAK_IFF_CHANNEL_INDEX=1
      - DISCORD_WEBHOOK_URL=  # Set when ready
    restart: unless-stopped
    depends_on:
      - meshtastic-serial-bridge
```

---

## Development Workflow

```bash
# 1. Fork and clone
cd ~/Desktop/CrypTAK/meshmonitor-cryptak

# 2. Local dev (no Docker needed)
npm install
cp .env.example .env
# Edit .env: MESHTASTIC_NODE_IP=192.168.50.79 (BRG01 WiFi direct)
npm run dev:full
# Frontend: http://localhost:5173
# Backend: http://localhost:3001

# 3. Test changes
npm run test:run
npm run typecheck
npm run lint

# 4. Build Docker image
docker build -t meshmonitor-cryptak:dev .

# 5. Deploy to Unraid
docker tag meshmonitor-cryptak:dev ghcr.io/wlcarden/meshmonitor-cryptak:latest
docker push ghcr.io/wlcarden/meshmonitor-cryptak:latest
ssh root@192.168.50.120 'cd /mnt/user/appdata/meshmonitor && docker compose pull && docker compose up -d'
```

---

## Estimated Timeline (When Resuming)

| Day | Task | Output |
|-----|------|--------|
| 1 | Fork repo, read codebase, understand packet flow | Understanding doc |
| 2 | DB schema migration + IFF detection hook | Backend working |
| 3 | Registry loader + affiliation resolver | nodes.yaml integrated |
| 4 | Frontend: TAK coloring on map markers | Visual IFF on map |
| 5 | Spoof detection + Discord alerts | Alerts working |
| 6 | Testing, edge cases, polish | Stable build |
| 7 | Docker build, deploy to Unraid, docs | Production ready |

---

## Current State Summary

**What's running:**
- MeshMonitor vanilla at http://192.168.50.120:8090 (admin / changeme)
- Serial bridge: BRG01 /dev/ttyACM0 → TCP 4403
- 6 nodes visible, cryptak channel detected
- Security scans active (duplicate keys, spam, time drift)

**What's committed:**
- `9e37a1f` — DEPLOYMENT.md, MESHMONITOR_FORK_PLAN.md, meshmonitor-integration/

**What's NOT started:**
- MeshMonitor fork (don't fork until ready to code)
- IFF detection backend
- TAK coloring frontend
- Spoof detection
- Registry integration

**CrypTAK fleet status:**
- 6/9 nodes IFF-upgraded (BRG01, TBS01, TBS02, VHC01, TRK01, RPT01)
- 3 inactive (BSE01 burned out, SOL01/SOL02 disassembled)
- Remote admin: PKC negotiation was in progress as of 11:30 AM EDT today

---

**To resume:** Read this file, verify pre-flight checklist, then start at Step 1.
