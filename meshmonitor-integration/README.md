# MeshMonitor Integration for CrypTAK

This directory contains the Docker deployment files for running MeshMonitor as a fleet monitoring solution for CrypTAK.

## Current Deployment (Phase 1)

- **Location:** `/mnt/user/appdata/meshmonitor/` on Unraid
- **Services:**
  - `meshtastic-serial-bridge`: Bridges USB serial (`/dev/ttyACM0`) to TCP port 4403
  - `meshmonitor`: Fleet monitoring web UI accessible at `http://192.168.50.120:8090`

## Configuration

See [`docker-compose.yml`](./docker-compose.yml) for the current deployment.

## Future Development

Phase 2 enhancements (IFF detection, TAK coloring, fleet registry integration) will be developed in a fork of MeshMonitor:
- Repository: `github.com/wlcarden/meshmonitor-cryptak`
- Documentation: [`MESHMONITOR_FORK_PLAN.md`](../MESHMONITOR_FORK_PLAN.md)

## Access

- **Web UI:** http://192.168.50.120:8090
- **Default Login:** admin / changeme (⚠️ **Change on first login!**)
- **API:** http://192.168.50.120:8090/api/ (Bearer token auth)

## Logs & Monitoring

```bash
# View serial bridge logs
ssh root@192.168.50.120 'docker logs meshtastic-serial-bridge'

# View MeshMonitor logs
ssh root@192.168.50.120 'docker logs meshmonitor'

# Follow logs live
ssh root@192.168.50.120 'docker compose -f /mnt/user/appdata/meshmonitor/docker-compose.yml logs -f'
```