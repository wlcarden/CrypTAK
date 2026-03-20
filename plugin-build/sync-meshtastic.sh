#!/bin/bash
#
# Meshtastic File Sync/Compare Tool
#
# This script helps you compare and sync your local Meshtastic files with the
# upstream repositories. Due to API changes between versions, automatic syncing
# often requires manual fixes.
#
# Usage:
#   ./sync-meshtastic.sh compare [branch_or_tag]   - Compare local vs upstream (default)
#   ./sync-meshtastic.sh sync [branch_or_tag]      - Download upstream files (use with caution!)
#   ./sync-meshtastic.sh revert                    - Revert to git version
#
# Example:
#   ./sync-meshtastic.sh compare 2.5.19   # Compare against specific release
#
# Sources:
#   - Model/AIDL: https://github.com/meshtastic/Meshtastic-Android
#   - Protobufs:  https://github.com/meshtastic/protobufs
#

set -e

ACTION="${1:-compare}"
BRANCH="${2:-main}"

# Protobufs repo uses 'master' as default, Android uses 'main'
# Map common branch names appropriately
if [[ "$BRANCH" == "main" ]]; then
    PROTO_BRANCH="master"
else
    PROTO_BRANCH="$BRANCH"
fi

# Base URLs for both repos
ANDROID_URL="https://raw.githubusercontent.com/meshtastic/Meshtastic-Android/${BRANCH}"
PROTO_URL="https://raw.githubusercontent.com/meshtastic/protobufs/${PROTO_BRANCH}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Target directories
MODEL_DIR="${SCRIPT_DIR}/app/src/main/java/org/meshtastic/core/model"
MODEL_UTIL_DIR="${MODEL_DIR}/util"
AIDL_MODEL_DIR="${SCRIPT_DIR}/app/src/main/aidl/org/meshtastic/core/model"
AIDL_SERVICE_DIR="${SCRIPT_DIR}/app/src/main/aidl/org/meshtastic/core/service"
PROTO_DIR="${SCRIPT_DIR}/app/src/main/protobufs"
PROTO_MESH_DIR="${PROTO_DIR}/meshtastic"

# Source paths in Meshtastic-Android repo
SRC_MODEL="core/model/src/main/kotlin/org/meshtastic/core/model"
SRC_MODEL_AIDL="core/model/src/main/aidl/org/meshtastic/core/model"
SRC_SERVICE_AIDL="core/service/src/main/aidl/org/meshtastic/core/service"

# Temp directory for downloads
TEMP_DIR=$(mktemp -d)
trap "rm -rf $TEMP_DIR" EXIT

# Files to sync
MODEL_FILES="DataPacket.kt NodeInfo.kt MyNodeInfo.kt"
UTIL_FILES="Extensions.kt DateTimeUtils.kt LocationUtils.kt"
AIDL_MODEL_FILES="DataPacket.aidl NodeInfo.aidl MyNodeInfo.aidl MeshUser.aidl Position.aidl"
AIDL_SERVICE_FILES="IMeshService.aidl"

# Proto files (from meshtastic/protobufs repo)
PROTO_ROOT_FILES="nanopb.proto"
PROTO_MESH_FILES="admin.proto apponly.proto atak.proto cannedmessages.proto channel.proto
clientonly.proto config.proto connection_status.proto device_ui.proto deviceonly.proto
interdevice.proto localonly.proto mesh.proto module_config.proto mqtt.proto paxcount.proto
portnums.proto powermon.proto remote_hardware.proto rtttl.proto storeforward.proto
telemetry.proto xmodem.proto"

# Options files (nanopb options)
PROTO_OPTIONS_FILES="admin.options apponly.options atak.options cannedmessages.options
channel.options clientonly.options config.options connection_status.options device_ui.options
deviceonly.options interdevice.options mesh.options module_config.options mqtt.options
rtttl.options storeforward.options telemetry.options xmodem.options"

echo "========================================================"
echo "Meshtastic File Sync Tool"
echo "========================================================"
echo "Sources:"
echo "  Model/AIDL: github.com/meshtastic/Meshtastic-Android"
echo "  Protobufs:  github.com/meshtastic/protobufs"
echo "Branch/Tag: ${BRANCH}"
echo "Action: ${ACTION}"
echo "========================================================"
echo ""

