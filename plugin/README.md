# CrypTAK Plugin

ATAK plugin that adds AES-256-GCM content encryption to Meshtastic LoRa mesh
radio. Fork of the
[Meshtastic ATAK Plugin](https://github.com/meshtastic/ATAK-Plugin) with
application-layer encryption, epoch-based key rotation, QR key distribution,
and FreeTAKServer relay integration.

## Relationship to Meshtastic ATAK Plugin

CrypTAK Plugin is a fork that shares the same Android package name
(`com.atakmap.android.meshtastic.plugin`) and ATAK intent actions as the
upstream Meshtastic ATAK Plugin. **They cannot be installed side by side.**
Installing CrypTAK Plugin will replace any existing Meshtastic ATAK Plugin
installation, and vice versa.

All upstream functionality (PLI, chat, file transfer, voice memos, external
GPS, server relay) is preserved. CrypTAK adds:

- **AES-256-GCM encryption** of all outgoing CoT payloads before they reach
  the Meshtastic radio
- **Epoch-based key rotation** for forward secrecy (configurable 1h–24h
  intervals)
- **QR code key distribution** — display on one device, scan on another
- **Data Package export** — share keys via `.zip` file transfer
- **FreeTAKServer relay** — bridge encrypted mesh traffic to/from a TAK server
  over WireGuard VPN

See [ENCRYPTION.md](ENCRYPTION.md) for the wire format, key generation,
rotation mechanism, and security considerations.

## Prerequisites

| Requirement                       | Source                                                                                   |
| --------------------------------- | ---------------------------------------------------------------------------------------- |
| ATAK-CIV                          | [tak.gov](https://www.tak.gov/) or [ATAK.app](https://www.atak.app/)                     |
| ATAK Plugin SDK (`pluginsdk.zip`) | [TAK Product Center](https://github.com/TAK-Product-Center/atak-civ) — extract to `sdk/` |
| Meshtastic Android app            | [Google Play](https://play.google.com/store/apps/details?id=com.geeksville.mesh)         |
| Meshtastic hardware               | RAK WisBlock (RAK4631) or LilyGo T-Beam — 868/915 MHz                                    |

## Building

```bash
cd plugin/

# Place ATAK Plugin SDK files first:
#   sdk/main.jar
#   sdk/android_keystore
#   sdk/proguard-release-keep.txt

./gradlew assembleCivDebug

# Output APK: app/build/outputs/apk/civ/debug/
```

Install to a connected device:

```bash
../scripts/install-plugin.sh
```

## Configuration

Access plugin settings via: **Settings > Tool Preferences > Specific Tool
Preferences > CrypTAK Preferences**

### Encryption

| Setting                     | Default | Description                                          |
| --------------------------- | ------- | ---------------------------------------------------- |
| Enable App-Layer Encryption | off     | Encrypt all outgoing CoT with AES-256-GCM            |
| Pre-Shared Key (PSK)        | —       | 256-bit key; generate with `openssl rand -base64 32` |
| Enable Epoch Rotation       | off     | Rotate key on a schedule for forward secrecy         |
| Epoch Rotation Interval     | 6h      | 1h, 2h, 4h, 6h, 12h, or 24h                          |

Key distribution options (from the encryption settings screen):

- **QR code** — display on one device, scan with another's camera
- **Data Package** — export a `.zip` to share via side-channel
- **Manual entry** — paste a Base64-encoded key

### Mesh Settings

| Setting                 | Default | Description                               |
| ----------------------- | ------- | ----------------------------------------- |
| Channel Index           | 0       | Meshtastic channel for ATAK traffic (0–7) |
| Filter by Channel Index | off     | Only receive from specified channel       |
| Hop Limit               | 3       | Max hops for outgoing messages (1–7)      |
| Request ACK             | off     | Request delivery acknowledgment           |

### PLI & Reporting

| Setting                     | Default | Description                                |
| --------------------------- | ------- | ------------------------------------------ |
| Limit PLI Reporting Rate    | on      | Reduce PLI frequency to conserve bandwidth |
| PLI Reporting Interval      | 5min    | Position update frequency (30s–30min)      |
| Only Send PLI and Chat      | off     | Optimized protobuf format                  |
| Send Read/Delivery Receipts | on      | Chat delivery and read receipts            |

### Display

| Setting                  | Default | Description                       |
| ------------------------ | ------- | --------------------------------- |
| Show Meshtastic Devices  | on      | Display mesh nodes as map markers |
| Hide Devices Without GPS | off     | Don't show nodes at 0,0           |
| Hide Local Node          | off     | Don't show your own device        |

### Server Relay

| Setting           | Default | Description                         |
| ----------------- | ------- | ----------------------------------- |
| Relay to Server   | off     | Forward mesh messages to TAK server |
| Relay from Server | off     | Forward TAK server PLI/chat to mesh |

### File Transfer

| Setting              | Default | Description                                              |
| -------------------- | ------- | -------------------------------------------------------- |
| Enable File Transfer | off     | Send files via Meshtastic (Short_Turbo preset, max 56KB) |

### Audio & Voice

| Setting        | Default | Description                    |
| -------------- | ------- | ------------------------------ |
| Text to Speech | off     | Read incoming messages aloud   |
| PTT KeyCode    | 79      | Hardware button for voice memo |

### External GPS

| Setting            | Default | Description                     |
| ------------------ | ------- | ------------------------------- |
| Use Meshtastic GPS | off     | Use device GPS as ATAK's source |

To use external GPS: **Settings > Callsign and Device Preferences > Device
Preferences > GPS Preferences** — set GPS Option to "Ignore internal GPS / Use
External or Network GPS Only", enable **Use Meshtastic GPS** in plugin
settings, and disable **Show Meshtastic Devices** to avoid duplicate markers.

## Architecture

```
ATAK CoT event
    │
    ▼
CotEventProcessor ─── encryption enabled? ──► AES-256-GCM encrypt
    │                                              │
    ▼                                              ▼
Meshtastic protobuf ◄─────────────────────── encrypted payload
    │
    ▼
IMeshService (Meshtastic Android app)
    │
    ▼
LoRa radio ──► mesh relay ──► destination radio
    │
    ▼
IMeshService ──► CotEventProcessor ──► AES-256-GCM decrypt ──► ATAK
```

### Core Services

| Class                  | Role                                                 |
| ---------------------- | ---------------------------------------------------- |
| `MeshServiceManager`   | Binds to Meshtastic Android app's `IMeshService`     |
| `CotEventProcessor`    | Converts between CoT XML and Meshtastic protobuf     |
| `FountainChunkManager` | Fountain code (LT code) encoding for large transfers |
| `NotificationHelper`   | User notifications for file transfers                |

### Message Types

- Position Location Information (PLI)
- GeoChat (All Chat and Direct Messages)
- Sensor data from Meshtastic nodes
- Generic CoT events (EXI-compressed)

## Troubleshooting

**Plugin not connecting to Meshtastic:**

- Ensure Meshtastic app is installed and running
- Check that Meshtastic device is paired and connected
- Verify plugin has necessary permissions

**Messages not being received:**

- Confirm channel settings match between devices
- Check hop limit is sufficient for your network
- Verify nodes are within radio range

**Encryption not working:**

- Confirm all devices share the same PSK
- If epoch rotation is enabled, ensure device clocks are synchronized
- Check ATAK logcat for `CrypTAK` tag entries

## Upstream

This plugin tracks the upstream
[Meshtastic ATAK Plugin](https://github.com/meshtastic/ATAK-Plugin). Upstream
features and bug fixes can be merged as needed. CrypTAK-specific changes live
in the encryption, key distribution, and server relay modules.

## License

See the [LICENSE](LICENSE) file for details.
