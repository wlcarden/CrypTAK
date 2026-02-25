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

| Node               | Type                  | Role             | GPS               | Notes                                               |
| ------------------ | --------------------- | ---------------- | ----------------- | --------------------------------------------------- |
| Lilygo 868/915     | LoRa radio (ESP32)    | CLIENT           | No                | Primary testing node; pairs via BT to Pixel 6a      |
| RAK 5005/4630 (x2) | LoRa radio (nRF52840) | CLIENT or ROUTER | Yes (4631 module) | Full GPS capability; can serve as mesh relay nodes  |
| Raspberry Pi 4B    | ARM server            | TAK Server       | No                | Runs FreeTAKServer in Docker; WiFi AP in field mode |
| Google Pixel 6a    | Android phone         | ATAK device      | Phone GPS         | Primary ATAK-CIV test device with Meshtastic plugin |

### Role Definitions

- **CLIENT:** End-user node. Sends and receives messages but does not actively relay for other nodes (unless no other path exists). Lower power consumption.
- **ROUTER:** Relay node. Actively forwards messages for other nodes in the mesh. Should be positioned for maximum coverage (elevated, central). Higher power draw.
- **TAK Server:** Aggregates CoT (Cursor on Target) events from all ATAK clients and redistributes to the group. Provides SA (Situational Awareness) persistence.

---

## Encryption Layers

Traffic is protected at two independent layers:

| Layer                         | Scope                  | Algorithm   | Key Source                                                  |
| ----------------------------- | ---------------------- | ----------- | ----------------------------------------------------------- |
| Meshtastic channel encryption | LoRa RF link           | AES-256-CTR | Channel PSK (shared across all nodes)                       |
| App-layer encryption          | End-to-end CoT payload | AES-256-GCM | Passphrase-derived key (SHA-256 single hash) in ATAK plugin |

The app-layer encryption ensures that even if the Meshtastic channel key is compromised, CoT payloads remain confidential. The channel encryption prevents casual RF eavesdropping.