download_file() {
    local url="$1"
    local dest_path="$2"
    curl -sfL "${url}" -o "${dest_path}" 2>/dev/null
}

compare_files() {
    echo "Comparing files with upstream..."
    echo "================================"
    echo ""

    local has_changes=false
    local model_changes=false
    local proto_changes=false

    echo "=== Kotlin Model Files ==="
    for file in $MODEL_FILES; do
        if download_file "${ANDROID_URL}/${SRC_MODEL}/${file}" "${TEMP_DIR}/${file}"; then
            if [[ -f "${MODEL_DIR}/${file}" ]]; then
                if ! diff -q "${MODEL_DIR}/${file}" "${TEMP_DIR}/${file}" > /dev/null 2>&1; then
                    echo "📝 ${file} - CHANGED"
                    has_changes=true
                    model_changes=true
                else
                    echo "✓  ${file} - up to date"
                fi
            else
                echo "🆕 ${file} - NEW (not in local)"
                has_changes=true
                model_changes=true
            fi
        else
            echo "⚠  ${file} - failed to download"
        fi
    done

    echo ""
    echo "=== Model Utility Files ==="
    for file in $UTIL_FILES; do
        if download_file "${ANDROID_URL}/${SRC_MODEL}/util/${file}" "${TEMP_DIR}/${file}"; then
            if [[ -f "${MODEL_UTIL_DIR}/${file}" ]]; then
                if ! diff -q "${MODEL_UTIL_DIR}/${file}" "${TEMP_DIR}/${file}" > /dev/null 2>&1; then
                    echo "📝 util/${file} - CHANGED"
                    has_changes=true
                    model_changes=true
                else
                    echo "✓  util/${file} - up to date"
                fi
            else
                echo "🆕 util/${file} - NEW"
                has_changes=true
                model_changes=true
            fi
        else
            echo "⚠  util/${file} - failed to download"
        fi
    done

    echo ""
    echo "=== AIDL Model Files ==="
    for file in $AIDL_MODEL_FILES; do
        if download_file "${ANDROID_URL}/${SRC_MODEL_AIDL}/${file}" "${TEMP_DIR}/${file}"; then
            if [[ -f "${AIDL_MODEL_DIR}/${file}" ]]; then
                if ! diff -q "${AIDL_MODEL_DIR}/${file}" "${TEMP_DIR}/${file}" > /dev/null 2>&1; then
                    echo "📝 aidl/${file} - CHANGED"
                    has_changes=true
                    model_changes=true
                else
                    echo "✓  aidl/${file} - up to date"
                fi
            else
                echo "🆕 aidl/${file} - NEW"
                has_changes=true
                model_changes=true
            fi
        else
            echo "⚠  aidl/${file} - failed to download"
        fi
    done

    echo ""
    echo "=== AIDL Service Files ==="
    for file in $AIDL_SERVICE_FILES; do
        if download_file "${ANDROID_URL}/${SRC_SERVICE_AIDL}/${file}" "${TEMP_DIR}/${file}"; then
            if [[ -f "${AIDL_SERVICE_DIR}/${file}" ]]; then
                if ! diff -q "${AIDL_SERVICE_DIR}/${file}" "${TEMP_DIR}/${file}" > /dev/null 2>&1; then
                    echo "📝 aidl/service/${file} - CHANGED"
                    has_changes=true
                    model_changes=true
                else
                    echo "✓  aidl/service/${file} - up to date"
                fi
            else
                echo "🆕 aidl/service/${file} - NEW"
                has_changes=true
                model_changes=true
            fi
        else
            echo "⚠  aidl/service/${file} - failed to download"
        fi
    done

    echo ""
    echo "=== Protobuf Files (meshtastic/protobufs) ==="

    # Root proto files
    for file in $PROTO_ROOT_FILES; do
        if download_file "${PROTO_URL}/${file}" "${TEMP_DIR}/${file}"; then
            if [[ -f "${PROTO_DIR}/${file}" ]]; then
                if ! diff -q "${PROTO_DIR}/${file}" "${TEMP_DIR}/${file}" > /dev/null 2>&1; then
                    echo "📝 ${file} - CHANGED"
                    has_changes=true
                    proto_changes=true
                else
                    echo "✓  ${file} - up to date"
                fi
            else
                echo "🆕 ${file} - NEW"
                has_changes=true
                proto_changes=true
            fi
        else
            echo "⚠  ${file} - failed to download"
        fi
    done

    # Meshtastic proto files
    for file in $PROTO_MESH_FILES; do
        if download_file "${PROTO_URL}/meshtastic/${file}" "${TEMP_DIR}/${file}"; then
            if [[ -f "${PROTO_MESH_DIR}/${file}" ]]; then
                if ! diff -q "${PROTO_MESH_DIR}/${file}" "${TEMP_DIR}/${file}" > /dev/null 2>&1; then
                    echo "📝 meshtastic/${file} - CHANGED"
                    has_changes=true
                    proto_changes=true
                else
                    echo "✓  meshtastic/${file} - up to date"
                fi
            else
                echo "🆕 meshtastic/${file} - NEW"
                has_changes=true
                proto_changes=true
            fi
        else
            echo "⚠  meshtastic/${file} - failed to download"
        fi
    done

    echo ""
    echo "=== Nanopb Options Files ==="
    for file in $PROTO_OPTIONS_FILES; do
        if download_file "${PROTO_URL}/meshtastic/${file}" "${TEMP_DIR}/${file}"; then
            if [[ -f "${PROTO_MESH_DIR}/${file}" ]]; then
                if ! diff -q "${PROTO_MESH_DIR}/${file}" "${TEMP_DIR}/${file}" > /dev/null 2>&1; then
                    echo "📝 meshtastic/${file} - CHANGED"
                    has_changes=true
                    proto_changes=true
                else
                    echo "✓  meshtastic/${file} - up to date"
                fi
            else
                echo "🆕 meshtastic/${file} - NEW"
                has_changes=true
                proto_changes=true
            fi
        else
            # Options files may not all exist upstream
            if [[ -f "${PROTO_MESH_DIR}/${file}" ]]; then
                echo "⚠  meshtastic/${file} - local only (not in upstream)"
            fi
        fi
    done

    echo ""
    if $has_changes; then
        echo "========================================================"
        echo "Changes detected!"
        echo ""
        if $model_changes; then
            echo "Model/AIDL changes - sync with caution (may break build):"
            echo "  ./sync-meshtastic.sh sync-models ${BRANCH}"
        fi
        if $proto_changes; then
            echo ""
            echo "Protobuf changes - generally safe to sync:"
            echo "  ./sync-meshtastic.sh sync-protos ${BRANCH}"
        fi
        echo ""
        echo "To sync everything (use with caution):"
        echo "  ./sync-meshtastic.sh sync ${BRANCH}"
        echo "========================================================"
    else
        echo "All files are up to date with upstream!"
    fi
}

