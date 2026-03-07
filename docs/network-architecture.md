# Network Architecture

Overview of the Meshtastic + ATAK mesh network topology and node roles.

---

## Home / Unraid Setup

Primary development and testing topology. ATAK devices connect to FreeTAKServer over WiFi for full CoT exchange. The LoRa mesh provides an off-grid backup path.

```
                              LoRa Mesh (915 MHz)
                     ┌────────────────────────────────────┐
                     │                                    │
    ┌────────────┐   │   ┌──────────────┐    ┌──────────────┐
    │ Pixel 6a   │──BT──>│ Lilygo       │<──>│ RAK Node A   │<──BT── Phone A
    │ (ATAK-CIV) │   │   │ LORA32       │    │ (5005/4630)  │
    └─────┬──────┘   │   └──────────────┘    └──────────────┘
          │          │                              ^
          │          │                              │ LoRa
          │          │                              v
          │          │                       ┌──────────────┐
          │          │                       │ RAK Node B   │<──BT── Phone B
          │          │                       │ (5005/4630)  │
          │          │                       └──────────────┘
          │          └────────────────────────────────────┘
          │
          │ WiFi / LAN
          v
    ┌─────────────────┐
    │ Unraid Server   │
    │ FreeTAKServer   │<── WiFi/LAN ── Phone A
    │ (Docker)        │<── WiFi/LAN ── Phone B
    └─────────────────┘
```

**Data flow:**

- **Primary path (WiFi):** ATAK devices send CoT events over TCP to FreeTAKServer, which relays to all connected clients.
- **LoRa path (backup):** When WiFi is unavailable, CoT events are serialized, optionally encrypted with AES-256-GCM at the app layer, and sent over the Meshtastic LoRa mesh via Bluetooth IPC.
- **Both paths active:** In normal operation, both paths run simultaneously. The LoRa path provides resilience if the server or LAN goes down.

---

## Field / Portable Setup

No internet or fixed infrastructure required. The Raspberry Pi acts as both WiFi access point and TAK server.

```
                              LoRa Mesh (915 MHz)
                     ┌────────────────────────────────────┐
                     │                                    │
    ┌────────────┐   │   ┌──────────────┐    ┌──────────────┐
    │ Pixel 6a   │──BT──>│ Lilygo       │<──>│ RAK Node A   │<──BT── Phone A
    │ (ATAK-CIV) │   │   │ LORA32       │    │ (5005/4630)  │
    └─────┬──────┘   │   └──────────────┘    └──────────────┘
          │          │                              ^
          │          │                              │ LoRa
          │          │                              v
          │          │                       ┌──────────────┐
          │          │                       │ RAK Node B   │<──BT── Phone B
          │          │                       │ (5005/4630)  │
          │          │                       └──────────────┘
          │          └────────────────────────────────────┘
          │
          │ WiFi (RPi hotspot)
          v
    ┌─────────────────┐
    │ Raspberry Pi 4B │
    │ WiFi AP + FTS   │<── WiFi ── Phone A
    │ (Docker)        │<── WiFi ── Phone B
    └─────────────────┘
```

> **Note:** The RPi broadcasts a WiFi access point. All ATAK devices connect to the RPi hotspot. No internet connection required. Configure the RPi as a WiFi AP using `hostapd` and `dnsmasq` or `nmcli`.

---

## Node Role Reference

| Node | ID | Type | Role | GPS | Notes |
|---|---|---|---|---|---|
| CrypTAK-BRG01 | !55c6ddbc | LilyGo T-Beam | TAK bridge | No | USB serial to Unraid; WiFi to LAN; MQTT bridge to Mosquitto |
| CrypTAK Base | !a51e2838 | RAK4631 | Fixed base station | Yes | Home rooftop, 155m, solar powered |
| CrypTAK-RLY01 | !3db00f2c | RAK4631 | ROUTER relay | Yes | Field relay; currently at home for case repair after drop |
| CrypTAK-RLY02 | !c6eadff0 | RAK4631 | ROUTER relay | No | Wall/vehicle powered relay |
| CrypTAK-VHC01 | !9aa4baf0 | RAK4631 | Vehicle/field | No | Mobile field node |
| Tracker Alpha | !01f94ec0 | RAK4631 | TRACKER | Yes | GPS position tracker |
| Raspberry Pi 4B | N/A | ARM server | TAK Server | No | Runs FreeTAKServer in Docker; WiFi AP in field mode |
| Google Pixel 6a | N/A | Android phone | ATAK device | Phone GPS | Primary ATAK-CIV test device with Meshtastic plugin |

### Role Definitions

- **CLIENT:** End-user node. Sends and receives messages but does not actively relay for other nodes (unless no other path exists). Lower power consumption.
- **ROUTER:** Relay node. Actively forwards messages for other nodes in the mesh. Should be positioned for maximum coverage (elevated, central). Higher power draw.
- **TAK Server:** Aggregates CoT (Cursor on Target) events from all ATAK clients and redistributes to the group. Provides SA (Situational Awareness) persistence.

---

## MQTT Bridge

The T-Beam bridge node (`CrypTAK-BRG01`, `!55c6ddbc`) connects to the Unraid Mosquitto broker
via WiFi, enabling MQTT-based mesh bridging between LoRa islands that lack direct RF connectivity.

### Configuration

| Setting | Value |
|---|---|
| Broker | `192.168.50.120:1883` (Unraid LAN) |
| Auth | Username/password required; no anonymous access |
| Channel 0 uplink | Enabled — mesh → MQTT |
| Channel 0 downlink | Enabled — MQTT → mesh |
| Encryption | Enabled (Meshtastic channel PSK) |
| Broker binding | `0.0.0.0:1883` (LAN-accessible) |

### MQTT Topics

Meshtastic publishes/subscribes on the standard topic scheme:

```
msh/US/2/e/LongFast/!<node-id>   # encrypted packets (uplink)
msh/US/2/c/LongFast/!<node-id>   # cleartext JSON (disabled)
```

### Remote Deployment Note

If the T-Beam is deployed away from home WiFi, configure the broker address to the
Unraid Tailscale IP (`100.101.255.78`) so it remains reachable from any network.

### Managing Mosquitto Users

```bash
docker exec -i mosquitto mosquitto_passwd -b /mosquitto/config/passwd <username> <password>
cd /mnt/user/appdata/tak-server && docker compose restart mosquitto
```

Current accounts: `nodered` (Node-RED mesh map), `openclaw` (Kit/AI assistant), `meshtastic` (T-Beam bridge)

---

## Encryption Layers

Traffic is protected at two independent layers:

| Layer                         | Scope                  | Algorithm   | Key Source                                                  |
| ----------------------------- | ---------------------- | ----------- | ----------------------------------------------------------- |
| Meshtastic channel encryption | LoRa RF link           | AES-256-CTR | Channel PSK (shared across all nodes)                       |
| App-layer encryption          | End-to-end CoT payload | AES-256-GCM | Passphrase-derived key (SHA-256 single hash) in ATAK plugin |

The app-layer encryption ensures that even if the Meshtastic channel key is compromised, CoT payloads remain confidential. The channel encryption prevents casual RF eavesdropping.
