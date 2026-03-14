# Security Model and Hardening Guide

## Threat Model

CrypTAK operates in environments where:

- The LoRa mesh is shared with untrusted nodes (public LongFast channel)
- The server is on a LAN potentially shared with other devices
- Remote access traverses the internet via VPN
- Field units operate on open WiFi with limited physical security

The primary defense layers are:

1. **App-layer encryption (AES-256-GCM):** TAK payloads are encrypted before
   reaching the mesh or server. The server relays ciphertext.
2. **VPN (WireGuard via headscale):** Remote access requires device enrollment
   and authenticated tunnel.
3. **Network segmentation:** Sensitive admin interfaces bound to localhost only.
4. **MQTT authentication:** Broker requires username/password (no anonymous).

---

## Audit Findings

### F1. Node-RED Editor Exposes Remote Code Execution

**EVIDENCE:** `server/docker-compose.yml:147` — port 1880 is bound to `0.0.0.0`
(all interfaces). When `NR_ADMIN_PASS` is blank (the default in `.env.example`),
anyone on the LAN or VPN can access the Node-RED editor and deploy arbitrary
JavaScript via function nodes.

**IMPACT:** Full container compromise. The Node-RED container has read access to
`cot-maps.js`, flow data, and can make outbound connections to FTS and MQTT.

**STATUS:** Mitigated. `NR_ADMIN_PASS` environment variable enables admin auth.
The `.env.example` documents this, and the CI/CD workflow authenticates with it.

**RECOMMENDATION:** Set `NR_ADMIN_PASS` before first deployment. Consider
binding to `127.0.0.1:1880` and accessing the editor via SSH tunnel, the same
way FTS-UI is accessed. The WebMap at `/tak-map` would then require VPN access.

### F2. Docker Socket Mount on GitHub Runner

**EVIDENCE:** `server/docker-compose.yml:344` — the `github-runner` container
mounts `/var/run/docker.sock`. This gives the runner (and any code it executes)
root-equivalent access to the Docker host.

**IMPACT:** A compromised GitHub repository or malicious pull request could
execute arbitrary commands on the Unraid server via Docker API.

**STATUS:** Accepted risk. The runner is required for CI/CD and runs in a
private repository. The `DISABLE_AUTO_UPDATE: "true"` setting prevents the
runner binary from being replaced remotely.

**RECOMMENDATION:** Use a fine-grained PAT with minimal scopes. Review PRs
before merging. Consider running the runner in a separate VM or using GitHub's
built-in hosted runners for non-deployment steps.

### F3. FTS Healthcheck Disabled

**EVIDENCE:** `server/docker-compose.yml:99` — `healthcheck: disable: true` on
`freetakserver` and several other services.

**IMPACT:** Docker cannot restart unhealthy FTS containers automatically. A
silently failed FTS instance will stop relaying CoT but `docker compose ps`
will still show "running."

**STATUS:** Unavoidable on Unraid. The runc state directory issue
(`/run/user/0/` missing in daemon context) prevents exec-based healthchecks.

**RECOMMENDATION:** Monitor FTS externally. The CI/CD workflow already verifies
services post-deploy. Consider adding a cron-based TCP probe
(`python3 -c "import socket; s=socket.socket(); s.connect(('localhost',8087))"`)
as a host-level health check.

### F4. CoT TCP Without TLS (Port 8087)

**EVIDENCE:** `server/docker-compose.yml:22` — port 8087 is plain TCP.
ATAK clients on the LAN connect without TLS.

**IMPACT:** CoT traffic between ATAK and FTS is unencrypted on the network.
On a shared WiFi network, an attacker can sniff all CoT events (positions,
chat, markers).

**STATUS:** Mitigated by two layers: (1) app-layer encryption means CoT
payloads are AES-256-GCM ciphertext, and (2) remote clients use VPN which
encrypts the full tunnel. Only LAN clients without the CrypTAK plugin
encryption enabled would be exposed.

**RECOMMENDATION:** Use port 8089 (SSL CoT) for all client connections where
possible. Ensure all team members have app-layer encryption enabled.

### F5. Default Credentials in .env.example

**EVIDENCE:** `server/.env.example:11-14` — contains sentinel values
`changeme_federation_password`, `changeme_cert_password`, etc.

**IMPACT:** If deployed without changing these values, all FTS secrets are
known. Additionally, FTS-UI defaults to `admin/password` on first boot.

**STATUS:** Documented. The `.env.example` comments explicitly state "Change ALL
of these before first boot." The deployment runbook (Step 4) repeats this.

**RECOMMENDATION:** The sentinel values contain `changeme` which is detectable.
Consider adding a pre-start validation script that refuses to launch if any
`changeme` values remain in `.env`.

### F6. MQTT Broker on LAN (Port 1883)

**EVIDENCE:** `server/docker-compose.yml:172` — `0.0.0.0:1883:1883` exposes
MQTT to the entire LAN.

**IMPACT:** Any device on the LAN can attempt MQTT connections. While anonymous
access is disabled (Mosquitto auth required), brute-force attacks on
credentials are possible.

**STATUS:** Authentication required. Three accounts configured: `nodered`,
`openclaw`, `meshtastic`.

