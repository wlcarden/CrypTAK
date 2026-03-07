---

## Next-Gen Hardware Spec (v2 — Finished Boards)

> **Current policy (2026-03-07):** All new node construction uses this spec. Existing WisBlock nodes
> remain in service until they need replacement. See legacy WisBlock sections below for reference.

### Design Principles

- Pre-assembled, pre-flashed devices only — no module assembly or 3D-printed cases
- All outdoor nodes: IP-rated enclosures
- Solar included where possible (nRF52840-based nodes preferred for solar power budget)
- MQTT-capable nodes (ESP32-S3) reserved for bridge/gateway roles

### Node Role Map

| Role | Device | MCU | WiFi | Solar | Price |
|---|---|---|---|---|---|
| MQTT Bridge | RAK WisMesh WiFi MQTT Gateway | ESP32-S3 | ✅ | ❌ | $70 |
| Solar MQTT relay (public bridge) | LilyGo T-Beam Supreme | ESP32-S3 | ✅ | ✅ | $58 |
| Vehicle node | LilyGo T-Beam Supreme | ESP32-S3 | ✅ | ✅ | $58 |
| Solar base station / field relay | Seeed SenseCAP Solar Node P1-Pro | nRF52840 | ❌ | ✅ 5W | $96 |
| Powered / solar relay | RAK WisMesh Repeater Mini | nRF52840 | ❌ | ✅ incl. | ~$100 |
| GPS tracker | Seeed Card Tracker T1000-E | nRF52840 | ❌ | ❌ | $40 |

### Suppliers

- **Rokland** (store.rokland.com) — WisMesh Gateway, T-Beam Supreme, WisMesh Repeater Mini; free US shipping
- **Seeed Studio** (seeedstudio.com) — SenseCAP Solar Node P1-Pro, T1000-E

---

## 1a. RAK WisMesh WiFi MQTT Gateway

**Role:** MQTT bridge — connects Meshtastic LoRa mesh to Mosquitto MQTT broker over WiFi.

**Hardware:** RAK3312 (ESP32-S3) + SX1262, WiFi + BLE5, optional IP65 enclosure.

### Setup

1. Power via USB-C
2. Connect Meshtastic app via BLE
3. Set device to CLIENT role (gateway doesnt need to relay RF — its a bridge, not a router)
4. Configure MQTT via app: Settings → Module Config → MQTT:
   - Server address: `192.168.50.120`
   - Port: `1883`
   - Username: `meshtastic`
   - Password: (see TOOLS.md)
   - Encryption enabled: ✅
   - JSON enabled: ❌
5. Enable uplink + downlink on Channel 0:
   - Channel 0 → Uplink: ✅, Downlink: ✅
6. Configure WiFi: Settings → Config → Network → WiFi SSID/PSK

**Or via CLI:**

```bash
meshtastic --host <device-ip>   --set mqtt.enabled true   --set mqtt.address 192.168.50.120   --set mqtt.port 1883   --set mqtt.username meshtastic   --set mqtt.password <password>   --set mqtt.encryption_enabled true   --set mqtt.json_enabled false
meshtastic --host <device-ip> --ch-set uplink_enabled true --ch-index 0
meshtastic --host <device-ip> --ch-set downlink_enabled true --ch-index 0
```

---

## 1b. LilyGo T-Beam Supreme — MQTT Relay / Vehicle Node

**Role:** Solar MQTT public relay (pirated WiFi) or vehicle node with GPS.

**Hardware:** ESP32-S3, SX1262, GPS (L76K or NEO-M10S), WiFi + BLE5, AXP2101 PMIC, 18650 holder.

> ⚠️ Ships with **SoftRF** firmware. Must flash Meshtastic before use.

### Flash Meshtastic

