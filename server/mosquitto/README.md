# Mosquitto (reference copies)

The broker mounts `/mnt/user/appdata/mosquitto/config` (see `docker-compose.yml`),
**not** this directory, so CI's rsync of `server/mosquitto/` deploys nothing.
These files mirror the live config so changes have history; apply them by hand:

```bash
# edit /mnt/user/appdata/mosquitto/config/{mosquitto.conf,acl.conf} on Unraid, then
docker kill -s HUP mosquitto   # reloads acl/password files without dropping clients
```

`passwd` (users: `meshtastic`, `nodered`, `openclaw`) is never committed.
Both files must be `mosquitto:mosquitto` (uid 1883) and mode `0640`: mosquitto 2.x
warns on world-readable or root-owned ACL/password files and future versions
refuse to load them.

Rules: with `acl_file` loaded, a user with no matching rule is denied everything,
and read filtering is silent — a subscriber sees a successful SUBSCRIBE and no
messages. Every new MQTT consumer needs a `user` block here.
