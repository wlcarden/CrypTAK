# Meshtastic Firmware and Device Configuration

## Prerequisites

Install the Meshtastic Python CLI for flashing and configuration:

```bash
pip install meshtastic
```

Alternatively, use the [Meshtastic Android app](https://play.google.com/store/apps/details?id=com.geeksville.mesh) for configuration over Bluetooth.

## Hardware in This Project

| Device                   | Role         | Firmware                              | Config                      |
| ------------------------ | ------------ | ------------------------------------- | --------------------------- |
| RAK19007 + RAK4631       | Base station | `firmware-rak4631-2.7.15.567b8ea.uf2` | `rak19007-base/device.yaml` |
| RAK WisBlock 5005 + 4630 | Field node   | `firmware-rak4631-2.7.15.567b8ea.uf2` | `rak5005-4630/device.yaml`  |
| LilyGo T-Beam            | TAK bridge   | ESP32 — use Web Flasher               | `tbeam-bridge/device.yaml`  |
| Lilygo 868/915 LORA32    | Field node   | ESP32 — use Web Flasher               | `lilygo-lora32/device.yaml` |

## Device Roles

- **Base station** (`ROUTER`) — Fixed solar-powered relay on the roof. Rebroadcasts all mesh traffic for the community. No GPS (uses fixed position).
- **TAK bridge** (`CLIENT`) — T-Beam connected to TAK server. Receives mesh packets and forwards to the relay service for injection into FTS. Serial enabled for USB bridge mode.
- **Field node** (`CLIENT`) — Mobile nodes carried in the field. GPS-enabled, battery-powered.

## Channel Strategy

All devices operate on the **default public LongFast channel** (PSK `AQ==`). This means:

- Our ROUTER base station relays traffic for the broader Meshtastic community
- We benefit from other users' relay infrastructure
- Any Meshtastic user in range can see our nodes' position broadcasts

TAK-specific payloads (chat, markers, routes) are protected by the **ATAK plugin's AES-256-GCM app-layer encryption**, not by the mesh channel. This provides end-to-end encryption for sensitive data while keeping the mesh open for community use.

## Flashing Firmware

### RAK WisBlock (nRF52840) — RAK19007, RAK5005

The RAK bootloader exposes a USB mass storage drive:

1. Connect the RAK WisBlock via USB.
2. Double-press the reset button. A USB drive named `RAK4631` should appear.
3. Drag and drop `firmware-rak4631-2.7.15.567b8ea.uf2` onto the drive.
4. The device reboots automatically when the copy completes.

### LilyGo T-Beam / LORA32 (ESP32)

Use the Meshtastic Web Flasher:

1. Connect the device via USB.
2. Open https://flasher.meshtastic.org in Chrome (requires Web Serial API).
3. Select the correct board variant:
   - T-Beam: `TBEAM` (or `TBEAM_V1.1` depending on revision)
   - LORA32: `TLORA_V2_1_16` or `T-LORA32_V2`
4. Follow the on-screen prompts to flash.

Alternative: use `esptool` from the command line.

## Applying Configuration

Connect the device via USB and run:

```bash
# RAK19007 base station
meshtastic --configure firmware/rak19007-base/device.yaml

# RAK5005 field node
meshtastic --configure firmware/rak5005-4630/device.yaml

# T-Beam bridge (over WiFi if already configured)
meshtastic --host 192.168.50.198 --configure firmware/tbeam-bridge/device.yaml

# Lilygo LORA32
meshtastic --configure firmware/lilygo-lora32/device.yaml
```

**Important:** `--configure` applies radio/device/position settings but does NOT reliably set channel config. Verify the channel after applying:

```bash
meshtastic --info | grep -A2 "Channels:"
# Should show: psk=default (AQ==), no channel name
```

## Verifying

After applying configuration, confirm the settings:

```bash
meshtastic --info
# or over WiFi:
meshtastic --host 192.168.50.198 --info
```

Check that:

- Channel PSK shows `default` (`AQ==`) — public LongFast
- Device role matches config (`ROUTER` for base station, `CLIENT` for others)
- LoRa region shows `US` with modem preset `LONG_FAST`
- GPS status matches the device (enabled for T-Beam and RAK5005, disabled for base station and LORA32)

## Encryption Layers

| Layer                     | Key                | Protects                                   | Scope             |
| ------------------------- | ------------------ | ------------------------------------------ | ----------------- |
| LoRa channel (Meshtastic) | Default PSK `AQ==` | Link-layer — public, shared with community | All mesh traffic  |
| App-layer (ATAK plugin)   | AES-256-GCM key    | TAK payloads — chat, markers, routes       | Team devices only |

The ATAK plugin key is configured in the plugin settings on each Android device and distributed via QR code or Data Package. Mesh position broadcasts (PLI) are intentionally unencrypted at the app layer so the relay service can read and forward them to FTS.
