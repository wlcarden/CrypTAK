# CrypTAK

CrypTAK adds AES-256-GCM content encryption to
[Meshtastic](https://meshtastic.org/) LoRa mesh radio, bundled with a self-hosted
[FreeTAKServer](https://github.com/FreeTAKTeam/FreeTAKServer) backend and an
[ATAK-CIV](https://www.tak.gov/) plugin that ties it all together.

CrypTAK traffic is carried as standard Meshtastic packets, so it can be relayed
by any Meshtastic node on the same channel — including existing community
infrastructure. The AES-256-GCM layer ensures that message content remains
confidential even as it hops through nodes you don't control. You supply endpoint
hardware (source and destination radios); the relay hops in between can be any
compatible node in range.

Remote access to the server uses a self-hosted WireGuard VPN
([headscale](https://headscale.net/)) with OIDC authentication
([Authelia](https://www.authelia.com/)), so the server is never exposed directly
to the internet.

> **Privacy scope:** Encryption hides message content. It does not hide the fact
> that transmissions are occurring, when, or from where. RF metadata (timing,
> signal strength, triangulation) is observable to anyone monitoring the spectrum.

---

## Architecture

```
┌─────────────────┐     LoRa mesh      ┌───────────────────────────────────┐
│  Android device │   (shared radio    │            Your server            │
│                 │    spectrum)       │                                   │
│  ATAK-CIV       │                   │  FreeTAKServer (CoT relay)         │
│  + CrypTAK      │◄──────────────────►│  nginx (reverse proxy)            │
│    plugin       │                   │  headscale (WireGuard VPN)         │
│                 │   WireGuard VPN   │  Authelia (OIDC auth)              │
│  Tailscale      │◄─────────────────►│                                   │
│  (VPN client)   │   (remote access) └───────────────────────────────────┘
└─────────────────┘
        ▲
        │ USB / TCP
        ▼
┌─────────────────┐
│  Meshtastic     │
│  LoRa node      │◄──── LoRa ────► [mesh nodes] ────► [other LoRa nodes]
│  (RAK / T-beam) │
└─────────────────┘
```

**Encryption boundary:** The CrypTAK plugin encrypts all ATAK payloads (chat,
PLI, CoT events) with AES-256-GCM before they leave the phone. The server relays
ciphertext — it never sees plaintext. Decryption happens only on devices that
hold the shared key.

---

## Repository Layout

```
CrypTAK/
├── plugin/     ATAK plugin — AES-256-GCM encryption, Meshtastic ↔ TAK bridge
├── server/     Self-hosted stack — FreeTAKServer, headscale VPN, Authelia, nginx
├── firmware/   Meshtastic node configuration and firmware files
├── docs/       Deployment runbook, hardware builds guide, network architecture
└── scripts/    Build, install, SDK setup, and signing verification scripts
```

---

## Prerequisites

| Requirement                       | Source                                                                                                 |
| --------------------------------- | ------------------------------------------------------------------------------------------------------ |
| ATAK-CIV APK                      | [tak.gov](https://www.tak.gov/) or [ATAK.app](https://www.atak.app/)                                   |
| ATAK Plugin SDK (`pluginsdk.zip`) | [TAK Product Center GitHub](https://github.com/TAK-Product-Center/atak-civ) — extract to `plugin/sdk/` |
| Android SDK                       | Standard Android development install                                                                   |
| Docker + Docker Compose           | For the server stack                                                                                   |
| Meshtastic hardware               | RAK WisBlock (RAK4631) or LilyGo T-Beam — 868/915 MHz                                                  |

The ATAK SDK is subject to TAK Product Center terms. It is not included in this
repository and must be obtained separately.

---

## Quick Start

### 1. Server

```bash
cd server/
cp .env.example .env
# Edit .env: set your domain, server IP, and generate secrets
docker compose up -d
```

See `docs/deployment-runbook.md` for the full 17-step deployment checklist,
including headscale enrollment and Authelia user setup.

### 2. Meshtastic nodes

Flash firmware and apply config:

```bash
# Adjust port and target device as needed
python3 -m meshtastic --port /dev/ttyACM0 \
  --configure firmware/rak5005-4630/device.yaml
```

See `firmware/README.md` for per-device flash and configuration procedures.

### 3. Plugin

```bash
cd plugin/

# Place ATAK Plugin SDK files in plugin/sdk/ first:
#   sdk/main.jar
#   sdk/android_keystore
#   sdk/proguard-release-keep.txt

./gradlew assembleCivDebug

# Install to connected Android device
../scripts/install-plugin.sh
```

See `docs/hardware-builds.md` for device setup and ATAK-CIV installation.

---

## Key Distribution

The shared AES-256 key is provisioned to each device via one of:

- **QR code** — display on one device, scan on another (in-app camera scanner)
- **Data Package** — export a `.zip` to share via file transfer or side-channel
- **Manual entry** — paste a Base64-encoded key directly

All methods are accessible from the plugin's encryption settings screen.

---

## Components

| Component                                                     | Role                                               | License                      |
| ------------------------------------------------------------- | -------------------------------------------------- | ---------------------------- |
| [Meshtastic](https://meshtastic.org/)                         | LoRa mesh radio firmware and app protocol          | Apache 2.0                   |
| [ATAK-CIV](https://www.tak.gov/)                              | Tactical awareness platform                        | See TAK Product Center terms |
| [FreeTAKServer](https://github.com/FreeTAKTeam/FreeTAKServer) | CoT relay server (community Python implementation) | Eclipse Public License 2.0   |
| [headscale](https://headscale.net/)                           | Self-hosted WireGuard coordination server          | BSD 3-Clause                 |
| [Authelia](https://www.authelia.com/)                         | OIDC authentication and authorization              | Apache 2.0                   |

---

## License

Apache 2.0 — see [LICENSE](LICENSE).

---

## Export Notice

This project implements AES-256 encryption. Encryption software may be subject
to U.S. Export Administration Regulations (EAR). Open-source encryption is
generally authorized under EAR §740.13(e). Review applicable regulations before
distributing outside the United States.
