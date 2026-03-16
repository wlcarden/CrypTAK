# CrypTAK Phone Flashing — Master Checklist

**Date:** 2026-03-15  
**Time window:** 4:00 PM – 8:00 PM EDT  
**Devices:** 3 × Google Pixel (1×6Pro, 2×6a)  
**Location:** Fairfax, VA

---

## PRE-FLASHING (do NOW, before 3 PM)

- [ ] **Download ATAK-CIV APK**
  - Go to https://tak.gov → Products → ATAK-CIV → Download
  - Save to: `~/Desktop/CrypTAK/phone-provision/apks/ATAK-CIV.apk`
  - Verify file exists and size > 100 MB

- [ ] **Prepare desktop environment**
  - Chrome browser (latest version) — needed for GrapheneOS web installer
  - USB-C cables × 3 (one per device)
  - Desk/workspace with good lighting
  - Phone stand (optional but useful)

- [ ] **Review provisioning script**
  ```bash
  cat ~/Desktop/CrypTAK/phone-provision/provision-phone.sh
  ```
  Script will auto-install apps, push server configs, and set up SSH.

- [ ] **Verify APK directory is complete**
  ```bash
  ls -lh ~/Desktop/CrypTAK/phone-provision/apks/
  ```
  Should have: `FDroid.apk` (12 MB), `ATAK-CIV.apk` (>100 MB)

- [ ] **Verify Headscale pre-auth keys are documented**
  - TAK-01: `hskey-auth--Za8CoDgmdob-...`
  - TAK-02: `hskey-auth-10qyNNg4aRQD-...`
  - TAK-03: `hskey-auth-xF3iS1g1sl8p-W-...`

---

## DEVICE 1: Pixel 6 Pro (TAK-01)

### Phase 1: GrapheneOS Flash (~15 min)
- [ ] **Enable Developer Options**
  - Settings → About Phone → tap "Build Number" 7 times
  - Verify: Settings → System → Developer Options appears

- [ ] **Enable OEM unlocking**
  - Settings → Developer Options → OEM Unlocking → ON
  - (Might require SIM card briefly; proceed if needed)

- [ ] **Reboot to bootloader**
  - Plug in USB-C
  - Command: `adb reboot bootloader`
  - Device should show "Fastboot" mode

- [ ] **Flash GrapheneOS**
  - Open https://grapheneos.org/install/web in Chrome
  - Select "Pixel 6 Pro (raven)"
  - Click "Flash release"
  - Wait ~10 minutes for flash to complete

- [ ] **Lock bootloader (CRITICAL)**
  - In bootloader menu: Navigate to "Lock the bootloader"
  - Confirm (device will wipe)
  - Wait for reboot

- [ ] **Complete setup wizard**
  - Skip all Google account prompts
  - Set PIN (8+ digits) or passphrase
  - **DO NOT** add any accounts yet

