package com.atakmap.android.meshtastic.encryption;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Integration-level tests for app-layer encryption flowing through simulated
 * send and receive paths.
 *
 * <p>These tests verify that encryption integrates correctly with the data flow
 * patterns used by MeshtasticMapComponent (send) and MeshtasticReceiver (receive),
 * including protobuf-like payloads, zlib-compressed CoT, fountain-coded data,
 * and the server relay path.
 */
class AppLayerEncryptionIntegrationTest {

    private static final int MAX_SINGLE_PACKET = 231;
    private static final String TEST_PSK = "integration-test-psk-2025";

    private AppLayerEncryptionManager sender;
    private AppLayerEncryptionManager receiver;

    @BeforeEach
    void setUp() {
        // Reset the singleton for test isolation
        resetSingleton();

        // In real deployment, sender and receiver are the same singleton on different devices.
        // Here we use the same singleton to simulate both sides with the same PSK.
        sender = AppLayerEncryptionManager.getInstance();
        sender.loadKey(TEST_PSK);
        sender.setEnabled(true);

        // Receiver is the same instance (same PSK, same key derivation)
        receiver = sender;
    }

    private void resetSingleton() {
        try {
            java.lang.reflect.Field instanceField =
                    AppLayerEncryptionManager.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            instanceField.set(null, null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to reset singleton", e);
        }
    }

    // ========================================================================
    // Send Path Integration Tests (simulating MeshtasticMapComponent)
    // ========================================================================

    @Test
    void sendPath_shouldEncryptPLIProtobufPayload() {
        // Simulate handleSelfPLI: TAKPacket.ADAPTER.encode(takPacket) produces protobuf bytes
        // Typical PLI protobuf is ~80-120 bytes
        byte[] pliProtobuf = createMockProtobuf(100);

        // Simulate encryptPayloadIfEnabled(takPacketBytes)
        byte[] encrypted = sender.encrypt(pliProtobuf);

        // Verify: encrypted, has correct marker, fits single packet
        assertThat(encrypted).isNotNull();
        assertThat(encrypted[0]).isEqualTo(AppLayerEncryptionManager.APP_LAYER_MARKER);
        assertThat(encrypted.length).isEqualTo(pliProtobuf.length + 30);
        assertThat(encrypted.length).isLessThanOrEqualTo(MAX_SINGLE_PACKET);

        // Verify: receiver can decrypt back to original protobuf
        byte[] decrypted = receiver.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(pliProtobuf);
    }

    @Test
    void sendPath_shouldEncryptChatProtobufPayload() {
        // Simulate handleAllChatMessage: TAKPacket with GeoChat
        // Typical chat protobuf is ~100-200 bytes
        byte[] chatProtobuf = createMockProtobuf(180);

        byte[] encrypted = sender.encrypt(chatProtobuf);

        assertThat(encrypted).isNotNull();
        assertThat(encrypted[0]).isEqualTo(AppLayerEncryptionManager.APP_LAYER_MARKER);
        // 180 + 30 = 210, fits single packet
        assertThat(encrypted.length).isLessThanOrEqualTo(MAX_SINGLE_PACKET);

        byte[] decrypted = receiver.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(chatProtobuf);
    }

    @Test
    void sendPath_shouldEncryptGenericCotZlibPayload() {
        // Simulate handleGenericCotEvent: CoT XML -> zlib compressed -> encrypted
        String cotXml = "<event version='2.0' uid='test-uid' type='a-f-G-U-C'>"
                + "<point lat='40.7128' lon='-74.0060' hae='100.0' ce='9999999.0' le='9999999.0'/>"
                + "<detail><contact callsign='TestUser'/></detail>"
                + "</event>";

        byte[] compressed = zlibCompress(cotXml.getBytes(StandardCharsets.UTF_8));
        assertThat(compressed).isNotNull();

        // Encrypt compressed CoT
        byte[] encrypted = sender.encrypt(compressed);
        assertThat(encrypted).isNotNull();
        assertThat(encrypted[0]).isEqualTo(AppLayerEncryptionManager.APP_LAYER_MARKER);

        // Receiver decrypts, then decompresses
        byte[] decrypted = receiver.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(compressed);

        // Verify decompression recovers original XML
        byte[] decompressed = zlibDecompress(decrypted);
        assertThat(new String(decompressed, StandardCharsets.UTF_8)).isEqualTo(cotXml);
    }

    @Test
    void sendPath_shouldPassThroughWhenEncryptionDisabled() {
        // Disable encryption
        sender.setEnabled(false);

        byte[] pliProtobuf = createMockProtobuf(100);

        // Simulate encryptPayloadIfEnabled - when disabled, returns original
        byte[] result;
        if (!sender.isEnabled()) {
            result = pliProtobuf;
        } else {
            result = sender.encrypt(pliProtobuf);
        }

        // Payload should be unchanged
        assertThat(result).isSameAs(pliProtobuf);
        assertThat(AppLayerEncryptionManager.isAppLayerEncrypted(result)).isFalse();
    }

    @Test
    void sendPath_shouldTriggerFountainForOversizedEncryptedPayload() {
        // Payload of 202 bytes + 30 overhead = 232 > 231 limit
        byte[] largePayload = createMockProtobuf(202);

        byte[] encrypted = sender.encrypt(largePayload);
        assertThat(encrypted).isNotNull();
        assertThat(encrypted.length).isGreaterThan(MAX_SINGLE_PACKET);

        // Verify the sendOrChunkPayload decision logic
        boolean needsFountain = encrypted.length > MAX_SINGLE_PACKET;
        assertThat(needsFountain).isTrue();

        // Even though fountain-chunked, the encrypted data should still decrypt
        byte[] decrypted = receiver.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(largePayload);
    }

    // ========================================================================
    // Receive Path Integration Tests (simulating MeshtasticReceiver)
    // ========================================================================

    @Test
    void receivePath_shouldDecryptPort72TAKPacket() {
        // Simulate: sender encrypts a TAKPacket protobuf
        byte[] originalTakPacket = createMockProtobuf(120);
        byte[] encrypted = sender.encrypt(originalTakPacket);

        // Simulate receive path on port 72:
        // byte[] takBytes = payload.getBytes().toByteArray();
        byte[] takBytes = encrypted;

        // if (AppLayerEncryptionManager.isAppLayerEncrypted(takBytes))
        assertThat(AppLayerEncryptionManager.isAppLayerEncrypted(takBytes)).isTrue();

        // AppLayerEncryptionManager encManager = AppLayerEncryptionManager.getInstance();
        // byte[] decrypted = encManager.decrypt(takBytes);
        byte[] decrypted = receiver.decrypt(takBytes);
        assertThat(decrypted).isNotNull();
        assertThat(decrypted).isEqualTo(originalTakPacket);

        // TAKPacket tp = TAKPacket.ADAPTER.decode(takBytes) would now use decrypted bytes
    }

    @Test
    void receivePath_shouldDecryptPort257ForwarderData() {
        // Simulate: sender encrypts a zlib-compressed CoT for ATAK_FORWARDER port
        byte[] compressedCot = zlibCompress("test-cot-data".getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = sender.encrypt(compressedCot);

        // Simulate ATAK_FORWARDER receive path
        byte[] raw = encrypted;

        // Check for app-layer encryption before legacy 0xEE check
        assertThat(AppLayerEncryptionManager.isAppLayerEncrypted(raw)).isTrue();

        byte[] decrypted = receiver.decrypt(raw);
        assertThat(decrypted).isNotNull();
        assertThat(decrypted).isEqualTo(compressedCot);
    }

    @Test
    void receivePath_shouldDecryptFountainReassembledData() {
        // Simulate: fountain coding reassembles a large encrypted payload
        byte[] largeCot = zlibCompress(new byte[500]);
        byte[] encrypted = sender.encrypt(largeCot);

        // Simulate processFountainData: data has been reassembled from fountain chunks
        byte[] reassembled = encrypted;

        assertThat(AppLayerEncryptionManager.isAppLayerEncrypted(reassembled)).isTrue();

        byte[] decrypted = receiver.decrypt(reassembled);
        assertThat(decrypted).isNotNull();
        assertThat(decrypted).isEqualTo(largeCot);
    }

    @Test
    void receivePath_shouldPassThroughUnencryptedProtobuf() {
        // Simulate: receive unencrypted protobuf (from device without encryption)
        byte[] plainProtobuf = createMockProtobuf(100);

        // Receive path: isAppLayerEncrypted check
        assertThat(AppLayerEncryptionManager.isAppLayerEncrypted(plainProtobuf)).isFalse();

        // Should NOT attempt decryption — just pass through to protobuf decode
        // The protobuf bytes are used directly
    }

    @Test
    void receivePath_shouldPassThroughLegacyEncrypted() {
        // Simulate: receive legacy 0xEE encrypted data
        byte[] legacyEncrypted = new byte[50];
        new SecureRandom().nextBytes(legacyEncrypted);
        legacyEncrypted[0] = (byte) 0xEE;

        // Should NOT be detected as app-layer encrypted
        assertThat(AppLayerEncryptionManager.isAppLayerEncrypted(legacyEncrypted)).isFalse();
    }

    @Test
    void receivePath_shouldPassThroughZlibData() {
        // Simulate: receive unencrypted zlib-compressed data
        byte[] zlibData = zlibCompress("test".getBytes(StandardCharsets.UTF_8));

        // zlib starts with 0x78 — not 0xFE
        assertThat(AppLayerEncryptionManager.isAppLayerEncrypted(zlibData)).isFalse();
    }

    @Test
    void receivePath_shouldHandleWrongKeyGracefully() {
        // Simulate: sender has different PSK than receiver
        byte[] plaintext = createMockProtobuf(100);
        byte[] encrypted = sender.encrypt(plaintext);

        // Reset singleton and create receiver with different PSK
        resetSingleton();
        AppLayerEncryptionManager wrongKeyReceiver = AppLayerEncryptionManager.getInstance();
        wrongKeyReceiver.loadKey("completely-different-psk");

        // Should detect as encrypted
        assertThat(AppLayerEncryptionManager.isAppLayerEncrypted(encrypted)).isTrue();

        // Should fail decryption (GCM auth tag mismatch) — returns null
        byte[] result = wrongKeyReceiver.decrypt(encrypted);
        assertThat(result).isNull();
    }

    // ========================================================================
    // Server Relay Path Integration Tests (onCotEvent)
    // ========================================================================

    @Test
    void serverRelay_shouldEncryptChatRelayPacket() {
        // Simulate onCotEvent chat relay: server sends CoT -> build TAKPacket -> encrypt -> send
        byte[] chatTakPacket = createMockProtobuf(90); // Typical server-relayed chat

        // Simulate encryptForRelay(takPacketBytes)
        AppLayerEncryptionManager encManager = AppLayerEncryptionManager.getInstance();
        assertThat(encManager.isEnabled()).isTrue();

        byte[] encrypted = encManager.encrypt(chatTakPacket);
        assertThat(encrypted).isNotNull();
        assertThat(encrypted[0]).isEqualTo(AppLayerEncryptionManager.APP_LAYER_MARKER);

        // Receiving device decrypts
        byte[] decrypted = receiver.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(chatTakPacket);
    }

    @Test
    void serverRelay_shouldEncryptPLIRelayPacket() {
        // Simulate onCotEvent PLI relay: server sends PLI CoT -> build TAKPacket -> encrypt -> send
        byte[] pliTakPacket = createMockProtobuf(110); // Typical server-relayed PLI

        AppLayerEncryptionManager encManager = AppLayerEncryptionManager.getInstance();
        byte[] encrypted = encManager.encrypt(pliTakPacket);

        assertThat(encrypted).isNotNull();
        assertThat(encrypted.length).isLessThanOrEqualTo(MAX_SINGLE_PACKET);

        byte[] decrypted = receiver.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(pliTakPacket);
    }

    @Test
    void serverRelay_shouldPassThroughWhenEncryptionDisabled() {
        // Simulate encryptForRelay when encryption is disabled
        sender.setEnabled(false);

        byte[] takPacketBytes = createMockProtobuf(100);

        // encryptForRelay logic: if !isEnabled(), return original
        AppLayerEncryptionManager encManager = AppLayerEncryptionManager.getInstance();
        byte[] result;
        if (!encManager.isEnabled()) {
            result = takPacketBytes;
        } else {
            result = encManager.encrypt(takPacketBytes);
        }

        assertThat(result).isSameAs(takPacketBytes);
    }

    // ========================================================================
    // Cross-Path Tests (full round-trip through send + receive)
    // ========================================================================

    @Test
    void fullRoundTrip_PLISendAndReceive() {
        // === SEND SIDE (MeshtasticMapComponent.handleSelfPLI) ===
        // 1. Build TAKPacket protobuf
        byte[] pliProtobuf = createMockProtobuf(95);

        // 2. encryptPayloadIfEnabled
        byte[] sendPayload;
        if (sender.isEnabled()) {
            sendPayload = sender.encrypt(pliProtobuf);
        } else {
            sendPayload = pliProtobuf;
        }
        assertThat(sendPayload).isNotNull();

        // 3. sendOrChunkPayload (fits single packet)
        assertThat(sendPayload.length).isLessThanOrEqualTo(MAX_SINGLE_PACKET);

        // === RECEIVE SIDE (MeshtasticReceiver port 72) ===
        // 4. Check for app-layer encryption
        byte[] takBytes = sendPayload;
        if (AppLayerEncryptionManager.isAppLayerEncrypted(takBytes)) {
            byte[] decrypted = receiver.decrypt(takBytes);
            assertThat(decrypted).isNotNull();
            takBytes = decrypted;
        }

        // 5. TAKPacket.ADAPTER.decode(takBytes) would succeed
        assertThat(takBytes).isEqualTo(pliProtobuf);
    }

    @Test
    void fullRoundTrip_GenericCotSendAndReceive() {
        // === SEND SIDE (MeshtasticMapComponent.handleGenericCotEvent) ===
        String cotXml = "<event version='2.0' uid='generic-1' type='b-m-p-s-p-i'>"
                + "<point lat='38.9' lon='-77.0' hae='50' ce='35' le='99'/>"
                + "<detail><link uid='src-uid' type='a-f-G'/></detail>"
                + "</event>";

        // 1. Compress to zlib
        byte[] compressed = zlibCompress(cotXml.getBytes(StandardCharsets.UTF_8));

        // 2. encryptPayloadIfEnabled
        byte[] sendPayload;
        if (sender.isEnabled()) {
            sendPayload = sender.encrypt(compressed);
        } else {
            sendPayload = compressed;
        }
        assertThat(sendPayload).isNotNull();

        // 3. Sent via ATAK_FORWARDER port (257)

        // === RECEIVE SIDE (MeshtasticReceiver port 257) ===
        byte[] raw = sendPayload;

        // 4. Check for app-layer encryption
        byte[] processedData;
        if (AppLayerEncryptionManager.isAppLayerEncrypted(raw)) {
            processedData = receiver.decrypt(raw);
            assertThat(processedData).isNotNull();
        } else {
            processedData = raw;
        }

        // 5. Decompress zlib -> XML
        byte[] decompressed = zlibDecompress(processedData);
        String recoveredXml = new String(decompressed, StandardCharsets.UTF_8);
        assertThat(recoveredXml).isEqualTo(cotXml);
    }

    @Test
    void fullRoundTrip_mixedEncryptedAndUnencryptedDevices() {
        // Device A sends encrypted, Device B and C receive
        byte[] plaintext = createMockProtobuf(80);
        byte[] encrypted = sender.encrypt(plaintext);

        // Device B has encryption enabled with same key — can decrypt
        assertThat(AppLayerEncryptionManager.isAppLayerEncrypted(encrypted)).isTrue();
        byte[] decryptedB = receiver.decrypt(encrypted);
        assertThat(decryptedB).isEqualTo(plaintext);

        // Device C has no encryption — isAppLayerEncrypted returns true but decrypt fails
        resetSingleton();
        AppLayerEncryptionManager noKeyReceiver = AppLayerEncryptionManager.getInstance();
        // No key loaded
        assertThat(AppLayerEncryptionManager.isAppLayerEncrypted(encrypted)).isTrue();
        byte[] decryptedC = noKeyReceiver.decrypt(encrypted);
        assertThat(decryptedC).isNull(); // Cannot decrypt without key
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Create mock protobuf-like bytes (starts with valid protobuf field tag).
     */
    private byte[] createMockProtobuf(int size) {
        byte[] data = new byte[size];
        new SecureRandom().nextBytes(data);
        // First byte: protobuf field 1, wire type 0 (varint) = 0x08
        data[0] = 0x08;
        return data;
    }

    /**
     * Compress data using zlib (matching MeshtasticMapComponent.zlibCompress).
     */
    private byte[] zlibCompress(byte[] data) {
        try {
            Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
            deflater.setInput(data);
            deflater.finish();

            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
            byte[] buffer = new byte[1024];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                out.write(buffer, 0, count);
            }
            deflater.end();
            out.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Compression failed", e);
        }
    }

    /**
     * Decompress zlib data (matching MeshtasticReceiver.decompressToXml logic).
     */
    private byte[] zlibDecompress(byte[] data) {
        try {
            Inflater inflater = new Inflater();
            inflater.setInput(data);

            ByteArrayOutputStream out = new ByteArrayOutputStream(data.length * 2);
            byte[] buffer = new byte[1024];
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0 && inflater.needsInput()) break;
                out.write(buffer, 0, count);
            }
            inflater.end();
            out.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Decompression failed", e);
        }
    }
}