1. Connect via USB-C
2. Go to [flasher.meshtastic.org](https://flasher.meshtastic.org)
3. Select board: **T-Beam S3 Core**
4. Click "Flash" — use 1200bps reset if needed; or hold BOOT → plug in → release after 3s
5. Firmware file: `firmware-tbeam-s3-core-X.X.X.xxxxxxx.bin`

### Configure for MQTT relay role

Same MQTT config as Gateway above. Additionally:
- Role: ROUTER (if acting as relay) or CLIENT (if vehicle/field)
- WiFi: Set to the target network SSID/PSK

### Solar wiring

The T-Beam Supreme AXP2101 PMIC supports solar input via the JST solar connector. Use a 5-6V solar panel rated for the 18650 charging current. Do not exceed 6V input.

---

## 1c. Seeed SenseCAP Solar Node P1-Pro

**Role:** Fixed solar base station or field relay (ROUTER).

**Hardware:** XIAO nRF52840, Wio-SX1262 LoRa, XIAO L76K GPS, 5W solar panel, 4× 18650 (included).

> Ships pre-flashed with Meshtastic firmware. No flash required.

### Setup

1. Insert 18650 batteries (4 included)
2. Mount solar panel (pre-wired)
3. Power on — LED indicates Meshtastic boot
4. Connect Meshtastic app via BLE
5. Set role to **ROUTER**
6. Set GPS to ENABLED
7. Verify position broadcasts

> **No WiFi/MQTT** — nRF52840 does not support WiFi. This device is a pure RF relay.
> Do not attempt MQTT configuration — it will not work.

---

## 1d. RAK WisMesh Repeater Mini

**Role:** Powered or solar relay (ROUTER), outdoor deployment.

**Hardware:** RAK4631 (nRF52840) + SX1262, IP67 enclosure, 3200 mAh battery + solar panel (included).

> Ships pre-flashed with Meshtastic firmware.

### Setup

1. Mount via included wall or pole bracket
2. Connect solar panel (pre-wired in enclosure)
3. Power on
4. Configure via Meshtastic BLE app:
   - Role: ROUTER
   - GPS: not available — N/A
5. Antenna: external RP-SMA, use included or upgrade to higher-gain

---

## 1e. Seeed Card Tracker T1000-E

**Role:** GPS tracker (TRACKER mode).

**Hardware:** nRF52840 + LR1110 (LoRa 863-928 MHz), AG3335 GPS, IP65, credit-card form factor.

> Ships pre-flashed. Note: uses **LR1110** radio (not SX1262) — fully Meshtastic compatible on 915 MHz.

### Setup

1. Power on via USB-C charge / wake
2. Connect via Meshtastic BLE app
3. Set role: **TRACKER**
4. GPS: ENABLED
5. Confirm position broadcasts to mesh

> The T1000-E has no display and no buttons for config. All config is via BLE app only.

# Hardware Builds

Firmware and configuration procedures for each device in the Meshtastic mesh network.

## 1. Lilygo 868/915 LORA32

ESP32-based LoRa radio with TFT display. No GPS module.

### Power

- USB-C 5V for bench/dev use
- LiPo battery via JST 1.25mm connector for portable deployment

### Firmware Flash

1. Connect device via USB-C
2. Open [Meshtastic Web Flasher](https://flasher.meshtastic.org)
3. Select board: **TLORA_V2_1_16** (or **T-LORA32_V2** depending on revision)
4. Click Flash — the Web Flasher downloads and applies the correct ESP32 firmware automatically (no local file needed)
5. Wait for reboot

**Alternative (CLI):** Use `esptool.py` to flash manually:

```bash
esptool.py --chip esp32 --port /dev/ttyUSB0 write_flash 0x1000 firmware.bin
```

### First Boot

- Device advertises via Bluetooth automatically
- Open Meshtastic Android app, scan for new devices, and pair
- Set device name in Meshtastic app settings

### Apply Configuration

Connect device via USB, then:

```bash
meshtastic --configure firmware/lilygo-lora32/device.yaml
```

### Status LEDs

| LED   | Meaning                      |
| ----- | ---------------------------- |
| Blue  | BLE active / advertising     |
| Green | GPS lock (N/A on this board) |
| Red   | Battery charging             |

### MQTT Bridge Configuration

CrypTAK-BRG01 acts as the Meshtastic MQTT bridge node. After applying base config, push MQTT settings:

```bash
meshtastic --port /dev/ttyACM0 \\
  --set mqtt.enabled true \\
  --set mqtt.address 192.168.50.120 \\
  --set mqtt.port 1883 \\
  --set mqtt.username meshtastic \\
  --set mqtt.password <password> \\
  --set mqtt.encryption_enabled true \\
  --set mqtt.json_enabled false

# Enable uplink + downlink on primary channel
meshtastic --port /dev/ttyACM0 --ch-set uplink_enabled true --ch-index 0
meshtastic --port /dev/ttyACM0 --ch-set downlink_enabled true --ch-index 0
```

Verify MQTT is active — Mosquitto logs should show `CONNACK to !55c6ddbc` and `PUBLISH from !55c6ddbc`
within 30 seconds of the node booting on WiFi.

> **Note:** The mesh-relay service holds `/dev/ttyACM0` while running. Stop it first:
> ```bash
> cd /mnt/user/appdata/tak-server && docker compose stop mesh-relay
> # ... run meshtastic commands ...
> docker compose start mesh-relay
> ```


---

## 2. RAK WisBlock 5005 + 4630 + 4631

nRF52840-based LoRa radio with GPS module. Two units available.

### Assembly

1. Mount **4630 LoRa module** on 5005 baseboard **slot A**
2. Mount **4631 GPS module** on 5005 baseboard **slot B**
3. Connect antenna to iPEX connector on the 4630 module
4. Connect GPS antenna (if external) to 4631 iPEX connector

### DIP Switches (5005 Baseboard)

- SW1 pin 1: **ON** (USB power/data)
- SW1 pin 2: **OFF**

> TODO: Verify exact DIP switch positions against RAK documentation before first power-on.

### Firmware Flash

1. Double-press the reset button on the 5005 baseboard
2. USB mass storage volume **RAK4631** appears on the host machine
3. Drag and drop `firmware/firmware-rak4631-2.7.15.567b8ea.uf2` onto the volume
4. Device reboots automatically after copy completes

### Apply Configuration

```bash
meshtastic --configure firmware/rak5005-4630/device.yaml
```

### GPS Cold Start

First GPS fix can take up to **5 minutes** outdoors with clear sky view. Subsequent fixes are faster once the almanac is cached.

---

## 3. Raspberry Pi 4B -- TAK Server

Runs FreeTAKServer in Docker. Acts as the CoT aggregation and relay point.

### OS Installation

1. Download [Raspberry Pi OS Lite 64-bit](https://www.raspberrypi.com/software/)
2. Flash to microSD using Raspberry Pi Imager
3. In Imager advanced options:
   - Enable SSH
   - Set hostname (e.g., `tak-server`)
   - Set username/password
4. Insert microSD into Pi 4B and boot

### Docker Installation

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
newgrp docker
```

### Deploy FreeTAKServer

```bash
# Copy tak-server/ directory to RPi (scp, rsync, USB, etc.)
cd tak-server
cp .env.example .env
nano .env        # Set FTS_MAIN_IP and other config
docker compose up -d
```

### Network Configuration

Find the Pi's IP address:

```bash
hostname -I
```

**Static IP recommended.** Set via one of:

- `/etc/dhcpcd.conf` on the Pi
- DHCP reservation on your router

---

## 4. Google Pixel 6a -- ATAK Test Device

Primary Android device running ATAK-CIV with the Meshtastic plugin.

### Enable USB Debugging

1. Settings -> About Phone -> tap **Build Number** 7 times
2. Settings -> System -> Developer Options -> enable **USB Debugging**
3. Connect via USB and accept the debugging prompt on the device

### Install ATAK and Plugin

From the ATAK-Plugin repo root with the device connected via USB:

```bash
./scripts/install-dev.sh
```

If `install-dev.sh` fails, download the ATAK-CIV APK manually:

1. Get ATAK-CIV 5.5.1.8 debug APK from [TAK Product Center GitHub Releases](https://github.com/TAK-Product-Center/atak-civ/releases)
2. Place at `apks/atak-civ/atak-civ-5.5.1.8-debug.apk`
3. Re-run `./scripts/install-dev.sh`

### Verify Plugin

1. Open ATAK
2. Navigate to Settings -> Plugins
3. Confirm **Meshtastic** plugin shows green/active status
