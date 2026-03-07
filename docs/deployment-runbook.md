# Deployment Runbook

Step-by-step checklist for deploying the Meshtastic + ATAK mesh network. Check off each item as you complete it.

---

## Prerequisites

Before starting deployment, confirm:

- [ ] All Meshtastic radios assembled and firmware flashed (see [hardware-builds.md](hardware-builds.md))
- [ ] Raspberry Pi OS installed on RPi 4B microSD card
- [ ] Dev machine has `adb` installed and on PATH
- [ ] Dev machine has Python `meshtastic` CLI installed (`pip install meshtastic`)
  - **Note:** CLI is invoked as `python3 -m meshtastic`, not `meshtastic` (not on PATH by default)
- [ ] Dev machine has Docker and Docker Compose installed (if building TAK server locally)
- [ ] USB cables available for each Meshtastic device

---

## Phase 1: Configure Meshtastic Mesh

All nodes in this mesh use the **LongFast default channel** (PSK = `AQ==`). This is
Meshtastic's built-in default — no key generation needed unless switching to a custom channel.

- [ ] **Step 1:** Connect each Meshtastic node via USB. Grant serial port access:

  ```bash
  sudo chmod a+rw /dev/ttyACM0   # or /dev/ttyUSB0 — check with: ls /dev/tty{ACM,USB}*
  ```

- [ ] **Step 2:** Apply configuration to each node:

  ```bash
  # T-beam (Lilygo) — connect via WiFi if already on the network
  python3 -m meshtastic --host 192.168.50.198 --configure firmware/lilygo-lora32/device.yaml

  # RAK nodes (repeat for each via USB)
  python3 -m meshtastic --port /dev/ttyACM0 --configure firmware/rak5005-4630/device.yaml
  ```

  Both YAML files already have `psk: "AQ=="` (LongFast default). No edits needed.

- [ ] **Step 3:** Verify mesh connectivity. With at least two nodes powered on, traceroute
      between them:

  ```bash
  # From RAK to T-beam (substitute node IDs from --nodes output)
  python3 -m meshtastic --port /dev/ttyACM0 --nodes
  python3 -m meshtastic --port /dev/ttyACM0 --traceroute '!55c6ddbc'
  ```

  A round-trip response confirms the LoRa link. Indoor SNR of 6–11 dB is normal.

  Known node IDs:

  | Node | ID | Hardware | Role | Notes |
  |---|---|---|---|---|
  | CrypTAK-BRG01 | `!55c6ddbc` | LilyGo T-Beam | Bridge | USB to Unraid; WiFi LAN; MQTT bridge |
  | CrypTAK Base | `!a51e2838` | RAK4631 | Base station | Home rooftop, solar, fixed |
  | CrypTAK-RLY01 | `!3db00f2c` | RAK4631 | ROUTER | Field relay |
  | CrypTAK-RLY02 | `!c6eadff0` | RAK4631 | ROUTER | Wall/vehicle powered |
  | CrypTAK-VHC01 | `!9aa4baf0` | RAK4631 | CLIENT | Vehicle/field node |
  | Tracker Alpha | `!01f94ec0` | RAK4631 | TRACKER | GPS tracker |

---

## Phase 2: Start TAK Server

The Unraid server (`192.168.50.120`) runs the full stack via Docker Compose. The
stack includes FreeTAKServer, FTS-UI (admin), Node-RED worldmap, Mosquitto MQTT,
Headscale VPN, nginx, and Authelia OIDC. See `tak-server/README.md` for details.

- [ ] **Step 4:** Configure environment:

  ```bash
  cd tak-server
  cp .env.example .env
  nano .env    # set UNRAID_IP and change all default passwords
  ```

  Key variables:
  - `UNRAID_IP` — LAN IP ATAK clients use to reach the server (e.g. `192.168.50.120`)
  - `FTS_FED_PASSWORD`, `FTS_CLIENT_CERT_PASSWORD`, `FTS_WEBSOCKET_KEY`, `FTS_SECRET_KEY` — change all from defaults
  - `FTS_API_KEY` — set to `Bearer token` initially; update after first FTS-UI login

- [ ] **Step 5:** Start core FTS first, then the rest of the stack:

  ```bash
  docker compose up -d freetakserver
  # Wait 60s for FTS to initialize, then:
  docker compose up -d
  ```

- [ ] **Step 6:** Verify all services are reachable:

  ```bash
  curl -s -o /dev/null -w "%{http_code}" http://<UNRAID_IP>:19023/  # → 200 (FTS API)
  curl -s -o /dev/null -w "%{http_code}" http://<UNRAID_IP>:5000/   # → 302 (FTS-UI login)
  curl -s -o /dev/null -w "%{http_code}" http://<UNRAID_IP>:1880/tak-map/  # → 200 (worldmap)
  ```

  Then open `http://<UNRAID_IP>:5000` — log in with `admin` / `password` and
  **change password immediately**.

---

## Phase 3: Set Up ATAK Devices

- [ ] **Step 7:** Install ATAK and the Meshtastic plugin on the Pixel 6a:

  ```bash
  ./scripts/install-dev.sh
  ```

  (See [hardware-builds.md](hardware-builds.md) Section 4 if this fails.)

