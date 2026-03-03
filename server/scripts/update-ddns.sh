#!/usr/bin/env bash
# Update Cloudflare DNS A record for vpn.thousand-pikes.com to current public IP.
# Exits silently if IP hasn't changed (avoids unnecessary API calls).
#
# Install as cron on Unraid:
#   echo "*/15 * * * * /mnt/user/appdata/tak-server/scripts/update-ddns.sh >> /var/log/ddns-update.log 2>&1" >> /boot/config/plugins/dynamix/users/root/crontab
#
# Requires: Cloudflare API token with DNS:Edit permission (from cloudflare.ini).

set -euo pipefail

ZONE_ID="72d2985627789764b70536f3d9384e9b"
RECORD_ID="9a4bab0f6c05d7d730769b073522538d"
DOMAIN="vpn.thousand-pikes.com"
CF_INI="/mnt/user/appdata/letsencrypt/cloudflare.ini"
IP_CACHE="/tmp/ddns-last-ip"

# Read API token from certbot's cloudflare.ini
CF_TOKEN=$(grep 'dns_cloudflare_api_token' "$CF_INI" | awk '{print $3}')
if [[ -z "$CF_TOKEN" ]]; then
    echo "$(date): ERROR: No API token in $CF_INI"
    exit 1
fi

# Get current public IP
CURRENT_IP=$(curl -sf https://api.ipify.org || curl -sf https://ifconfig.me)
if [[ -z "$CURRENT_IP" ]]; then
    echo "$(date): ERROR: Could not determine public IP"
    exit 1
fi

# Skip update if IP hasn't changed
if [[ -f "$IP_CACHE" ]] && [[ "$(cat "$IP_CACHE")" == "$CURRENT_IP" ]]; then
    exit 0
fi

# Update Cloudflare DNS record
RESPONSE=$(curl -sf -X PATCH \
    "https://api.cloudflare.com/client/v4/zones/$ZONE_ID/dns_records/$RECORD_ID" \
    -H "Authorization: Bearer $CF_TOKEN" \
    -H "Content-Type: application/json" \
    --data "{\"content\":\"$CURRENT_IP\"}")

if echo "$RESPONSE" | python3 -c "import sys,json; sys.exit(0 if json.load(sys.stdin)['success'] else 1)" 2>/dev/null; then
    echo "$CURRENT_IP" > "$IP_CACHE"
    echo "$(date): Updated $DOMAIN → $CURRENT_IP"
else
    echo "$(date): ERROR: Cloudflare API update failed"
    echo "$RESPONSE"
    exit 1
fi
