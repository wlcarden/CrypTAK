#!/bin/bash
# FTS Watchdog — restarts freetakserver if it enters a zombie state.
#
# FTS can crash internally (listen loop throws "list index out of range")
# while the container process stays alive. Docker's restart: always only
# triggers on container exit, so this watchdog catches the zombie case.
#
# Install as a cron job on the Docker host:
#   */5 * * * * /path/to/fts-watchdog.sh >> /var/log/fts-watchdog.log 2>&1
#
# Or run as a systemd timer.

set -euo pipefail

CONTAINER="freetakserver"
ERROR_PATTERN="error in Receive connections listen function"
# How many recent error lines trigger a restart
THRESHOLD=3
# Only check the last N lines of logs
LOG_LINES=50

# Is container running?
if ! docker ps --filter "name=${CONTAINER}" --filter "status=running" --format '{{.Names}}' | grep -q "^${CONTAINER}$"; then
    echo "$(date -Iseconds) WARN: ${CONTAINER} not running — Docker restart policy should handle this"
    exit 0
fi

# Count fatal error pattern in recent logs
ERROR_COUNT=$(docker logs "${CONTAINER}" --tail "${LOG_LINES}" 2>&1 | grep -c "${ERROR_PATTERN}" || true)

if [ "${ERROR_COUNT}" -ge "${THRESHOLD}" ]; then
    echo "$(date -Iseconds) ALERT: ${CONTAINER} has ${ERROR_COUNT} '${ERROR_PATTERN}' errors in last ${LOG_LINES} log lines — restarting"
    docker restart "${CONTAINER}"
    sleep 10

    # Verify it came back
    if docker ps --filter "name=${CONTAINER}" --filter "status=running" --format '{{.Names}}' | grep -q "^${CONTAINER}$"; then
        echo "$(date -Iseconds) OK: ${CONTAINER} restarted successfully"
    else
        echo "$(date -Iseconds) CRIT: ${CONTAINER} failed to restart!"
        exit 1
    fi
else
    # Optionally uncomment for verbose OK logging:
    # echo "$(date -Iseconds) OK: ${CONTAINER} healthy (${ERROR_COUNT} errors)"
    :
fi
