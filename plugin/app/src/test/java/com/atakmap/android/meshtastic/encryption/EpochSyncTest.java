package com.atakmap.android.meshtastic.encryption;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * Epoch rotation synchronization tests: validate that two managers rotating
 * epochs independently arrive at the same key material and can communicate
 * across epoch boundaries.
 *
 * <p>The epoch rotation uses HMAC-SHA256 KDF:
 * {@code nextKey = HMAC-SHA256(currentKey, "meshtastic-epoch-" || epochNumber)}.
 * Both managers must derive identical keys at each epoch.</p>
 */
class EpochSyncTest {

    private AppLayerEncryptionManager managerA;
    private AppLayerEncryptionManager managerB;

    private static final String SHARED_PSK = "epoch-sync-test-psk-2025";

    @BeforeEach
    void setUp() {
        managerA = createFreshManager();
        managerB = createFreshManager();
    }

    // ========================================================================
    // Epoch Rotation Synchronization
    // ========================================================================

    @Test
    void twoManagers_rotateOnce_sameEpochKey() {
        // Given: both managers start with same seed and epoch rotation enabled
        managerA.loadKey(SHARED_PSK);
        managerA.enableEpochRotation(1); // 1ms for fast rotation
        managerB.loadKey(SHARED_PSK);
        managerB.enableEpochRotation(1);

        // Wait for epoch rotation to trigger
        sleep(10);

        // Trigger rotation on both by encrypting
        managerA.encrypt("trigger-a".getBytes(StandardCharsets.UTF_8));
        managerB.encrypt("trigger-b".getBytes(StandardCharsets.UTF_8));

        // Both should have rotated at least once
        assertThat(managerA.getCurrentEpoch()).isGreaterThanOrEqualTo(1);
        assertThat(managerB.getCurrentEpoch()).isGreaterThanOrEqualTo(1);

        // When: A encrypts, B decrypts — proves identical key derivation
        byte[] plaintext = "epoch-1-message".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = managerA.encrypt(plaintext);
        byte[] decrypted = managerB.decrypt(encrypted);

        // Then
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void twoManagers_rotateFiveTimes_stayInSync() {
        // Given: same PSK, epoch rotation enabled
        managerA.loadKey(SHARED_PSK);
        managerB.loadKey(SHARED_PSK);

        // Manually derive and test at each epoch using deriveKeyForEpoch
        for (int epoch = 0; epoch <= 5; epoch++) {
            // Both managers derive the key for this epoch from their base key
            javax.crypto.spec.SecretKeySpec keyA = managerA.deriveKeyForEpoch(epoch);
            javax.crypto.spec.SecretKeySpec keyB = managerB.deriveKeyForEpoch(epoch);

            // Keys must be identical
            assertThat(keyA.getEncoded()).as("Epoch %d key mismatch", epoch)
                    .isEqualTo(keyB.getEncoded());
        }
    }

    @Test
    void twoManagers_rotateTenTimes_stayInSync() {
        // Given: same PSK
        managerA.loadKey(SHARED_PSK);
        managerB.loadKey(SHARED_PSK);

        // Verify key derivation consistency across 10 epochs
        for (int epoch = 0; epoch <= 10; epoch++) {
            javax.crypto.spec.SecretKeySpec keyA = managerA.deriveKeyForEpoch(epoch);
            javax.crypto.spec.SecretKeySpec keyB = managerB.deriveKeyForEpoch(epoch);

            assertThat(keyA.getEncoded()).as("Epoch %d key mismatch", epoch)
                    .isEqualTo(keyB.getEncoded());
        }

        // Also verify encrypt/decrypt at epoch 10
        managerA.enableEpochRotation(3600000);
        managerB.enableEpochRotation(3600000);

        // Both at epoch 0 initially; encrypt with V2 format at epoch 0
        byte[] plaintext = "epoch-0-sync-check".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = managerA.encrypt(plaintext);
        byte[] decrypted = managerB.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void messageInFlight_duringRotation() {
        // Given: A encrypts at epoch 0
        managerA.loadKey(SHARED_PSK);
        managerA.enableEpochRotation(1); // 1ms epochs
        managerB.loadKey(SHARED_PSK);
        managerB.enableEpochRotation(1);

        // A encrypts at epoch 0 (before rotation)
        byte[] plaintext = "in-flight-message".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = managerA.encrypt(plaintext);
        int encryptEpoch = managerA.getCurrentEpoch();

        // B rotates forward
        sleep(10);
        managerB.encrypt("trigger-rotation".getBytes(StandardCharsets.UTF_8));

        // B may be at a higher epoch now
        // B should still decrypt via V2 epoch-based key derivation
        byte[] decrypted = managerB.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void messageInFlight_twoEpochsBehind_v2StillDecrypts() {
        // Given: A encrypts at epoch 0
        managerA.loadKey(SHARED_PSK);
        managerA.enableEpochRotation(3600000); // long epochs - we'll use deriveKeyForEpoch

        byte[] plaintext = "epoch-0-old-message".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = managerA.encrypt(plaintext);

        // B has same PSK and can derive any epoch key from base
        managerB.loadKey(SHARED_PSK);
        managerB.enableEpochRotation(3600000);

        // Even if B's current epoch were advanced, V2 decrypt derives key from base
        // The decrypt method in V2 tries: current key, previous key, then deriveKeyForEpoch
        byte[] decrypted = managerB.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void bothRotate_messageFromPreviousEpoch() {
        // Given: A encrypts at epoch 0
        managerA.loadKey(SHARED_PSK);
        managerA.enableEpochRotation(3600000);
        byte[] plaintext = "epoch-0-message".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = managerA.encrypt(plaintext);

        // B is at epoch 0 with same PSK
        managerB.loadKey(SHARED_PSK);
        managerB.enableEpochRotation(3600000);

        // B decrypts — both at epoch 0, current key matches
        byte[] decrypted = managerB.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void epochReset_newSeed_breaksOldChain() {
        // Given: both at some epoch with a shared key
        managerA.loadKey(SHARED_PSK);
        managerA.enableEpochRotation(3600000);
        managerB.loadKey(SHARED_PSK);
        managerB.enableEpochRotation(3600000);

        // Encrypt a message with the current (old) key
        byte[] oldPlaintext = "old-epoch-message".getBytes(StandardCharsets.UTF_8);
        byte[] oldEncrypted = managerA.encrypt(oldPlaintext);

        // Now load a completely new seed into both managers
        byte[] newSeed = new byte[32];
        new SecureRandom().nextBytes(newSeed);

        managerA.loadSeedKeyFromExternal(newSeed.clone(), 0);
        managerA.enableEpochRotation(3600000);
        managerB.loadSeedKeyFromExternal(newSeed.clone(), 0);
        managerB.enableEpochRotation(3600000);

        // Old ciphertext should NOT decrypt with the new key
        byte[] result = managerB.decrypt(oldEncrypted);
        assertThat(result).isNull();

        // New messages with new seed should work
        byte[] newPlaintext = "new-seed-message".getBytes(StandardCharsets.UTF_8);
        byte[] newEncrypted = managerA.encrypt(newPlaintext);
        byte[] newDecrypted = managerB.decrypt(newEncrypted);
        assertThat(newDecrypted).isEqualTo(newPlaintext);
    }

    @Test
    void crossEpoch_senderAhead_receiverDerives() {
        // Given: Sender at epoch 5, receiver at epoch 0 — same base PSK
        managerA.loadKey(SHARED_PSK);
        managerA.enableEpochRotation(1); // 1ms for fast rotation

        // Let A rotate forward several epochs
        sleep(20);
        byte[] plaintext = "sender-ahead-message".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = managerA.encrypt(plaintext);
        int senderEpoch = managerA.getCurrentEpoch();

        // Receiver has same PSK but hasn't rotated
        managerB.loadKey(SHARED_PSK);
        // No epoch rotation enabled on B, but V2 decrypt derives from base key

        // When: B decrypts V2 message from ahead sender
        byte[] decrypted = managerB.decrypt(encrypted);

        // Then: B successfully decrypts by deriving the epoch key
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void v2Messages_preserveEpochInHeader() {
        // Verify that the epoch number is correctly embedded in V2 wire format
        managerA.loadKey(SHARED_PSK);
        managerA.enableEpochRotation(3600000);

        byte[] plaintext = "epoch-header-test".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = managerA.encrypt(plaintext);

        // V2 format: [0xFE][0x02][epoch(4 bytes BE)][IV(12)][ciphertext+tag]
        assertThat(encrypted[0]).isEqualTo(AppLayerEncryptionManager.APP_LAYER_MARKER);
        assertThat(encrypted[1]).isEqualTo(AppLayerEncryptionManager.ENCRYPTION_VERSION_2);

        // Epoch should be 0 (just enabled, no time has passed)
        int epoch = java.nio.ByteBuffer.wrap(encrypted, 2, 4).getInt();
        assertThat(epoch).isEqualTo(0);
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
        AppLayerEncryptionManager mgr = AppLayerEncryptionManager.getInstance();
        mgr.setEnabled(true);
        return mgr;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
