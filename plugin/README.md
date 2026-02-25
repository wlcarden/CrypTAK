# Meshtastic ATAK Plugin

Official Meshtastic ATAK Plugin for sending Cursor on Target (CoT) events to IMeshService in the Meshtastic Android app.

## Overview

The Meshtastic ATAK Plugin enables seamless integration between the Android Team Awareness Kit (ATAK) and Meshtastic mesh networking devices. This plugin allows tactical teams to share position location information (PLI), chat messages, and other CoT events over Meshtastic's long-range, low-power mesh network.

## Features

### Core Functionality
- **Position Location Information (PLI)** - Share real-time location data between ATAK devices via Meshtastic
- **Chat Integration** - Send and receive GeoChat messages through the mesh network
- **File Transfer** - Transfer mission packages and files using fountain code encoding (requires Short_Turbo modem preset)
- **Voice Memos** - Record speech-to-text messages and broadcast via Meshtastic
- **External GPS Support** - Use Meshtastic device's GPS as external GPS source for ATAK
- **Server Relay** - Forward CoT events between Meshtastic mesh and TAK servers

### Architecture Improvements (v1.1.15+)
The plugin has been refactored for better maintainability and performance:
- Modular architecture with separated concerns
- Centralized service management
- Improved error handling and logging
- Fountain code encoding for reliable large data transfers over lossy networks
- Thread-safe singleton patterns for shared resources

## Installation

