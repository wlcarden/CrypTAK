#!/usr/bin/env bash
# Renew Let's Encrypt TLS certificate for vpn.thousand-pikes.com
# Uses DNS-01 challenge via Cloudflare API (no inbound port needed).
#
# Install as cron on Unraid:
#   echo "30 3 * * 1 /mnt/user/appdata/tak-server/scripts/renew-cert.sh >> /var/log/certbot-renew.log 2>&1" >> /boot/config/plugins/dynamix/users/root/crontab
#
# certbot renew is idempotent — exits cleanly if cert isn't due for renewal.

set -euo pipefail

LE_DIR="/mnt/user/appdata/letsencrypt"

docker run --rm \
    -v "$LE_DIR:/etc/letsencrypt" \
    certbot/dns-cloudflare:latest \
    renew --quiet --non-interactive

# Restart nginx to pick up new cert.
# Cannot use 'nginx -s reload' — bind-mounted config gets stale file handles on Unraid.
docker restart headscale-nginx 2>/dev/null && \
    echo "$(date): nginx restarted" || \
    echo "$(date): nginx restart failed (container may be down)"
