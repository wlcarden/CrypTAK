# Operations Log

Running log of maintenance, incidents, and infrastructure changes.

---

## 2026-09-30 — CI deploy downgraded headscale (stale pin) — ~4 min VPN control-plane outage

### Problem

Pushing 84c0f8d (CoT identity fixes) triggered `deploy-server.yml`. The rsync
replaced `/mnt/user/appdata/tak-server/docker-compose.yml` with the repo copy,
which still pinned `headscale:0.28`; the server had been upgraded to 0.29.4 on
2026-09-28 (Tailscale Android >=1.102.3 crash fix) without committing the pin.
`docker compose up -d` recreated headscale on 0.28, which refuses the
0.29-migrated SQLite schema (`database_versions = v0.29.4`):

```
SQLite schema failed to validate: >> Remove table "database_versions"
FTL Error initializing ... invalid schema
```

Crash loop 02:35:08–02:39:28 UTC; CI step "Verify core services running"
failed on `headscale`. tak-01 and unraid-tak reconnected within seconds of
the restore. The same run also pulled new images and recreated freetakserver,
mosquitto, nodered, mesh-relay and incident-tracker, resetting every ATAK
and relay session to FTS — expected behaviour of `pull` + `up -d` on floating
tags, but worth knowing before pushing to `server/**` during an operation.

### Resolution

- Pin set to `ghcr.io/juanfont/headscale:0.29.4` in place on the server and
  `docker compose up -d --no-deps headscale`; verified `minimum_version=v1.80`
  and nodes online.
- Same line committed to `server/docker-compose.yml` (e2c2978) so the next
  rsync is byte-identical and compose leaves headscale alone.

### Lesson

Anything under `server/` that is changed on the server and not committed is
reverted by the next deploy — the rsync excludes only `.env`,
`docker-compose.override.yml` and the authelia/headscale/incident-tracker
config files. Before pushing to `server/**`, diff the deployed tree against
the repo: `diff <(git show HEAD:server/docker-compose.yml)
<(ssh unraid cat /mnt/user/appdata/tak-server/docker-compose.yml)`.

---


## 2026-09-30 — CoT identity bugs: WebMap marker keying, mesh-relay FTS wedge, MQTT node attribution

### Problem

Reported as "no CoT events from TAK-01". Ingest was healthy (ATAK → FTS 8087
measured at ~1 event / 11 s). Three separate identity bugs downstream:

1. **WebMap** (`cot-maps.js` + Node-RED `fn_cot`): markers keyed by callsign,
   and the callsign regex lacked `\b`, so ATAK's `<link parent_callsign="TAK-01">`
   on a placed marker matched before `<contact callsign="Skinwalker">`. Every
   CoT object from TAK-01 overwrote the previous one under key `"TAK-01"` — the
   device icon was replaced by its last placed (hostile) marker, titled TAK-01.
2. **mesh-relay** (`FtsClient`): discarded the StreamReader. FTS broadcasts the
   CoT stream to every client; nothing drained it, the receive window closed
   and FTS's send queue stalled (784 KB notsent, rwnd_limited 100%, backoff 15,
   no read for 15.3 h). TAK-01 never reached the mesh.
3. **mesh-relay MQTT handler**: read `sender` (the uplinking gateway, always
   GW01) instead of `from` (originating node). Nine foreign public-LongFast
   nodes were attributed to GW01: ~40 km position jumps, 101%↔44% battery,
   spurious "rebooted" warnings. GW01 has no GPS and never publishes position;
   its marker was never GW01.

### Resolution

- cot-maps.js: `extractCallsign()` (prefers `<contact>`, `\b`-anchored
  fallback); marker identity = CoT `uid`, callsign kept as `_callsign` and
  shown as popup title; `parseRemarks()` preserves operator comments verbatim
  unless `<remarks source="incident-tracker">`. Unit-tested offline with the
  real uid/callsign/type shapes from `FTSDataBase.db`.
- flows.json `fn_cot`: cache keyed by uid; one-time migration drops legacy
  callsign-keyed entries and sends worldmap deletes; tracker overrides stay
  keyed by callsign, endpoints resolve callsign → uid; orphaned trail history
  purged. `fn_serve_clients` reports `_callsign`.
