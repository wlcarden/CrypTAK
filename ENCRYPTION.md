# Meshtastic ATAK Plugin — Application-Layer Encryption

## What It Does

The app-layer encryption feature adds **AES-256-GCM encryption** to CoT (Cursor on Target) payloads *before* they are handed to the Meshtastic radio. This creates a second layer of encryption independent of whatever the radio firmware provides.

**What it protects:**
- Message content (PLI, chat, generic CoT) is encrypted end-to-end between ATAK devices
- Physical compromise of a LoRa radio does not expose message content
- Over-the-air interception of Meshtastic traffic reveals only ciphertext
- With epoch rotation enabled, compromise of the current key does not reveal past traffic (forward secrecy)

**What it does NOT protect:**
- Traffic analysis — an observer can still see that messages are being sent, their timing, and approximate size
- Meshtastic-level metadata — node IDs, hop counts, and radio-layer headers remain in the clear
- Device compromise — if the Android phone running ATAK is compromised, the key can be extracted from memory or preferences
- Denial of service — an attacker can jam the radio channel regardless of encryption
- Authenticity of the sender identity — any device with the PSK can impersonate any callsign (there is no per-device identity binding)

## Generating a Pre-Shared Key (PSK)

Generate a cryptographically random 256-bit key:

```bash
openssl rand -base64 32
```

This produces a 44-character base64 string like:
```
K7gNU3sdo+OL0wNhqoVWhr3g6s1xYv72ol/pe/Unols=
```

**Do NOT:**
- Use a passphrase or dictionary words as the PSK
- Use the same PSK across unrelated teams
- Transmit the PSK over the Meshtastic mesh itself
- Store the PSK in plaintext on shared drives

## Distributing the Key

Recommended distribution methods (in order of preference):

1. **QR code in person** — Generate a QR code containing the base64 key string. Each team member scans it with ATAK or a QR scanner app. The `KeyImportReceiver` handles the import automatically.

2. **ATAK Data Package over TAK Server** — Include the key in an encrypted ATAK data package distributed over a TLS-protected TAK Server connection.

3. **Secure side-channel** — Send the key via Signal, encrypted email, or read it aloud over a secure voice channel.

**Never** distribute the key over the Meshtastic mesh, unencrypted SMS, or email.

### Importing via QR Code

1. Generate the QR code from the base64 key string (any QR generator works)
2. On the receiving device, scan the QR code with any scanner app
3. The plugin's `KeyImportReceiver` intercepts the scanned text, validates it is exactly 32 bytes when decoded, loads it into the encryption manager, and enables encryption
4. Alternatively, broadcast the key programmatically:
   ```java
   Intent intent = new Intent("com.atakmap.android.meshtastic.IMPORT_ENCRYPTION_KEY");
   intent.putExtra("key", "K7gNU3sdo+OL0wNhqoVWhr3g6s1xYv72ol/pe/Unols=");
   context.sendBroadcast(intent);
   ```

### Manual Entry

1. Open ATAK Settings > Tool Preferences > Meshtastic Preferences
2. Scroll to **Application-Layer Encryption**
3. Enable **App-Layer Encryption**
4. Tap **Pre-Shared Key (PSK)** and paste the base64 key string
5. All team members must enter the identical key

## How Epoch Rotation Works

Epoch rotation provides **forward secrecy**: if an attacker compromises the current key, they cannot decrypt messages encrypted under previous epochs.

### Mechanism

1. When epoch rotation is enabled, the encryption manager starts at **epoch 0** using the base key (SHA-256 hash of the PSK)
2. After each epoch interval (default: 6 hours), the key advances:
   ```
   epoch_key[1] = HMAC-SHA256(base_key,     "meshtastic-epoch-" || 0x00000001)
   epoch_key[2] = HMAC-SHA256(epoch_key[1],  "meshtastic-epoch-" || 0x00000002)
   epoch_key[3] = HMAC-SHA256(epoch_key[2],  "meshtastic-epoch-" || 0x00000003)
   ...
   ```
3. The derivation is **forward-only** — knowing `epoch_key[n]` does not help derive `epoch_key[n-1]`
4. The epoch number is embedded in the wire format (V2), so receivers know which epoch key to use for decryption
5. Receivers can derive any epoch key from the base key by chaining forward, so a device that was offline for several epochs can still decrypt

