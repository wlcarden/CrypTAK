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

## Deployment

### 1. Docker Compose

The flow runs in the `nodered` service defined in `../docker-compose.yml`. Two
volumes are mounted:

```yaml
volumes:
  - /mnt/user/appdata/nodered/data:/data
  - ./nodered/lib:/data/lib:ro
```

### 2. Settings.js

Add the external module reference to Node-RED's settings file on the server
(`/mnt/user/appdata/nodered/data/settings.js`):

```javascript
functionGlobalContext: {
    cotMaps: require('/data/lib/cot-maps')
},
```

This loads `lib/cot-maps.js` (color, icon, and affiliation maps) into the
Node-RED global context, referenced by the FTS CoT TCP Client function node.

### 3. Flow Import

Copy `flows.json` to the Node-RED data directory, or import via the Node-RED
editor (Menu > Import > select file).

### 4. Restart

```bash
docker compose restart nodered
```

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

After editing, restart Node-RED to reload the module.
