package com.atakmap.android.meshtastic.encryption;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Unit tests for AppLayerEncryptionManager.
 *
 * Tests encrypt/decrypt round-trip, key management, backward compatibility,
 * tamper detection, and wire format compliance.
 */
class AppLayerEncryptionManagerTest {

    private AppLayerEncryptionManager manager;

    @BeforeEach
    void setUp() {
        // Use reflection to reset the singleton for test isolation
        try {
            java.lang.reflect.Field instanceField =
                    AppLayerEncryptionManager.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            instanceField.set(null, null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to reset singleton", e);
        }

        manager = AppLayerEncryptionManager.getInstance();
    }

    // ========================================================================
    // Encrypt/Decrypt Round-Trip Tests
    // ========================================================================

    @Test
    void shouldEncryptAndDecryptRoundTrip() {
        // Given
        manager.loadKey("test-psk-12345");
        byte[] plaintext = "Hello, Meshtastic!".getBytes(StandardCharsets.UTF_8);

        // When
        byte[] encrypted = manager.encrypt(plaintext);
        byte[] decrypted = manager.decrypt(encrypted);

        // Then
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void shouldEncryptAndDecryptEmptyishPayload() {
        // Given - single byte payload (minimum meaningful data)
        manager.loadKey("test-psk");
        byte[] plaintext = new byte[]{0x42};

        // When
        byte[] encrypted = manager.encrypt(plaintext);
        byte[] decrypted = manager.decrypt(encrypted);

        // Then
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void shouldEncryptAndDecryptLargePayload() {
        // Given - simulate a large CoT event (400+ bytes, typical for chat)
        manager.loadKey("large-payload-test");
        byte[] plaintext = new byte[500];
        new SecureRandom().nextBytes(plaintext);

        // When
        byte[] encrypted = manager.encrypt(plaintext);
        byte[] decrypted = manager.decrypt(encrypted);

        // Then
        assertThat(decrypted).isEqualTo(plaintext);
        assertThat(encrypted.length).isEqualTo(plaintext.length + AppLayerEncryptionManager.ENCRYPTION_OVERHEAD);
    }

    @Test
    void shouldEncryptAndDecryptTypicalPLISize() {
        // Given - ~190 bytes typical for PLI protobuf
        manager.loadKey("pli-test-key");
        byte[] plaintext = new byte[190];
        new SecureRandom().nextBytes(plaintext);

        // When
        byte[] encrypted = manager.encrypt(plaintext);
        byte[] decrypted = manager.decrypt(encrypted);

        // Then
        assertThat(decrypted).isEqualTo(plaintext);
        // PLI + encryption overhead should still fit in single LoRa packet (231 bytes)
        assertThat(encrypted.length).isEqualTo(190 + 30); // 220 bytes
        assertThat(encrypted.length).isLessThanOrEqualTo(231);
    }

    @Test
    void shouldProduceDifferentCiphertextForSamePlaintext() {
        // Given - same plaintext encrypted twice should produce different ciphertext
        // (due to random IV)
        manager.loadKey("nonce-test");
        byte[] plaintext = "same message".getBytes(StandardCharsets.UTF_8);

        // When
        byte[] encrypted1 = manager.encrypt(plaintext);
        byte[] encrypted2 = manager.encrypt(plaintext);

        // Then
        assertThat(encrypted1).isNotEqualTo(encrypted2);
        // But both should decrypt to the same plaintext
        assertThat(manager.decrypt(encrypted1)).isEqualTo(plaintext);
        assertThat(manager.decrypt(encrypted2)).isEqualTo(plaintext);
    }

    // ========================================================================
    // Wire Format Tests
    // ========================================================================

    @Test
    void shouldProduceCorrectWireFormat() {
        // Given
        manager.loadKey("format-test");
        byte[] plaintext = "test".getBytes(StandardCharsets.UTF_8);

        // When
        byte[] encrypted = manager.encrypt(plaintext);

        // Then
        assertThat(encrypted).isNotNull();
        assertThat(encrypted[0]).isEqualTo(AppLayerEncryptionManager.APP_LAYER_MARKER); // 0xFE
        assertThat(encrypted[1]).isEqualTo(AppLayerEncryptionManager.ENCRYPTION_VERSION_1); // 0x01
        // Bytes 2-13 are the IV (12 bytes)
        // Bytes 14+ are ciphertext + GCM auth tag
        assertThat(encrypted.length).isEqualTo(
                1 + 1 + 12 + plaintext.length + 16); // marker + version + IV + ciphertext + tag
    }

    @Test
    void shouldHaveCorrectOverheadConstant() {
        // The ENCRYPTION_OVERHEAD constant should match actual overhead
        assertThat(AppLayerEncryptionManager.ENCRYPTION_OVERHEAD).isEqualTo(30);
        assertThat(AppLayerEncryptionManager.getOverhead()).isEqualTo(30);
    }

    @Test
    void shouldUseCorrectMarkerByte() {
        // 0xFE should not collide with protobuf field tags, 0xEE (legacy), or 0xC2 (codec2)
        assertThat(AppLayerEncryptionManager.APP_LAYER_MARKER).isEqualTo((byte) 0xFE);
    }

    // ========================================================================
    // Detection Tests
    // ========================================================================

    @Test
    void shouldDetectAppLayerEncryptedData() {
        // Given
        manager.loadKey("detect-test");
        byte[] plaintext = "test".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = manager.encrypt(plaintext);

        // When/Then
        assertThat(AppLayerEncryptionManager.isAppLayerEncrypted(encrypted)).isTrue();
    }

    @Test
    void shouldNotDetectUnencryptedDataAsEncrypted() {
        // Given
        byte[] protobuf = new byte[]{0x08, 0x01, 0x10, 0x02}; // typical protobuf start
        byte[] zlib = new byte[]{0x78, (byte) 0x9C}; // zlib magic number
        byte[] legacyEncrypted = new byte[]{(byte) 0xEE, 0x01, 0x02}; // old 0xEE marker

        // When/Then
        assertThat(AppLayerEncryptionManager.isAppLayerEncrypted(protobuf)).isFalse();
        assertThat(AppLayerEncryptionManager.isAppLayerEncrypted(zlib)).isFalse();
        assertThat(AppLayerEncryptionManager.isAppLayerEncrypted(legacyEncrypted)).isFalse();
        assertThat(AppLayerEncryptionManager.isAppLayerEncrypted(null)).isFalse();
        assertThat(AppLayerEncryptionManager.isAppLayerEncrypted(new byte[0])).isFalse();
        assertThat(AppLayerEncryptionManager.isAppLayerEncrypted(new byte[]{(byte) 0xFE})).isFalse(); // too short
    }

    // ========================================================================
    // Key Management Tests
    // ========================================================================

    @Test
    void shouldFailEncryptionWithNoKey() {
        // Given - no key loaded

        // When
        byte[] result = manager.encrypt("test".getBytes(StandardCharsets.UTF_8));

        // Then
        assertThat(result).isNull();
    }

    @Test
    void shouldFailDecryptionWithNoKey() {
        // Given - encrypt with key, then clear it
        manager.loadKey("temp-key");
        byte[] encrypted = manager.encrypt("test".getBytes(StandardCharsets.UTF_8));
        manager.clearKeys();

        // When
        byte[] result = manager.decrypt(encrypted);

        // Then
        assertThat(result).isNull();
    }

    @Test
    void shouldFailDecryptionWithWrongKey() {
        // Given
        manager.loadKey("correct-key");
        byte[] encrypted = manager.encrypt("secret message".getBytes(StandardCharsets.UTF_8));

        // Load different key
        manager.loadKey("wrong-key");

        // When
        byte[] result = manager.decrypt(encrypted);

        // Then
        assertThat(result).isNull();
    }

    @Test
    void shouldDecryptWithSamePSK() {
        // Given - simulate two devices with same PSK
        manager.loadKey("shared-psk-abc123");
        byte[] plaintext = "tactical data".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = manager.encrypt(plaintext);

        // Simulate second device: reset and load same PSK
        manager.clearKeys();
        manager.loadKey("shared-psk-abc123");

        // When
        byte[] decrypted = manager.decrypt(encrypted);

        // Then
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void shouldLoadKeyFromBytes() {
        // Given
        byte[] keyMaterial = new byte[32];
        new SecureRandom().nextBytes(keyMaterial);
        manager.loadKeyFromBytes(keyMaterial);

        byte[] plaintext = "raw key test".getBytes(StandardCharsets.UTF_8);

        // When
        byte[] encrypted = manager.encrypt(plaintext);
        byte[] decrypted = manager.decrypt(encrypted);

        // Then
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void shouldRejectInvalidKeyMaterial() {
        // Given - wrong key length
        manager.loadKeyFromBytes(new byte[16]); // 128-bit, not 256-bit

        // When
        byte[] result = manager.encrypt("test".getBytes(StandardCharsets.UTF_8));

        // Then
        assertThat(result).isNull();
    }

    @Test
    void shouldNotReloadSamePSK() {
        // Given
        manager.loadKey("same-psk");
        byte[] plaintext = "test".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted1 = manager.encrypt(plaintext);

        // When - loading same PSK should be a no-op (key not re-derived)
        manager.loadKey("same-psk");
        byte[] decrypted = manager.decrypt(encrypted1);

        // Then
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void shouldClearKeysFromMemory() {
        // Given
        manager.loadKey("to-be-cleared");
        assertThat(manager.hasKey()).isTrue();

        // When
        manager.clearKeys();

        // Then
        assertThat(manager.hasKey()).isFalse();
        assertThat(manager.encrypt("test".getBytes())).isNull();
    }

    // ========================================================================
    // Enable/Disable Tests
    // ========================================================================

    @Test
    void shouldReportEnabledStateCorrectly() {
        // Initially disabled
        assertThat(manager.isEnabled()).isFalse();

        // Enable without key - still effectively disabled
        manager.setEnabled(true);
        assertThat(manager.isEnabled()).isFalse();

        // Load key - now truly enabled
        manager.loadKey("test-key");
        manager.setEnabled(true);
        assertThat(manager.isEnabled()).isTrue();

        // Disable
        manager.setEnabled(false);
        assertThat(manager.isEnabled()).isFalse();
    }

    // ========================================================================
    // Tamper Detection Tests
    // ========================================================================

    @Test
    void shouldDetectTamperedCiphertext() {
        // Given
        manager.loadKey("tamper-test");
        byte[] encrypted = manager.encrypt("sensitive data".getBytes(StandardCharsets.UTF_8));

        // When - flip a bit in the ciphertext (after header)
        encrypted[20] ^= 0xFF;

        // Then - decryption should fail (GCM auth tag verification)
        byte[] result = manager.decrypt(encrypted);
        assertThat(result).isNull();
    }

    @Test
    void shouldDetectTamperedIV() {
        // Given
        manager.loadKey("iv-tamper-test");
        byte[] encrypted = manager.encrypt("test data".getBytes(StandardCharsets.UTF_8));

        // When - modify the IV
        encrypted[5] ^= 0xFF;

        // Then - decryption should fail
        byte[] result = manager.decrypt(encrypted);
        assertThat(result).isNull();
    }

    @Test
    void shouldRejectTruncatedData() {
        // Given
        manager.loadKey("truncate-test");
        byte[] encrypted = manager.encrypt("test".getBytes(StandardCharsets.UTF_8));

        // When - truncate to less than minimum size
        byte[] truncated = Arrays.copyOf(encrypted, 15); // less than 30 byte minimum

        // Then
        byte[] result = manager.decrypt(truncated);
        assertThat(result).isNull();
    }

    @Test
    void shouldRejectWrongVersionByte() {
        // Given
        manager.loadKey("version-test");
        byte[] encrypted = manager.encrypt("test".getBytes(StandardCharsets.UTF_8));

        // When - change version byte to unsupported version
        encrypted[1] = 0x99;

        // Then
        byte[] result = manager.decrypt(encrypted);
        assertThat(result).isNull();
    }

    @Test
    void shouldRejectWrongMarkerByte() {
        // Given
        manager.loadKey("marker-test");
        byte[] encrypted = manager.encrypt("test".getBytes(StandardCharsets.UTF_8));

        // When - change marker byte
        encrypted[0] = (byte) 0xEE; // legacy marker

        // Then
        byte[] result = manager.decrypt(encrypted);
        assertThat(result).isNull();
    }

    // ========================================================================
    // Edge Cases
    // ========================================================================

    @Test
    void shouldHandleNullInputs() {
        manager.loadKey("null-test");

        assertThat(manager.encrypt(null)).isNull();
        assertThat(manager.encrypt(new byte[0])).isNull();
        assertThat(manager.decrypt(null)).isNull();
    }

    @Test
    void shouldHandleNullPSK() {
        manager.loadKey(null);
        assertThat(manager.hasKey()).isFalse();

        manager.loadKey("");
        assertThat(manager.hasKey()).isFalse();
    }

    // ========================================================================
    // Epoch Rotation Tests
    // ========================================================================

    @Test
    void shouldTrackEpochNumber() {
        // Given
        manager.loadKey("epoch-test");

        // When
        assertThat(manager.getCurrentEpoch()).isEqualTo(0);
    }

    @Test
    void shouldSupportEnableDisableEpochRotation() {
        // Given
        manager.loadKey("epoch-toggle-test");

        // When
        manager.enableEpochRotation(3600000); // 1 hour
        manager.disableEpochRotation();

        // Then - should still encrypt/decrypt
        byte[] plaintext = "epoch test".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = manager.encrypt(plaintext);
        byte[] decrypted = manager.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    // ========================================================================
    // Message Size Budget Tests
    // ========================================================================

    @Test
    void shouldKeepPLIUnderSinglePacketLimit() {
        // PLI is typically ~190 bytes in protobuf format
        // With 30 bytes overhead: 190 + 30 = 220 bytes
        // LoRa single-packet limit: 231 bytes
        manager.loadKey("pli-budget-test");
        byte[] pliPayload = new byte[190];
        byte[] encrypted = manager.encrypt(pliPayload);

        assertThat(encrypted.length).isLessThanOrEqualTo(231);
    }

    @Test
    void shouldKeepChatUnderSinglePacketLimit() {
        // Chat is typically ~200 bytes in protobuf format
        // With 30 bytes overhead: 200 + 30 = 230 bytes
        // LoRa single-packet limit: 231 bytes
        manager.loadKey("chat-budget-test");
        byte[] chatPayload = new byte[200];
        byte[] encrypted = manager.encrypt(chatPayload);

        assertThat(encrypted.length).isLessThanOrEqualTo(231);
    }

    @Test
    void shouldExceedSinglePacketLimitForLargePayloads() {
        // Payloads > 201 bytes will exceed 231 after encryption
        // These need fountain coding
        manager.loadKey("overflow-test");
        byte[] largePayload = new byte[202];
        byte[] encrypted = manager.encrypt(largePayload);

        assertThat(encrypted.length).isGreaterThan(231);
    }
}
