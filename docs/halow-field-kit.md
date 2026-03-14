# HaLow Field Kit — Build Guide

Wi-Fi HaLow (802.11ah) mesh network for field ATAK operations. Provides full IP
networking at range without internet infrastructure, with automatic reach-back to
the home Unraid server when connectivity is available.

---

## Architecture Overview

```
Field Deployment                                    Home (Unraid)
                                                    ┌─────────────────┐
  ┌──────────┐   HaLow mesh (915 MHz)              │ FreeTAKServer   │
  │ ATAK     │   802.11s + batman-adv               │ Node-RED WebMap │
  │ Phone A  │──WiFi──┐                             │ Headscale VPN   │
  └──────────┘        │                             └────────┬────────┘
                      v                                      │
  ┌──────────┐   ┌─────────────┐   ┌────────────┐           │ Headscale
  │ ATAK     │   │ Base Pi 4   │   │ HaLow Node │           │ WireGuard
  │ Phone B  │──>│ FTS + AP +  │<─>│ (OpenWRT)  │           │
  └──────────┘   │ halow-bridge│   └────────────┘           │
                 └──────┬──────┘                             │
  ┌──────────┐          │                                    │
  │ ATAK     │──WiFi────┘                                    │
  │ Phone C  │    2.4 GHz AP          ┌──────────────┐       │
  └──────────┘    (RT5370 dongle)     │ 5G Hotspot / │       │
                                      │ Open WiFi    │───────┘
                                      └──────┬───────┘
                                             │ USB WiFi
                                             │ (MT7601U dongle)
                                      ┌──────┘
                                      │ WAN client
                                      v
                               Base Pi 4 (when internet available)
```

**Data flow:**

- **Field-only (no internet):** ATAK phones connect to the Base Pi's 2.4 GHz AP.
  ATAK multicast SA (239.2.3.1:6969) provides peer discovery. Local FTS on the Pi
  aggregates CoT and provides persistence. HaLow mesh extends range between nodes.

- **With internet:** When a 5G hotspot or open WiFi is plugged in / available, the
  WAN dongle connects and Tailscale establishes a Headscale VPN tunnel automatically.
  `halow-bridge` syncs buffered CoT events to the home Unraid FTS and begins live
  forwarding. Disconnecting internet returns to field-only mode with local buffering.

---

## Why HaLow

| Property           | Wi-Fi HaLow (802.11ah)     | Standard Wi-Fi | LoRa        |
| ------------------ | -------------------------- | -------------- | ----------- |
| Frequency          | 915 MHz (sub-GHz)          | 2.4/5 GHz      | 915 MHz     |
| Range              | 1-2 km                     | ~50 m          | 5-15 km     |
| Throughput         | 1-15 Mbps                  | 50-600 Mbps    | 0.3-50 kbps |
| IP native          | Yes (full TCP/UDP)         | Yes            | No          |
| Mesh capable       | Yes (802.11s)              | Limited        | Yes         |
| Coexists with LoRa | Yes (different modulation) | N/A            | N/A         |

HaLow gives us full IP networking at range. ATAK, FTS, Mumble, and any TCP/UDP
service work natively without protocol translation. LoRa mesh continues to operate
simultaneously on the same band (OFDM vs CSS modulation — no interference).

---

## Hardware — Base Pi 4 Node

The base node runs Pi OS (not OpenWRT) because it needs the full Python/Docker stack
for FTS, halow-bridge, and Mumble.

### Parts List

| #         | Component                           | Purpose                      | Est. Price                                         |
| --------- | ----------------------------------- | ---------------------------- | -------------------------------------------------- |
| 1         | Raspberry Pi 4B (4GB)               | Base compute                 | existing                                           |
| 2         | microSD 32GB+ (A2 rated)            | OS + FTS storage             | ~$10                                               |
| 3         | Morse Micro MM6108 HaLow HAT        | 915 MHz mesh radio           | ~$50-70 **(OUT OF STOCK — Seeed Studio, 2026-03)** |
| 4         | RT5370 USB WiFi dongle (w/ antenna) | 2.4 GHz AP for ATAK phones   | $22                                                |
| 5         | GenBasic MT7601U USB nano dongle    | WAN client (hotspot/WiFi)    | $10                                                |
| 6         | USB GPS dongle (u-blox 7/8)         | Position for CoT + NTP       | ~$15                                               |
| 7         | USB-C battery bank (20000+ mAh, PD) | Power (avoids GPIO conflict) | ~$30-40                                            |
| 8         | USB-A to USB-C cable                | Pi power from battery        | ~$5                                                |
| 9         | 915 MHz antenna (SMA)               | HaLow radio antenna          | ~$10-15                                            |
| 10        | Weatherproof case                   | Field protection             | ~$15-20                                            |
| 11        | Short SMA pigtail / extension       | Antenna routing through case | ~$5                                                |
| **Total** |                                     |                              | **~$170-210** (+ existing Pi)                      |

