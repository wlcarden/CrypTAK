package com.atakmap.android.meshtastic.encryption;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Wire format robustness (fuzzing) tests: verify that the decrypt path never crashes
 * when given corrupted, truncated, or garbage input.
 *
 * <p>On a real mesh network, packets will be corrupted, truncated, and interleaved
 * with non-encrypted traffic. The decrypt path must always return null gracefully
 * and never throw an exception to the caller.</p>
 */
class WireFormatFuzzTest {

    private AppLayerEncryptionManager manager;

    @BeforeEach
    void setUp() {
        manager = createFreshManager();
        manager.loadKey("fuzz-test-psk-2025");
    }

    // ========================================================================
    // Truncated / Minimal Input Tests
    // ========================================================================

    @Test
    void decrypt_emptyByteArray() {
        byte[] result = manager.decrypt(new byte[0]);
        assertThat(result).isNull();
    }

    @Test
    void decrypt_singleByte() {
        // Just the magic byte, nothing else
        byte[] result = manager.decrypt(new byte[]{(byte) 0xFE});
        assertThat(result).isNull();
    }

    @Test
    void decrypt_magicPlusVersion_noPayload() {
        // V1 header only, no IV or ciphertext
        byte[] result = manager.decrypt(new byte[]{(byte) 0xFE, 0x01});
        assertThat(result).isNull();
    }

    @Test
    void decrypt_v2Header_truncatedEpoch() {
        // V2 header with incomplete epoch field
        byte[] result = manager.decrypt(new byte[]{(byte) 0xFE, 0x02, 0x00, 0x00});
        assertThat(result).isNull();
    }

    @Test
    void decrypt_validHeader_truncatedIV() {
        // Valid V1 header + only 6 bytes of IV (needs 12)
        byte[] data = new byte[8]; // marker(1) + version(1) + partial IV(6)
        data[0] = (byte) 0xFE;
        data[1] = 0x01;
        // bytes 2-7 are partial IV (zeros)
        byte[] result = manager.decrypt(data);
        assertThat(result).isNull();
    }

    @Test
    void decrypt_validHeader_truncatedCiphertext() {
        // Valid V1 header + full IV + only 2 bytes (less than 16-byte GCM tag)
        byte[] data = new byte[1 + 1 + 12 + 2]; // 16 bytes total, needs at least 30
        data[0] = (byte) 0xFE;
        data[1] = 0x01;
        new SecureRandom().nextBytes(Arrays.copyOfRange(data, 2, 14)); // random IV
        byte[] result = manager.decrypt(data);
        assertThat(result).isNull();
    }

    // ========================================================================
    // Corrupted Ciphertext Tests
    // ========================================================================

    @Test
    void decrypt_validHeader_corruptedCiphertext() {
        // Create a valid encrypted message, then corrupt the ciphertext body
        byte[] plaintext = "corruption test".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = manager.encrypt(plaintext);
        assertThat(encrypted).isNotNull();

        // Flip random bits in the ciphertext area (after header + IV)
        int ciphertextStart = 1 + 1 + 12; // marker + version + IV
        SecureRandom rng = new SecureRandom();
        for (int i = 0; i < 5; i++) {
            int pos = ciphertextStart + rng.nextInt(encrypted.length - ciphertextStart);
            encrypted[pos] ^= (byte) (1 << rng.nextInt(8));
        }

        // Should return null (GCM auth tag fails), no exception
        byte[] result = manager.decrypt(encrypted);
        assertThat(result).isNull();
    }

    @Test
    void decrypt_validHeader_corruptedIV() {
        // Create a valid encrypted message, then corrupt the IV
        byte[] plaintext = "iv corruption test".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = manager.encrypt(plaintext);
        assertThat(encrypted).isNotNull();

        // Flip bits in the IV only (bytes 2-13)
        encrypted[5] ^= 0xFF;
        encrypted[8] ^= 0x42;

        // Should return null (GCM decryption fails), no exception
        byte[] result = manager.decrypt(encrypted);
        assertThat(result).isNull();
    }

    @Test
    void decrypt_validHeader_corruptedAuthTag() {
        // Create a valid encrypted message, then corrupt the last byte (in the auth tag)
        byte[] plaintext = "auth tag test".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = manager.encrypt(plaintext);
        assertThat(encrypted).isNotNull();

        // Flip the last byte (which is in the GCM auth tag area)
        encrypted[encrypted.length - 1] ^= 0xFF;

        // Should return null, no exception
        byte[] result = manager.decrypt(encrypted);
        assertThat(result).isNull();
    }

    // ========================================================================
    // Unknown / Invalid Format Tests
    // ========================================================================

