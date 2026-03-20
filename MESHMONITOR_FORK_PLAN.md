# MeshMonitor Fork Plan: CrypTAK Fleet Monitoring

**Status:** Pre-development (Phase 1 deployment complete)  
**Target Release:** Phase 2 (IFF detection + TAK coloring) — 2-3 weeks

## Overview

We're forking [Yeraze/meshmonitor](https://github.com/Yeraze/meshmonitor) to add CrypTAK-specific features:

1. **IFF Detection** — recognize nodes on cryptak private channel
2. **TAK Coloring** — MIL-STD-2525 affiliation markers (blue/yellow/green/red)
3. **Fleet Registry** — integrate `nodes.yaml` for node metadata & verification
4. **Spoof Detection** — alert on public key mismatches
5. **Admin Tooling** — bulk IFF deployment, PKC key management dashboard

## Repository Structure

```
github.com/wlcarden/meshmonitor-cryptak

├── .github/
│   ├── workflows/
│   │   ├── build.yml (extend for CrypTAK tests)
│   │   └── docker-build.yml (multi-arch builds)
│   └── PULL_REQUEST_TEMPLATE.md

├── docs/
│   ├── CRYPTAK_INTEGRATION.md (this project's specifics)
│   ├── IFF_DETECTION.md (technical deep-dive)
│   ├── DEPLOYMENT_CRYPTAK.md (operator guide)
│   └── ...existing MeshMonitor docs

├── server/
│   ├── src/
│   │   ├── db/
│   │   │   ├── schema.ts (add affiliation, public_key fields)
│   │   │   ├── migrations/
│   │   │   │   ├── 001_base_meshmonitor.sql (existing)
│   │   │   │   └── 002_cryptak_iff_fields.sql (NEW)
│   │   │   └── ...
│   │   │
│   │   ├── services/
│   │   │   ├── meshtasticService.ts (existing, extend)
│   │   │   ├── iffDetectionService.ts (NEW)
│   │   │   ├── nodeRegistryService.ts (NEW)
│   │   │   ├── spoofDetectionService.ts (NEW)
│   │   │   └── ...
│   │   │
│   │   ├── api/
│   │   │   ├── routes/ (existing endpoints)
│   │   │   ├── cryptak/ (NEW)
│   │   │   │   ├── iff.ts (IFF status, affiliation)
│   │   │   │   ├── registry.ts (nodes.yaml CRUD)
│   │   │   │   ├── spoof.ts (spoof detection alerts)
│   │   │   │   └── admin.ts (bulk provisioning)
│   │   │   └── ...
│   │   │
│   │   └── index.ts (main server)
│   │
│   └── ...

├── web/
│   ├── src/
│   │   ├── components/
│   │   │   ├── Map.tsx (extend node markers with TAK coloring)
│   │   │   ├── NodeDetail.tsx (show affiliation, public key)
│   │   │   ├── AdminPanel/ (NEW)
│   │   │   │   ├── IFFDeployment.tsx (bulk channel push)
│   │   │   │   ├── PKCKeyManagement.tsx (key dashboard)
│   │   │   │   └── SpoofDetection.tsx (alerts panel)
│   │   │   └── ...
│   │   │
│   │   ├── pages/
│   │   │   ├── Dashboard.tsx (existing)
│   │   │   ├── CrypTAKFleetOverview.tsx (NEW)
│   │   │   └── ...
│   │   │
│   │   ├── styles/
│   │   │   ├── cryptak-colors.css (NEW, TAK color scheme)
│   │   │   └── ...
│   │   │
│   │   └── ...
│   └── ...

├── config/
│   ├── cryptak-default.json (NEW, IFF settings)
│   └── ...

├── patches/ (VERSION CONTROL)
│   ├── 001-iff-detection.patch
│   ├── 002-tak-coloring.patch
│   ├── 003-nodes-yaml-integration.patch
│   ├── 004-spoof-detection.patch
│   └── 005-admin-tools.patch

├── docker-compose.yml (existing, updated for CrypTAK)
├── Dockerfile (existing, extend for CrypTAK deps)
├── DEPLOYMENT.md (existing)
└── README.md (update with CrypTAK features)
```

