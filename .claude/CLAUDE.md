# CrypTAK — Project Rules

See `docs/claude-code-context.md` for repo layout, build commands, and security notes.

## Meshtastic Node Configuration — Use the Provisioning Script

**All node configuration goes through `firmware/provision.sh`.** No exceptions.

- Initial setup, reprovisioning, profile changes, channel config, setting changes — all of it.
- Ad-hoc `meshtastic --set` / `--ch-set` commands cause configuration drift. We learned this the hard way on BSE01 (inconsistent settings that were invisible until something broke).
- If a setting is missing, add it to the correct profile YAML in `firmware/profiles/` or to Step 5 in `provision.sh`, then reprovision. Don't just `--set` it and move on.

**Read-only and operational commands are fine:** `--info`, `--get`, `--nodes`, `--sendtext`, `--traceroute`.

**Emergency exception:** If a node is in the field and needs an immediate fix, apply it via CLI but document what changed, open a TODO to update the profile/script, and flag it to Leighton.

---

## Project Context (ported from OpenClaw memories, March-April 2026)

### What CrypTAK Is
AES-256-GCM encrypted ATAK comms over Meshtastic LoRa mesh, with self-hosted FreeTAKServer backend. Repo: github.com/wlcarden/CrypTAK (public as of v0.9.0, released 2026-03-14).

### Server Infrastructure
- Unraid server at 192.168.50.120 (SSH alias: unraid)
- Docker stack: FTS, Node-RED (WebMap at :1880/tak-map), Mosquitto (:1883), Mumble (:64738), mesh-relay, MeshMonitor (:8090)
- MeshMonitor deployed 2026-03-20: web UI for fleet monitoring, serial bridge to GW01 via TCP 4403
- Node-RED context storage: localfilesystem (mesh node state survives restarts)
- FTS data: /mnt/user/appdata/tak-server/
- MeshMonitor compose: /mnt/user/appdata/meshmonitor/docker-compose.yml
- Docker compose override: /mnt/user/appdata/tak-server/docker-compose.override.yml

### MQTT Architecture (refactored 2026-03-28)
- mesh-relay consumes from Mosquitto topic msh/US/2/2/json/LongFast/#
- GW01 (RAK11200) is the WiFi MQTT gateway, replaced BRG01 serial bridge role
- Mosquitto broker on Unraid, user meshtastic, password in secrets/mqtt.md
- mesh-relay connects via paho-mqtt 2.1.0 (no more serial/TCP contention issues)
- relay.py: ~1148 lines, handles position/telemetry/detection/neighborinfo to CoT conversion

### Node Fleet

> **Source of truth: `firmware/nodes.yaml`** — this table may be stale.

| Node ID | Name | Hardware | Role | WiFi | Notes |
|---------|------|----------|------|------|-------|
| !087a29a4 | GW01 | RAK11200 (ESP32) | CLIENT | Yes | WiFi MQTT gateway, USB-powered on Unraid, fixed position |
| !55c6ddbc | BRG01 | T-Beam v1 | CLIENT | Yes | Bridge node |
| !435ae49c | RPT02 | T-Beam Supreme S3 | ROUTER | Yes (unstable) | Repeater |
| !435bab94 | RPT03 | T-Beam Supreme S3 | ROUTER | Yes (unstable) | Repeater |
| !01f94ec0 | BSE01 | RAK4631 (nRF52840) | ROUTER | No | Fixed base station, no GPS |
| !c6eadff0 | SOL01 | RAK4631 | ROUTER | No | Solar relay |
| !4ad09103 | RPT01 | RAK4631 | ROUTER | No | Repeater |
| !9aa4baf0 | VHC01 | RAK4631 | CLIENT | No | Vehicle node |
| !dce7b97d | TRK01 | SenseCAP T1000-E | Tracker | No | GPS tracker |

All active nodes run firmware 2.7.15.567b8ea.

### Known Issues and Lessons Learned

**Serial port contention (resolved 2026-03-28):** mesh-relay was fighting meshtastic-serial-bridge for /dev/ttyACM0. Fixed by switching mesh-relay to MQTT ingest. Never run meshtastic --port /dev/ttyACM0 while serial bridge container is running.

**Protobuf parse errors from BRG01:** T-Beam firmware logs debug output (ANSI escape codes) to same UART as protobuf stream. Irrelevant now that mesh-relay uses MQTT through GW01.

**T-Beam Supreme (ESP32-S3) config instability:** Incremental serial config writes sometimes revert on reboot. Use YAML batch config (meshtastic --configure) for reliable provisioning.

**FTS zombie state (fixed 2026-03-16):** FTS can stay alive with broken listen loop (list index out of range forever). restart: always does not help. FTS watchdog cron (/boot/config/scripts/fts-watchdog.sh) runs every 5 min on Unraid. Also fixed from_id null guard in relay.py.

