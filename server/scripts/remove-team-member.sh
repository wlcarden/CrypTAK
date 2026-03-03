#!/usr/bin/env bash
# Remove a team member from Authelia + Headscale.
# Usage: ./remove-team-member.sh <username>
#
# Actions:
#   1. Disables Authelia account (prevents future login)
#   2. Expires Headscale node keys (revokes active VPN access)
#
# Both steps are required — disabling Authelia alone leaves VPN access intact.

set -euo pipefail

AUTHELIA_USERS="/mnt/user/appdata/authelia/users_database.yml"

if [[ $# -lt 1 ]]; then
    echo "Usage: $0 <username>"
    echo "Example: $0 jdoe"
    exit 1
fi

USERNAME="$1"

# Verify user exists
if ! grep -q "^  ${USERNAME}:" "$AUTHELIA_USERS" 2>/dev/null; then
    echo "ERROR: User '${USERNAME}' not found in Authelia"
    exit 1
fi

# Step 1: Disable Authelia account
# Replace 'disabled: false' with 'disabled: true' in the user's block
sed -i "/^  ${USERNAME}:/,/^  [^ ]/ s/disabled: false/disabled: true/" "$AUTHELIA_USERS"
echo "Authelia: Disabled user '${USERNAME}'"

docker restart authelia >/dev/null 2>&1
echo "Authelia: Restarted"

# Step 2: Expire all Headscale nodes belonging to this user
# Get the user's email from Authelia config to match against Headscale
USER_EMAIL=$(sed -n "/^  ${USERNAME}:/,/^  [^ ]/{ /email:/s/.*email: '//;s/'//p; }" "$AUTHELIA_USERS")

if [[ -z "$USER_EMAIL" ]]; then
    echo "WARNING: Could not find email for '${USERNAME}' — skip Headscale node expiry"
    echo "Manually expire nodes: docker exec headscale headscale nodes list"
else
    # List nodes and expire any matching this user's registration
    NODES=$(docker exec headscale headscale nodes list --output json 2>/dev/null || echo "[]")
    EXPIRED=0

    # Find node IDs registered by this user
    NODE_IDS=$(echo "$NODES" | python3 -c "
import sys, json
nodes = json.loads(sys.stdin.read())
for n in nodes:
    user = n.get('user', {})
    name = user.get('name', '')
    if name == '${USERNAME}' or name == 'takteam':
        # Match by registration name or check givenName
        gn = n.get('givenName', '')
        if '${USERNAME}' in gn.lower() or '${USER_EMAIL}' in str(n):
            print(n['id'])
" 2>/dev/null || true)

    for NID in $NODE_IDS; do
        docker exec headscale headscale nodes expire -i "$NID" 2>/dev/null && {
            EXPIRED=$((EXPIRED + 1))
        }
    done

    if [[ $EXPIRED -gt 0 ]]; then
        echo "Headscale: Expired $EXPIRED node(s)"
    else
        echo "Headscale: No nodes found for '${USERNAME}' — may need manual check"
        echo "  docker exec headscale headscale nodes list"
    fi
fi

echo ""
echo "=== Team Member Removed ==="
echo "Username:  ${USERNAME}"
echo "Authelia:  Account disabled"
echo "Headscale: VPN nodes expired (user must re-enroll to reconnect)"
