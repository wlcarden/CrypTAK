# CrypTAK WebMap

Node-RED flow that provides a browser-based tactical map at `/tak-map`. Displays
TAK markers from FreeTAKServer, Meshtastic mesh node positions via MQTT, and
NOAA weather alerts — with US state and county boundary overlays.

## Features

- TAK markers split by MIL-STD-2525 affiliation (Friendly, Hostile, Neutral,
  Unknown) with independent layer toggles
- Meshtastic mesh node positions via MQTT with owned-node highlighting
- NOAA active weather alerts (severity-colored polygons)
- US States and Counties boundary overlays (hidden by default, toggleable)
- MGRS coordinate display
- Event-driven replay — new browser clients receive all cached state on connect
- Auto-expiring markers via TTL (TAK: from CoT stale time, Mesh: 10 minutes)
- Color fading — marker colors fade toward gray as events age, indicating
  staleness visually (worldmap doesn't support per-marker icon opacity)
- WebMap drawing → ATAK: shapes drawn in the browser (polygon, rectangle,
  circle, polyline) are injected as CoT events to FTS, appearing on all
  connected ATAK clients

## Deployment

Deployment is automated via GitHub Actions (`.github/workflows/deploy-server.yml`).
Pushing to `server/**` on `main` triggers:

1. rsync files to `/mnt/user/appdata/tak-server/`
2. `docker compose up -d` (recreates containers if compose changed)
3. Deploy `flows.json` to Node-RED via the admin API
4. Restart Node-RED to reload `settings.js` and `cot-maps.js`

### Docker Compose Volumes

```yaml
volumes:
  - /mnt/user/appdata/nodered/data:/data
  - ./nodered/settings.js:/data/settings.js:ro # functionGlobalContext
  - ./nodered/lib:/opt/cot-maps:ro # cot-maps.js module
  - ./nodered/public:/data/public:ro # favicon, logo
```

Key: `lib/` is mounted at `/opt/cot-maps` (not `/data/lib`) because Node-RED
reserves `/data/lib/` for its internal function library.

### settings.js

Mounted read-only from the repo. Configures:

- `functionGlobalContext.net` — Node.js `net` module for TCP sockets
- `functionGlobalContext.cotMaps` — `cot-maps.js` parsing library
- `httpStatic` — serves `public/favicon.ico` and `cryptak-logo.png`

### flows.json

Deployed via the Node-RED admin API (`POST /flows`), NOT by filesystem copy.
Node-RED reads flows from `/data/flows.json` (the persistent Docker volume),
which is separate from the rsync'd repo copy. The CI/CD workflow pushes the
repo version through the API to keep them in sync.

## Configuration

Connection parameters are hardcoded in the flow nodes:

| Parameter   | Node         | Value                     |
| ----------- | ------------ | ------------------------- |
| FTS host    | fn_cot       | `192.168.50.120:8087`     |
| MQTT broker | mqtt_broker1 | `192.168.50.120:1883`     |
| Map center  | wmap1        | `38.87, -77.30` (DC/NoVA) |
| Map path    | wmap1        | `/tak-map`                |

To change these, edit the corresponding nodes in `flows.json` or via the
Node-RED editor.

## Adding Icons and Colors

Edit `lib/cot-maps.js` directly — no need to touch `flows.json`:

- `colorMap`: MIL-STD-2525 affiliation code to hex color
- `iconMap`: CoT type suffix to [Font Awesome](https://fontawesome.com/v4/icons/)
  icon class (matched longest-first)
- `affiliationNames`: Affiliation code to worldmap layer name
- `fadeColor()`: Blends marker color toward gray based on age factor

After editing, restart Node-RED to reload the module (CI/CD does this
automatically).
