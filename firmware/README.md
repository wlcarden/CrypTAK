# Meshtastic Firmware and Device Configuration

## Prerequisites

Install the Meshtastic Python CLI for flashing and configuration:

```bash
pip install meshtastic
```

Alternatively, use the [Meshtastic Android app](https://play.google.com/store/apps/details?id=com.geeksville.mesh) for configuration over Bluetooth.

## Hardware in This Project

| Device                 | Firmware File                                  | Config File                 |
| ---------------------- | ---------------------------------------------- | --------------------------- |
| Lilygo 868/915 LORA32  | ESP32 — use Web Flasher (no local file needed) | `lilygo-lora32/device.yaml` |
| RAK WisBlock 5005/4630 | `firmware-rak4631-2.7.15.567b8ea.uf2`          | `rak5005-4630/device.yaml`  |

## Flashing Firmware

### Lilygo LORA32 (ESP32)

Use the Meshtastic Web Flasher:

1. Connect the Lilygo via USB.
2. Open https://flasher.meshtastic.org in Chrome (requires Web Serial API).
3. Select the board (TLORA_V2_1_16 or T-LORA32_V2 depending on revision). The Web Flasher downloads and applies the correct ESP32 firmware automatically — no local firmware file is needed.
4. Follow the on-screen prompts to flash.

Alternative: use `esptool` from the command line.

### RAK WisBlock (nRF52840)

The RAK bootloader exposes a USB mass storage drive:

1. Connect the RAK WisBlock via USB.
2. Double-press the reset button. A USB drive named `RAK4631` should appear.
3. Drag and drop `firmware-rak4631-2.7.15.567b8ea.uf2` onto the drive.
4. The device reboots automatically when the copy completes.

## Generating Channel Keys

All Meshtastic nodes in the mesh must share the same channel PSK (pre-shared key). Generate one with:

```bash
meshtastic --generate-key
```

This outputs a 32-byte base64-encoded key. Copy this value into the `psk` field in both `lilygo-lora32/device.yaml` and `rak5005-4630/device.yaml`, replacing the TODO placeholder. Every node must have the identical PSK to communicate.

## Applying Configuration

Connect the device via USB and run:

```bash
meshtastic --configure firmware/lilygo-lora32/device.yaml
# or
meshtastic --configure firmware/rak5005-4630/device.yaml
```

The CLI writes each config section to the device and reboots it.

## Verifying

After applying configuration, confirm the settings:

```bash
meshtastic --info
```

Check that:

- Channel name shows `ATAK`
- Device role shows `CLIENT` (or `ROUTER` if changed)
- LoRa region shows `US` with modem preset `LONG_FAST`
- GPS status matches the device (enabled for RAK, disabled for Lilygo)

## Channel PSK vs ATAK Plugin PSK

These are two separate encryption keys serving different layers:

- **Meshtastic Channel PSK** — Encrypts the LoRa link layer. Set in `device.yaml` and applied via the Meshtastic CLI. All Meshtastic nodes must share this key to form a mesh.
- **ATAK Plugin PSK** — Provides application-layer AES-256-GCM encryption on top of the LoRa link. Configured in the ATAK plugin settings on each Android device. Encrypts CoT (Cursor on Target) messages before they reach Meshtastic.

Both keys must be generated and distributed to all team members. Compromise of the channel PSK exposes LoRa traffic; compromise of the plugin PSK exposes CoT message content. Using both provides defense in depth.
