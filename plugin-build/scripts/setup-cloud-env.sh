#!/usr/bin/env bash
#
# Set up the Android build environment for ATAK-Plugin in a cloud/CI container.
#
# Installs: JDK 17, Android SDK (platform 36, build-tools 35+36, NDK r25b, CMake 3.22.1),
# configures Gradle proxy from HTTP_PROXY env var, and verifies the ATAK Plugin SDK.
#
# Usage:
#   ./scripts/setup-cloud-env.sh        # Full setup
#   ./scripts/setup-cloud-env.sh --check # Just verify everything is present
#
# Idempotent: safe to run multiple times. Skips components already installed.
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
ANDROID_SDK_ROOT="${ANDROID_HOME:-/home/user/android-sdk}"
LOCAL_PROPS="${PROJECT_ROOT}/local.properties"
GRADLE_PROPS="${PROJECT_ROOT}/gradle.properties"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

CHECK_ONLY=false
if [[ "${1:-}" == "--check" ]]; then
    CHECK_ONLY=true
fi

# --------------------------------------------------------------------------
# Helpers
# --------------------------------------------------------------------------
log() { echo "[setup] $*"; }
err() { echo "[setup] ERROR: $*" >&2; }

check_component() {
    local name="$1" path="$2"
    if [[ -e "$path" ]]; then
        log "$name: OK ($path)"
        return 0
    else
        if $CHECK_ONLY; then
            err "$name: MISSING ($path)"
        fi
        return 1
    fi
}

# --------------------------------------------------------------------------
# JDK 17
# --------------------------------------------------------------------------
setup_jdk() {
    if java -version 2>&1 | grep -q 'version "17\|version "21'; then
        log "JDK: OK ($(java -version 2>&1 | head -1))"
        return
    fi
    if $CHECK_ONLY; then err "JDK 17+: MISSING"; return 1; fi

    log "Installing JDK 17..."
    if command -v apt-get &>/dev/null; then
        sudo apt-get update -qq && sudo apt-get install -y -qq openjdk-17-jdk-headless
    else
        err "Cannot install JDK: apt-get not found. Install JDK 17+ manually."
        return 1
    fi
}

# --------------------------------------------------------------------------
# Android SDK command-line tools
# --------------------------------------------------------------------------
setup_cmdline_tools() {
    local sdkmanager="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager"
    if check_component "Android cmdline-tools" "$sdkmanager"; then return; fi
    if $CHECK_ONLY; then return 1; fi

    log "Downloading Android command-line tools..."
    mkdir -p "${ANDROID_SDK_ROOT}/cmdline-tools"
    local tmpzip="/tmp/cmdline-tools.zip"
    curl -fSL -o "$tmpzip" "$CMDLINE_TOOLS_URL"
    unzip -o -q "$tmpzip" -d "${ANDROID_SDK_ROOT}/cmdline-tools"
    mv "${ANDROID_SDK_ROOT}/cmdline-tools/cmdline-tools" "${ANDROID_SDK_ROOT}/cmdline-tools/latest" 2>/dev/null || true
    rm -f "$tmpzip"
    log "Command-line tools installed"
}