## Implementation Phases

### Phase 2.1: Database Schema & IFF Detection (1 week)

**Database Changes**

New fields in `nodes` table:
```sql
ALTER TABLE nodes ADD COLUMN affiliation TEXT DEFAULT 'unknown'; -- 'friendly', 'unknown', 'neutral', 'suspect'
ALTER TABLE nodes ADD COLUMN verified_public_key TEXT; -- from registry match
ALTER TABLE nodes ADD COLUMN last_seen_iff TIMESTAMP; -- when first seen on cryptak channel
ALTER TABLE nodes ADD COLUMN public_key_changed BOOLEAN DEFAULT false;
ALTER TABLE nodes ADD COLUMN registry_entry_id TEXT; -- FK to registry table
```

New table for fleet registry:
```sql
CREATE TABLE IF NOT EXISTS fleet_registry (
  id TEXT PRIMARY KEY,
  node_id TEXT NOT NULL UNIQUE,
  node_name TEXT,
  short_name TEXT,
  role TEXT, -- 'bridge', 'client', 'router', 'repeater', 'tracker'
  public_key TEXT,
  notes TEXT,
  gps_variant TEXT, -- 'l76k', 'ublox', 'none', 'external'
  added_date TIMESTAMP,
  updated_date TIMESTAMP
);
```

**Backend: IFF Detection Service**

File: `server/src/services/iffDetectionService.ts`

```typescript
export class IFFDetectionService {
  /**
   * Process incoming radio event, determine if from cryptak channel
   * Mark node as 'friendly' if channel index == 1 (cryptak)
   */
  async processRadioEvent(nodeId: string, channelIndex: number, payload: any) {
    const affiliation = channelIndex === 1 ? 'friendly' : 'unknown';
    
    await db.update(nodes)
      .set({
        affiliation,
        last_seen_iff: channelIndex === 1 ? new Date() : undefined,
      })
      .where(eq(nodes.id, nodeId));
  }

  /**
   * Get current affiliation of a node
   */
  async getNodeAffiliation(nodeId: string): Promise<string> {
    const node = await db.query.nodes.findFirst({
      where: eq(nodes.id, nodeId),
    });
    return node?.affiliation || 'unknown';
  }

  /**
   * Bulk query all node affiliations (for dashboard)
   */
  async getAllAffiliations() {
    return db.query.nodes.findMany({
      columns: { id: true, longName: true, affiliation: true, last_seen_iff: true },
    });
  }
}
```

**API Endpoint**

File: `server/src/api/cryptak/iff.ts`

```typescript
// GET /api/cryptak/iff/status
export async function getIFFStatus(req: Request, res: Response) {
  const affiliations = await iffService.getAllAffiliations();
  res.json({
    timestamp: new Date(),
    nodes: affiliations,
    summary: {
      friendly: affiliations.filter(n => n.affiliation === 'friendly').length,
      unknown: affiliations.filter(n => n.affiliation === 'unknown').length,
      neutral: affiliations.filter(n => n.affiliation === 'neutral').length,
      suspect: affiliations.filter(n => n.affiliation === 'suspect').length,
    },
  });
}
```

---

### Phase 2.2: TAK Coloring & Frontend (2 weeks)

**Color Scheme Definition**

File: `web/src/styles/cryptak-colors.css`

```css
:root {
  /* MIL-STD-2525 affiliation colors */
  --color-friendly: #3333ff;     /* Blue */
  --color-unknown: #ffff00;      /* Yellow */
  --color-neutral: #00cc00;      /* Green */
  --color-suspect: #ff3333;      /* Red */
  
  /* Secondary markers */
  --color-friendly-dark: #0000cc;
  --color-suspect-dark: #cc0000;
}

.node-marker {
  transition: fill 0.3s ease, stroke 0.3s ease;
}

.node-marker.friendly {
  fill: var(--color-friendly);
  stroke: var(--color-friendly-dark);
}

.node-marker.unknown {
  fill: var(--color-unknown);
  stroke: #cccc00;
}

.node-marker.neutral {
  fill: var(--color-neutral);
  stroke: #009900;
}

.node-marker.suspect {
  fill: var(--color-suspect);
  stroke: var(--color-suspect-dark);
  animation: pulse-alert 1s infinite;
}

@keyframes pulse-alert {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.7; }
}

.affiliation-badge {
  display: inline-block;
  padding: 4px 8px;
  border-radius: 4px;
  font-weight: bold;
  font-size: 0.8rem;
}

.affiliation-badge.friendly {
  background: var(--color-friendly);
  color: white;
}

.affiliation-badge.suspect {
  background: var(--color-suspect);
  color: white;
  font-weight: bold;
}
```