sync_models() {
    echo "⚠️  WARNING: Syncing models may break the build!"
    echo "   The upstream files may have API changes that are"
    echo "   incompatible with the rest of the codebase."
    echo ""
    read -p "Continue? (y/N) " -n 1 -r
    echo ""
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "Aborted."
        return 1
    fi

    echo ""
    echo "Downloading model files..."
    echo "--------------------------"

    mkdir -p "${MODEL_DIR}" "${MODEL_UTIL_DIR}" "${AIDL_MODEL_DIR}" "${AIDL_SERVICE_DIR}"

    for file in $MODEL_FILES; do
        echo -n "  ${file}... "
        if download_file "${ANDROID_URL}/${SRC_MODEL}/${file}" "${MODEL_DIR}/${file}"; then
            echo "✓"
        else
            echo "✗"
        fi
    done

    for file in $UTIL_FILES; do
        echo -n "  util/${file}... "
        if download_file "${ANDROID_URL}/${SRC_MODEL}/util/${file}" "${MODEL_UTIL_DIR}/${file}"; then
            # Patch BuildConfig references
            sed -i.bak -e '/import.*BuildConfig/d' -e 's/BuildConfig\.DEBUG/false/g' "${MODEL_UTIL_DIR}/${file}" 2>/dev/null || true
            rm -f "${MODEL_UTIL_DIR}/${file}.bak"
            echo "✓"
        else
            echo "✗"
        fi
    done

    for file in $AIDL_MODEL_FILES; do
        echo -n "  aidl/${file}... "
        if download_file "${ANDROID_URL}/${SRC_MODEL_AIDL}/${file}" "${AIDL_MODEL_DIR}/${file}"; then
            echo "✓"
        else
            echo "✗"
        fi
    done

    for file in $AIDL_SERVICE_FILES; do
        echo -n "  aidl/service/${file}... "
        if download_file "${ANDROID_URL}/${SRC_SERVICE_AIDL}/${file}" "${AIDL_SERVICE_DIR}/${file}"; then
            echo "✓"
        else
            echo "✗"
        fi
    done

    echo ""
    echo "Model sync complete!"
}

