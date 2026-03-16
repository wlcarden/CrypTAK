# Headwind MDM Setup — CrypTAK

## Server Info
- **Container name:** `hmdm`
- **LAN URL:** `http://192.168.50.120:8095`
- **Tailscale URL:** `http://100.64.0.1:8095` (only reachable from Tailnet)
- **base.url in config:** `http://100.64.0.1:8095` (ROOT.xml)
- **MQTT port:** 31000
- **DB:** PostgreSQL container `hmdm-db`, user `hmdm`, db `hmdm`
- **Config file:** `/usr/local/tomcat/conf/Catalina/localhost/ROOT.xml` (inside container)
- **Admin credentials:** `admin` / `Fi$hpaste1990!!` (changed from default by Leighton 2026-03-15)

## Known Issues

### Initialization failure on restart
**Symptom:** `Application initialization has failed — The signal file for application initialization completion already exists`  
**Cause:** Stale signal dir at `/usr/local/tomcat/work/` persists across restarts  
**Fix:**
```bash
docker exec hmdm rm -rf /usr/local/tomcat/work
docker restart hmdm
```

### API 404 from LAN IP
**Symptom:** `curl http://192.168.50.120:8095/rest/public/account/login` returns 404  
**Cause:** HMDM `base.url` is set to the Tailscale IP (`100.64.0.1`), so the REST framework only handles requests arriving at that address. Desktop is not on Tailnet so LAN requests route to Tomcat directly but the servlet filter may not match.  
**Workaround:** Access HMDM web UI from a device on the Tailnet (tak-01 browser, or a VPN-connected desktop), or change `base.url` to LAN IP.  
**TODO:** Consider changing `base.url` to `http://192.168.50.120:8095` so LAN admin access works.

## Enrollment Process (NOT YET DONE on TAK-01/02/03)

Phone enrollment requires the **Headwind MDM Launcher APK** — NOT the regular HMDM Android app.

### Steps:
1. **Download launcher APK** from HMDM server:  
   `http://192.168.50.120:8095/api/app/download/hmdm.apk`  
   Or from official: `https://h-mdm.com/files/hmdm-launcher.apk`

2. **Push and install:**
   ```bash
   adb install -r hmdm-launcher.apk
   ```

3. **Open app on phone** → enter server URL:  
   `http://100.64.0.1:8095` (from Tailnet) or LAN IP if base.url changed

4. **Enter device ID/QR code** — generate a new device in HMDM web UI first:
   - Web UI → Devices → Add Device → set device number (e.g. `TAK-01`)
   - Get the QR code / device code shown in UI
   - Enter code in the launcher app on phone

5. **Launcher becomes device owner** (replaces home screen) — configure in HMDM:
   - Assign a configuration (app whitelist, policies, etc.)
   - Can kiosk-lock to ATAK-only if desired, or leave as normal launcher

### Add to provision-phone.sh
- Download hmdm-launcher.apk to `apks/` dir
- Add install step after other app installs
- Manual step: user must open app and enter server URL + device code

## What HMDM Can Do (vs Can't)
**Can:**
- Remote APK push/install/uninstall
- Policy enforcement (screen lock timer, VPN requirement, app whitelist)
- File push to device
- Remote wipe
- App version enforcement + auto-update
- Device location tracking (if GPS permission granted)
- Remote lock

**Cannot:**
- Shell/terminal access (use Termux SSH for that)
- Force reboot (unless device owner mode enabled)
- Access files on device (only push, not pull)

## Login Method
HMDM uses **salted hash authentication**: Client sends `MD5(password).upper()`, server stores `SHA1(MD5(password))`.

**Login via REST API:**
```bash
# Get MD5 of password
MD5=$(python3 -c "import hashlib; print(hashlib.md5('Fi\$hpaste1990!!'.encode()).hexdigest().upper())")

# POST to login endpoint
curl -X POST http://192.168.50.120:8095/rest/public/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"login\":\"admin\",\"password\":\"$MD5\"}" \
  -c /tmp/hmdm_cookies.txt

# API calls include Cookie from above
curl -X GET http://192.168.50.120:8095/rest/private/devices/search \
  -H "Content-Type: application/json" \
  -d '{"pageSize":10,"pageNum":1}' \
  -b /tmp/hmdm_cookies.txt
```

## Status as of 2026-03-16
- ✅ Server running on Unraid (container `hmdm`)
- ✅ DB healthy (`hmdm-db`)
- ✅ Admin logged in, API working
- ✅ `base.url` fixed to LAN IP (`192.168.50.120:8095`)
- ❌ TAK-01 NOT enrolled (launcher never installed)
- ❌ TAK-02/03 NOT enrolled (phones not yet provisioned)
- 📥 Headwind launcher APK downloading
