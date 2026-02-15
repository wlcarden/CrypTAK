#!/usr/bin/env bash
#
# Download and extract the ATAK Plugin SDK from the ATAK CIV GitHub repository.
# This provides atak-gradle-takdev.jar which is required to build ATAK plugins.
#
# Usage:
#   ./scripts/setup-sdk.sh           # Download from default URL
#   ./scripts/setup-sdk.sh <url>     # Download from custom URL
#
# The Gradle build will also auto-download on first build if the JAR is missing,
# but this script is useful for CI environments or pre-fetching.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SDK_DIR="${PROJECT_ROOT}/pluginsdk"
SDK_ZIP="${SDK_DIR}/pluginsdk.zip"
SDK_JAR="${SDK_DIR}/atak-gradle-takdev.jar"

DEFAULT_URL="https://github.com/deptofdefense/AndroidTacticalAssaultKit-CIV/raw/refs/heads/main/pluginsdk.zip"
SDK_URL="${1:-${DEFAULT_URL}}"

if [ -f "${SDK_JAR}" ]; then
    echo "ATAK SDK already present at ${SDK_JAR}"
    echo "Delete ${SDK_DIR} and re-run to force re-download."
    exit 0
fi

echo "Downloading ATAK Plugin SDK..."
echo "  URL: ${SDK_URL}"
echo "  Destination: ${SDK_DIR}/"

mkdir -p "${SDK_DIR}"

# Download
if command -v curl &>/dev/null; then
    curl -fSL -o "${SDK_ZIP}" "${SDK_URL}"
elif command -v wget &>/dev/null; then
    wget -q -O "${SDK_ZIP}" "${SDK_URL}"
else
    echo "Error: neither curl nor wget found" >&2
    exit 1
fi

echo "Extracting pluginsdk.zip..."
unzip -o -q "${SDK_ZIP}" -d "${SDK_DIR}"

# The JAR might be nested in a subdirectory inside the zip
if [ ! -f "${SDK_JAR}" ]; then
    FOUND_JAR=$(find "${SDK_DIR}" -name "atak-gradle-takdev.jar" -type f | head -1)
    if [ -n "${FOUND_JAR}" ]; then
        cp "${FOUND_JAR}" "${SDK_JAR}"
        echo "Copied JAR from ${FOUND_JAR}"
    else
        echo "Error: atak-gradle-takdev.jar not found in pluginsdk.zip" >&2
        echo "Contents of ${SDK_DIR}:"
        find "${SDK_DIR}" -type f | head -20
        exit 1
    fi
fi

echo ""
echo "ATAK SDK ready at ${SDK_JAR}"
echo "You can now build with: ./gradlew assembleCivDebug"
