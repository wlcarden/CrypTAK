package com.atakmap.android.meshtastic.encryption;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

/**
 * Two-party protocol tests: validate that two independent AppLayerEncryptionManager
 * instances, initialized with the same seed key, can communicate correctly.
 *
 * <p>This is the most critical gap in the single-instance test suite. On a real mesh
 * network, two devices each have their own AppLayerEncryptionManager singleton,
 * initialized with the same PSK, encrypting and decrypting independently.</p>
 */
class TwoPartyProtocolTest {

    private AppLayerEncryptionManager managerA;
    private AppLayerEncryptionManager managerB;

    @BeforeEach
    void setUp() {
        // Create two independent manager instances via singleton reset
        managerA = createFreshManager();
        managerB = createFreshManager();
    }

    // ========================================================================
    // Two-Party Round-Trip Communication
    // ========================================================================

    @Test
    void twoManagers_sameSeed_canDecryptEachOther() {
        // Given: both managers have the same PSK
        managerA.loadKey("shared-secret-key-2025");
        managerB.loadKey("shared-secret-key-2025");

        byte[] plaintextAtoB = "Hello from A".getBytes(StandardCharsets.UTF_8);
        byte[] plaintextBtoA = "Hello from B".getBytes(StandardCharsets.UTF_8);

        // When: A encrypts, B decrypts
        byte[] encryptedAtoB = managerA.encrypt(plaintextAtoB);
        byte[] decryptedByB = managerB.decrypt(encryptedAtoB);

        // Then: B recovers A's plaintext
        assertThat(decryptedByB).isEqualTo(plaintextAtoB);

        // When: B encrypts, A decrypts (reverse direction)
        byte[] encryptedBtoA = managerB.encrypt(plaintextBtoA);
        byte[] decryptedByA = managerA.decrypt(encryptedBtoA);

        // Then: A recovers B's plaintext
        assertThat(decryptedByA).isEqualTo(plaintextBtoA);
    }

    @Test
    void twoManagers_differentSeeds_cannotDecrypt() {
        // Given: managers have different PSKs
        managerA.loadKey("alpha-team-psk");
        managerB.loadKey("bravo-team-psk");

        byte[] plaintext = "classified message".getBytes(StandardCharsets.UTF_8);

        // When: A encrypts
        byte[] encrypted = managerA.encrypt(plaintext);

        // Then: B cannot decrypt (returns null, does NOT crash)
        byte[] result = managerB.decrypt(encrypted);
        assertThat(result).isNull();
    }

    @Test
    void twoManagers_multipleMessages_allDecryptCorrectly() {
        // Given: same PSK
        managerA.loadKey("bulk-message-test");
        managerB.loadKey("bulk-message-test");

        SecureRandom rng = new SecureRandom();
        Set<String> ivSet = new HashSet<>();

        // When: 50 messages from A to B, 50 from B to A, interleaved
        for (int i = 0; i < 50; i++) {
            // A -> B
            byte[] msgA = ("Message A-" + i).getBytes(StandardCharsets.UTF_8);
            byte[] encA = managerA.encrypt(msgA);
            assertThat(encA).isNotNull();

            // Collect IV for nonce collision check (bytes 2-13 for V1)
            byte[] ivA = new byte[12];
            System.arraycopy(encA, 2, ivA, 0, 12);
            ivSet.add(java.util.Base64.getEncoder().encodeToString(ivA));

            byte[] decA = managerB.decrypt(encA);
            assertThat(decA).isEqualTo(msgA);

            // B -> A
            byte[] msgB = ("Message B-" + i).getBytes(StandardCharsets.UTF_8);
            byte[] encB = managerB.encrypt(msgB);
            assertThat(encB).isNotNull();

            byte[] ivB = new byte[12];
            System.arraycopy(encB, 2, ivB, 0, 12);
            ivSet.add(java.util.Base64.getEncoder().encodeToString(ivB));

            byte[] decB = managerA.decrypt(encB);
            assertThat(decB).isEqualTo(msgB);
        }

        // Then: no nonce collisions across all 100 messages
        assertThat(ivSet).hasSize(100);
    }

    @Test
    void twoManagers_emptyPayload_roundTrip() {
        // Given: same PSK
        managerA.loadKey("empty-payload-test");
        managerB.loadKey("empty-payload-test");

        // When: encrypt empty byte array
        byte[] encrypted = managerA.encrypt(new byte[0]);

        // Then: encrypt returns null for empty input (per AppLayerEncryptionManager contract)
        assertThat(encrypted).isNull();
    }