**React Component: Map Marker with Affiliation**

File: `web/src/components/Map.tsx` (extend existing)

```typescript
import { getNodeAffiliation } from '@api/cryptak/iff';

interface MarkerProps {
  node: Node;
  affiliation: 'friendly' | 'unknown' | 'neutral' | 'suspect';
}

export function MapMarker({ node, affiliation }: MarkerProps) {
  const markerColor = {
    friendly: '#3333ff',
    unknown: '#ffff00',
    neutral: '#00cc00',
    suspect: '#ff3333',
  }[affiliation];

  return (
    <Marker
      position={[node.lat, node.lon]}
      icon={L.divIcon({
        className: `node-marker ${affiliation}`,
        html: `
          <div class="marker-content">
            <div class="marker-circle" style="background: ${markerColor}"></div>
            <div class="marker-label">${node.shortName}</div>
          </div>
        `,
      })}
    >
      <Popup>
        <div>
          <h3>{node.longName}</h3>
          <div class="affiliation-badge ${affiliation}">
            Affiliation: {affiliation.toUpperCase()}
          </div>
          <p>Battery: {node.batteryLevel}%</p>
          <p>Last Seen: {formatTime(node.lastHeard)}</p>
          <p>Role: {node.role}</p>
        </div>
      </Popup>
    </Marker>
  );
}
```

**Node Detail Panel Enhancement**

File: `web/src/components/NodeDetail.tsx` (extend existing)

```typescript
export function NodeDetail({ nodeId }: { nodeId: string }) {
  const node = useQuery({
    queryKey: ['node', nodeId],
    queryFn: async () => fetchNode(nodeId),
  });

  const affiliation = useQuery({
    queryKey: ['iff', nodeId],
    queryFn: async () => {
      const res = await fetch(`/api/cryptak/iff/status?nodeId=${nodeId}`);
      return res.json();
    },
  });

  return (
    <div class="node-detail-panel">
      <h2>{node.data?.longName}</h2>
      
      {/* Affiliation Badge */}
      <div class={`affiliation-section affiliation-${affiliation.data?.affiliation}`}>
        <span class="badge">{affiliation.data?.affiliation.toUpperCase()}</span>
        {affiliation.data?.affiliation === 'suspect' && (
          <WarningIcon /> <strong>⚠️ SPOOFING ALERT</strong>
        )}
      </div>

      {/* IFF Status */}
      <div class="iff-status">
        <p><strong>Channel:</strong> {node.data?.lastReceivedChannel}</p>
        <p><strong>Last IFF Seen:</strong> {formatTime(affiliation.data?.last_seen_iff)}</p>
      </div>

      {/* Registry Match */}
      <div class="registry-info">
        <p><strong>Registry Entry:</strong> {affiliation.data?.registry_entry_id || 'Not in registry'}</p>
        <p><strong>Public Key:</strong> {maskKey(node.data?.publicKey)}</p>
        {affiliation.data?.public_key_changed && (
          <p class="warning">⚠️ Public key changed since last entry</p>
        )}
      </div>

      {/* Rest of existing node detail */}
    </div>
  );
}
```

---

### Phase 2.3: Fleet Registry Integration (1 week)

**YAML Parser & Loader**

File: `server/src/services/nodeRegistryService.ts`

