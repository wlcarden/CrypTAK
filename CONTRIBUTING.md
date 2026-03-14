# Contributing to CrypTAK

## Repository Structure

```
CrypTAK/
├── plugin/              Android ATAK plugin (Gradle)
├── server/              Docker services
│   ├── mesh-relay/      Meshtastic → FTS relay (Python)
│   ├── incident-tracker/ Event monitoring (Python)
│   ├── halow-bridge/    Field-to-home CoT bridge (Python)
│   ├── nodered/         WebMap flows and UI
│   ├── fts-patches/     FTS 2.2.1 bug fixes
│   ├── field-services/  systemd units for field hardware
│   └── scripts/         Server-side utilities
├── firmware/            Meshtastic node profiles + provisioning
├── docs/                Project documentation
└── scripts/             Dev and deployment scripts
```

## Development Setup

### Server Services

```bash
cd server/
cp .env.example .env
# Edit .env with your server IP and secrets
docker compose up -d
```

### ATAK Plugin

Requires ATAK-CIV 5.5.1+ and the ATAK Plugin SDK (not included — download
from [TAK Product Center](https://github.com/TAK-Product-Center/atak-civ)).

```bash
cd plugin/
# Place SDK files in plugin/sdk/
./gradlew assembleCivDebug
```

### Meshtastic CLI

```bash
pip install meshtastic
# Always invoke as: python3 -m meshtastic (not bare 'meshtastic')
```

---

## Adding a New Meshtastic Node Profile

1. **Create the profile** in `firmware/profiles/<name>.yaml`. Use an existing
   profile as a template. Key fields:

   ```yaml
   device:
     role: CLIENT # CLIENT, ROUTER, ROUTER_CLIENT, TRACKER
   position:
     gps_mode: ENABLED
     position_broadcast_secs: 120
   ```

2. **Register the node** in `firmware/nodes.yaml`:

   ```yaml
   CrypTAK-NEW01:
     profile: <name>
     short_name: CN01
   ```

3. **Provision** by connecting the hardware and running:

   ```bash
   ./firmware/provision.sh "CrypTAK-NEW01"
   ```

   The script auto-detects the serial port, applies the profile, sets channel
   config (position precision 32, NeighborInfo), and verifies the result.

4. If the node has a **fixed position** (relay/base station), add coordinates
   to `nodes.yaml`:

   ```yaml
   CrypTAK-NEW01:
     profile: relay
     short_name: CN01
     gps: false
     latitude: 38.8419
     longitude: -77.2934
     altitude: 155
   ```

5. To make the node appear as **friendly (blue)** on the WebMap, add its hex ID
   (without `!` prefix) to the `FRIENDLY_NODES` env var in
   `server/docker-compose.yml` under the `mesh-relay` service.

---

## Testing relay.py Changes Locally

The mesh relay runs as a standalone Python script — no Docker required for
development:

```bash
cd server/mesh-relay/

# Install dependencies
pip install meshtastic

# Run with a TCP-connected T-Beam (adjust host/port)
MESH_HOST=192.168.50.198 \
FTS_HOST=192.168.50.120 \
FTS_PORT=8087 \
FRIENDLY_NODES=55c6ddbc,a51e2838 \
LOG_LEVEL=DEBUG \
python3 relay.py
```

For USB serial:

```bash
MESH_SERIAL=/dev/ttyACM0 FTS_HOST=localhost python3 relay.py
```

**Unit tests:**

```bash
cd server/mesh-relay/
python3 -m pytest test_relay.py -v
```

The relay service connects to both a Meshtastic node and FreeTAKServer. For
local testing, you need at least FTS running (`docker compose up -d
freetakserver`) and a reachable mesh node (USB or TCP).

---

## CI/CD Pipeline

Pushes to `server/**` on `main` trigger automatic deployment to the Unraid
server via a self-hosted GitHub Actions runner.

**Workflow:** `.github/workflows/deploy-server.yml`

1. `rsync` server files to `/mnt/user/appdata/tak-server/` (excludes `.env`,
   local configs, auth secrets)
2. `docker compose build` for services with `build:` directives
   (incident-tracker, mesh-relay)
3. `docker compose pull` + `up -d` for image-based services
4. Deploy Node-RED flows via `/flows` API (with admin auth if configured)
5. Restart Node-RED to pick up `settings.js` and `cot-maps.js` changes
6. Verify core services are running

**Profile services** (incident-tracker, mesh-relay) are deployed with
`--no-deps` since they're in optional profiles.

The runner container (`github-runner`) is excluded from self-deploy to avoid
killing its own process mid-job.

---

## Coding Conventions

### Python (relay.py, bridge.py, incident-tracker)

- Python 3.10+ (match statements allowed)
- 4-space indentation, 100-char line limit
- Type hints on function signatures
- `logging` module for all output (no `print()`)
- Environment variables for all configuration (no hardcoded IPs/ports)
- `asyncio` for network I/O, threading only for Meshtastic library callbacks

### Node-RED / JavaScript (cot-maps.js, flows.json)

- Logic in external modules (`lib/cot-maps.js`), not inline function nodes
- Function nodes handle socket management only
- Changes to `cot-maps.js` require container restart (loaded at startup via
  `functionGlobalContext`)

### Docker Compose

- Services that don't need host access bind to `127.0.0.1` (FTS-UI, REST API,
  headscale-ui, Prometheus)
- Memory limits on all services
- Healthchecks disabled on Unraid (runc issue) — verify manually
- Profile services for optional components (mesh-relay, incident-tracker)

### Firmware (YAML profiles)

- One profile per role in `firmware/profiles/`
- All devices must have `position_precision: 32` (set by provision.sh)
- NeighborInfo enabled on all devices for topology visualization

---

## Commits

- Use conventional commit prefixes: `feat()`, `fix()`, `docs()`, `chore()`
- Scope to the affected subsystem: `feat(mesh-relay):`, `fix(webmap):`,
  `docs(field):`, `feat(firmware):`
- Keep commits atomic — one logical change per commit

---

## Security

- Never commit secrets (`.env` files, API keys, passwords)
- Validate external input at system boundaries
- Use parameterized queries / safe XML construction
- See [docs/security.md](docs/security.md) for the full security model

---

## Export Notice

This project implements AES-256 encryption. Be aware of U.S. Export
Administration Regulations (EAR) when distributing. See the README for details.
