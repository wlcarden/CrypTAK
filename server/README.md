# FreeTAKServer + Headscale Docker Stack

FTS stack (CoT relay, admin UI, worldmap) behind a self-hosted Headscale VPN.
Team phones use the official Tailscale app pointed at `tak.thousand-pikes.com`
(Cloudflare Tunnel → Headscale) — no Tailscale accounts, no per-user fees.

| Endpoint          | Address                        | Access    | Purpose                            |
| ----------------- | ------------------------------ | --------- | ---------------------------------- |
| ATAK connection   | `<tailscale-ip>:8087` TCP      | Tailscale | CoT relay for ATAK clients         |
| Admin panel       | `<tailscale-ip>:5000`          | Tailscale | FTS user mgmt, DataPackages        |
| REST API          | `<tailscale-ip>:19023`         | Tailscale | FTS API                            |
| Worldmap          | `<tailscale-ip>:1880`          | Tailscale | Node-RED browser map               |
| Headscale UI      | http://192.168.50.120:8083     | LAN only  | VPN node/key management            |
| Headscale control | https://tak.thousand-pikes.com | Internet  | Tailscale coordination (CF Tunnel) |

`<tailscale-ip>` = Unraid host's Tailscale IP shown in Headscale UI after enrollment.

---

## First Boot

### Step 1 — Prepare config

```bash
cd tak-server/
cp .env.example .env
nano .env    # change all passwords; set FTS_API_KEY=Bearer token (default)
```

### Step 2 — Start core FTS only

```bash
docker compose up -d freetakserver
docker compose logs -f freetakserver
```

Wait for logs to show `CoTService started` (30–60 seconds).

### Step 3 — Set the API key

The default API key is literally `Bearer token`. Set it in `.env`:

```
FTS_API_KEY=Bearer token
```

After first login to FTS-UI, create a new API user with a real token via the admin panel and update `.env`.

### Step 4 — Start FTS-UI

```bash
docker compose up -d freetakserver-ui
docker compose logs -f freetakserver-ui
```

Wait for `wsgi starting up on http://...:5000`.

### Step 5 — Verify

```bash
docker compose ps    # both containers should show "healthy" or "running"
```

Open http://192.168.50.120:5000 — log in with default credentials (`admin` / `password`) and
**change the password immediately**.

---

## ATAK Client Connection

1. Open ATAK → gear icon → **Network** → **TAK Servers** → **+**
2. Fill in:
   - **Server address:** `192.168.50.120`
   - **Port:** `8087`
   - **Protocol:** TCP
3. Tap OK — the connection indicator should turn green

---

## Unraid Notes

- Data persists in `/mnt/user/appdata/fts/data` and `/mnt/user/appdata/fts-ui/data`
- Both directories are on the array and survive reboots
- Manage via `docker compose` over SSH — Unraid's Docker GUI shows the containers but
  doesn't understand compose relationships; don't reconfigure individual containers there
- Port 19023 (REST API) is not firewalled by default on Unraid home LAN — do not
  forward it externally
- Docker daemon DNS: `/etc/docker/daemon.json` sets `8.8.8.8`/`1.1.1.1` (required for
  ghcr.io pulls; applied after `kill -HUP $(pidof dockerd)` or full Docker restart)
- `ghcr.io` is also in `/etc/hosts` as a fallback (added during initial setup)

---

## Raspberry Pi 4B Setup (Field)

```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
newgrp docker
# Copy tak-server/ to RPi, then:
cd tak-server/
cp .env.example .env
nano .env     # set UNRAID_IP to RPi's LAN IP (from hostname -I)
```

Follow the same First Boot steps above. The compose file works identically on the RPi;
volume paths (`/mnt/user/appdata/`) should be changed to local paths (e.g. `./data`).

---

## Updating

```bash
docker compose pull
docker compose up -d
```

Back up data first: `docker compose stop && cp -r /mnt/user/appdata/fts /mnt/user/appdata/fts.backup`

---

## Troubleshooting

| Symptom                       | Cause                             | Fix                                                                  |
| ----------------------------- | --------------------------------- | -------------------------------------------------------------------- |
| ATAK can't connect            | Wrong IP in `.env` or FTSConfig   | Confirm `UNRAID_IP` and `/opt/fts/FTSConfig.yaml` both set to LAN IP |
| FTS-UI shows no data          | `FTS_API_KEY` wrong or blank      | Set `FTS_API_KEY=Bearer token` in `.env`; restart freetakserver-ui   |
| Docker pull fails (DNS error) | dockerd using `[::1]` DNS         | Add to `/etc/hosts`: `140.82.113.34  ghcr.io`; restart compose       |
| Container exits immediately   | Startup error                     | `docker compose logs freetakserver` — look for Python tracebacks     |
| Port conflict                 | Something else on 8087/5000/19023 | Check `ss -tlnp` on Unraid; remap in compose if needed               |
| freetakserver-ui won't start  | Waiting for core healthcheck      | healthcheck uses python3 socket; wait 60–90s for core to be healthy  |