```typescript
import yaml from 'js-yaml';
import fs from 'fs';

export class NodeRegistryService {
  private registryPath = process.env.REGISTRY_PATH || '/config/nodes.yaml';

  async loadRegistry() {
    try {
      const content = fs.readFileSync(this.registryPath, 'utf8');
      const parsed = yaml.load(content) as any;
      
      // Import into database
      for (const [nodeId, entry] of Object.entries(parsed.nodes || {})) {
        await db.insert(fleet_registry).values({
          id: `${nodeId}-${Date.now()}`,
          node_id: nodeId,
          node_name: entry.name,
          short_name: entry.short_name,
          role: entry.role,
          public_key: entry.public_key,
          notes: entry.notes,
          gps_variant: entry.gps_variant,
        });
      }
    } catch (err) {
      logger.error(`Failed to load registry: ${err}`);
    }
  }

  async getRegistryEntry(nodeId: string) {
    return db.query.fleet_registry.findFirst({
      where: eq(fleet_registry.node_id, nodeId),
    });
  }

  async matchNode(node: Node) {
    const registryEntry = await this.getRegistryEntry(node.id);
    if (!registryEntry) return { matched: false };

    const publicKeyMatches = registryEntry.public_key === node.publicKey;
    return {
      matched: true,
      entry: registryEntry,
      publicKeyMatches,
    };
  }
}
```

**API Endpoint**

File: `server/src/api/cryptak/registry.ts`

```typescript
// GET /api/cryptak/registry/status
export async function getRegistryStatus(req: Request, res: Response) {
  const entries = await db.query.fleet_registry.findMany();
  const nodes = await db.query.nodes.findMany();

  const matched = nodes.filter(n => {
    const entry = entries.find(e => e.node_id === n.id);
    return entry && entry.public_key === n.publicKey;
  }).length;

  res.json({
    registry_entries: entries.length,
    mesh_nodes: nodes.length,
    matched: matched,
    unmatched: nodes.length - matched,
    last_sync: new Date(),
  });
}
```

---

### Phase 2.4: Spoof Detection (1 week)

**Detection Logic**

File: `server/src/services/spoofDetectionService.ts`

```typescript
export class SpoofDetectionService {
  async checkNodeTrust(nodeId: string, incomingNodeInfo: any) {
    const registryEntry = await registryService.getRegistryEntry(nodeId);
    const dbNode = await db.query.nodes.findFirst({
      where: eq(nodes.id, nodeId),
    });

    if (!registryEntry) {
      // Not in registry — neutral trust
      return { status: 'unknown', reason: 'not_in_registry' };
    }

    const nameMatches = registryEntry.node_name === incomingNodeInfo.longName;
    const keyMatches = registryEntry.public_key === incomingNodeInfo.publicKey;

    if (!nameMatches && !keyMatches) {
      // Name AND key mismatch = spoofing attempt
      return {
        status: 'suspect',
        reason: 'name_and_key_mismatch',
        severity: 'critical',
        registryName: registryEntry.node_name,
        incomingName: incomingNodeInfo.longName,
      };
    }

    if (!keyMatches && dbNode?.verified_public_key) {
      // Key changed from verified value = likely reflash
      return {
        status: 'warning',
        reason: 'key_changed',
        severity: 'medium',
        oldKey: maskKey(dbNode.verified_public_key),
        newKey: maskKey(incomingNodeInfo.publicKey),
      };
    }

    if (nameMatches && keyMatches) {
      // All good
      return { status: 'friendly', reason: 'registry_match' };
    }

    return { status: 'neutral', reason: 'partial_match' };
  }

  async logSpoofAlert(nodeId: string, alert: any) {
    await db.insert(spoof_alerts).values({
      id: generateId(),
      node_id: nodeId,
      status: alert.status,
      reason: alert.reason,
      severity: alert.severity,
      details: JSON.stringify(alert),
      timestamp: new Date(),
    });

    // Send to Discord if critical
    if (alert.severity === 'critical') {
      await notifyDiscord({
        title: '🚨 SPOOFING ALERT',
        description: `Node ${nodeId} claims to be "${alert.registryName}" but has wrong public key!`,
        color: 0xff3333,
      });
    }
  }
}
```

**API Endpoint**

File: `server/src/api/cryptak/spoof.ts`