sync_protos() {
    echo "Downloading protobuf files..."
    echo "-----------------------------"

    mkdir -p "${PROTO_DIR}" "${PROTO_MESH_DIR}"

    # Root proto files
    for file in $PROTO_ROOT_FILES; do
        echo -n "  ${file}... "
        if download_file "${PROTO_URL}/${file}" "${PROTO_DIR}/${file}"; then
            echo "✓"
        else
            echo "✗"
        fi
    done

    # Meshtastic proto files
    for file in $PROTO_MESH_FILES; do
        echo -n "  meshtastic/${file}... "
        if download_file "${PROTO_URL}/meshtastic/${file}" "${PROTO_MESH_DIR}/${file}"; then
            echo "✓"
        else
            echo "✗"
        fi
    done

    # Options files
    for file in $PROTO_OPTIONS_FILES; do
        echo -n "  meshtastic/${file}... "
        if download_file "${PROTO_URL}/meshtastic/${file}" "${PROTO_MESH_DIR}/${file}"; then
            echo "✓"
        else
            echo "✗ (may not exist upstream)"
        fi
    done

    echo ""
    echo "Protobuf sync complete!"
}

sync_all() {
    sync_protos
    echo ""
    sync_models

    echo ""
    echo "========================================================"
    echo "Full sync complete! Run './gradlew assembleDebug' to test."
    echo ""
    echo "If build fails, revert with:"
    echo "  ./sync-meshtastic.sh revert"
    echo "========================================================"
}

revert_files() {
    echo "Reverting files to git version..."
    git checkout -- "${MODEL_DIR}/" "${AIDL_MODEL_DIR}/" "${AIDL_SERVICE_DIR}/" "${PROTO_DIR}/" 2>/dev/null || true
    echo "Done!"
}

show_help() {
    echo "Usage: $0 <action> [branch_or_tag]"
    echo ""
    echo "Actions:"
    echo "  compare      - Compare local files with upstream (default)"
    echo "  sync         - Sync all files (models + protos)"
    echo "  sync-models  - Sync only model/AIDL files (may break build)"
    echo "  sync-protos  - Sync only protobuf files (generally safe)"
    echo "  revert       - Revert all changes to git version"
    echo "  help         - Show this help"
    echo ""
    echo "Examples:"
    echo "  $0 compare              # Compare with main branch"
    echo "  $0 compare 2.5.19       # Compare with release tag"
    echo "  $0 sync-protos          # Sync protos from main"
    echo "  $0 sync-protos 2.5.19   # Sync protos from release tag"
    echo ""
    echo "Repositories:"
    echo "  https://github.com/meshtastic/Meshtastic-Android"
    echo "  https://github.com/meshtastic/protobufs"
}

case "$ACTION" in
    compare)
        compare_files
        ;;
    sync)
        sync_all
        ;;
    sync-models)
        sync_models
        ;;
    sync-protos)
        sync_protos
        ;;
    revert)
        revert_files
        ;;
    help|--help|-h)
        show_help
        ;;
    *)
        echo "Unknown action: $ACTION"
        echo ""
        show_help
        exit 1
        ;;
esac