1. Install the Meshtastic Android app from [Google Play](https://play.google.com/store/apps/details?id=com.geeksville.mesh)
2. Install ATAK-CIV from [tak.gov](https://tak.gov)
3. Download the latest Meshtastic ATAK Plugin APK from [Releases](https://github.com/meshtastic/ATAK-Plugin/releases)
4. Install the plugin APK on your Android device
5. Launch ATAK and load the Meshtastic plugin from the plugins menu
<img width="1080" height="2400" alt="image" src="https://github.com/user-attachments/assets/5f58a101-7293-476d-94a1-0e98925f2402" />


## Configuration

### Plugin Settings

Access plugin settings via: **Settings → Tool Preferences → Specific Tool Preferences → Meshtastic Preferences**

#### Mesh Settings
- **Meshtastic Channel Index** - Select the Meshtastic channel to use for ATAK traffic (0-7, default: 0)
- **Filter by Channel Index** - Only receive messages from the specified channel (default: off)
- **Hop Limit** - Maximum number of hops for outgoing messages (1-7, default: 3)
- **Request ACK** - Request delivery acknowledgment, increases channel usage (default: off)

#### PLI & Reporting
- **Limit PLI Reporting Rate** - Reduce PLI frequency to conserve mesh bandwidth (default: on)
- **PLI Reporting Interval** - How often to send position updates (30s to 30min, default: 5min)
- **Only Send PLI and Chat** - Use optimized protobuf format, recommended for Meshtastic (default: off)
- **Send Read/Delivery Receipts** - Send delivery and read receipts for chat messages (default: on)

#### Display Settings
- **Show Meshtastic Devices** - Display all Meshtastic nodes as markers on the map (default: on)
- **Hide Devices Without GPS** - Don't show nodes reporting 0,0 coordinates (default: off)
- **Hide Local Node** - Don't show your own Meshtastic device on map (default: off)

#### Server Relay
- **Relay to Server** - Forward messages from Meshtastic to connected TAK servers (default: off)
- **Relay from Server** - Forward PLI and chat from TAK servers to Meshtastic (default: off)

#### File Transfer
- **Enable File Transfer** - Allow sending files via Meshtastic, requires Short_Turbo preset, max 56KB (default: off)

#### Audio & Voice
- **Text to Speech** - Read incoming Meshtastic text messages aloud (default: off)
- **PTT KeyCode** - Hardware button code for voice memo recording (default: 79)

#### GPS Settings
- **Use Meshtastic GPS** - Use Meshtastic device GPS as ATAK's external GPS source (default: off)

#### Application-Layer Encryption
- **Enable App-Layer Encryption** - Encrypt all outgoing CoT payloads with AES-256-GCM before they reach the Meshtastic radio (default: off)
- **Pre-Shared Key (PSK)** - 256-bit key shared across the team; generate with `openssl rand -base64 32`
- **Enable Epoch Rotation** - Automatically rotate the encryption key on a configurable interval for forward secrecy (default: off)
- **Epoch Rotation Interval** - Key rotation period: 1h, 2h, 4h, 6h (default), 12h, or 24h

See [ENCRYPTION.md](ENCRYPTION.md) for full documentation on key generation, distribution, wire format, and security considerations.

## Using Meshtastic as External GPS

To use your Meshtastic device as ATAK's GPS source:

### Requirements
- Meshtastic device with GPS receiver (e.g., LILYGO T-Beam)
- GPS enabled and configured on Meshtastic device
- Position packets configured in Meshtastic settings

### ATAK Configuration
1. Navigate to **Settings → Callsign and Device Preferences → Device Preferences → GPS Preferences**
2. Set **GPS Option** to "Ignore internal GPS / Use External or Network GPS Only"
3. Enable **Use Meshtastic GPS** in plugin settings
4. Disable **Show Meshtastic Devices** to avoid duplicate markers

## Voice Memo Feature

The Voice Memo tool allows hands-free message transmission:

1. Access via **Meshtastic Plugin Tool Menu → Voice Memo**
2. Press and hold configured PTT button to record
3. Release to convert speech to text and transmit
4. Recipients with TTS enabled will hear the message

**Note:** Currently supports English only, powered by Vosk speech recognition library.

## Technical Details

### Architecture Components

#### Core Services
- **MeshServiceManager** - Handles connection and communication with Meshtastic Android app
- **CotEventProcessor** - Processes and converts between CoT and Meshtastic formats
- **FountainChunkManager** - Manages fountain code encoding for large data transfers
- **NotificationHelper** - Handles user notifications for file transfers

#### Data Flow
1. CoT events from ATAK are intercepted by the plugin
2. Events are processed and converted to Meshtastic protobuf format
3. Large messages (>231 bytes) are encoded using fountain codes (LT codes)
4. Packets are sent via IMeshService to connected Meshtastic device
5. Incoming Meshtastic packets are converted back to CoT format
6. CoT events are injected into ATAK's event dispatcher

### Message Types Supported
- Position Location Information (PLI)
- GeoChat messages (All Chat Rooms and Direct Messages)
- Sensor data from Meshtastic nodes
- Generic CoT events (with EXI compression)

### Performance Optimizations
- Fountain code encoding for reliable large payload transfer over lossy networks
- EXI compression for generic CoT events
- Optimized protobuf for PLI and chat messages
- Configurable hop limits for network reach control

## Building from Source

### Requirements
- Android Studio Arctic Fox or later
- Android SDK 33
- Android NDK
- ATAK SDK (CIV or MIL)

### Build Steps
```bash
# Clone the repository
git clone --recurse-submodules https://github.com/meshtastic/ATAK-Plugin.git
cd ATAK-Plugin

# Configure local.properties with SDK paths and signing keys
cp local.properties.example local.properties
# Edit local.properties with your configuration

# Build the plugin
./gradlew assembleCivDebug

# Output APK will be in app/build/outputs/apk/
```

## Video Walkthrough

For a comprehensive demonstration of features and setup, watch our [video walkthrough](https://www.youtube.com/watch?v=7cn4ofiSd0A).

## Troubleshooting

### Common Issues

**Plugin not connecting to Meshtastic:**
- Ensure Meshtastic app is installed and running
- Check that Meshtastic device is paired and connected
- Verify plugin has necessary permissions

**Messages not being received:**
- Confirm channel settings match between devices
- Check hop limit is sufficient for your network
- Verify nodes are within radio range

**GPS not working:**
- Ensure Meshtastic device has GPS fix
- Verify position packets are enabled in Meshtastic
- Check ATAK GPS settings are configured for external GPS

## Contributing

Contributions are welcome! Please:
1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Submit a pull request

See [CONTRIBUTING.md](CONTRIBUTING.md) for detailed guidelines.

## Support

- **Issues:** [GitHub Issues](https://github.com/meshtastic/ATAK-Plugin/issues)
- **Discussions:** [Meshtastic Discord](https://discord.gg/meshtastic)
- **Documentation:** [Meshtastic Docs](https://meshtastic.org)

## License

See the [LICENSE](LICENSE) file for details.

## Acknowledgments

- ATAK development team at TAK.gov
- Meshtastic community and contributors
- Vosk speech recognition library
- EXIficient compression library

## Version History

See [CHANGELOG.md](CHANGELOG.md) for version history and release notes.
