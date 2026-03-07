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