### Configuration

- **Enable Epoch Rotation**: Toggle in Meshtastic Preferences (requires encryption to be enabled first)
- **Epoch Rotation Interval**: 1h, 2h, 4h, 6h (default), 12h, or 24h
- **Current Epoch**: Read-only display showing the active epoch number

### What Happens During Key Rotation

1. The encryption manager detects that the epoch has expired
2. It saves the current key as the "previous epoch key"
3. It derives the new epoch key using HMAC-SHA256
4. New outbound messages use the new key with V2 wire format
5. For a brief window, the manager retains the previous epoch key to decrypt in-flight messages that were encrypted under the old epoch
6. All team devices rotate independently based on their own clocks — the epoch number in the wire format ensures they use the correct key regardless of minor clock drift

### Clock Synchronization

Epoch rotation does **not** require precise clock synchronization between devices. Each device:
- Tracks elapsed time since it loaded the PSK
- Advances epochs based on elapsed time, not wall clock
- Embeds the epoch number in every encrypted message
- Receivers derive the correct key from the embedded epoch number

Devices that have been offline will derive the necessary key on-demand when they receive a message with a higher epoch number.

## Wire Format

### V1 (No Epoch Rotation)
```
[0xFE] [0x01] [IV (12 bytes)] [Ciphertext + GCM Auth Tag (16 bytes)]
```
Total overhead: **30 bytes**

### V2 (Epoch Rotation Enabled)
```
[0xFE] [0x02] [Epoch (4 bytes, big-endian)] [IV (12 bytes)] [Ciphertext + GCM Auth Tag (16 bytes)]
```
Total overhead: **34 bytes**

The `0xFE` marker byte is chosen to not collide with valid protobuf field tags (`0x08`, `0x10`, etc.), zlib headers (`0x78`), the legacy encryption marker (`0xEE`), or Codec2 audio (`0xC2`).

## Device Compromise Response

If a device is compromised (lost, stolen, or suspected of key extraction):

1. **Immediately** generate a new PSK: `openssl rand -base64 32`
2. Distribute the new PSK to all remaining team members via a secure channel
3. All team members enter the new PSK (this resets the epoch counter to 0)
4. The compromised device's old key can no longer decrypt new traffic
5. If epoch rotation was active, the attacker cannot decrypt messages from previous epochs (forward secrecy)
6. If the compromised device had TAK Server access, revoke its certificate on the TAK Server

**If epoch rotation was NOT active:** The attacker can decrypt all past traffic captured with the compromised key. This is why epoch rotation is recommended for high-security deployments.

## Packet Size Budget

Meshtastic LoRa packets have a **231-byte** maximum payload. With encryption overhead:

| Message Type | Typical Size | + V1 Overhead | + V2 Overhead | Fits Single Packet? |
|-------------|-------------|---------------|---------------|-------------------|
| PLI         | 80-120 bytes | 110-150 bytes | 114-154 bytes | Yes |
| Chat (short)| 100-180 bytes| 130-210 bytes | 134-214 bytes | Usually |
| Chat (long) | 200+ bytes  | 230+ bytes    | 234+ bytes    | Fountain coded |
| Generic CoT | Variable    | Variable      | Variable      | Fountain coded if > 231 |

Messages exceeding 231 bytes after encryption are automatically sent using fountain coding (Luby Transform codes), which provides reliable delivery over lossy LoRa links.

## Interoperability

- **Encrypted device -> Non-encrypted device**: The non-encrypted device receives a `0xFE`-prefixed payload that fails protobuf parsing. The message is silently dropped. There is no error indication on the receiving side.
- **Non-encrypted device -> Encrypted device**: The encrypted device receives a valid protobuf (no `0xFE` marker), processes it normally. Unencrypted messages are accepted.
- **Mismatched PSKs**: The receiving device attempts decryption, the GCM authentication tag check fails, and the message is dropped. No error is surfaced to the user.
- **Legacy encryption (0xEE)**: The legacy `CryptoUtils` encryption (marker `0xEE`) and the new app-layer encryption (marker `0xFE`) coexist without conflict. The receive path checks for both markers.