- webmap/index.html: sidebar layer lists render `_callsign` as the title
  (worldmap `name` stays the uid so click-to-locate works); filter and sort
  use the title. Backup `index.html.bak-20260930-221448`.
- CONNECTED panel: with objects no longer collapsing onto one key, placed
  markers were listed as "connected clients". `parseCotToMarker` now sets
  `_client` positively — `<takv platform>` or `<contact endpoint>` present,
  `how` not `h-g-i-g-o`, uid not `CrypTAK-*` — plus `_platform`/`_device`/
  `_appVersion`; `fn_serve_clients` requires it and returns `uid`, which the
  CONNECTED row uses for click-to-popup (callsign no longer matches worldmap
  identity). Verified against FTS `Takv`/`Contact`/`Event` rows: TAK-01 =
  ATAK-CIV 5.5.1.8 on a Pixel 6 with endpoint `*:-1:stcp`; placed markers
  have none of it.
- relay.py: `_drain_inbound` task per connection, torn down on EOF so the
  next `send()` reconnects (FTS drops clients shortly after registration);
  `_mqtt_node_id()` decodes `from`; `MQTT_OWNED_ONLY` (default true) drops
  non-fleet nodes while the uplink still carries public LongFast.
- Tests: `server/mesh-relay/test_relay_fts.py` (11), all fail on the
  pre-fix code with the intended assertions.

Deployed in place (Python r+/truncate) to
`/mnt/cache/appdata/tak-server/nodered/lib/cot-maps.js`,
`/mnt/cache/appdata/nodered/data/flows.json` (backups `*.bak-20260930-214311`)
and the mesh-relay build context; image rebuilt. Verified live: uid-keyed
markers for TAK-01 (friendly) plus Skinwalker / Four Chuds With AKs /
N.30.212812 as separate objects; all FTS subscriber sockets SendQ 0,
rwnd_limited none.

### Consequences / follow-ups

- GW01 will drop off the WebMap when its last foreign-sourced marker expires
  (ttl 1800 s). To place it, set `fixed_position` in its profile via
  `firmware/provision.sh`, or render it from `nodes.yaml` in Node-RED.
- Committed on branch `fix/cot-identity`: relay.py, test_relay_fts.py and the
  WebMap files under `server/nodered/`, which is their versioned source — CI
  (`deploy-server.yml`, on push to `main` touching `server/**`) rsyncs
  `lib/cot-maps.js` and `webmap/index.html` to the live mount paths, POSTs
  `flows.json` through the Node-RED admin API, and restarts Node-RED. Until
  the branch lands on `main`, any CI run from `main` would revert the
  in-place patches to the pre-fix versions.
- incident-tracker `src/cot/fts_client.py` has the same write-only bug
  (holds `_reader`, never reads) — same drain fix applies.
- Audit TODO still open: move GW01's MQTT uplink from ch0 (LongFast) to ch1
  (cryptak) so foreign nodes stop arriving at all.

### Follow-ups applied (same day)

- incident-tracker `src/cot/fts_client.py`: same write-only client, same
  drain + EOF-teardown fix (host-side socket showed 920 KB `notsent`,
  `rwnd_limited 100%`, idle 13 h). Real-socket tests added to
  `tests/test_fts_client.py`.
- mesh-relay `FtsClient.keepalive()`: the 30 s idle path now re-registers
  with FTS if the peer dropped us (FTS closes clients on its own schedule and
  on every restart) instead of waiting for the next mesh position, which GW01
  never sends; also guards `refresh_sa()` against the writer being gone.
- Node-RED `mqtt_mesh`: topic `msh/+/2/json/#` → `msh/+/2/2/json/#`. GW01's
  MQTT root is `msh/US/2` and the firmware appends `/2/json/…`, so the old
  subscription matched nothing — the sidebar "Mesh Network" panel had read
  "No mesh nodes heard" since ~April 2026 (every `meshRegistry.lastHeard`
  ~6 months old, past the 48 h / 72 h filters). Parser keys on `payload.from`
  and only checks `/json/`.