```typescript
// GET /api/cryptak/spoof/alerts
export async function getSpoofAlerts(req: Request, res: Response) {
  const alerts = await db.query.spoof_alerts.findMany({
    where: gt(spoof_alerts.timestamp, new Date(Date.now() - 24 * 60 * 60 * 1000)),
    orderBy: [desc(spoof_alerts.timestamp)],
  });

  res.json({
    total: alerts.length,
    critical: alerts.filter(a => a.severity === 'critical').length,
    warnings: alerts.filter(a => a.severity === 'medium').length,
    alerts: alerts,
  });
}
```

---

### Phase 2.5: Admin Tools (1-2 weeks)

**Bulk IFF Channel Deployment**

File: `server/src/services/adminService.ts`

```typescript
export class AdminService {
  /**
   * Deploy cryptak channel to multiple nodes via remote admin
   */
  async deployIFFChannelBulk(nodeIds: string[], options: any = {}) {
    const results = [];

    for (const nodeId of nodeIds) {
      try {
        const node = await meshtastic.getNode(nodeId);
        
        // Get current channels
        const channels = await meshtastic.getChannels(nodeId);
        
        // Add cryptak channel (index 1)
        const cryptakChannel = {
          index: 1,
          settings: {
            psk: process.env.ADMIN_CHANNEL_PSK,
            name: 'cryptak',
            uplinkEnabled: false,
            downlinkEnabled: false,
          },
        };
        
        channels.push(cryptakChannel);
        await meshtastic.setChannels(nodeId, channels, { delayMs: 25000 });
        
        // Add admin key to node
        const adminKeys = await meshtastic.getAdminKeys(nodeId);
        adminKeys.push(process.env.ADMIN_KEY_PUBLIC);
        await meshtastic.setAdminKeys(nodeId, adminKeys);

        results.push({
          nodeId,
          status: 'success',
          timestamp: new Date(),
        });
      } catch (err) {
        results.push({
          nodeId,
          status: 'failed',
          error: err.message,
          timestamp: new Date(),
        });
      }
    }

    return results;
  }
}
```

**Admin Panel UI Component**

File: `web/src/components/AdminPanel/IFFDeployment.tsx`

```typescript
export function IFFDeployment() {
  const [selectedNodes, setSelectedNodes] = useState<string[]>([]);
  const [deploying, setDeploying] = useState(false);
  const [results, setResults] = useState<any[]>([]);

  const handleDeploy = async () => {
    setDeploying(true);
    const res = await fetch('/api/cryptak/admin/deploy-iff', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ nodeIds: selectedNodes }),
    });
    const data = await res.json();
    setResults(data.results);
    setDeploying(false);
  };

  return (
    <div class="iff-deployment-panel">
      <h2>⚡ Bulk IFF Deployment</h2>
      
      <div class="node-selector">
        <label>Select nodes to deploy cryptak channel:</label>
        <ul>
          {nodes.map(n => (
            <li key={n.id}>
              <input
                type="checkbox"
                checked={selectedNodes.includes(n.id)}
                onChange={() => toggleNode(n.id)}
              />
              <span>{n.longName} ({n.id})</span>
              <span class={`affiliation affiliation-${n.affiliation}`}>
                {n.affiliation}
              </span>
            </li>
          ))}
        </ul>
      </div>

      <button
        onClick={handleDeploy}
        disabled={selectedNodes.length === 0 || deploying}
        class="btn-primary"
      >
        {deploying ? 'Deploying...' : 'Deploy IFF Channel'}
      </button>

      {results.length > 0 && (
        <div class="deployment-results">
          <h3>Results:</h3>
          <table>
            <tr>
              <th>Node</th>
              <th>Status</th>
              <th>Time</th>
            </tr>
            {results.map(r => (
              <tr key={r.nodeId} class={`status-${r.status}`}>
                <td>{r.nodeId}</td>
                <td>{r.status === 'success' ? '✅' : '❌'} {r.status}</td>
                <td>{formatTime(r.timestamp)}</td>
              </tr>
            ))}
          </table>
        </div>
      )}
    </div>
  );
}
```

---

## Testing Strategy

### Unit Tests
- IFF detection logic (channel index check)
- Registry matching (exact + fuzzy)
- Spoof detection (all alert types)
- Key validation (base64 parsing, length check)

