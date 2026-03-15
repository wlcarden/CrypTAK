#!/usr/bin/env bash
# Rebuild the CrypTAK ATAK server config data package.
# Edit the CONFIG section and re-run to regenerate the .zip.
#
# Usage:
#   ./rebuild.sh
#   adb push cryptak-server-config.zip /sdcard/atak/tools/
#   In ATAK: Settings → Import Data → Local SD (or Files menu → Import)

set -euo pipefail
cd "$(dirname "$0")"

# ── CONFIG ────────────────────────────────────────────────────────────────
FTS_HOST="100.64.0.1"       # Headscale IP for primary FTS (change to field Pi 100.64.0.2 for field use)
FTS_PORT="8087"              # FTS TCP CoT port
FTS_PROTO="tcp"              # tcp or ssl
SERVER_NAME="CrypTAK Primary FTS"
TEAM_COLOR="Cyan"            # TAK team colour: Cyan, Blue, Red, Yellow, Green, ...
# ─────────────────────────────────────────────────────────────────────────

CONNECT="${FTS_HOST}:${FTS_PORT}:${FTS_PROTO}"
ZIP="cryptak-server-config.zip"

python3 - << PYEOF
import zipfile

manifest = """<?xml version="1.0" encoding="utf-8"?>
<MissionPackageManifest version="2">
    <Configuration>
        <Parameter name="uid" value="cryptak-server-config-v1"/>
        <Parameter name="name" value="CrypTAK Server Config"/>
        <Parameter name="onReceiveDelete" value="false"/>
    </Configuration>
    <Contents>
        <Content ignore="false" zipEntry="cryptak-fts.pref"/>
    </Contents>
</MissionPackageManifest>
"""

pref = """<?xml version='1.0' standalone='yes'?>
<preferences>
    <preference version="1" name="cot_streams">
        <entry key="count" class="class java.lang.Integer">1</entry>
        <entry key="description0" class="class java.lang.String">${SERVER_NAME}</entry>
        <entry key="enabled0" class="class java.lang.Boolean">true</entry>
        <entry key="connectString0" class="class java.lang.String">${CONNECT}</entry>
    </preference>
    <preference version="1" name="com.atakmap.app_preferences">
        <entry key="locationTeam" class="class java.lang.String">${TEAM_COLOR}</entry>
    </preference>
</preferences>
"""

with zipfile.ZipFile("${ZIP}", "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("MANIFEST/manifest.xml", manifest)
    z.writestr("cryptak-fts.pref", pref)

print(f"Rebuilt: ${ZIP}  (server: ${CONNECT}, team: ${TEAM_COLOR})")
PYEOF