### USB Port Allocation (Pi 4 has 4 USB ports)

| Port       | Device                  | Interface              |
| ---------- | ----------------------- | ---------------------- |
| USB 2.0 #1 | RT5370 AP dongle        | `wlan_ap` (udev rule)  |
| USB 2.0 #2 | GPS dongle              | `/dev/ttyACM0` → gpsd  |
| USB 3.0 #1 | MT7601U WAN dongle      | `wlan_wan` (udev rule) |
| USB 3.0 #2 | Free (spare / keyboard) | —                      |

### Physical Stack

```
┌─────────────────────────┐
│  915 MHz antenna (SMA)  │  ← routes through case lid
├─────────────────────────┤
│  Morse Micro HaLow HAT  │  ← GPIO header onto Pi
├─────────────────────────┤
│  Raspberry Pi 4B         │  ← USB dongles on sides
├─────────────────────────┤
│  USB-C battery bank      │  ← sits below / beside
└─────────────────────────┘
```

> **GPIO note:** The HaLow HAT uses the SPI bus (GPIO 7-11) + a few control pins.
> Verify the specific HAT pinout before ordering to confirm no conflict with other
> GPIO peripherals. Most Pi HATs with SPI leave I2C (GPIO 2-3) and UART free.

---

## Network Interfaces (Base Pi)

| Interface    | Hardware         | IP Range          | Role                  |
| ------------ | ---------------- | ----------------- | --------------------- |
| `halow0`     | Morse Micro HAT  | 10.0.0.x/24       | HaLow 802.11s mesh    |
| `wlan_ap`    | RT5370 dongle    | 192.168.73.1/24   | 2.4 GHz AP (hostapd)  |
| `wlan_wan`   | MT7601U dongle   | DHCP from hotspot | WAN client (optional) |
| `eth0`       | Onboard          | DHCP / static     | Wired fallback        |
| `tailscale0` | Tailscale daemon | 100.64.0.x        | Headscale VPN tunnel  |

### Dongle Role Assignment (important — do not swap)

- **RT5370 → AP role**: `rt2800usb` driver has proven `hostapd` AP mode support on Pi.
  The external antenna improves range to ATAK phones.
- **MT7601U → WAN client role**: Only needs managed/client mode (wpa_supplicant).
  `mt7601u` AP mode support is kernel-version-dependent and unreliable on Pi.

### udev Rules (persistent interface naming)

Create `/etc/udev/rules.d/70-wifi-dongles.rules`:

```
# RT5370 — AP dongle (always wlan_ap)
SUBSYSTEM=="net", ACTION=="add", ATTR{idVendor}=="148f", ATTR{idProduct}=="5370", NAME="wlan_ap"

# MT7601U — WAN dongle (always wlan_wan)
SUBSYSTEM=="net", ACTION=="add", ATTR{idVendor}=="0e8d", ATTR{idProduct}=="7601", NAME="wlan_wan"
```

> **Note:** Verify actual USB vendor/product IDs with `lsusb` after plugging in.
> The IDs above are typical but may differ by manufacturer. Update the rules to match.

---

## Software Stack

### Operating System

Raspberry Pi OS Lite 64-bit (Bookworm). Flash with Raspberry Pi Imager.

Imager advanced options:

- Enable SSH (password or key)
- Set hostname: `tak-field`
- Set username/password
- Set locale/timezone

### Core Services

| Service          | Install Method                                      | Purpose                     |
| ---------------- | --------------------------------------------------- | --------------------------- |
| mm-wlan driver   | Compile from source                                 | Morse Micro HaLow driver    |
| batman-adv       | `apt install batctl`                                | Mesh routing over HaLow     |
| hostapd          | `apt install hostapd`                               | 2.4 GHz AP on RT5370        |
| dnsmasq          | `apt install dnsmasq`                               | DHCP for AP clients         |
| FreeTAKServer    | Docker                                              | Local CoT aggregation       |
| murmurd (Mumble) | Docker or apt                                       | Push-to-talk voice          |
| Tailscale        | `curl -fsSL https://tailscale.com/install.sh \| sh` | VPN to home Unraid          |
| halow-bridge     | Python service (this repo)                          | CoT bridge + offline buffer |
| gpsd             | `apt install gpsd`                                  | GPS → NTP + position        |

