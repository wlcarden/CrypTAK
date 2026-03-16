# Headwind MDM Setup — CrypTAK
_Last updated: 2026-03-16_

---

## Server Info
- **Container name:** `hmdm`
- **DB container:** `hmdm-db`
- **Unraid host:** `192.168.50.120`
- **Web UI (LAN):** `http://192.168.50.120:8095/`
- **Web UI (Tailnet):** `http://100.64.0.1:8095/` (only reachable if you're on Tailscale)
- **MQTT port:** `31000` (device push notifications)
- **Config file (inside container):** `/usr/local/tomcat/conf/Catalina/localhost/ROOT.xml`
- **DB:** PostgreSQL, user `hmdm`, db `hmdm`, password in ROOT.xml

---

## Admin Access

### Web UI
Go to `http://192.168.50.120:8095/` in browser.  
Login: `admin` / `Fi$hpaste1990!!`

### REST API
HMDM's auth is **not** standard HTTP basic auth. The login flow:
1. Client sends `MD5(password).toUpperCase()` as the password field (this is what the web UI JS does)
2. Server stores `SHA1(MD5(password)).toUpperCase()` in the DB
3. Session cookie (`authToken`) returned on success — include it in all subsequent requests

**Login:**
```bash
MD5=$(python3 -c "import hashlib; print(hashlib.md5('Fi\$hpaste1990!!'.encode()).hexdigest().upper())")

curl -X POST http://192.168.50.120:8095/rest/public/auth/login \
  -H "Content-Type: application/json" \
  -d "{\"login\":\"admin\",\"password\":\"$MD5\"}" \
  -c /tmp/hmdm_cookies.txt
# Saves session cookie to /tmp/hmdm_cookies.txt
```

**Authenticated API call example:**
```bash
curl -X POST http://192.168.50.120:8095/rest/private/devices/search \
  -H "Content-Type: application/json" \
  -b /tmp/hmdm_cookies.txt \
  -d '{"pageSize":20,"pageNum":1}'
```

**Confirmed working endpoints:**
- `POST /rest/public/auth/login` — login, returns session cookie
- `POST /rest/private/devices/search` — list enrolled devices
- `PUT /rest/private/devices` — create/update device (exact payload TBD)

**Endpoints that DON'T exist (404 — don't waste time on these):**
- `/rest/public/account/login` — wrong path
- `/rest/public/v1/account/login` — wrong path
- `/hmdm/rest/...` — no context prefix
- `/api/v1/...` — not the HMDM API style

---

## Password Reset (if locked out)

Direct DB edit — no need to know old password:

```bash
# Calculate SHA1(MD5(newpassword)).upper()
python3 -c "
import hashlib
pw = 'YourNewPassword'
md5 = hashlib.md5(pw.encode()).hexdigest().upper()
sha1 = hashlib.sha1(md5.encode()).hexdigest().upper()
print(sha1)
"
# Then set it in DB:
ssh unraid "docker exec hmdm-db psql -U hmdm hmdm -c \
  \"UPDATE users SET password='<SHA1_FROM_ABOVE>' WHERE login='admin';\""
```

---

## Known Issues & Fixes

### 1. Initialization failure on restart
**Symptom:** Container log shows:
```
[HMDM-INITIALIZER]: Application initialization has failed
[HMDM-INITIALIZER]: The signal file for application initialization completion already exists
```
**Cause:** Stale signal directory at `/usr/local/tomcat/work/` left over from previous run.  
**Fix:**
```bash
docker exec hmdm rm -rf /usr/local/tomcat/work
docker restart hmdm
```

### 2. Liquibase DB migration lock (deadlock on restart)
**Symptom:** Container log spams:
```
liquibase.lockservice.StandardLockService: Waiting for changelog lock....
```
**Cause:** Previous restart crashed mid-migration and left a DB lock.  
**Fix:**
```bash
ssh unraid "docker exec hmdm-db psql -U hmdm hmdm -c \
  'UPDATE databasechangeloglock SET locked=false, lockgranted=null, lockedby=null WHERE id=1;'"
docker restart hmdm
```

### 3. REST API 404 from LAN desktop
**Symptom:** `curl http://192.168.50.120:8095/rest/...` returns 404 for everything.  
**Root cause investigated:** The `base.url` in ROOT.xml was originally set to `http://100.64.0.1:8095` (Tailscale IP). This was changed to LAN IP on 2026-03-16. If this recurs, verify:
```bash
ssh unraid "docker exec hmdm grep base.url /usr/local/tomcat/conf/Catalina/localhost/ROOT.xml"
# Should show: value="http://192.168.50.120:8095"
```
If it shows the Tailscale IP, fix it:
```bash
ssh unraid "docker exec hmdm sed -i \
  's|value=\"http://100.64.0.1:8095\"|value=\"http://192.168.50.120:8095\"|' \
  /usr/local/tomcat/conf/Catalina/localhost/ROOT.xml"
docker restart hmdm
```
Then clear the Liquibase lock if needed (see issue #2 above).

---

## Device Enrollment Process

Enrollment requires the **Headwind MDM Launcher APK** — a replacement launcher that registers the device.

### Pre-enrollment: Create device record in HMDM
1. Log in to web UI → **Devices** → **+ Add Device**
2. Set **Device Number** (use `TAK-01`, `TAK-02`, `TAK-03`)
3. Assign a **Configuration** if one exists
4. Save → you get a **QR code / device ID code** for enrollment

### On the phone (ADB):
```bash
# Install launcher (download from h-mdm.com or local APK)
adb install -r apks/HeadwindMDM.apk

# Open the launcher
adb shell monkey -p com.hmdm.launcher -c android.intent.category.LAUNCHER 1
```

### On the phone (manual):
1. Open Headwind MDM launcher app
2. Enter server URL: `http://192.168.50.120:8095` (on LAN) or `http://100.64.0.1:8095` (on Tailnet)
3. Enter the device ID code from HMDM web UI
4. Launcher registers the device and fetches its configuration

### Add to provision-phone.sh (TODO):
```bash
# After all other app installs:
adb install -r apks/HeadwindMDM.apk
echo ">>> MANUAL STEP: Open HeadwindMDM app on phone, enter server URL + device code"
echo ">>> Server: http://192.168.50.120:8095"
echo ">>> Device code: (create in HMDM web UI first)"
```

---

## What HMDM Can / Can't Do

**Can:**
- Remote APK install/uninstall
- Policy enforcement (screen lock, VPN requirements, app whitelist)
- Config file push to device
- Remote wipe / lock
- App version enforcement + forced updates
- Device location tracking (with GPS permission)

**Cannot:**
- Shell / terminal access → use Termux SSH for that
- Pull files off device → push only
- Force reboot (unless device owner mode active)

---

## DB Access (for admin/debug)
```bash
ssh unraid "docker exec hmdm-db psql -U hmdm hmdm"

# Useful queries:
# List users:    SELECT login, name FROM users;
# List devices:  SELECT number, description, lastupdate FROM devices;
# Device detail: SELECT * FROM devices WHERE number='TAK-01';
```

---

## Status (2026-03-16)
- ✅ Server running on Unraid (container `hmdm`)
- ✅ DB healthy (`hmdm-db`)
- ✅ Admin access confirmed via web UI and REST API
- ✅ `base.url` set to LAN IP (`192.168.50.120:8095`)
- ✅ HMDM-SETUP.md in repo with full access/debug docs
- 📥 Headwind launcher APK downloading (from h-mdm.com)
- ❌ TAK-01 NOT enrolled (launcher not yet installed)
- ❌ TAK-02/03 NOT enrolled (phones not yet provisioned)
