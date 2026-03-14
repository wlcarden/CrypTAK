# Field Unit — reTerminal Deployment

The CrypTAK field unit is a portable TAK server running on a Seeed reTerminal
(CM4-based) with the E10-1 expansion board. It provides local CoT aggregation,
encrypted voice, and automatic reach-back to the home server when connectivity
is available.

---

## Hardware

### Base Unit

| Component | Model                           | Purpose                                                 |
| --------- | ------------------------------- | ------------------------------------------------------- |
| Compute   | Seeed reTerminal (CM4, 4GB RAM) | Linux host with 5" touchscreen                          |
| Expansion | reTerminal E10-1                | UPS (BQ25790), 4G modem slot, GPIO breakout             |
| UPS       | BQ25790 (on E10-1)              | 2S Li-ion charge controller, powers through barrel jack |
| Modem     | Quectel EC25-EU                 | 4G LTE data + GNSS (GPS/GLONASS/Galileo)                |
| Antenna   | SMA 4G + SMA GNSS               | Modem data and positioning                              |

### Power

The BQ25790 on the E10-1 board manages 2S Li-ion batteries with barrel jack
input (12V). The reTerminal runs from the UPS output, providing uninterrupted
operation through power interruptions.

**Known issue — Seeed BQ25790 driver:** The stock driver's ADC readback is
incorrect for 2S configurations. A patched driver fixes the VREG and ADC
scaling. This is applied during setup by `scripts/setup-field-pi.sh`.

### GNSS

The EC25-EU modem provides GPS/GLONASS/Galileo positioning via its secondary
GNSS receiver. GNSS is enabled at boot by sending `AT+QGPS=1` to
`/dev/ttyUSB2`. The `ec25-gnss.service` systemd unit handles this.

**GNSS output:** gpsd reads NMEA sentences from the modem's NMEA port and
provides a JSON API on `localhost:2947`. The `gps-mqtt-bridge.py` script
subscribes to gpsd and publishes TPV (Time-Position-Velocity) messages to MQTT
topic `cryptak/field/gps/tpv`. Node-RED field flows consume this topic and
build a CoT PLI event for the field unit (callsign `CRYPTAK-TERM01`).

---

## Software Stack

### Docker Services (`docker-compose.field.yml`)

| Service         | Port             | Purpose                                | Memory Limit |
| --------------- | ---------------- | -------------------------------------- | ------------ |
| `freetakserver` | 8087, 8089, 8080 | CoT aggregation for field ATAK clients | 768 MB       |
| `mumble`        | 64738 TCP/UDP    | Encrypted push-to-talk voice (Opus)    | 256 MB       |
| `halow-bridge`  | —                | CoT buffer + forward to home FTS       | 128 MB       |

Total Docker memory: ~1.1 GB of 4 GB available.

### systemd Services (`server/field-services/`)

| Service                   | Script                       | Purpose                           |
| ------------------------- | ---------------------------- | --------------------------------- |
| `ec25-gnss.service`       | AT command to `/dev/ttyUSB2` | Enable EC25 GNSS receiver at boot |
| `gps-mqtt-bridge.service` | `gps-mqtt-bridge.py`         | gpsd → MQTT bridge (2s interval)  |
| `power-button.service`    | `power-button.py`            | reTerminal button handler         |

### Power Button Behavior

The reTerminal's built-in button (mapped to `KEY_SLEEP` via `gpio_keys` input
device) is handled by `power-button.py`:

| Action      | Duration     | Effect                                                                              |
| ----------- | ------------ | ----------------------------------------------------------------------------------- |
| Short press | < 2 seconds  | Toggle display backlight (via STM32 sysfs `/sys/class/backlight/1-0045/brightness`) |
| Long press  | >= 5 seconds | Graceful shutdown (`shutdown -h now`)                                               |

The 2–5 second dead zone prevents accidental shutdowns. Power-cycle the barrel
jack to restart after shutdown.

---

## Setup

### Prerequisites

- Seeed reTerminal with E10-1 expansion and Raspberry Pi OS (64-bit Bookworm)
- EC25-EU modem installed in E10-1 M.2 slot
- 2S Li-ion batteries installed in E10-1

### Automated Setup

```bash
# On the reTerminal:
sudo ./scripts/setup-field-pi.sh
```

This script installs Docker, configures hostapd (WiFi AP), dnsmasq (DHCP),
gpsd, udev rules, and the field systemd services.

### Manual Setup

1. **Install Docker:**

   ```bash
   curl -fsSL https://get.docker.com | sh
   sudo usermod -aG docker $USER
   ```

2. **Enable GNSS:**

   ```bash
   sudo cp server/field-services/ec25-gnss.service /etc/systemd/system/
   sudo systemctl enable --now ec25-gnss
   ```