### HaLow Driver (mm-wlan)

The Morse Micro driver must be compiled for Pi OS. The OpenMANET project has
documented this process for Pi 4:

```bash
# General steps (verify against current mm-wlan docs):
sudo apt install build-essential linux-headers-$(uname -r) git
git clone https://github.com/MorseMicro/mm-wlan-driver.git
cd mm-wlan-driver
make
sudo make install
sudo modprobe mm_wlan
```

> **This is the highest-risk step.** Driver compilation depends on exact kernel
> version and may require patches. Test this first when hardware arrives.

### hostapd Configuration

`/etc/hostapd/hostapd.conf`:

```
interface=wlan_ap
driver=nl80211
ssid=CrypTAK-Field
hw_mode=g
channel=6
wmm_enabled=0
auth_algs=1
wpa=2
wpa_passphrase=<CHANGE-THIS>
wpa_key_mgmt=WPA-PSK
rsn_pairwise=CCMP
```

### dnsmasq Configuration

`/etc/dnsmasq.d/ap.conf`:

```
interface=wlan_ap
dhcp-range=192.168.73.10,192.168.73.50,255.255.255.0,12h
```

### Field Server Stack (Docker Compose)

All three services (FTS + Mumble + halow-bridge) are managed by a single compose file:

```bash
cd /opt/cryptak
cp .env.field.example .env.field
nano .env.field    # Change all passwords, set REMOTE_FTS_HOST
docker compose --env-file .env.field up -d
```

See `server/docker-compose.field.yml` for the full compose spec and
`server/.env.field.example` for all configuration options.

> FTS on Pi 4 with 4GB RAM is viable but tight. The field instance runs minimal
> config (no DB persistence, no UI) to reduce memory. Full persistence stays on Unraid.
> Combined memory limits: FTS 768MB + Mumble 256MB + bridge 128MB = ~1.1GB of 4GB.

### Tailscale Setup

```bash
# Point at our Headscale instance
tailscale up --login-server https://vpn.thousand-pikes.com --hostname tak-field
```

After enrollment, the Pi gets a 100.64.0.x address and can reach Unraid FTS.

---

## Auto-Bridge Behavior

The `halow-bridge` service (to be built at `server/halow-bridge/`) manages the
field-to-home CoT synchronization:

```
Boot
  │
  v
Start local FTS on Pi ── ATAK phones connect via AP
  │
  v
halow-bridge starts ── listens on UDP 239.2.3.1:6969 (ATAK multicast)
  │                  ── also subscribes to local FTS CoT stream
  │
  v
Check: Tailscale tunnel up? ──No──> Buffer mode
  │                                  │
  Yes                                │ Store CoT events in ring buffer
  │                                  │ (last N hours, bounded by disk)
  v                                  │
Live forward to Unraid FTS ◄────────┘ Flush buffer on reconnect
  │
  v
Tailscale tunnel drops? ──> Return to buffer mode
```

**Key design decisions:**

- Ring buffer, not unbounded queue — field ops may run hours/days without internet
- Forward to Unraid FTS via TCP :8087 (same protocol as ATAK clients)
- Deduplicate on CoT event UID + time to avoid replay on sync
- Service pattern matches `mesh-relay` (same reconnect, backoff, logging conventions)

---

## HaLow Field Nodes (OpenWRT)

Lightweight mesh extenders that repeat HaLow signal. No server software.

### Hardware

- Raspberry Pi Zero 2W (or Pi 3A+) + Morse Micro HaLow HAT
- Or: dedicated OpenMANET board (if available)

### Software

OpenMANET firmware (OpenWRT-based). Pre-built images available from the OpenMANET
project. Configured for 802.11s mesh + batman-adv. These are deploy-and-forget relay
nodes positioned for coverage.

### Estimated Cost

~$50-80 per field node (Pi Zero 2W + HaLow HAT + antenna + battery + case).

---

## LoRa Coexistence

HaLow and LoRa both operate at 915 MHz but use different modulation:

|            | HaLow     | LoRa                        |
| ---------- | --------- | --------------------------- |
| Modulation | OFDM      | CSS (Chirp Spread Spectrum) |
| Bandwidth  | 1-8 MHz   | 125-500 kHz                 |
| Throughput | 1-15 Mbps | 0.3-50 kbps                 |

They coexist without interference. The existing Meshtastic mesh continues to operate
as a low-bandwidth backup path alongside HaLow.

**Antenna separation:** Use separate antennas for HaLow and LoRa. Co-locating two
915 MHz antennas on the same device requires ~30 cm physical separation or a
bandpass filter to avoid desense. On the base Pi, the LoRa radio (T-Beam) is a
separate device connected via USB — its antenna is physically separate.

---

## Voice Comms

Mumble server on the base Pi provides encrypted push-to-talk voice over the HaLow
IP mesh. ATAK phones run Mumble client alongside ATAK.

```bash
# Mumble server (murmurd) — Docker
docker run -d \
  --name mumble \
  --restart always \
  -p 64738:64738/tcp \
  -p 64738:64738/udp \
  mumblevoip/mumble-server:latest
```

Mumble uses Opus codec at ~40 kbps per stream. HaLow's 1-15 Mbps throughput handles
this easily even with multiple simultaneous speakers.

---

## Deployment Checklist

### Pre-Deploy (at home)

- [ ] Flash Pi OS to microSD, configure SSH + hostname
- [ ] Compile and install mm-wlan HaLow driver
- [ ] Install hostapd, dnsmasq, Docker, Tailscale, gpsd
- [ ] Configure udev rules for USB dongle naming
- [ ] Deploy FTS Docker container
- [ ] Deploy Mumble Docker container
- [ ] Deploy halow-bridge service
- [ ] Enroll Pi in Headscale (`tailscale up --login-server ...`)
- [ ] Test: ATAK phone connects to AP, gets DHCP, reaches FTS
- [ ] Test: Tailscale tunnel connects to Unraid, halow-bridge syncs
- [ ] Test: Mumble voice works phone-to-phone over AP

### Field Setup

1. Power on base Pi (USB-C battery bank)
2. Wait ~60s for boot + FTS + hostapd + HaLow
3. Connect ATAK phones to `CrypTAK-Field` WiFi
4. Add TAK server in ATAK: `192.168.73.1:8087` TCP
5. (Optional) Connect Mumble client to `192.168.73.1`
6. (Optional) Plug in 5G hotspot for Unraid reach-back

---

## Development Tasks

| Task                           | Location                          | Status          | Blocked By                 |
| ------------------------------ | --------------------------------- | --------------- | -------------------------- |
| `halow-bridge` service         | `server/halow-bridge/`            | Done (30 tests) | —                          |
| Pi OS setup script             | `scripts/setup-field-pi.sh`       | Done            | —                          |
| Field compose + env            | `server/docker-compose.field.yml` | Done            | —                          |
| mm-wlan driver compilation     | On Pi hardware                    | Not started     | HaLow HAT (out of stock)   |
| Field node OpenWRT images      | `firmware/halow-node/`            | Not started     | HaLow hardware + OpenMANET |
| Meshtastic-to-multicast bridge | Optional                          | Not started     | Design decision            |
| WebMap HaLow topology overlay  | `server/nodered/`                 | Not started     | HaLow mesh running         |

---

## Open Questions

- [ ] **Morse Micro HAT GPIO pinout:** Confirm no conflict with other Pi peripherals.
      Check SPI pin usage vs I2C/UART availability.
- [ ] **mm-wlan driver kernel compatibility:** Pi OS Bookworm ships kernel 6.x.
      Verify driver compiles cleanly. May need specific kernel headers or patches.
- [ ] **HaLow HAT availability:** Morse Micro evaluation kits vs third-party HATs.
      Check current stock and lead times.
- [ ] **Antenna selection:** 915 MHz antenna for HaLow — confirm SMA connector type
      matches HAT. Same band as LoRa but different gain/pattern requirements (HaLow
      wants wider bandwidth coverage than narrow-band LoRa).
- [ ] **Power budget:** Pi 4 + HaLow HAT + 2 USB dongles + GPS. Estimate total draw
      and battery runtime at 20000 mAh.
- [ ] **Field FTS memory:** Monitor Pi 4 (4GB) RAM usage under load with FTS + Mumble + halow-bridge + hostapd all running. May need swap or memory limits.
