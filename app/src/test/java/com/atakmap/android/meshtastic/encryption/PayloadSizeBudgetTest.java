package com.atakmap.android.meshtastic.encryption;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

/**
 * Payload size budget verification tests: verify that realistic CoT payloads,
 * after encryption, either fit within the Meshtastic MTU or are correctly
 * identified as needing fountain coding.
 *
 * <p>Constants:
 * <ul>
 *   <li>Meshtastic MTU: 231 bytes (conservative)</li>
 *   <li>V1 overhead: 30 bytes [magic(1) + version(1) + IV(12) + auth_tag(16)]</li>
 *   <li>V2 overhead: 34 bytes [magic(1) + version(1) + epoch(4) + IV(12) + auth_tag(16)]</li>
 *   <li>Maximum plaintext for single LoRa packet with V2: 231 - 34 = 197 bytes</li>
 * </ul>
 */
class PayloadSizeBudgetTest {

    private static final int MESHTASTIC_MTU = 231;
    private static final int V1_OVERHEAD = 30;
    private static final int V2_OVERHEAD = 34;
    private static final int MAX_V2_PLAINTEXT = MESHTASTIC_MTU - V2_OVERHEAD; // 197

    private AppLayerEncryptionManager manager;

    @BeforeEach
    void setUp() {
        manager = createFreshManager();
        manager.loadKey("payload-size-test-psk");
    }

    // ========================================================================
    // V2 Payload Size Budget Tests
    // ========================================================================

    @Test
    void typicalPLI_fitsInOnePacket() {
        // Typical compressed PLI: ~120 bytes
        manager.enableEpochRotation(3600000);
        byte[] payload = createPayload(120);

        byte[] encrypted = manager.encrypt(payload);
        assertThat(encrypted).isNotNull();

        // Exact size: 120 + 34 = 154 bytes
        assertThat(encrypted.length).isEqualTo(120 + V2_OVERHEAD);
        assertThat(encrypted.length).isLessThanOrEqualTo(MESHTASTIC_MTU);
    }

    @Test
    void largePLI_fitsInOnePacket() {
        // Large PLI with full precision: ~190 bytes
        manager.enableEpochRotation(3600000);
        byte[] payload = createPayload(190);

        byte[] encrypted = manager.encrypt(payload);
        assertThat(encrypted).isNotNull();

        // 190 + 34 = 224 bytes <= 231
        assertThat(encrypted.length).isEqualTo(190 + V2_OVERHEAD);
        assertThat(encrypted.length).isLessThanOrEqualTo(MESHTASTIC_MTU);
    }

    @Test
    void maxSinglePacket_exactFit() {
        // Maximum plaintext that fits: 197 bytes
        manager.enableEpochRotation(3600000);
        byte[] payload = createPayload(MAX_V2_PLAINTEXT);

        byte[] encrypted = manager.encrypt(payload);
        assertThat(encrypted).isNotNull();

        // 197 + 34 = 231 = exactly MTU
        assertThat(encrypted.length).isEqualTo(MESHTASTIC_MTU);
    }

    @Test
    void overMTU_needsFountainCoding() {
        // One byte over the maximum: 198 bytes
        manager.enableEpochRotation(3600000);
        byte[] payload = createPayload(198);

        byte[] encrypted = manager.encrypt(payload);
        assertThat(encrypted).isNotNull();

        // 198 + 34 = 232 > 231 — needs fountain coding
        assertThat(encrypted.length).isEqualTo(198 + V2_OVERHEAD);
        assertThat(encrypted.length).isGreaterThan(MESHTASTIC_MTU);
    }

    @Test
    void typicalChat_exceedsMTU() {
        // Representative GeoChat: ~300 bytes
        manager.enableEpochRotation(3600000);
        byte[] payload = createPayload(300);

        byte[] encrypted = manager.encrypt(payload);
        assertThat(encrypted).isNotNull();

        // 300 + 34 = 334 bytes, clearly exceeds MTU
        assertThat(encrypted.length).isEqualTo(300 + V2_OVERHEAD);
        assertThat(encrypted.length).isGreaterThan(MESHTASTIC_MTU);
    }