**RECOMMENDATION:** Consider binding to `127.0.0.1:1883` if only Docker
services need access (they can reach it via the `taknet` network name
`mosquitto:1883`). The T-Beam MQTT bridge would then need to be on the Docker
network or use VPN. If LAN access is required (for direct T-Beam WiFi), ensure
strong passwords and consider fail2ban or connection rate limiting.

### F7. Field Unit AP Password Hardening

**EVIDENCE:** `docs/halow-field-kit.md:211` and `scripts/setup-field-pi.sh`
configure a WPA2 AP with a configurable passphrase.

**IMPACT:** If the field AP passphrase is weak or shared widely, unauthorized
devices can connect to the field FTS and inject CoT events.

**STATUS:** Template uses `<CHANGE-THIS>` placeholder.

**RECOMMENDATION:** Use a strong random passphrase (16+ characters). Document
passphrase rotation procedure for team turnover. Consider MAC filtering as an
additional layer (though not a strong control).

### F8. Admin Key in Firmware Provisioning

**EVIDENCE:** `firmware/README.md:100` — the admin key
`FVmX/5EbFDNF8D1IB5rT6UaDil6dacMR9vpjOqoy0Eo=` is in the repository.

**IMPACT:** Anyone with access to the repo can remotely administer any
provisioned node (reboot, reconfigure, etc.) via the T-Beam bridge.

**STATUS:** Acceptable for a private repository. The admin key only works over
Meshtastic PKC — the attacker would need a Meshtastic node on the same channel
within radio range.

**RECOMMENDATION:** If the repository becomes public, rotate the admin key.
Consider generating per-deployment keys during provisioning rather than using a
shared static key.

---

## Port Exposure Summary

### Home Server (Unraid)

| Port  | Service                | Binding   | Auth                     | Risk                    |
| ----- | ---------------------- | --------- | ------------------------ | ----------------------- |
| 8087  | FTS CoT TCP            | 0.0.0.0   | None (CoT protocol)      | Low — payload encrypted |
| 8089  | FTS CoT SSL            | 0.0.0.0   | TLS cert                 | Low                     |
| 8080  | FTS Data Package HTTP  | 0.0.0.0   | None                     | Medium — data packages  |
| 8443  | FTS Data Package HTTPS | 0.0.0.0   | TLS cert                 | Low                     |
| 1880  | Node-RED               | 0.0.0.0   | Optional (NR_ADMIN_PASS) | **High if no auth**     |
| 1883  | Mosquitto MQTT         | 0.0.0.0   | Username/password        | Medium                  |
| 8082  | nginx (Headscale)      | 0.0.0.0   | OIDC (Authelia)          | Low                     |
| 9443  | nginx (Direct TLS)     | 0.0.0.0   | TLS + OIDC               | Low                     |
| 5000  | FTS-UI                 | 127.0.0.1 | Password                 | Low                     |
| 19023 | FTS REST API           | 127.0.0.1 | API key                  | Low                     |
| 8083  | headscale-ui           | 127.0.0.1 | None (localhost)         | Low                     |
| 9090  | Prometheus             | 127.0.0.1 | None (localhost)         | Low                     |

### Field Unit

| Port  | Service          | Binding | Auth     | Risk               |
| ----- | ---------------- | ------- | -------- | ------------------ |
| 8087  | FTS CoT TCP      | 0.0.0.0 | None     | Medium — AP-scoped |
| 8089  | FTS CoT SSL      | 0.0.0.0 | TLS cert | Low                |
| 8080  | FTS Data Package | 0.0.0.0 | None     | Medium — AP-scoped |
| 64738 | Mumble           | 0.0.0.0 | Password | Low                |

Field unit ports are scoped to the WiFi AP network (192.168.73.0/24). Access
requires the AP passphrase.

---

## Hardening Checklist

- [ ] Change ALL `changeme_*` values in `.env` before first boot
- [ ] Change FTS-UI default password (`admin/password`) on first login
- [ ] Set `NR_ADMIN_PASS` in `.env` to enable Node-RED admin auth
- [ ] Set strong Mosquitto passwords for all accounts
- [ ] Set strong field AP passphrase
- [ ] Verify admin interfaces are on localhost only (5000, 19023, 8083, 9090)
- [ ] Enable app-layer encryption on all ATAK devices
- [ ] Enroll all remote devices via Headscale VPN
- [ ] Rotate FTS API key after first boot
- [ ] If repo goes public: rotate firmware admin key, MQTT passwords

---

## Security Hardening History

Previous security work (see CHANGELOG.md v0.6.0):

- **XSS prevention:** Input sanitization in `cot-maps.js` for CoT callsigns
  and remarks fields rendered in WebMap popups
- **XML injection:** Guards against malformed CoT XML in relay and Node-RED
- **Port binding:** REST API (19023), FTS-UI (5000), headscale-ui (8083),
  Prometheus (9090) bound to 127.0.0.1
- **Node-RED auth:** `NR_ADMIN_PASS` environment variable
- **Image pinning:** Specific version tags on all Docker images
  (nodered:4.1, authelia:4.39, headscale:0.28, mosquitto:2)
- **Deploy safety:** CI/CD rsync excludes secrets, health check validation
- **MQTT auth:** Anonymous access disabled, three named accounts