### Integration Tests
- Full workflow: node discovery → registry match → affiliation assignment
- Bulk IFF deployment (simulate multiple nodes, verify delay timing)
- Database schema migration (backward compatibility)

### E2E Tests (on Unraid test environment)
- Deploy MeshMonitor with cryptak fork
- Spin up 3 virtual Meshtastic nodes (meshtasticd)
- Test affiliation coloring, spoof alerts, registry sync
- Verify no regression in existing MeshMonitor features

### Manual QA Checklist
- [ ] All 6 CrypTAK nodes visible on map with correct colors
- [ ] Nodes change from yellow (unknown) → blue (friendly) when IFF deployed
- [ ] Registry import matches nodes correctly
- [ ] Spoof alert fires if node name claimed ≠ registry name
- [ ] Bulk IFF deployment completes all nodes in < 5 min
- [ ] Admin console accessible (with password)
- [ ] Performance: < 500ms load time for 50+ nodes

---

## Branching Strategy

```
main (stable)
├── develop (integration)
│   ├── feature/iff-detection (IFF service + API)
│   ├── feature/tak-coloring (React components + CSS)
│   ├── feature/registry-integration (YAML loader + matching)
│   ├── feature/spoof-detection (alert logic + Discord notify)
│   └── feature/admin-tools (bulk deployment UI)
└── upstream/main (track meshmonitor upstream changes)
```

Each feature branch has:
- Implementation code
- Unit tests (90%+ coverage)
- Integration tests
- Updated docs
- Squash-merge to `develop` after review
- Release to `main` when all phases complete

---

## Documentation Deliverables

1. **CRYPTAK_INTEGRATION.md** — High-level overview & architecture
2. **IFF_DETECTION.md** — Technical deep-dive: packet flow, channel detection
3. **DEPLOYMENT_CRYPTAK.md** — Operator guide for Unraid + Discord
4. **API_REFERENCE.md** — REST endpoints, request/response examples
5. **TROUBLESHOOTING.md** — Common issues & fixes
6. **CONTRIBUTING.md** — Development setup, testing, PR process

---

## Timeline

| Phase | Dates | Deliverable |
|-------|-------|-------------|
| **1** | Done ✅ | MeshMonitor on Unraid, serial bridge running |
| **2.1** | Week 1 (Mar 20-26) | IFF detection service, DB schema, API endpoint |
| **2.2** | Week 2-3 (Mar 27 - Apr 2) | TAK coloring, React components, marker styling |
| **2.3** | Week 3 (Mar 27 - Apr 2) | Registry loader, nodes.yaml integration |
| **2.4** | Week 4 (Apr 3-9) | Spoof detection service, alerts, Discord notify |
| **2.5** | Week 5 (Apr 10-16) | Bulk IFF deployment UI, PKC key dashboard |
| **3** | Week 6 (Apr 17-23) | Comprehensive docs, hardening, production release |

---

## Success Criteria

✅ **Phase 1:**
- [ ] MeshMonitor running on Unraid, accessible at 8090
- [ ] All 6 CrypTAK nodes auto-discovered
- [ ] Serial bridge stable (< 0.1% packet loss)
- [ ] Default password changed on first login

✅ **Phase 2:**
- [ ] IFF detection working (nodes show correct affiliation)
- [ ] TAK coloring matches MIL-STD-2525
- [ ] Registry auto-loads on startup
- [ ] Spoof alerts trigger for key mismatches
- [ ] Bulk IFF deployment succeeds on all nodes in < 5 min
- [ ] No regressions in existing MeshMonitor features
- [ ] Admin password + optional OIDC auth enabled
- [ ] Full documentation complete

✅ **Phase 3:**
- [ ] Integrated with Discord (/mesh commands)
- [ ] Metrics exported (Prometheus)
- [ ] TLS + reverse proxy (Authelia) set up
- [ ] Automated backups working
- [ ] High-availability ready (multi-bridge support)

---

**Maintainer:** Kit (wlcarden)  
**Status:** Ready for Phase 2.1 development  
**Last Updated:** 2026-03-20 11:45 EDT