3. **Install GPS bridge:**

   ```bash
   sudo cp server/scripts/gps-mqtt-bridge.py /usr/local/bin/
   pip install paho-mqtt
   sudo cp server/field-services/gps-mqtt-bridge.service /etc/systemd/system/
   sudo systemctl enable --now gps-mqtt-bridge
   ```

4. **Install power button handler:**

   ```bash
   sudo cp server/scripts/power-button.py /usr/local/bin/
   sudo cp server/field-services/power-button.service /etc/systemd/system/
   sudo systemctl enable --now power-button
   ```

5. **Deploy Docker services:**

   ```bash
   cd /opt/cryptak/server
   cp .env.field.example .env.field
   # Edit .env.field — change ALL passwords
   docker compose -f docker-compose.field.yml --env-file .env.field up -d
   ```

6. **Configure WiFi AP (hostapd):**

   ```bash
   # /etc/hostapd/hostapd.conf
   interface=wlan0
   ssid=CrypTAK-Field
   hw_mode=g
   channel=6
   wpa=2
   wpa_passphrase=<CHANGE-THIS>
   wpa_key_mgmt=WPA-PSK
   rsn_pairwise=CCMP
   ```

7. **Tailscale enrollment (for reach-back):**
   ```bash
   tailscale up --login-server https://vpn.thousand-pikes.com --hostname tak-field
   ```

---

## ATAK Client Configuration

ATAK phones connect to the field unit's WiFi AP and point at the local FTS:

| Setting    | Value                   |
| ---------- | ----------------------- |
| WiFi SSID  | `CrypTAK-Field`         |
| TAK Server | `192.168.73.1:8087` TCP |
| Mumble     | `192.168.73.1:64738`    |

---

## Reach-Back (halow-bridge)

When the reTerminal has internet access (via 4G modem, tethered phone, or
open WiFi), Tailscale establishes a VPN tunnel to the home Unraid server.
The halow-bridge service automatically:

1. Detects VPN connectivity to `REMOTE_FTS_HOST` (default: `100.64.0.1`)
2. Flushes buffered CoT events (up to `BUFFER_MAX=5000`)
3. Begins live forwarding of new CoT events
4. Falls back to buffering mode when VPN drops

Events are deduplicated by CoT UID + timestamp to prevent replay on sync.

---

## Known Issues

### BQ25790 ADC / VREG Scaling

The stock Seeed BQ25790 driver reports incorrect battery voltage and charge
current for 2S configurations. The patched driver corrects ADC multipliers
and VREG target voltage. Applied by `setup-field-pi.sh`.

### EC25 GNSS Cold Start

First GNSS fix after power-on can take 1–3 minutes (cold start). The
`ec25-gnss.service` has a 10-second delay after ModemManager starts to ensure
the modem's AT interface is ready before sending `AT+QGPS=1`.

### FTS Memory on CM4

FTS 2.2.1 on 4 GB RAM is viable but tight. The field configuration disables
database persistence (`FTS_COT_TO_DB=false`) and uses `warning` log level to
reduce memory pressure. Monitor with `docker stats`. If OOM occurs, reduce
`FTS_NUM_ROUTING_WORKERS` from 2 to 1.

### Display Backlight Path

The backlight sysfs path (`/sys/class/backlight/1-0045/brightness`) is
specific to the reTerminal's STM32 coprocessor. This path does not exist on
standard Raspberry Pi — the power button handler will log errors but continue
running.

### Power Button Event Device

`power-button.py` searches for the `gpio_keys` input device under
`/sys/class/input/`. If the device is not found (e.g., on a non-reTerminal
Pi), it falls back to `/dev/input/event3`. The handler logs which device it
opens at startup.

---

## Troubleshooting

| Symptom                     | Cause                      | Fix                                                                  |
| --------------------------- | -------------------------- | -------------------------------------------------------------------- |
| No GPS fix after 3+ minutes | EC25 GNSS not enabled      | Check `systemctl status ec25-gnss` — verify AT command sent          |
| MQTT topic empty            | gpsd not running or no fix | Check `systemctl status gpsd` and `gps-mqtt-bridge` logs             |
| ATAK can't reach FTS        | Wrong WiFi network or IP   | Verify phone is on `CrypTAK-Field` AP, server at `192.168.73.1:8087` |
| Display won't toggle        | Wrong backlight path       | Check `ls /sys/class/backlight/` for correct path                    |
| Shutdown doesn't work       | power-button.py not root   | Service must run as root (no `User=` in systemd unit)                |
| halow-bridge not syncing    | No VPN tunnel              | Check `tailscale status` — ensure home server is reachable           |
| FTS OOM killed              | Memory pressure            | Set `FTS_NUM_ROUTING_WORKERS=1`, check `docker stats`                |