**Stale WebMap markers (fixed 2026-03-16):** Node-RED worldmap cached 469+ markers without expiry. Added stale-time purge to cache write and replay filter.

**Ghost markers (fixed 2026-03-08):** NODEDB_SEED_MAX_AGE_SECS=7200 applied to connect-time and periodic seeds.

**TRK01 (T1000-E) provisioning:** Bootloader = hold button + plug-unplug-plug magnetic cable for solid green LED. UF2 drag-and-drop only, NOT adafruit-nrfutil DFU. security.is_managed: true silently rejects all config writes; requires full flash erase.

**VHC01 battery drain (2026-03-30):** Offline 9 days in parking garage, drained to 10%/3.301V. Recovery: full power cycle (unplug, 30s wait, hold reset while plugging in). Solar through windshield may be unreliable (UV coating).

### Battery Monitor Cron
- Runs every 2 hours, logs to mesh-telemetry/battery-log.csv
- Thresholds: voltage < 3.5V = WARNING, < 3.3V = CRITICAL
- GW01 not heard > 30 min = ALERT (USB canary)
- Other nodes not heard > 6 hours = STALE
- TRK01 excluded (known offline)
- All-nodes-same-staleness = meshmonitor issue, not nodes
- Model: Haiku (downgraded from Sonnet for cost)

### reTerminal Field Unit (deployed 2026-03-13/14)
- Seeed reTerminal CM4: 4GB RAM, 32GB eMMC, 5" touchscreen (1280x720)
- E10-1 expansion: RS-232/485, Mini-PCIe (4G modem), dual 18650 UPS
- BQ25790 charger patched (ADC enable fix for 2S battery reading)
- Docker stack: FTS (QEMU amd64 emulation on armv7l), Node-RED, Mosquitto, Mumble, mesh-relay
- GPS: EC25 GNSS -> gpsd -> MQTT -> Node-RED -> CoT (uid CRYPTAK-TERM01, callsign TERM01)
- Power button: short press = toggle display, 5s hold = shutdown
- F1/F2/F3 buttons: hardware mismatch with DT, not working (non-blocking)
- SSH: ssh pi@192.168.50.174
- Field compose: docker-compose.field.yml + .env.field

### mesh-diag Tool (MVP, 2026-03-18)
- Location: tools/mesh-diag/
- FastAPI + vanilla HTML/JS for reTerminal touchscreen
- /api/status, /api/diagnose, /api/settings endpoints
- Run: .venv/bin/python -m uvicorn server.app:app --host 0.0.0.0 --port 8100

### CrypTAK v0.9.0 Release (2026-03-14)
- Public repo, tagged v0.9.0
- v1.0.0 blockers: WM1302 LoRa, sensor hardware field test, GPS outdoor fix
- Security audit: 8 findings in docs/security.md
- Firmware admin key rotated (secrets.sh pattern)

### WebMap Editing Rules
- Edit via /mnt/cache/appdata/tak-server/nodered/webmap/index.html (absolute path bypasses FUSE)
- Write in-place (Python r+/truncate), never sed -i or cp (ESTALE -116 on Unraid shfs)

### Hardware Procurement
- Rokland: RAK Gold US distributor, free shipping, first call for RAK
- Seeed Studio: cheapest for SenseCAP/Wio
- T-Beam Supreme ships with SoftRF firmware, needs Meshtastic flash
- Austin Mesh (austinmesh.org/devices/) reference build for solar relays (~$182 BOM)

### Deploy Paths
- CrypTAK repo on Unraid: /mnt/user/appdata/github-runner/CrypTAK/CrypTAK/
- CI/CD via GitHub Actions
- Deploy key on Unraid: ~/.ssh/cryptak_deploy (ed25519, write access)

### Credentials (reference locations only)
- Node-RED admin password: NR_ADMIN_PASS env var (check secrets/unraid.md)
- MQTT credentials: secrets/mqtt.md
- Mosquitto ACL: `/mnt/user/appdata/mosquitto/config/acl.conf` on Unraid (users: `meshtastic` rw, `nodered` read on `msh/#`). Reference copy + apply notes in `server/mosquitto/`; reload with `docker kill -s HUP mosquitto`. Any new MQTT consumer needs a `user` block or it is silently denied.
- MeshMonitor login: admin; current password is outside Git at ~/.config/cryptak/meshmonitor/credentials.json. Recovery copy and handling instructions: docs/meshmonitor-access.md. The old deployment-doc password is invalid; do not retry it or reset credentials without authorization.

### Audit TODOs (from 2026-03-28)
- Move MQTT uplink from ch0 to ch1 (cryptak) on GW01
- Add cryptak channel to RPT02/RPT03 (need USB, WiFi did not persist on Supremes)
- Bind FTS 8087 to localhost only
- Consider IoT VLAN for mesh WiFi credentials
- Build nodeinfo callsign cache in MQTT handler
