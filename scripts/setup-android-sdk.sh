#!/usr/bin/env bash
# setup-android-sdk.sh — Download and install Android command-line tools + required SDK components
# Run ONCE after: sudo apt install -y openjdk-17-jdk wget unzip
#
# What this installs:
#   - Android command-line tools (sdkmanager, avdmanager)
#   - platforms;android-36 (compile SDK)
#   - build-tools;35.0.0
#   - build-tools;36.0.0
#   - ndk;25.1.8937393
#   - cmake;3.22.1

set -euo pipefail

SDK_ROOT="$HOME/Android/Sdk"
CMDLINE_TOOLS_DIR="$SDK_ROOT/cmdline-tools"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
ZIP_FILE="/tmp/cmdline-tools.zip"

echo "=== Android SDK Setup ==="
echo "SDK root: $SDK_ROOT"
echo ""

# Verify JDK 17
JAVA_VER=$(java -version 2>&1 | awk -F'"' 'NR==1{print $2}' | cut -d. -f1)
if [[ "$JAVA_VER" != "17" ]]; then
  echo "ERROR: JDK 17 required, found: $(java -version 2>&1 | head -1)" >&2
  echo "Run: sudo apt install -y openjdk-17-jdk && sudo update-alternatives --config java" >&2
  exit 1
fi
echo "JDK version: OK (17)"

# Create SDK directory structure
mkdir -p "$CMDLINE_TOOLS_DIR"

# Download cmdline-tools
if [[ -f "$CMDLINE_TOOLS_DIR/latest/bin/sdkmanager" ]]; then
  echo "cmdline-tools already installed, skipping download."
else
  echo ""
  echo "Downloading Android command-line tools..."
  wget -q --show-progress -O "$ZIP_FILE" "$CMDLINE_TOOLS_URL"
  echo "Extracting..."
  unzip -q "$ZIP_FILE" -d "$CMDLINE_TOOLS_DIR"
  # sdkmanager requires the directory to be named "latest"
  if [[ -d "$CMDLINE_TOOLS_DIR/cmdline-tools" ]]; then
    mv "$CMDLINE_TOOLS_DIR/cmdline-tools" "$CMDLINE_TOOLS_DIR/latest"
  fi
  rm -f "$ZIP_FILE"
  echo "cmdline-tools installed."
fi

SDKMANAGER="$CMDLINE_TOOLS_DIR/latest/bin/sdkmanager"

# Accept licenses
echo ""
echo "Accepting SDK licenses..."
yes | "$SDKMANAGER" --licenses > /dev/null 2>&1 || true

# Install required SDK components
echo ""
echo "Installing SDK components (this may take several minutes)..."
"$SDKMANAGER" \
  "platforms;android-36" \
  "build-tools;35.0.0" \
  "build-tools;36.0.0" \
  "ndk;25.1.8937393" \
  "cmake;3.22.1"

echo ""
echo "=== Android SDK setup complete ==="
echo ""
echo "Next steps:"
echo "  1. Add to ~/.bashrc or ~/.zshrc:"
echo "       export ANDROID_HOME=$SDK_ROOT"
echo "       export PATH=\$PATH:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools"
echo ""
echo "  2. Clone the plugin repo:"
echo "       cd ~/Desktop/Meshtastic"
echo "       git clone https://github.com/wlcarden/ATAK-Plugin.git"
echo "       cd ATAK-Plugin"
echo "       git checkout claude/meshtastic-app-encryption-SAcS1"
echo "       echo \"sdk.dir=$SDK_ROOT\" > local.properties"
echo ""
echo "  3. Download ATAK SDK from:"
echo "       https://github.com/TAK-Product-Center/atak-civ/releases"
echo "     Save zip to: ~/Desktop/Meshtastic/sdk-archives/atak-civ-5.5.1.8/"
echo "     Extract main.jar, android_keystore, proguard-release-keep.txt to ATAK-Plugin/sdk/"