- …which was necessary but not sufficient. With the right filter the broker
  logged SUBSCRIBE from `nodered-mesh-map` and **zero deliveries**, while
  mesh-relay's client received every message. The broker loads its config
  from `/mnt/user/appdata/mosquitto/config` (not the rsynced
  `tak-server/mosquitto/`, so the repo copy was a dead file) and has had
  `acl_file acl.conf` since 2026-03-29 with a single rule: `user meshtastic`
  → `readwrite msh/#`. With an ACL loaded, users without a rule are denied
  everything, and read filtering is not logged — Node-RED connects as user
  `nodered`, so it was silently read-denied from the day the ACL appeared.
  Added `user nodered / topic read msh/#`, `docker kill -s HUP mosquitto`,
  and fixed the file to `mosquitto:mosquitto 0640` (mosquitto warned it will
  refuse world-readable/root-owned ACL files in a future version).
  Reference copies + apply notes now in `server/mosquitto/`.
  CLAUDE.md's "Mosquitto ACLs: TODO (not yet implemented)" is stale.

---


## 2026-04-07 — tak-02 VPN Re-enrollment + Router DNS Persistence

### Problem

tak-02 (Pixel 6a) lost its Tailscale/Headscale VPN configuration. The node was
still registered in headscale (identifier 11, IP 100.64.0.9) but showing offline.
The Tailscale app on the phone had no active tunnel (`tailscale0`/`tun0` interface
absent).

Separately, the ASUS router's split DNS settings — required for WiFi-based VPN
enrollment — were non-persistent. A router reboot would have broken future
enrollments.

### Resolution: VPN Re-enrollment

Used the WiFi + split DNS enrollment path (phone was on home WiFi):

1. Disabled Private DNS on phone (`settings put global private_dns_mode off`)
   — GrapheneOS DoT to 1.1.1.1 was bypassing local dnsmasq, preventing
   `vpn.thousand-pikes.com` from resolving to the LAN IP.
2. Cycled WiFi to pick up correct DHCP DNS order (router 192.168.50.1 first).
3. Cleared Tailscale app data (`pm clear com.tailscale.ipn`).
4. Generated pre-auth key on headscale.
5. Walked through Tailscale UI: Get Started → Settings → Accounts → Menu →
   "Use an alternate server" → `https://vpn.thousand-pikes.com` → back →
   Menu → "Use an auth key" → entered key → "Add account".
6. Enrollment confirmed via headscale logs (`Node connected node.id=12`).
7. Deleted old stale node (identifier 11) from headscale.
8. Renamed new node to `tak-02` (identifier 12).
9. Restored Private DNS to `opportunistic`.

**Final state:**

| ID  | Name   | IP         | Status |
| --- | ------ | ---------- | ------ |
| 12  | tak-02 | 100.64.0.5 | online |

Note: VPN IP changed from 100.64.0.9 (old registration) to 100.64.0.5 (new).

### Resolution: Router DNS Persistence

The ASUS GT-AXE11000 (Merlin 388.23883) had two split DNS settings that were
applied to the live `/etc/dnsmasq.conf` but would be lost on reboot:

1. **`address=/vpn.thousand-pikes.com/192.168.50.120`** — Already persistent.
   Was in `/jffs/configs/dnsmasq.conf.add` (Merlin appends this file to the
   generated config on boot).

2. **`dhcp-option=lan,6,192.168.50.1,1.1.1.1,8.8.8.8`** — NOT persistent.
   The router's nvram has `dhcp_dns1_x=1.1.1.1`, so on reboot the generated
   config would push 1.1.1.1 first, causing clients to skip local dnsmasq.

   **Fix:** Created `/jffs/scripts/dnsmasq.postconf` — a Merlin hook that runs
   after config generation but before dnsmasq starts. It seds the
   `dhcp-option=lan,6,...` line to put 192.168.50.1 first:

   ```sh
   #!/bin/sh
   CONFIG=$1
   sed -i 's/^dhcp-option=lan,6,.*/dhcp-option=lan,6,192.168.50.1,1.1.1.1,8.8.8.8/' "$CONFIG"
   ```

   Why `dnsmasq.postconf` instead of `dnsmasq.conf.add`: dnsmasq sends ALL
   matching `dhcp-option` entries for the same tag+option, so a duplicate in
   `.conf.add` would cause clients to receive two sets of DNS servers. The
   postconf script modifies the existing line in place.

