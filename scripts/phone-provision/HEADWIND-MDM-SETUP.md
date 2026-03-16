# Headwind MDM Initial Configuration

**Status:** ✅ Running at `http://100.64.0.1:8095/` (Tailscale only)  
**Database:** PostgreSQL 15 (running)  
**Device communication:** Port 31000 (MQTT over ActiveMQ)

---

## First-Time Admin Login

1. Open browser → `http://100.64.0.1:8095/`
2. Default credentials: `admin` / `admin`
3. **IMMEDIATELY change admin password:**
   - Settings → Users → Admin → Change password
   - Use strong password (20+ chars, mixed case + numbers + symbols)

---

## Device Enrollment Flow (Per Phone)

### 1. Create Device Profile

Settings → Device Configuration → New Config:
- **Name:** `CrypTAK-Mission-Profile`
- **Type:** Standard (or Premium if using optional features)
- **Configuration:**
  - **Main screen widget:** Disable (no homescreen clutter)
  - **WiFi:** Allow (for provisioning), restrict SSID list if needed
  - **VPN:** Enforce Tailscale (see below)
  - **Apps:** Whitelist ATAK-CIV, Tailscale, Termux only
  - **USB debugging:** DISABLE (after provisioning)
  - **Developer options:** DISABLE

### 2. VPN Configuration (Tailscale Integration)

Settings → Device Configuration → Network:
- **VPN enforcement:** Mandatory
- **VPN type:** Android VPN API (standard)
- **Configuration:** Since Tailscale on GrapheneOS uses system VPN, MDM can monitor but not fully control
  - Alternative: Use MDM's built-in VPN if needed, but Tailscale is preferred for military-grade crypto

### 3. Application Management

Settings → Device Configuration → Applications:
- **Disable:** Google Play Services, Play Store, all Google apps
- **Allow:** Only F-Droid, ATAK-CIV, Tailscale, Termux, OpenStreetMap
- **Set app permissions:** Auto-grant location to ATAK-CIV only

### 4. Security Policies

Settings → Device Configuration → Security:
- **Screen lock:** Mandatory PIN (8+ digits)
- **Inactivity timeout:** 15 minutes → lock
- **Failed attempts:** 10 → factory reset
- **Encryption:** Enforce full-disk encryption (GrapheneOS default)
- **Updates:** Auto-update system + apps (via F-Droid)

---

## Device Pre-Auth Keys (Generated)

| Device | Key | Expiry |
|--------|-----|--------|
| TAK-01 | `hskey-auth--Za8CoDgmdob-M5Pziy1Yxxi7z-P1d9w_O9kEuzcgbAr_DDMCde8eLTaKWtkYbkvZFzjNQq8hJ-FW` | 2026-03-18 (72h) |
| TAK-02 | `hskey-auth-10qyNNg4aRQD-OzNUAtUAypNfbkhmR54gM-X-YHaHPqAylDkIUWesYvg_rHx04J54oyV0-75tn8nr` | 2026-03-18 (72h) |
| TAK-03 | `hskey-auth-xF3iS1g1sl8p-W-0SIM_OUvVpxnsWh6wybyezBJcF6F_X191qvA74yDY57PdPjiQdxvpYnGUaXaZ3` | 2026-03-18 (72h) |

---

## Monitoring & Enrollment Status

After each phone provisioning:

1. **Headscale UI:** https://vpn.thousand-pikes.com
   - Verify device appears as `TAK-01/TAK-02/TAK-03`
   - Check IP assignment (`100.64.0.x`)

2. **Headwind MDM Console:**
   - Devices → should show enrolled phone with:
     - Last check-in: recent (within 1 hour)
     - Battery level: current %
     - Configuration applied: yes

3. **ATAK Confirmation:**
   - Open ATAK on any phone
   - Map should show all 3 devices as Cyan markers
   - Verify callsigns: TAK-01, TAK-02, TAK-03

---

## Remote Management Commands (via Headwind MDM)

Once enrolled, you can:
- **Lock device remotely:** Devices → [device] → Actions → Lock
- **Wipe device:** Devices → [device] → Actions → Wipe (irreversible)
- **Push configuration:** Configuration → [profile] → Deploy to devices
- **Push apps:** Apps → [app] → Install to devices
- **Monitor:** Dashboard → Device status, battery, last check-in

---

## SSH Access (for debugging)

Each phone has Termux with SSH on port 8022 (Tailscale only):

```bash
# From desktop (requires Tailscale connection to 100.64.0.0/24):
ssh -p 8022 u0_a[UID]@100.64.0.[phone-ip]
# UID from phone: adb shell id | grep -oP 'uid=\K[0-9]+'
```

---

## Troubleshooting

| Issue | Solution |
|-------|----------|
| Can't access MDM dashboard | Verify Tailscale connection: `ip addr show tun0` |
| Device not enrolling | Check pre-auth key expiry (72h from creation) → generate new key |
| ATAK not connecting to FTS | Verify firewall: `ssh unraid "nc -zv 100.64.0.1 8087"` |
| Termux SSH not working | Ensure `sv-enable sshd` was run in Termux; check `ps aux \| grep sshd` |