- [ ] **Step 8:** Add TAK server connection in ATAK:
  - Open ATAK -> Settings -> Network Preferences -> TAK Server Management
  - Add server: host = Tailscale IP (100.x.x.x) or LAN IP, port = `8087`, protocol = TCP

- [ ] **Step 9:** Enable Meshtastic plugin:
  - ATAK -> Settings -> Plugins -> Meshtastic -> Enable
  - Connect Meshtastic app to radio (BT, USB, or TCP network)
  - In plugin settings: select **Channel 0**, set hop limit to **3**

- [ ] **Step 10:** Enable app-layer encryption:
  - Meshtastic Plugin Preferences -> **Enable App-Layer Encryption**
  - Enter the team passphrase (all devices must use the same passphrase)

---

## Phase 4: Team Onboarding

- [ ] **Step 11:** Export onboarding data package

  In the ATAK plugin preferences, tap **Export Onboarding Package** (requires App-Layer Encryption enabled). A `.zip` Mission Package is written to external storage and a share sheet opens. Send via Signal, AirDrop, USB, or any available channel. The package contains the encryption PSK, epoch rotation settings, and TAK server connection profile.

- [ ] **Step 12:** Distribute the package to team members. Each member imports the package into ATAK to auto-configure all settings.

---

## Phase 5: Verification

Run through each check to confirm the deployment is working end-to-end:

- [ ] **Step 13:** PLI (blue force tracking dot) appears on all devices for all connected team members
  - RAK nodes require outdoor GPS fix (cold start: 1–5 min with clear sky)
  - T-beam has no GPS — will not show PLI dot

- [ ] **Step 14:** GeoChat message sent from one device delivers to all other devices

- [ ] **Step 15:** Confirm FTS received the GeoChat (server-side check):

  ```bash
  ssh root@192.168.50.120
  docker exec freetakserver grep 'b-t-f' \
    /home/freetak/FreeTAKServer/core/cot_management/logs/CotManagement/DEBUG.log | tail -5
  ```

  A `b-t-f` entry confirms FTS received and routed the message. Full message text visible in:

  ```bash
  docker exec freetakserver tail -20 \
    /home/freetak/FreeTAKServer/components/extended/mission/logs/Mission/DEBUG.log
  ```

- [ ] **Step 16:** Logcat on the receiving device shows `Decrypted payload`:

  ```bash
  adb logcat | grep -i "Decrypted payload"
  ```

- [ ] **Step 17:** Logcat on the sending device shows `Encrypted`:

  ```bash
  adb logcat | grep -i "Encrypted"
  ```

---

## Troubleshooting

| Symptom                                             | Likely Cause                             | Fix                                                                                                                                          |
| --------------------------------------------------- | ---------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| Plugin shows red in ATAK                            | ATAK version mismatch                    | Install ATAK-CIV 5.5.1 (plugin requires this version)                                                                                        |
| No Bluetooth pairing                                | Meshtastic app not set up                | Install Meshtastic Android app first; pair radio there. Plugin communicates via Meshtastic app IPC, not direct BT. Also works over TCP/WiFi. |
| GeoChat not visible in Meshtastic app               | Expected behavior                        | Plugin sends DATA portnum packets (not TEXT_MESSAGE_APP); app-layer encryption makes payload binary. Message IS transmitted on LoRa.         |
| Messages not appearing on remote devices            | Channel PSK mismatch                     | Verify all nodes use same PSK (`AQ==` for LongFast default). Check with: `python3 -m meshtastic --port /dev/ttyACM0 --nodes`                 |
| Messages arrive but show as garbled / no decryption | App-layer encryption passphrase mismatch | Confirm ATAK plugin encryption passphrase is identical on all devices                                                                        |
| TAK server unreachable from ATAK                    | Wrong IP or server not running           | Confirm ATAK is connected via Tailscale (100.x.x.x) or LAN IP; run `docker compose ps` on Unraid                                             |
| ATAK connection drops after ~30 min                 | FTS SQLAlchemy session corruption        | Verify both patch files are bind-mounted in docker-compose.yml (table_controllers_patched.py, event_table_controller_patched.py)             |
| GPS position not updating (RAK nodes)               | GPS cold start delay                     | Wait up to 5 minutes outdoors with clear sky; check GPS antenna connection                                                                   |
| No LoRa link between nodes                          | Nodes not on same channel/PSK            | Run traceroute: `python3 -m meshtastic --port /dev/ttyACM0 --traceroute '!<node-id>'`                                                        |
| `meshtastic: command not found`                     | Not on PATH                              | Use `python3 -m meshtastic` instead                                                                                                          |
| `Permission denied: /dev/ttyACM0`                   | Missing dialout group                    | Run `sudo chmod a+rw /dev/ttyACM0` (temporary) or `sudo usermod -a -G dialout $USER` (permanent)                                             |
| `install-dev.sh` fails                              | Missing ATAK APK                         | Download ATAK-CIV 5.5.1.8 debug APK and place at `apks/atak-civ/atak-civ-5.5.1.8-debug.apk`                                                  |
