<p align="center">
  <img src="logo.png" alt="CrypTAK" width="120">
</p>

# CrypTAK

Privacy-focused situational awareness over LoRa mesh radio. CrypTAK bridges
[Meshtastic](https://meshtastic.org/) mesh networks to
[FreeTAKServer](https://github.com/FreeTAKTeam/FreeTAKServer) with AES-256-GCM
content encryption, a real-time WebMap, automated incident detection, and a
field-deployable command unit — all self-hosted, no cloud dependencies.

CrypTAK traffic travels as standard Meshtastic packets relayed by any node on
the channel, including community infrastructure. The AES-256-GCM layer ensures
message content stays confidential even through nodes you don't control.

Remote access uses a self-hosted WireGuard VPN
([headscale](https://headscale.net/)) with OIDC authentication
([Authelia](https://www.authelia.com/)), so the server is never exposed
directly to the internet.

> **Privacy scope:** Encryption hides message content. It does not hide that
> transmissions are occurring, when, or from where. RF metadata (timing, signal
> strength, triangulation) is observable to anyone monitoring the spectrum.

---

## Architecture

```
                      ┌──────────────────────────────────────────────────────┐
                      │                   Home Server (Unraid)               │
                      │                                                      │
                      │  ┌─────────────┐  ┌──────────┐  ┌───────────────┐   │
                      │  │ FreeTAK     │  │ Node-RED  │  │ Mesh Relay    │   │
                      │  │ Server      │  │ WebMap    │  │ (relay.py)    │   │
                      │  │ :8087 CoT   │  │ :1880     │  │               │   │
                      │  └──────┬──────┘  └─────┬─────┘  └───────┬───────┘   │
                      │         │               │                │           │
                      │         └───────┬───────┘                │           │
                      │                 │ taknet (Docker)        │           │
                      │  ┌──────────┐  ┌┴─────────┐  ┌──────────┴────────┐  │
                      │  │ Headscale│  │ Mosquitto │  │ T-Beam Bridge     │  │
                      │  │ VPN      │  │ MQTT      │  │ (USB serial)      │  │
                      │  │ :9443    │  │ :1883     │  │                   │  │
                      │  └──────────┘  └──────────┘  └─────────┬─────────┘  │
                      │     ┌────────────────────┐             │           │
                      │     │  MeshMonitor       │◄────────────┘           │
                      │     │  Fleet Monitor     │  (serial bridge)        │
                      │     │  :8090             │                         │
                      │     └────────────────────┘                         │
                      └──────────────────────────────────────────┼───────────┘
                                                                 │
                             ┌────────── LoRa 915 MHz ───────────┤
                             │                                   │

```

---

## Fleet Monitoring (MeshMonitor Integration)

CrypTAK includes a dedicated fleet monitoring dashboard based on [MeshMonitor](https://meshmonitor.org/) with CrypTAK-specific enhancements:

**Features:**
- Real-time node positions and telemetry on interactive map
- Battery levels, environmental sensors, signal strength
- Message history and channel monitoring
- **IFF Detection:** Automatic identification of nodes on cryptak private channel (blue markers)
- **TAK Affiliation Coloring:** MIL-STD-2525 scheme (blue=friendly, yellow=unknown, green=neutral, red=suspect)
- **Fleet Registry:** Integration with `nodes.yaml` for node metadata and public key verification
- **Spoof Detection:** Alerts on public key mismatches and name spoofing attempts
- **Remote Admin:** Secure node management via PKC (reboot, factory reset, channel config)
- **Bulk Operations:** Deploy IFF channel to multiple nodes simultaneously

**Access:**
- LAN: `http://192.168.50.120:8090` (change admin password on first login!)
- Tailscale: Available via gateway tunnel (configure in headscale)
- Future: Protected by Authelia reverse proxy with OIDC

**Documentation:**
- See [`DEPLOYMENT.md`](DEPLOYMENT.md) for setup and configuration details
- See [`MESHMONITOR_FORK_PLAN.md`](MESHMONITOR_FORK_PLAN.md) for CrypTAK-specific enhancements roadmap
