#!/bin/bash
# Renew Let's Encrypt cert for vpn.thousand-pikes.com (DNS-01 via Cloudflare).
#
# certbot renew is idempotent — exits cleanly if cert is not yet within the
# renewal window. We only restart headscale-nginx when the cert file actually
# changed, so the nightly cron is a true no-op outside the renewal window
# (no VPN blip from a redundant restart).
#
# Deploy path: /mnt/user/appdata/tak-server/scripts/renew-cert.sh
#   (synced from repo by .github/workflows/deploy-server.yml)
# Schedule:    /boot/config/plugins/dynamix/cryptak-renew-cert.cron
# Credentials: /mnt/user/appdata/letsencrypt/cloudflare.ini (NOT in repo)
#
# See server/scripts/README.md for the full setup procedure.

set -uo pipefail

LE_DIR=/mnt/user/appdata/letsencrypt
CERT="$LE_DIR/live/vpn.thousand-pikes.com/fullchain.pem"

before=$(stat -c %Y "$CERT" 2>/dev/null || echo 0)

docker run --rm \
    -v "$LE_DIR:/etc/letsencrypt" \
    certbot/dns-cloudflare:latest \
    renew --quiet --non-interactive

after=$(stat -c %Y "$CERT" 2>/dev/null || echo 0)

if [ "$after" != "$before" ]; then
    if docker restart headscale-nginx >/dev/null 2>&1; then
        echo "$(date): cert renewed, nginx reloaded"
    else
        echo "$(date): cert renewed, nginx reload FAILED" >&2
        exit 1
    fi
else
    echo "$(date): no renewal needed"
fi