# --------------------------------------------------------------------------
# Android SDK components
# --------------------------------------------------------------------------
setup_sdk_components() {
    local sdkmanager="${ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager"

    local components=(
        "platforms;android-36"
        "build-tools;35.0.0"
        "build-tools;36.0.0"
        "platform-tools"
        "ndk;25.1.8937393"
        "cmake;3.22.1"
    )

    # Check which are missing
    local missing=()
    for comp in "${components[@]}"; do
        local dir_name="${comp//;/\/}"
        if [[ ! -d "${ANDROID_SDK_ROOT}/${dir_name}" ]]; then
            missing+=("$comp")
        fi
    done

    if [[ ${#missing[@]} -eq 0 ]]; then
        log "All SDK components: OK"
        return
    fi
    if $CHECK_ONLY; then
        for comp in "${missing[@]}"; do err "SDK component MISSING: $comp"; done
        return 1
    fi

    log "Accepting licenses..."
    yes 2>/dev/null | "$sdkmanager" --licenses >/dev/null 2>&1 || true

    log "Installing ${#missing[@]} SDK component(s): ${missing[*]}"
    "$sdkmanager" "${missing[@]}"
    log "SDK components installed"
}

# --------------------------------------------------------------------------
# local.properties (sdk.dir)
# --------------------------------------------------------------------------
setup_local_properties() {
    if grep -q "sdk.dir=${ANDROID_SDK_ROOT}" "$LOCAL_PROPS" 2>/dev/null; then
        log "local.properties sdk.dir: OK"
        return
    fi
    if $CHECK_ONLY; then err "local.properties sdk.dir incorrect"; return 1; fi

    if [[ -f "$LOCAL_PROPS" ]]; then
        sed -i "s|^sdk.dir=.*|sdk.dir=${ANDROID_SDK_ROOT}|" "$LOCAL_PROPS"
    else
        echo "sdk.dir=${ANDROID_SDK_ROOT}" > "$LOCAL_PROPS"
    fi
    log "local.properties updated: sdk.dir=${ANDROID_SDK_ROOT}"
}

# --------------------------------------------------------------------------
# Gradle proxy (from HTTP_PROXY env var)
# --------------------------------------------------------------------------
setup_gradle_proxy() {
    if [[ -z "${HTTP_PROXY:-}" ]]; then
        log "Gradle proxy: skipped (no HTTP_PROXY env var)"
        return
    fi

    # Check if proxy is already configured
    if grep -q "systemProp.http.proxyHost" "$GRADLE_PROPS" 2>/dev/null; then
        log "Gradle proxy: already configured"
        return
    fi
    if $CHECK_ONLY; then err "Gradle proxy: not configured"; return 1; fi

    # Parse proxy URL: http://user:password@host:port
    local proxy_url="${HTTP_PROXY}"
    proxy_url="${proxy_url#http://}"
    proxy_url="${proxy_url#https://}"

    local user_pass="" host_port=""
    if [[ "$proxy_url" == *"@"* ]]; then
        user_pass="${proxy_url%%@*}"
        host_port="${proxy_url#*@}"
    else
        host_port="$proxy_url"
    fi

    local host="${host_port%%:*}"
    local port="${host_port#*:}"
    port="${port%%/*}"

    local proxy_user="${user_pass%%:*}"
    local proxy_pass="${user_pass#*:}"

    {
        echo "systemProp.http.proxyHost=${host}"
        echo "systemProp.http.proxyPort=${port}"
        if [[ -n "$proxy_user" ]]; then
            echo "systemProp.http.proxyUser=${proxy_user}"
            echo "systemProp.http.proxyPassword=${proxy_pass}"
        fi
        echo "systemProp.https.proxyHost=${host}"
        echo "systemProp.https.proxyPort=${port}"
        if [[ -n "$proxy_user" ]]; then
            echo "systemProp.https.proxyUser=${proxy_user}"
            echo "systemProp.https.proxyPassword=${proxy_pass}"
        fi
    } >> "$GRADLE_PROPS"
    log "Gradle proxy configured from HTTP_PROXY"
}

# --------------------------------------------------------------------------
# ATAK Plugin SDK (delegates to existing setup-sdk.sh)
# --------------------------------------------------------------------------
setup_atak_sdk() {
    local sdk_jar="${PROJECT_ROOT}/pluginsdk/atak-gradle-takdev.jar"
    if check_component "ATAK Plugin SDK" "$sdk_jar"; then return; fi
    if $CHECK_ONLY; then return 1; fi

    if [[ -x "${SCRIPT_DIR}/setup-sdk.sh" ]]; then
        log "Running setup-sdk.sh for ATAK Plugin SDK..."
        "${SCRIPT_DIR}/setup-sdk.sh"
    else
        err "setup-sdk.sh not found; ATAK Plugin SDK must be installed manually"
        return 1
    fi
}

# --------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------
main() {
    log "ATAK-Plugin cloud environment setup"
    log "Project root: ${PROJECT_ROOT}"
    log "Android SDK: ${ANDROID_SDK_ROOT}"
    if $CHECK_ONLY; then log "Mode: check only (no changes)"; fi
    echo ""

    local failures=0

    setup_jdk || ((failures++))
    setup_cmdline_tools || ((failures++))
    setup_sdk_components || ((failures++))
    setup_local_properties || ((failures++))
    setup_gradle_proxy || ((failures++))
    setup_atak_sdk || ((failures++))

    echo ""
    if [[ $failures -gt 0 ]]; then
        err "$failures component(s) need attention"
        return 1
    else
        log "Environment ready. Build with: ./gradlew assembleCivDebug"
    fi
}

main "$@"
