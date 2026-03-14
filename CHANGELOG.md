# Changelog

All notable changes to CrypTAK are documented in this file. Grouped by
milestone. Dates are approximate (based on commit history).

---

## [0.9.0] — 2026-03-14 (Pre-release)

### Field Unit — reTerminal Deployment

- Full field stack in `docker-compose.field.yml` (FTS, Node-RED, Mosquitto, Mumble)
- reTerminal E10-1 integration: BQ25790 UPS, EC25-EU GNSS, power button handler
- GPS-MQTT bridge (`gps-mqtt-bridge.py`): EC25 GNSS → gpsd → MQTT → Node-RED
- Power button handler (`power-button.py`): short press display toggle, 5s hold shutdown
- systemd units for ec25-gnss, gps-mqtt-bridge, and power-button services
- Field Node-RED flows with GPS → CoT PLI pipeline
- Field Pi setup script (`scripts/setup-field-pi.sh`)

### Firmware Provisioning

- Auto-detect GPS hardware via firmware probe in `provision.sh`
- Prototype-solar profile and CrypTAK-SOL02 node
- Max-diagnostics profiles with full telemetry and nodeInfo intervals
- Rebroadcast mode ALL on all profiles

### Bug Fixes

- Patch CreateLoggerController for `FTS_LOG_LEVEL=warning` crash
- Remove stale RLY01 entry (hardware repurposed as SOL02)
- SOL02 GPS hardware detection fix

---

## [0.8.0] — 2026-03-01

### WebMap Mesh Panel

- Full mesh network status panel (node count, channel utilization, signal dots)
- Mesh topology visualization via NeighborInfo (SNR-colored polylines)
- Telemetry-driven mesh panel improvements
- Owned nodes show correct names before nodeinfo arrives
- Suppress duplicate MQTT mesh markers
- Opacity flash fix (Leaflet layeradd hook)

### Mesh Relay Enhancements

- DETECTION_SENSOR_APP → CoT alarm forwarding (GPIO sensor events)
- Age-filter periodic nodedb seed to prevent ghost markers
- Mesh node provisioning system and relay site finder script

### Hardware Documentation

- Next-gen finished-board hardware spec (v2)
- MQTT bridge enablement on T-Beam (BRG01)

---

## [0.7.0] — 2026-02-20

### Mesh Telemetry and Tracking

- Mesh telemetry pipeline: voltage, channel utilization, air_util_tx, uptime, SNR, hops
- `__meshTelemetry` CoT detail tag through full pipeline
- Reboot detection via uptime decrease → event markers
- GPS asset tracker support with trail history and WebMap controls
- Tracker icon (crosshairs) and icon picker in popup
- Battery status in WebMap sidebar, popup, and tooltip
- Confidence-based icon tiers for incident markers
- SNR and hop count in relay position logs

### Mesh Node Filtering

- Filter mesh nodes from connected clients sidebar
- Use UID prefix instead of XML tag for mesh/tracker detection

---

## [0.6.0] — 2026-02-15

### Security Hardening

- Phase 1: XSS prevention, XML injection guards in cot-maps.js
- Phase 2: YAML injection protection in provisioning
- Phase 3: Port binding hardening (REST API, FTS-UI, headscale-ui → localhost only)
- Phase 4: Node-RED admin auth (NR_ADMIN_PASS environment variable)
- Phase 5: Docker image version pinning
- Phase 6: Deploy safety (rsync excludes, tolerant health checks)
- Direct TLS path for remote VPN enrollment (bypasses Cloudflare)
- Cert renewal and DDNS scripts

### Mesh Relay Reliability

- Serial → TCP fallback for mesh relay connection
- Fix null writer crash after refresh_sa closes broken connection
- Fix SA refresh infinite loop
- Fix position relay: firmware suppresses unchanged positions to API
- Fix mesh markers going stale: reseed PLI from nodedb every 120s
- Fix lastHeard=0 bypass in nodedb age filter

---

## [0.5.0] — 2026-02-01

### CI/CD Pipeline

- GitHub Actions self-hosted runner on Unraid
- Automatic deployment on push to `server/**`
- Node-RED flow deployment via admin API with auth support
- Build local images before deploying (fixes stale image issue)
- Rsync excludes for secrets and local configs
- Health check verification for core services

### WebMap Enhancements

- Tactical sidebar (connected clients, mesh nodes)
- Live marker color refresh every 30s
- Suspect layer split and 12h timestamps
- Steeper fade curve for marker staleness
- External CoT parsing module (`cot-maps.js`)
- fn_cot close handler race condition fix on flow redeploy

---

## [0.4.0] — 2026-01-20

### Mesh Relay Service

- `server/mesh-relay/relay.py`: Meshtastic → FTS CoT bridge
- Position polling from nodedb (compensates firmware position suppression)
- T-Beam TCP keepalive + heartbeat (firmware idle timeout workaround)
- Serial and TCP interface support with auto-reconnect
- Friendly/neutral node classification via FRIENDLY_NODES env var
- Mosquitto MQTT broker with authentication (no anonymous access)
- Firmware configs for 4 mesh nodes (T-Beam, RAK4631 x3)

---

## [0.3.0] — 2026-01-10

### Incident Tracker

- 8 data sources: RSS, NWS weather, USGS earthquakes, Waze traffic, DC Crime,
  Reddit, StopICE, custom feeds
- Geographic and keyword filtering
- Optional AI-powered analysis (Anthropic Claude)
- CoT injection to FTS with category-based affiliation and icons
- Deduplication via fingerprint + Jaccard similarity
- 249 unit tests

### WebMap Features

- WebMap drawing → ATAK CoT injection
- CrypTAK branding (favicon, top bar logo)
- Layer toggles, affiliation filtering, MGRS grid, event-driven replay
- Marker staleness (color fading) and cache sweep
- Marker opacity based on age

---

## [0.2.0] — 2025-12-15

### Server Stack

- FreeTAKServer Docker deployment with 5 bug patches
  - SQLAlchemy `session.merge()` fix (prevents session corruption)
  - EventTableController patch
  - Dead socket cleanup in send_component_data_controller
  - AllowedCLIIPs schema fix in MainConfig
  - CreateLoggerController log level handling
- FTS-UI with socket.io v4 shim
- Node-RED WebMap integration
- Headscale VPN + Authelia OIDC for remote access
- nginx reverse proxy with TLS
- HaLow bridge service (`server/halow-bridge/`) with offline buffering

---

## [0.1.0] — 2025-11-01

### ATAK Plugin — App-Layer Encryption

- AES-256-GCM content encryption for all ATAK payloads
- Encrypted direct message relay via onCotEvent
- QR code key distribution (in-app scanner)
- Data Package onboarding export/import
- Manual key entry (Base64)
- Key rotation with epoch tracking
- 199 unit tests

### Monorepo Setup

- Combined plugin, server, firmware, docs, scripts into single repository
- Apache 2.0 license
- Initial documentation (README, plugin README, deployment runbook)
