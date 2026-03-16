# CrypTAK Mission Device Provisioning Guide
**Last updated:** 2026-03-15
**Target devices:** Pixel 6 Pro (raven), Pixel 6a × 2 (bluejay)

---

## Pre-Flight Checklist

Before touching any phone:
- [ ] Download ATAK-CIV APK: https://tak.gov → Products → ATAK-CIV → Download
- [ ] Save to: `~/Desktop/CrypTAK/phone-provision/apks/ATAK-CIV.apk`
- [ ] Download F-Droid APK: https://f-droid.org/F-Droid.apk
- [ ] Save to: `~/Desktop/CrypTAK/phone-provision/apks/FDroid.apk`
- [ ] Have USB-C cable + Chrome browser ready
- [ ] GrapheneOS web installer: https://grapheneos.org/install/web

---

## Device Assignments

| Device | Model | Callsign | Headscale Key | Tailscale IP (assigned) |
|--------|-------|----------|---------------|------------------------|
| Phone 1 | Pixel 6 Pro (raven) | TAK-01 | hskey-auth--Za8CoDgmdob-M5Pziy1Yxxi7z-P1d9w_O9kEuzcgbAr_DDMCde8eLTaKWtkYbkvZFzjNQq8hJ-FW | TBD after enrollment |
| Phone 2 | Pixel 6a (bluejay) | TAK-02 | hskey-auth-10qyNNg4aRQD-OzNUAtUAypNfbkhmR54gM-X-YHaHPqAylDkIUWesYvg_rHx04J54oyV0-75tn8nr | TBD after enrollment |
| Phone 3 | Pixel 6a (bluejay) | TAK-03 | hskey-auth-xF3iS1g1sl8p-W-0SIM_OUvVpxnsWh6wybyezBJcF6F_X191qvA74yDY57PdPjiQdxvpYnGUaXaZ3 | TBD after enrollment |

---

## Phase 1: GrapheneOS Flash

**Do this for each device:**

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
./provision-phone.sh TAK-01   # or TAK-02, TAK-03
```

The script will:
- Install F-Droid, Tailscale, ATAK-CIV
- Push ATAK server config
- Push Termux + SSH setup
- Disable USB debugging

---

## Phase 4: Tailscale VPN Enrollment (on device)

1. Open Tailscale app
2. Tap "Log in with auth key"
3. Paste the pre-auth key for this device (see table above)
4. Wait for enrollment confirmation

**Lock down VPN:**
- Settings → Network → VPN → Tailscale → gear icon
- Enable "Always-on VPN" ✓
- Enable "Block connections without VPN" ✓

---

## Phase 5: ATAK Configuration (on device)

Open ATAK → Preferences (hamburger menu):
- Team: Cyan
- CoT Interval: 30 seconds
- Callsign: TAK-01 (or TAK-02/03)

Add server:
- Menu → Network Preferences → Manage Server Connections → +
- Name: CrypTAK-Home | Address: `100.64.0.1` | Port: `8087` | Protocol: TCP
- Name: CrypTAK-Field | Address: `100.64.0.2` | Port: `8087` | Protocol: TCP

---

## Phase 6: Termux SSH (remote access)

Open Termux → run:
```bash
pkg update -y && pkg install -y openssh termux-services termux-boot
ssh-keygen -t ed25519 -f ~/.ssh/id_ed25519 -N ""
# Send the public key to desktop via clipboard or share
cat ~/.ssh/id_ed25519.pub
```

**On desktop:** Add public key from each phone to `~/.ssh/authorized_keys` on relevant systems, and add the desktop's public key to each phone's `~/.ssh/authorized_keys`.

Then in Termux:
```bash
sv-enable sshd
```

Termux will keep sshd running via runit service manager.

**SSH into any phone (when on Tailscale):**
```bash
ssh -p 8022 u0_a[N]@100.64.0.[phone-ip]
# Find the user with: adb shell id
```

---

## Infrastructure Reference

| Service | URL | Notes |
|---------|-----|-------|
| Headwind MDM | http://100.64.0.1:8095 | Tailscale only, login: admin/admin (CHANGE ON FIRST LOGIN) |
| Home FTS | 100.64.0.1:8087 | CoT primary |
| Field FTS | 100.64.0.2:8087 | CoT when field Pi deployed |
| Headscale UI | https://vpn.thousand-pikes.com | Node management |

---

## Post-Provisioning Security

- [ ] USB debugging → OFF on all devices
- [ ] Duress PINs written in a secure location (NOT on devices)
- [ ] Headwind MDM admin password changed from default
- [ ] Test: boot each device, verify it appears on ATAK map