    @Test
    void v1_vs_v2_overheadDifference() {
        // Encrypt same 190-byte payload with V1 and V2 format
        byte[] payload = createPayload(190);

        // V1 (epoch rotation disabled)
        byte[] v1Encrypted = manager.encrypt(payload);
        assertThat(v1Encrypted).isNotNull();
        assertThat(v1Encrypted[1]).isEqualTo(AppLayerEncryptionManager.ENCRYPTION_VERSION_1);
        assertThat(v1Encrypted.length).isEqualTo(190 + V1_OVERHEAD); // 220

        // V2 (epoch rotation enabled)
        manager.enableEpochRotation(3600000);
        byte[] v2Encrypted = manager.encrypt(payload);
        assertThat(v2Encrypted).isNotNull();
        assertThat(v2Encrypted[1]).isEqualTo(AppLayerEncryptionManager.ENCRYPTION_VERSION_2);
        assertThat(v2Encrypted.length).isEqualTo(190 + V2_OVERHEAD); // 224

        // Document the 4-byte epoch cost
        assertThat(v2Encrypted.length - v1Encrypted.length).isEqualTo(4);
    }

    @Test
    void encryptionOverhead_isExactlyPredictable() {
        // V2 mode: for every input size, output = input + 34 (no variable padding)
        manager.enableEpochRotation(3600000);

        int[] testSizes = {1, 50, 100, 150, 197, 200, 231};
        for (int size : testSizes) {
            byte[] payload = createPayload(size);
            byte[] encrypted = manager.encrypt(payload);

            assertThat(encrypted).as("Payload size %d", size).isNotNull();
            assertThat(encrypted.length).as("Payload size %d: overhead must be exactly %d", size, V2_OVERHEAD)
                    .isEqualTo(size + V2_OVERHEAD);
        }
    }

    @Test
    void encryptionOverhead_v1_isExactlyPredictable() {
        // V1 mode: for every input size, output = input + 30 (no variable padding)
        int[] testSizes = {1, 50, 100, 150, 197, 200, 231};
        for (int size : testSizes) {
            byte[] payload = createPayload(size);
            byte[] encrypted = manager.encrypt(payload);

            assertThat(encrypted).as("V1 payload size %d", size).isNotNull();
            assertThat(encrypted.length).as("V1 payload size %d: overhead must be exactly %d", size, V1_OVERHEAD)
                    .isEqualTo(size + V1_OVERHEAD);
        }
    }

    // ========================================================================
    // Overhead Constant Verification
    // ========================================================================

    @Test
    void overheadConstants_matchActualValues() {
        assertThat(AppLayerEncryptionManager.ENCRYPTION_OVERHEAD).isEqualTo(V1_OVERHEAD);
        assertThat(AppLayerEncryptionManager.ENCRYPTION_OVERHEAD_V2).isEqualTo(V2_OVERHEAD);
    }

    @Test
    void getActiveOverhead_reflectsMode() {
        // V1 mode (default)
        assertThat(manager.getActiveOverhead()).isEqualTo(V1_OVERHEAD);

        // V2 mode
        manager.enableEpochRotation(3600000);
        assertThat(manager.getActiveOverhead()).isEqualTo(V2_OVERHEAD);

        // Back to V1
        manager.disableEpochRotation();
        assertThat(manager.getActiveOverhead()).isEqualTo(V1_OVERHEAD);
    }

    @Test
    void maxSinglePacketPlaintext_v1() {
        // V1 max: 231 - 30 = 201 bytes
        int maxV1 = MESHTASTIC_MTU - V1_OVERHEAD;
        assertThat(maxV1).isEqualTo(201);

        byte[] payload = createPayload(maxV1);
        byte[] encrypted = manager.encrypt(payload);
        assertThat(encrypted.length).isEqualTo(MESHTASTIC_MTU);

        // One byte more exceeds MTU
        byte[] overPayload = createPayload(maxV1 + 1);
        byte[] overEncrypted = manager.encrypt(overPayload);
        assertThat(overEncrypted.length).isGreaterThan(MESHTASTIC_MTU);
    }

    @Test
    void maxSinglePacketPlaintext_v2() {
        // V2 max: 231 - 34 = 197 bytes
        manager.enableEpochRotation(3600000);
        assertThat(MAX_V2_PLAINTEXT).isEqualTo(197);

        byte[] payload = createPayload(MAX_V2_PLAINTEXT);
        byte[] encrypted = manager.encrypt(payload);
        assertThat(encrypted.length).isEqualTo(MESHTASTIC_MTU);

        // One byte more exceeds MTU
        byte[] overPayload = createPayload(MAX_V2_PLAINTEXT + 1);
        byte[] overEncrypted = manager.encrypt(overPayload);
        assertThat(overEncrypted.length).isGreaterThan(MESHTASTIC_MTU);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private byte[] createPayload(int size) {
        byte[] data = new byte[size];
        new SecureRandom().nextBytes(data);
        // Make it look like protobuf (first byte = field tag)
        if (size > 0) {
            data[0] = 0x08;
        }
        return data;
    }

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
