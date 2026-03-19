# Mesh Diag — Meshtastic Field Diagnostic Tool

## Philosophy
A **Meshtastic diagnostic multimeter** — hardware-agnostic, mesh-agnostic, fleet-optional.
Diagnose first, fleet-aware optionally, modify carefully.

## Target Hardware
- Seeed reTerminal (CM4, 4GB RAM) with E10-1 expansion
- 5" touchscreen, 1280×720 landscape
- USB-A ports for connecting radios
- Chromium kiosk mode

## Architecture
- **Backend:** FastAPI + Meshtastic Python library (`meshtastic`)
- **Frontend:** Single-page vanilla HTML/CSS/JS, WebSocket for live updates
- **No heavy frameworks.** CM4 can't handle React/Electron overhead.
- **Offline-first.** No network dependency.

## UI Layout (1280×720 landscape, touch-first)
- Tab bar (top): Status | Diagnose | Modify | Log — 64px+ touch targets
- Main content: scrollable vertically, card-based
- Status bar (bottom): connected node info + eject button
- Min touch target: 64px (260 DPI at 5")
- No menus, no dropdowns, no hamburger. Everything visible or one tap away.

## Modes

### Mode 1: Status (read-only, auto-populates on USB connect)
- Identity: name, short name, node ID, hardware model, firmware version
- RF: region, modem preset, hop limit, channel utilization, airtime
- GPS: fix type, satellites, HDOP, coordinates, last fix age
- Power: battery %, voltage, charging state, uptime, reboot count
- Channels: list with PSK type, precision, muted
- Security: admin key hash, is_managed, serial enabled
- Mesh: nodedb count, neighbor list with SNR
- Color-coded cards: green=healthy, yellow=attention, red=problem

### Mode 2: Diagnose (read-only, automated health checks)
- Rules engine evaluates radio state and produces prioritized findings
- Each finding: severity icon + plain-English explanation + recommended action
- Tapping a finding links to the relevant Modify action
- Example findings:
  - 🔴 is_managed: true — config changes silently ignored
  - 🟡 position_precision < 32 — coordinates masked
  - 🟡 GPS fix stale — no update in N minutes
  - 🟢 Firmware current
  - 🔴 Default PSK — traffic readable by anyone
  - 🟡 Empty nodedb — not hearing any nodes

### Mode 3: Modify (write, explicit, with guardrails)
- Auto-exports config backup before any change (timestamped + node ID)
- Actions contextual to Diagnose findings, not static buttons
- "Apply fleet profile" is ONE option (loads from profiles/ dir)
- Before/after preview for every change
- Confirmation dialog with exact diff
- Restore-from-backup capability

### Mode 4: Log (session history)
- Every diagnostic session timestamped: node ID, findings, changes, backup ref
- Exportable as JSON/markdown

## Auto-Detection
- udev rule watches for USB serial devices
- Backend probes new /dev/ttyACM* with Meshtastic API
- WebSocket pushes "radio connected" / "radio disconnected" to UI
- Handles hot-plug/unplug gracefully

## Diagnostic Rules (initial set)
1. is_managed check
2. admin_key presence and fleet match (optional)
3. position_precision evaluation
4. GPS health (fix type, age, satellite count)
5. Firmware version (compare against known versions in firmware/ dir)
6. Channel config sanity (PSK type, precision per channel)
7. Battery/power health
8. Nodedb population and signal quality
9. Channel utilization / duty cycle
10. Reboot count (high = instability)
11. Hardware model vs role mismatch (e.g., TRACKER role on non-GPS hardware)

## Project Structure
```
tools/mesh-diag/
├── DESIGN.md               # This file
├── README.md               # User-facing docs
├── server/
│   ├── app.py              # FastAPI backend + WebSocket
│   ├── radio.py            # Meshtastic connection manager (auto-detect, hot-plug)
│   ├── diagnostics.py      # Health check rules engine
│   ├── config_backup.py    # Auto-backup before modify
│   ├── models.py           # Pydantic models for radio state
│   └── requirements.txt
├── ui/
│   ├── index.html          # Single-page app
│   ├── style.css           # Touch-first responsive CSS
│   └── app.js              # WebSocket + tab switching + touch
├── profiles/               # Fleet profiles (optional)
│   └── cryptak.yaml        # CrypTAK fleet spec
├── backups/                 # Auto-saved config exports (gitignored)
├── logs/                    # Session logs (gitignored)
├── install.sh              # Setup script for reTerminal
└── mesh-diag.service        # systemd unit for auto-start on boot
```

## Physical Button Mapping (if F1-F3+O enabled)
- F1 → Status tab
- F2 → Diagnose tab
- F3 → Modify tab
- O  → Log tab

## Future
- Channel compatibility checker (can two radios talk?)
- Firmware library with hardware-appropriate flash
- Mesh topology visualization on touchscreen
- Remote diagnostics (diagnose via mesh admin, not just USB)
