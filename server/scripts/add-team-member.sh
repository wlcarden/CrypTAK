#!/usr/bin/env bash
# Add a new team member across Authelia + Headscale in one command.
# Usage: ./add-team-member.sh <username> <display_name> <email>
#
# Creates:
#   1. Authelia account (VPN login + WebMap auth)
#   2. Headscale preauth URL for Tailscale enrollment
#
# Run from the Unraid box or SSH into it first.

set -euo pipefail

AUTHELIA_USERS="/mnt/user/appdata/authelia/users_database.yml"
HEADSCALE_USER="takteam"  # All team members share the VPN user namespace

if [[ $# -lt 3 ]]; then
    echo "Usage: $0 <username> <display_name> <email>"
    echo "Example: $0 jdoe 'John Doe' jdoe@team.local"
    exit 1
fi

USERNAME="$1"
DISPLAY_NAME="$2"
EMAIL="$3"

# Validate username — alphanumeric + hyphens/underscores only
if [[ ! "$USERNAME" =~ ^[a-zA-Z0-9_-]+$ ]]; then
    echo "ERROR: Username must contain only letters, numbers, hyphens, and underscores"
    exit 1
fi

# Check for duplicates
if grep -q "^  ${USERNAME}:" "$AUTHELIA_USERS" 2>/dev/null; then
    echo "ERROR: User '${USERNAME}' already exists in Authelia"
    exit 1
fi

# Generate password
echo "Enter password for ${USERNAME}:"
read -rs PASSWORD
echo "Confirm password:"
read -rs PASSWORD2
if [[ "$PASSWORD" != "$PASSWORD2" ]]; then
    echo "ERROR: Passwords do not match"
    exit 1
fi

# Hash password with Authelia's container (piped via stdin to avoid /proc exposure)
HASH=$(printf '%s' "$PASSWORD" | docker run --rm -i ghcr.io/authelia/authelia:latest \
    authelia crypto hash generate argon2 --stdin 2>/dev/null \
    | grep 'Digest:' | awk '{print $2}')

if [[ -z "$HASH" ]]; then
    echo "ERROR: Failed to generate password hash"
    exit 1
fi

# Escape single quotes for YAML (double them per YAML spec)
DISPLAY_NAME_SAFE="${DISPLAY_NAME//\'/\'\'}"
EMAIL_SAFE="${EMAIL//\'/\'\'}"

# Append to Authelia users database
cat >> "$AUTHELIA_USERS" <<EOF
  ${USERNAME}:
    disabled: false
    displayname: '${DISPLAY_NAME_SAFE}'
    password: '${HASH}'
    email: '${EMAIL_SAFE}'
    groups:
      - users
EOF

echo "Authelia: Created user '${USERNAME}'"
docker restart authelia >/dev/null 2>&1
echo "Authelia: Restarted"

# Generate a Headscale preauth key (single-use, 30-day expiry)
PREAUTH=$(docker exec headscale headscale preauthkeys create \
    --user "$HEADSCALE_USER" --expiration 720h 2>/dev/null \
    | tail -1)

echo ""
echo "=== Team Member Created ==="
echo "Username:  ${USERNAME}"
echo "Email:     ${EMAIL}"
echo ""
echo "=== VPN Enrollment ==="
echo "1. Install Tailscale on their device"
echo "2. Settings > Use alternate server > https://vpn.thousand-pikes.com"
echo "3. Login with: ${USERNAME} / (password they set)"
echo "4. Preauth key (if needed): ${PREAUTH}"
echo ""
echo "=== ATAK Setup ==="
echo "Once on VPN, connect ATAK to: 100.64.0.1:8087 (TCP)"
echo "WebMap: http://100.64.0.1:1880/tak-map"
