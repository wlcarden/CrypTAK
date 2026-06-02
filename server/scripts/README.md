# server/scripts

Server-side scripts that get rsync'd to `/mnt/user/appdata/tak-server/scripts/`
by `.github/workflows/deploy-server.yml`. Most are invoked by other services
(Node-RED flows, FTS hooks, cron). This README documents the install procedure
for components that need host-level wiring beyond what the deploy workflow does
automatically.

## Files

| File                                           | Purpose                                                 |
| ---------------------------------------------- | ------------------------------------------------------- |
| `renew-cert.sh`                                | Let's Encrypt cert renewal for `vpn.thousand-pikes.com` |
| `cryptak-renew-cert.cron`                      | Cron fragment for the above                             |
| `update-ddns.sh`                               | DDNS update for the home IP                             |
| `add-team-member.sh` / `remove-team-member.sh` | FTS user management                                     |
| `fts-watchdog.sh`                              | Detects FTS zombie listen-loop state                    |
| `gps-mqtt-bridge.py`                           | reTerminal GPS → MQTT bridge                            |
| `power-button.py`                              | reTerminal hardware power-button handler                |
| `check-env.py`                                 | Pre-deploy `.env` validator                             |

## Let's Encrypt cert renewal

### Architecture

- **Domain:** `vpn.thousand-pikes.com` (the public Headscale endpoint)
- **Challenge type:** DNS-01 via Cloudflare API — no inbound port required, so
  renewal works even when the server is unreachable from the internet
- **Cert lifetime:** 90 days (Let's Encrypt standard)
- **Renewal cadence:** daily at 03:00; `certbot renew` is idempotent and only
  acts when the cert is within 30 days of expiry
- **Reload trigger:** `headscale-nginx` is restarted **only when the cert file
  changes** (compared by mtime before/after the certbot run). Outside the
  renewal window the cron is a true no-op — no VPN blip.

### Install procedure (one-time, per Unraid host)

The script itself is auto-deployed by the GitHub Actions workflow. What needs
manual install on a fresh host:

1. **Cloudflare API token** (one-time, not in repo):

   ```bash
   mkdir -p /mnt/user/appdata/letsencrypt
   cat > /mnt/user/appdata/letsencrypt/cloudflare.ini <<'EOF'
   dns_cloudflare_api_token = <token-with-Zone:DNS:Edit-permission>
   EOF
   chmod 600 /mnt/user/appdata/letsencrypt/cloudflare.ini
   ```

2. **Initial cert issuance** (only if no cert exists yet):

   ```bash
   docker run --rm \
     -v /mnt/user/appdata/letsencrypt:/etc/letsencrypt \
     certbot/dns-cloudflare:latest \
     certonly --dns-cloudflare \
       --dns-cloudflare-credentials /etc/letsencrypt/cloudflare.ini \
       -d vpn.thousand-pikes.com \
       --agree-tos -m <your-email> --non-interactive
   ```

3. **Install the cron fragment** (persists across reboots):

   ```bash
   cp /mnt/user/appdata/tak-server/scripts/cryptak-renew-cert.cron \
      /boot/config/plugins/dynamix/cryptak-renew-cert.cron
   /usr/local/sbin/update_cron
   ```

   Unraid concatenates all `/boot/config/plugins/<plugin>/*.cron` files into
   `/etc/cron.d/root` at boot. The script that does this is `update_cron`;
   running it manually applies changes without a reboot.

4. **Verify**:
   ```bash
   grep cryptak /etc/cron.d/root
   bash /mnt/user/appdata/tak-server/scripts/renew-cert.sh   # should report "no renewal needed"
   ```

### Why this layout

- The **script** lives in `server/scripts/` so it's deployed by the existing
  CI/CD pipeline. Updates to renewal logic auto-deploy.
- The **cron fragment** must live on the boot USB (`/boot/config/plugins/dynamix/`)
  because `/etc/cron.d/` is tmpfs and is regenerated at every boot. Anything
  in `/etc/cron.d/` directly is wiped on reboot.
- The Unraid `user.scripts` plugin's "custom schedule" feature does **not**
  survive reboots (its schedule lives only in `schedule.json`, which the
  plugin's `.plg` install code does not sync into the boot-time cron rebuild).
  Use the `*.cron` fragment pattern instead.

## Headscale preauth key rotation

The fleet phones enroll into the tailnet via a long-lived reusable preauth key.
Headscale preauth keys expire (90 days by default). When the last unexpired key
expires, new device enrollments silently fail until a fresh key is issued.

### Check current state

```bash
ssh unraid "docker exec headscale headscale preauthkeys list"
```

Unexpired keys are displayed in green; expired keys in red. You want at least
one **reusable** and **unexpired** key for the `takteam` user (user ID 1) at
all times.

### Issue a new reusable key

```bash
ssh unraid "docker exec headscale headscale preauthkeys create \
    --reusable --user 1 --expiration 90d"
```

The full key value is printed **only once** — record it immediately in the
secrets vault and in the HMDM device-provisioning config. After creation, the
CLI only ever shows a truncated form.

### Suggested cadence

Every 60 days, issue a new key and update the HMDM provisioning config. Don't
delete the old key until you've confirmed the new one works for at least one
enrollment.
