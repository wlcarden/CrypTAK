#!/usr/bin/env bash
# gen-test-keys.sh — Generate labeled AES-256-GCM test keys
# Usage: ./gen-test-keys.sh [label]
# Output saved to test-artifacts/notes/keys-<label>-<date>.txt

set -euo pipefail

LABEL="${1:-test}"
DATE=$(date +%Y%m%d-%H%M%S)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUTPUT_DIR="$SCRIPT_DIR/../test-artifacts/notes"
OUTPUT_FILE="$OUTPUT_DIR/keys-${LABEL}-${DATE}.txt"

mkdir -p "$OUTPUT_DIR"

echo "Generating AES-256-GCM key for label: ${LABEL}"

KEY_B64=$(openssl rand -base64 32)

cat > "$OUTPUT_FILE" <<EOF
# Test Key — ${LABEL}
# Generated: ${DATE}
# Algorithm: AES-256-GCM
# Key size: 256 bits (32 bytes)
#
# WARNING: For testing only. Never use generated keys in production.

KEY_BASE64=${KEY_B64}
EOF

echo "Key written to: $OUTPUT_FILE"
echo ""
echo "KEY_BASE64=${KEY_B64}"
echo ""
echo "Deploy via adb (both devices must use same key):"
echo "  adb -s <DEVICE_SERIAL> shell am broadcast \\"
echo "    -a com.atakmap.android.meshtastic.SET_ENCRYPTION_KEY \\"
echo "    --es key_b64 '${KEY_B64}'"