    @Test
    void twoManagers_maxPayload_roundTrip() {
        // Given: same PSK, 231-byte payload (Meshtastic MTU)
        managerA.loadKey("max-payload-test");
        managerB.loadKey("max-payload-test");

        byte[] plaintext = new byte[231];
        new SecureRandom().nextBytes(plaintext);

        // When: A encrypts, B decrypts
        byte[] encrypted = managerA.encrypt(plaintext);
        byte[] decrypted = managerB.decrypt(encrypted);

        // Then: correct round-trip
        assertThat(decrypted).isEqualTo(plaintext);
        // Verify encrypted output size: 231 + 30 (V1 overhead) = 261 bytes
        assertThat(encrypted.length).isEqualTo(231 + AppLayerEncryptionManager.ENCRYPTION_OVERHEAD);
    }

    @Test
    void twoManagers_unicodePayload_roundTrip() {
        // Given: same PSK, UTF-8 protobuf-like payload with multi-byte characters
        managerA.loadKey("unicode-test");
        managerB.loadKey("unicode-test");

        // Simulates a GeoChat TAKPacket with multi-byte unicode chars
        String unicodeMessage = "位置報告: 座標 40°42'N 74°00'W — Ωmega τeam 🎯";
        byte[] plaintext = unicodeMessage.getBytes(StandardCharsets.UTF_8);

        // When: A encrypts, B decrypts
        byte[] encrypted = managerA.encrypt(plaintext);
        byte[] decrypted = managerB.decrypt(encrypted);

        // Then: byte-perfect round-trip
        assertThat(decrypted).isEqualTo(plaintext);
        String recovered = new String(decrypted, StandardCharsets.UTF_8);
        assertThat(recovered).isEqualTo(unicodeMessage);
    }

