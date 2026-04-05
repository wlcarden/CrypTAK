# CrypTAK Mission Device Provisioning Guide

**Last updated:** 2026-04-05
**Target devices:** Pixel 6 Pro (raven), Pixel 6 × 2

---

## Pre-Flight Checklist

Before touching any phone:

- [ ] APKs present in `apks/` (ATAK-CIV, F-Droid, Tailscale, Termux, Meshtastic, CrypTAK-Plugin, HeadwindMDM)
- [ ] Generate Headscale pre-auth key per device:
  ```bash
  ssh unraid "docker exec headscale headscale preauthkeys create --user 1 --expiration 72h"
  ```
- [ ] Have USB-C cable + Chrome browser ready
- [ ] GrapheneOS web installer: https://grapheneos.org/install/web

---

## Device Assignments

| Device  | Model               | Callsign | Headscale IP | Status                                     |
| ------- | ------------------- | -------- | ------------ | ------------------------------------------ |
| Phone 1 | Pixel 6 Pro (raven) | TAK-01   | 100.64.0.4   | Enrolled, online                           |
| Phone 2 | Pixel 6 (oriole)    | TAK-02   | 100.64.0.5   | Provisioned 2026-04-05                     |
| Phone 3 | Pixel 6 (oriole)    | TAK-03   | —            | USB-C fastboot HW fault, needs replacement |

Pre-auth keys are generated per-session (72h expiry) and passed to the provisioning script.
Do NOT hardcode keys — generate fresh ones on provisioning day.

---

## Phase 1: GrapheneOS Flash

**Do this for each device (skip if already running GrapheneOS):**

1. Enable Developer Options: Settings → About Phone → tap Build Number 7×
2. Enable OEM unlocking: Settings → Developer Options → OEM unlocking → ON
3. Reboot to bootloader: `adb reboot bootloader`
4. Open https://grapheneos.org/install/web in Chrome
5. Click "Flash release" → select your device → follow prompts (~10 min)
6. **DO NOT SKIP: Lock bootloader after flash:**
   - In bootloader menu: navigate to "Lock the bootloader" → confirm
7. First boot → complete setup wizard
8. **Skip all Google sign-in prompts**

---

## Phase 2: Initial Security Setup (on device)

Settings → Security → Screen Lock:

- [ ] Set PIN (use 8+ digits) or strong passphrase
- [ ] Enable Duress PIN: Settings → Security → Duress PIN (set a DIFFERENT PIN that wipes on entry)
- [ ] Enable Auto-wipe: Settings → Security → Auto-reboot → 72 hours

Settings → System → Developer Options (OFF — re-enable only for ADB setup, then OFF again)

---

## Phase 3: ADB Setup & App Install

**Enable ADB temporarily:**

1. Settings → About Phone → tap Build Number 7×
2. Settings → Developer Options → USB Debugging → ON
3. Connect USB-C to desktop

**Run provisioning script:**

```bash
cd ~/Desktop/CrypTAK/phone-provision
./provision-phone.sh TAK-02 hskey-auth-XXXXX...
```

The script will:

- Install F-Droid, ATAK-CIV, Tailscale, Termux, Meshtastic, CrypTAK Plugin, HeadwindMDM
- Configure cellular data policy (Data Saver + app whitelisting)
- Push ATAK server config (callsign, team, FTS connections)
- Push Termux SSH setup script
- Push offline map tiles (NoVA streets)
- Generate HMDM enrollment QR code
- Print manual steps with the pre-auth key

---

## Phase 4: Tailscale VPN Enrollment (on device)

1. Open Tailscale app
2. Tap "Log in with auth key"
3. Paste the pre-auth key (printed by provisioning script)
4. Wait for enrollment confirmation

**Lock down VPN:**

- Settings → Network → VPN → Tailscale → gear icon
- Enable "Always-on VPN"
- Enable "Block connections without VPN"

---

## Phase 5: ATAK Configuration (on device)

Open ATAK → Preferences (hamburger menu):

- Team and callsign are pre-loaded by provisioning script
- Server connections pre-configured: CrypTAK-Home (100.64.0.1:8087) + CrypTAK-Field (100.64.0.2:8087)
- Verify callsign matches device assignment

---

## Phase 6: Termux SSH (remote access)

Open Termux → run:

```bash
bash /sdcard/termux_setup.sh
```

The script generates SSH keys, configures sshd on port 8022, and prints the phone's public key.

**SSH into any phone (when on Tailscale):**

```bash
ssh -p 8022 u0_a[N]@100.64.0.[phone-ip]
# Find the user with: adb shell id
```

---

## Phase 7: Encryption Key

Load the CrypTAK AES-256-GCM PSK onto the new phone:

- **QR scan** from an existing device (preferred), or
- **Data Package import** from `tak-packages/`, or
- **Manual entry** in ATAK → Tool Preferences → Meshtastic Preferences → PSK

---

## Infrastructure Reference

| Service      | URL                            | Notes                      |
| ------------ | ------------------------------ | -------------------------- |
| Headwind MDM | http://100.64.0.1:8095         | Tailscale only             |
| Home FTS     | 100.64.0.1:8087                | CoT primary                |
| Field FTS    | 100.64.0.2:8087                | CoT when field Pi deployed |
| Headscale UI | https://vpn.thousand-pikes.com | Node management            |
| WebMap       | http://100.64.0.1:1880/tak-map | Tactical map               |

---

## Post-Provisioning Verification

- [ ] USB debugging → OFF on all devices
- [ ] Duress PINs written in a secure location (NOT on devices)
- [ ] VPN always-on + kill-switch enabled
- [ ] Check Headscale enrollment: `ssh unraid "docker exec headscale headscale nodes list"`
- [ ] Open ATAK on each phone — verify cyan markers appear for all devices
- [ ] Test SSH: `ssh -p 8022 u0_aXXX@100.64.0.x`
- [ ] Encryption key loaded and verified