### Headscale Cleanup

- Renamed node 5 from `tak-99` back to `tak-01` (was mis-renamed at some point)
- Deleted orphan node 9 (`localhost`, created 2026-04-06, short-lived test enrollment)

### Headscale Node Table (post-change)

| ID  | Name               | IP         | User    | Status  |
| --- | ------------------ | ---------- | ------- | ------- |
| 1   | unraid-tak         | 100.64.0.1 | takteam | online  |
| 3   | localhost-mjnmat1o | 100.64.0.3 | admin   | offline |
| 4   | tak-field          | 100.64.0.2 | takteam | offline |
| 5   | tak-01             | 100.64.0.4 | takteam | offline |
| 12  | tak-02             | 100.64.0.5 | takteam | online  |

---

## 2026-04-07 — TAK-01 Remote Health Check + HMDM Config Fixes

### Investigation

TAK-01 (Pixel 6 Pro) was showing as `tak-99` in headscale (renamed back to
`tak-01` above). Device is currently VPN-offline (last headscale connection
02:49 UTC) but was recently checking in with HMDM (last update 02:23 UTC,
via Tailscale VPN).

HMDM server was down — `UnknownHostException: hmdm-db` during Tomcat
initialization (startup race: HMDM container tried to connect before
PostgreSQL was ready, `depends_on` only waits for container start, not DB
readiness). Restarted HMDM container to fix.

### TAK-01 vs TAK-02 Comparison

| Field         | TAK-01         | TAK-02         | Status       |
| ------------- | -------------- | -------------- | ------------ |
| Model         | Pixel 6 Pro    | Pixel 6a       | OK           |
| Android       | 16             | 16             | OK           |
| MDM mode      | **false**      | true           | NEEDS ADB    |
| Serial        | unknown        | 18301FDF6006BA | NEEDS ADB    |
| Default lnchr | true           | true           | OK           |
| All 8 apps    | installed      | installed      | OK           |
| App versions  | match config   | match config   | OK           |
| Tailscale     | offline        | online         | NEEDS ACCESS |
| Last location | 38.844/-77.095 | —              | OK           |

### Config Changes Applied (remote, via HMDM DB)

1. **`autoupdate=true`** — HMDM will now auto-install updated APKs when
   devices sync. Previously `false`, meaning app updates required manual
   action.
2. **`bluetooth=true`** — HMDM now enforces Bluetooth enabled. Previously
   `null` (unmanaged). Required for Meshtastic BT pairing.

Both changes apply to config 3 ("CrypTAK Field Device") and affect all
devices (TAK-01, TAK-02, TAK-03) on next sync.

### TODO: Next Physical Access to TAK-01

These require ADB (USB cable to the Pixel 6 Pro):

1. **Set device owner** — enables silent APK installs:
   ```bash
   adb shell dpm set-device-owner com.hmdm.launcher/.AdminReceiver
   ```
2. **Re-enroll Tailscale** if VPN hasn't reconnected by then:
   ```bash
   # Use enroll-tailscale.sh or the manual WiFi+split-DNS procedure
   # documented in tailscale-enrollment memory
   ```
3. **Grant HMDM permissions** (if not already):
   ```bash
   adb shell pm grant com.hmdm.launcher android.permission.ACCESS_FINE_LOCATION
   adb shell pm grant com.hmdm.launcher android.permission.ACCESS_COARSE_LOCATION
   adb shell pm grant com.hmdm.launcher android.permission.POST_NOTIFICATIONS
   adb shell appops set --uid com.hmdm.launcher MANAGE_EXTERNAL_STORAGE allow
   adb shell appops set com.hmdm.launcher REQUEST_INSTALL_PACKAGES allow
   ```
4. **Verify serial/IMEI** reporting works after device owner is set