    @Test
    void twoManagers_sameSeed_rawBytes_canDecryptEachOther() {
        // Given: both managers loaded from the same raw 32-byte key
        byte[] sharedKey = new byte[32];
        new SecureRandom().nextBytes(sharedKey);

        managerA.loadKeyFromBytes(sharedKey.clone());
        managerB.loadKeyFromBytes(sharedKey.clone());

        byte[] plaintext = "raw key two-party test".getBytes(StandardCharsets.UTF_8);

        // When: A encrypts, B decrypts
        byte[] encrypted = managerA.encrypt(plaintext);
        byte[] decrypted = managerB.decrypt(encrypted);

        // Then
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    void twoManagers_base64Import_canDecryptEachOther() {
        // Given: both managers import the same base64 key
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        String base64Key = java.util.Base64.getEncoder().encodeToString(keyBytes);

        assertThat(managerA.importKeyFromBase64(base64Key)).isTrue();
        assertThat(managerB.importKeyFromBase64(base64Key)).isTrue();

        byte[] plaintext = "base64 import two-party test".getBytes(StandardCharsets.UTF_8);

        // When: A encrypts, B decrypts
        byte[] encrypted = managerA.encrypt(plaintext);
        byte[] decrypted = managerB.decrypt(encrypted);

        // Then
        assertThat(decrypted).isEqualTo(plaintext);
    }

    // ========================================================================
    // Restart-Cycle Key Persistence Tests
    // ========================================================================

    /**
     * Simulates the full QR import + restart cycle for a base64 key:
     *   1. Manager A imports a base64 key via importKeyFromBase64() (mimics importKeyAndSave)
     *   2. The base64 string is "stored in preferences"
     *   3. Manager B (fresh, simulating restart) loads the same string via the
     *      initialize() code path: detects valid base64 → importKeyFromBase64()
     *   4. Both managers must produce the same key (cross-decrypt succeeds)
     *
     * This is the exact bug scenario: pre-fix, initialize() would call loadKey()
     * which SHA-256 hashes the string, producing a different key than the raw
     * base64 decode that importKeyFromBase64() used.
     */
    @Test
    void restartCycle_base64Key_sameKeyAfterReload() {
        // Given: generate a random 32-byte key, encode as base64
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        String base64Key = java.util.Base64.getEncoder().encodeToString(keyBytes);

        // Step 1: Manager A imports via base64 (as importKeyAndSave would)
        assertThat(managerA.importKeyFromBase64(base64Key)).isTrue();
        managerA.setEnabled(true);

        // Step 2: A encrypts a message
        byte[] plaintext = "restart-cycle test message".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = managerA.encrypt(plaintext);
        assertThat(encrypted).isNotNull();

        // Step 3: Manager B simulates restart — loads from "stored" base64 string
        // This mirrors what initialize() now does: detect base64, use importKeyFromBase64
        assertThat(managerB.importKeyFromBase64(base64Key)).isTrue();
        managerB.setEnabled(true);

        // Step 4: B must successfully decrypt A's message
        byte[] decrypted = managerB.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(plaintext);

        // And vice versa: B encrypts, A decrypts
        byte[] encrypted2 = managerB.encrypt(plaintext);
        byte[] decrypted2 = managerA.decrypt(encrypted2);
        assertThat(decrypted2).isEqualTo(plaintext);
    }

    /**
     * Verifies that the SHA-256 loadKey() path is NOT used for base64 keys.
     * If loadKey() were used instead of importKeyFromBase64(), the resulting key
     * would be SHA-256(base64_string) instead of the raw decoded bytes.
     * This test ensures those produce different keys (proving the bug would be detectable).
     */
    @Test
    void base64Key_loadKeyVsImportFromBase64_produceDifferentKeys() {
        // Given: a valid base64 key
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        String base64Key = java.util.Base64.getEncoder().encodeToString(keyBytes);

        // Manager A uses the WRONG path: loadKey (SHA-256 of string)
        managerA.loadKey(base64Key);
        managerA.setEnabled(true);

        // Manager B uses the CORRECT path: importKeyFromBase64 (raw bytes)
        assertThat(managerB.importKeyFromBase64(base64Key)).isTrue();
        managerB.setEnabled(true);

        // When: A encrypts
        byte[] plaintext = "cross-path test".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = managerA.encrypt(plaintext);

        // Then: B cannot decrypt (keys are different)
        byte[] decrypted = managerB.decrypt(encrypted);
        assertThat(decrypted).isNull();
    }

    /**
     * Verifies that passphrase-style keys (non-base64) still work correctly
     * across a simulated restart. Both the import and reload paths use loadKey()
     * which applies SHA-256 derivation.
     */
    @Test
    void restartCycle_passphraseKey_sameKeyAfterReload() {
        // Given: a passphrase that is NOT valid base64 decoding to 32 bytes
        String passphrase = "my-team-secret-key-2025!";

        // Step 1: Manager A loads via passphrase
        managerA.loadKey(passphrase);
        managerA.setEnabled(true);

        // Step 2: A encrypts a message
        byte[] plaintext = "passphrase restart test".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = managerA.encrypt(plaintext);
        assertThat(encrypted).isNotNull();

        // Step 3: Manager B simulates restart with same passphrase
        managerB.loadKey(passphrase);
        managerB.setEnabled(true);

        // Step 4: B must decrypt A's message
        byte[] decrypted = managerB.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    /**
     * Verifies that a team of 3 devices using the same base64 key can all
     * communicate after independent restarts (all using importKeyFromBase64).
     */
    @Test
    void restartCycle_threeParty_base64Key_allInteroperate() {
        // Given: same base64 key for all three
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        String base64Key = java.util.Base64.getEncoder().encodeToString(keyBytes);

        AppLayerEncryptionManager managerC = createFreshManager();

        // All three import the same key (simulating independent QR scans + restarts)
        assertThat(managerA.importKeyFromBase64(base64Key)).isTrue();
        managerA.setEnabled(true);
        assertThat(managerB.importKeyFromBase64(base64Key)).isTrue();
        managerB.setEnabled(true);
        assertThat(managerC.importKeyFromBase64(base64Key)).isTrue();
        managerC.setEnabled(true);

        byte[] msgA = "from A".getBytes(StandardCharsets.UTF_8);
        byte[] msgB = "from B".getBytes(StandardCharsets.UTF_8);
        byte[] msgC = "from C".getBytes(StandardCharsets.UTF_8);

        // A -> B, A -> C
        byte[] encA = managerA.encrypt(msgA);
        assertThat(managerB.decrypt(encA)).isEqualTo(msgA);
        assertThat(managerC.decrypt(encA)).isEqualTo(msgA);

        // B -> A, B -> C
        byte[] encB = managerB.encrypt(msgB);
        assertThat(managerA.decrypt(encB)).isEqualTo(msgB);
        assertThat(managerC.decrypt(encB)).isEqualTo(msgB);

        // C -> A, C -> B
        byte[] encC = managerC.encrypt(msgC);
        assertThat(managerA.decrypt(encC)).isEqualTo(msgC);
        assertThat(managerB.decrypt(encC)).isEqualTo(msgC);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Creates a fresh AppLayerEncryptionManager by resetting the singleton.
     * Each call returns a new independent instance.
     */
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
}