---

## Headscale VPN Setup

Run these steps after FTS is healthy. Headscale starts automatically with `docker compose up -d`.

### Step 1 — Add Headscale to Cloudflare Tunnel

In your cloudflared tunnel config (`config.yml`), add an ingress rule **before** the catch-all:

```yaml
- hostname: tak.thousand-pikes.com
  service: http://192.168.50.120:8082
  originRequest:
    http2Origin: true # required for gRPC — Headscale peer update streams
    noTLSVerify: true # internal traffic is plain HTTP; Cloudflare handles TLS
```

Also add the DNS record in the Cloudflare dashboard:
`tak.thousand-pikes.com` → CNAME → `<your-tunnel-id>.cfargotunnel.com` (proxied)

Restart cloudflared after editing its config.

### Step 2 — Create a Headscale user and pre-auth key

```bash
# Create a user namespace for the team
docker exec headscale headscale users create takteam

# Create a reusable pre-auth key (phones and Unraid host all use this key)
docker exec headscale headscale preauthkeys create \
  --user takteam --reusable --expiration 90d
```

Copy the key — you'll need it for the next two steps.

### Step 3 — Enroll the Unraid host

Install Tailscale on Unraid via **Community Apps** (search "Tailscale") or via SSH:

```bash
curl -fsSL https://tailscale.com/install.sh | sh
```

Then register the host with Headscale (run on Unraid via SSH):

```bash
tailscale up \
  --login-server https://tak.thousand-pikes.com \
  --advertise-tags=tag:tak-server \
  --authkey <preauth-key-from-step-2>
```

The Unraid host now has a stable Tailscale IP (100.x.x.x). Note it — this is what
goes in ATAK as the TAK server address.

Make Tailscale persistent across Unraid reboots by adding to `/boot/config/go`:

```bash
echo 'tailscale up --login-server https://tak.thousand-pikes.com' >> /boot/config/go
```

### Step 4 — Set up Headscale-UI

Open http://192.168.50.120:8083 in a browser.

Generate an API key for the UI:

```bash
docker exec headscale headscale apikeys create
```

In the UI: enter `https://tak.thousand-pikes.com` as the Headscale URL and paste the API key.

### Step 5 — Enroll team phones

Two methods. OIDC (Authelia) is preferred — no keys to distribute.

#### Method A: OIDC (Authelia browser login) — recommended

> **LAN requirement:** Tailscale's ts2021 enrollment upgrade stream is not compatible
> with Cloudflare's HTTP proxy. Enrollment **must** use the LAN URL, not the public URL.

1. Install the **Tailscale** app
2. Open Settings (gear icon, top-right) → tap ⋮ (three dots) → **"Use an alternate server"**
3. Enter: `http://192.168.50.120:8082`
4. Tap **"Add account"** → tap **"Log in"**
5. The browser opens to the Authelia login page — enter Authelia credentials
6. After login, the phone is enrolled and appears in `headscale nodes list`

To add a user to Authelia:

```bash
# Generate Argon2id hash for their password
docker run --rm ghcr.io/authelia/authelia:latest \
  authelia crypto hash generate argon2 --password 'their_password'
# Add entry to users_database.yml, then:
docker restart authelia
```

#### Method B: Pre-auth key (no browser required — useful for headless/scripted enrollment)

1. Install the **Tailscale** app
2. Set alternate server to `http://192.168.50.120:8082` (same as above)
3. Enter the pre-auth key from Step 2 when prompted

The phone joins the tailnet and gets a Tailscale IP. The ACL policy (`acls.hujson`)
allows it to reach FTS ports on the Unraid host.

### Step 6 — Configure ATAK on each phone

1. Open ATAK → gear → **Network** → **TAK Servers** → **+**
2. Fill in:
   - **Server address:** Unraid host's Tailscale IP (100.x.x.x from Step 3)
   - **Port:** `8087`
   - **Protocol:** TCP
3. Tap OK — connection indicator should turn green

Update the TAK server address in the Meshtastic plugin preferences (and regenerate the
Data Package) so onboarding packages embed the Tailscale IP, not the LAN IP.

---

## ATAK Client Connection (updated)

Connect via Tailscale (works on LAN and cellular):

- **Server address:** Unraid Tailscale IP (100.x.x.x) — find it in Headscale UI
- **Port:** `8087`
- **Protocol:** TCP

LAN-only connection (no VPN, LAN only):

- **Server address:** `192.168.50.120`
- **Port:** `8087`

---

## TODO

- [ ] TLS cert setup for encrypted CoT (port 8089) — place certs in `/mnt/user/appdata/fts/data/certs/`
- [ ] RPi compose variant with local bind mount paths instead of `/mnt/user/appdata/`
- [ ] Change default API token (`Bearer token`) via FTS-UI after first login
- [ ] Replace Tailscale public DERP relays with self-hosted `derper` for fully self-contained stack