### Phase 2: Initial Security (~5 min)
- [ ] **Set Duress PIN**
  - Settings → Security → Duress PIN
  - Use a DIFFERENT PIN from your main PIN
  - Keep in secure location (write down, don't store on device)

- [ ] **Enable USB Debugging (temporary)**
  - Settings → Developer Options → USB Debugging → ON

### Phase 3: ADB Provisioning (~10 min)
- [ ] **Run provisioning script**
  ```bash
  ~/Desktop/CrypTAK/phone-provision/provision-phone.sh TAK-01
  ```
  Script will install: F-Droid, ATAK-CIV, push configs

- [ ] **Push ATAK server config package**
  ```bash
  adb push ~/Desktop/CrypTAK/phone-provision/tak-packages/cryptak-server-config.zip \
           /sdcard/atak/tools/
  ```
  *(Sets FTS connection + Cyan team — imported in Phase 6)*

- [ ] **Disable USB Debugging**
  - Settings → Developer Options → USB Debugging → OFF
  - Verify off: Settings → Developer Options disappears

### Phase 4: Tailscale Enrollment (~2 min)
- [ ] **Open Tailscale app**
- [ ] **"Log in with auth key"**
- [ ] **Paste pre-auth key:**
  ```
  hskey-auth--Za8CoDgmdob-M5Pziy1Yxxi7z-P1d9w_O9kEuzcgbAr_DDMCde8eLTaKWtkYbkvZFzjNQq8hJ-FW
  ```
- [ ] **Verify enrollment** (check Headscale UI later)

### Phase 5: VPN Lock (~1 min)
- [ ] **Force VPN connection**
  - Settings → Network & internet → VPN → Tailscale → gear icon
  - Enable "Always-on VPN" ✓
  - Enable "Block connections without VPN" ✓

### Phase 6: ATAK First Boot (~3 min)
- [ ] **Open ATAK app**
- [ ] **Accept permissions** (location, storage)
- [ ] **Import server config package**
  - Hamburger menu (☰) → Import Manager → Local SD
  - Navigate to: `/sdcard/atak/tools/cryptak-server-config.zip`
  - Tap to import — applies FTS connection + Cyan team automatically
- [ ] **Set callsign:** TAK-01 (hamburger → Settings → Callsign)
- [ ] **Verify team:** Cyan (auto-set by package)
- [ ] **Verify server connected:** CrypTAK Primary FTS (100.64.0.1:8087)

### Phase 7: SSH Setup (~5 min)
- [ ] **Open Termux app**
- [ ] **Run setup script**
  ```bash
  bash /sdcard/termux_setup.sh
  ```
- [ ] **SSH public key will print** — save for desktop config

---

## DEVICE 2: Pixel 6a (TAK-02)

### Repeat all steps above, but:
- Use `TAK-02` in script: `./provision-phone.sh TAK-02`
- Push server config: `adb push .../tak-packages/cryptak-server-config.zip /sdcard/atak/tools/`
- Set callsign to **TAK-02** in ATAK
- Use **TAK-02 pre-auth key:**
  ```
  hskey-auth-10qyNNg4aRQD-OzNUAtUAypNfbkhmR54gM-X-YHaHPqAylDkIUWesYvg_rHx04J54oyV0-75tn8nr
  ```

---

## DEVICE 3: Pixel 6a (TAK-03)

### Repeat all steps above, but:
- Use `TAK-03` in script: `./provision-phone.sh TAK-03`
- Push server config: `adb push .../tak-packages/cryptak-server-config.zip /sdcard/atak/tools/`
- Set callsign to **TAK-03** in ATAK
- Use **TAK-03 pre-auth key:**
  ```
  hskey-auth-xF3iS1g1sl8p-W-0SIM_OUvVpxnsWh6wybyezBJcF6F_X191qvA74yDY57PdPjiQdxvpYnGUaXaZ3
  ```

---

## POST-FLASHING VERIFICATION

After all 3 phones are provisioned:

### Check Headscale Enrollment
```bash
ssh unraid "docker exec headscale headscale nodes list"
```
Should show 3 new nodes:
- `TAK-01` (Pixel 6 Pro) — IP 100.64.0.x
- `TAK-02` (Pixel 6a) — IP 100.64.0.x
- `TAK-03` (Pixel 6a) — IP 100.64.0.x

### Check ATAK Map
- Open ATAK on **any** phone
- Map should show **3 cyan markers** (all devices)
- Verify callsigns: TAK-01, TAK-02, TAK-03

### Check SSH Access (from desktop)
```bash
# Get Tailscale IP from Headscale list above, then:
ssh -p 8022 u0_a[UID]@100.64.0.[phone-ip]
# Example: ssh -p 8022 u0_a123@100.64.0.15
```
Should connect without password (key-based auth).

---

## CRITICAL SUCCESS FACTORS

✅ **GrapheneOS bootloader LOCKED** (default security posture)  
✅ **Duress PINs documented** offline (not on any device)  
✅ **USB Debugging OFF** (hardens attack surface)  
✅ **Tailscale enrolled** with pre-auth keys (expires 72h)  
✅ **VPN always-on + kill-switch** enabled (no leaks)  
✅ **ATAK seeing all 3 devices** on map (networking verified)  
✅ **SSH key-based auth only** (passwords disabled)

---

## TIMELINE ESTIMATE

| Phase | Devices | Time |
|-------|---------|------|
| GrapheneOS flash + setup | 1 device | 30 min |
| x3 devices (parallel possible with multiple USB cables) | 3 devices | 1.5 hours |
| Tailscale + ATAK + SSH | 3 devices | 15 min |
| Verification + testing | all | 15 min |
| **Total** | | ~2 hours |

---

## TROUBLESHOOTING

| Issue | Fix |
|-------|-----|
| `adb: command not found` | Install: `sudo apt install android-tools-adb` |
| GrapheneOS installer not showing device | Unlock bootloader first; reboot phone in fastboot |
| Headscale key expired | Generate new key: `docker exec headscale headscale preauthkeys create --expiration 72h --user 1` |
| ATAK not showing all 3 devices | Wait 2 min, restart ATAK app, verify Tailscale connected |
| SSH timeout | Check: `ping 100.64.0.x` from desktop; verify Tailscale connected both sides |