    @Test
    void decrypt_unknownVersion() {
        // [0xFE, 0x03, ...] — version 3 doesn't exist
        byte[] data = new byte[40];
        new SecureRandom().nextBytes(data);
        data[0] = (byte) 0xFE;
        data[1] = 0x03; // unknown version

        byte[] result = manager.decrypt(data);
        assertThat(result).isNull();
    }

    @Test
    void decrypt_noMagicByte_validProtobuf() {
        // Bytes that look like a valid unencrypted protobuf (first byte != 0xFE)
        byte[] protobuf = new byte[]{0x08, 0x01, 0x12, 0x05, 0x48, 0x65, 0x6C, 0x6C, 0x6F};

        byte[] result = manager.decrypt(protobuf);
        assertThat(result).isNull();
    }

    @Test
    void decrypt_legacyMarker_notAppLayer() {
        // Legacy 0xEE marker should not be treated as app-layer encrypted
        byte[] data = new byte[50];
        new SecureRandom().nextBytes(data);
        data[0] = (byte) 0xEE;

        byte[] result = manager.decrypt(data);
        assertThat(result).isNull();
    }

    // ========================================================================
    // Garbage Input Tests
    // ========================================================================

    @Test
    void decrypt_allZeros() {
        byte[] result = manager.decrypt(new byte[100]);
        assertThat(result).isNull();
    }

    @Test
    void decrypt_allOnes() {
        byte[] data = new byte[100];
        Arrays.fill(data, (byte) 0xFF);
        byte[] result = manager.decrypt(data);
        assertThat(result).isNull();
    }

    @Test
    void decrypt_randomGarbage_1000iterations() {
        SecureRandom rng = new SecureRandom();

        for (int i = 0; i < 1000; i++) {
            int length = 1 + rng.nextInt(500); // 1-500 bytes
            byte[] garbage = new byte[length];
            rng.nextBytes(garbage);

            byte[] result = manager.decrypt(garbage);
            assertThat(result).as("Random garbage iteration %d (length %d) should return null", i, length)
                    .isNull();
        }
    }

    // ========================================================================
    // V2-Specific Fuzz Tests
    // ========================================================================

    @Test
    void decrypt_v2_truncatedBeforeIV() {
        // V2: marker(1) + version(1) + epoch(4) but no IV
        byte[] data = new byte[]{(byte) 0xFE, 0x02, 0x00, 0x00, 0x00, 0x05};
        byte[] result = manager.decrypt(data);
        assertThat(result).isNull();
    }

    @Test
    void decrypt_v2_corruptedEpoch_stillGraceful() {
        // Encrypt a valid V2 message, then corrupt the epoch field
        manager.enableEpochRotation(3600000);
        byte[] plaintext = "v2 epoch corrupt".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = manager.encrypt(plaintext);
        assertThat(encrypted).isNotNull();

        // Set epoch to a very high value (epoch 999999)
        encrypted[2] = 0x00;
        encrypted[3] = 0x0F;
        encrypted[4] = 0x42;
        encrypted[5] = 0x3F;

        // Should attempt to derive key for epoch 999999 from base —
        // the derived key won't match, so GCM auth fails -> null
        byte[] result = manager.decrypt(encrypted);
        assertThat(result).isNull();
    }

    @Test
    void decrypt_v2_negativeEpoch_graceful() {
        // V2 with epoch bytes that decode to a negative int
        byte[] data = new byte[40];
        new SecureRandom().nextBytes(data);
        data[0] = (byte) 0xFE;
        data[1] = 0x02;
        data[2] = (byte) 0xFF; // This makes epoch negative when read as signed int
        data[3] = (byte) 0xFF;
        data[4] = (byte) 0xFF;
        data[5] = (byte) 0xFF;

        byte[] result = manager.decrypt(data);
        assertThat(result).isNull();
    }

    @Test
    void decrypt_v1_exactMinimumSize_noPlaintext() {
        // Exactly 30 bytes: marker(1) + version(1) + IV(12) + tag(16)
        // This is a valid V1 message with zero plaintext bytes — but
        // the GCM tag won't validate because we fabricated it
        byte[] data = new byte[30];
        data[0] = (byte) 0xFE;
        data[1] = 0x01;
        new SecureRandom().nextBytes(data); // fill with random
        data[0] = (byte) 0xFE;  // restore header
        data[1] = 0x01;

        byte[] result = manager.decrypt(data);
        assertThat(result).isNull();
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private AppLayerEncryptionManager createFreshManager() {
        try {
            java.lang.reflect.Field instanceField =
                    AppLayerEncryptionManager.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            instanceField.set(null, null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to reset singleton", e);
        }
        return AppLayerEncryptionManager.getInstance();
    }
}
