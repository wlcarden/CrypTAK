package com.atakmap.android.meshtastic.encryption;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import com.atakmap.android.meshtastic.ProtectedSharedPreferences;
import com.atakmap.android.meshtastic.util.Constants;
import com.atakmap.coremap.log.Log;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Application-layer encryption manager for CoT payloads transiting Meshtastic LoRa mesh.
 *
 * <p>Encrypts serialized protobuf (or compressed CoT) payloads BEFORE they are handed
 * to the Meshtastic radio, using key material stored on the phone only. This ensures
 * that physical compromise of a LoRa radio does not expose message content.</p>
 *
 * <h3>Wire Format</h3>
 * <pre>
 * [MARKER (1 byte: 0xFE)] [VERSION (1 byte)] [IV (12 bytes)] [Ciphertext + GCM Auth Tag]
 * </pre>
 *
 * <h3>Key Management</h3>
 * <ul>
 *   <li><b>Option B (PSK):</b> Pre-shared key distributed out-of-band (QR code, data package).
 *       Key is derived using SHA-256 and stored in Android Keystore when available.</li>
 *   <li><b>Option D (Epoch Rotation):</b> Epoch-based key rotation using HKDF chain.
 *       New epoch key = HKDF(previous_key, epoch_counter). Forward-only derivation.</li>
 * </ul>
 *
 * <h3>Cipher</h3>
 * AES-256-GCM with 12-byte random nonce and 128-bit authentication tag.
 * Uses Android platform crypto primitives only (no external crypto libraries).
 *
 * <h3>Overhead</h3>
 * Total per-message overhead: 30 bytes (1 marker + 1 version + 12 IV + 16 auth tag).
 * PLI ~190 bytes + 30 = ~220 bytes (fits single LoRa packet at 231-byte limit).
 */
public class AppLayerEncryptionManager {
    private static final String TAG = "AppLayerEncryption";

    // Wire format constants
    /** Magic byte identifying app-layer encrypted payloads. Chosen to not collide
     *  with valid protobuf field tags or existing markers (0xEE, 0xC2). */
    public static final byte APP_LAYER_MARKER = (byte) 0xFE;

    /** Current encryption format version. Receivers check this to select the
     *  appropriate decryption logic. */
    public static final byte ENCRYPTION_VERSION_1 = 0x01;

    // AES-256-GCM parameters
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;   // 96 bits, recommended for GCM
    private static final int GCM_TAG_LENGTH = 128;  // 128-bit authentication tag

    // Header size: marker (1) + version (1) + IV (12) = 14 bytes
    // Auth tag appended by GCM: 16 bytes
    // Total overhead: 30 bytes
    public static final int ENCRYPTION_OVERHEAD = 1 + 1 + GCM_IV_LENGTH + (GCM_TAG_LENGTH / 8);

    // Android Keystore alias for the derived key
    private static final String KEYSTORE_ALIAS = "meshtastic_app_layer_key";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    // Epoch rotation constants
    private static final long DEFAULT_EPOCH_DURATION_MS = 6 * 60 * 60 * 1000L; // 6 hours
    private static final String HKDF_INFO_PREFIX = "meshtastic-epoch-";

    // Singleton
    private static volatile AppLayerEncryptionManager instance;
    private static final Object INSTANCE_LOCK = new Object();

    // Crypto state
    private final SecureRandom secureRandom = new SecureRandom();
    private volatile SecretKeySpec currentKey;
    private volatile String currentPsk;

    // Epoch rotation state
    private volatile int currentEpoch = 0;
    private volatile long epochStartTimeMs = 0;
    private volatile long epochDurationMs = DEFAULT_EPOCH_DURATION_MS;
    private volatile boolean epochRotationEnabled = false;

    // Configuration
    private volatile boolean enabled = false;

    private AppLayerEncryptionManager() {
        // Private constructor for singleton
    }

    /**
     * Get the singleton instance.
     */
    public static AppLayerEncryptionManager getInstance() {
        if (instance == null) {
            synchronized (INSTANCE_LOCK) {
                if (instance == null) {
                    instance = new AppLayerEncryptionManager();
                }
            }
        }
        return instance;
    }

    /**
     * Initialize the manager from SharedPreferences.
     * Call this during plugin startup to load saved encryption configuration.
     *
     * @param context Application context for accessing preferences
     */
    public void initialize(Context context) {
        SharedPreferences prefs = new ProtectedSharedPreferences(
                PreferenceManager.getDefaultSharedPreferences(context)
        );

        enabled = prefs.getBoolean(Constants.PREF_PLUGIN_EXTRA_ENCRYPTION, false);
        String psk = prefs.getString(Constants.PREF_PLUGIN_ENCRYPTION_PSK, "");

        if (enabled && psk != null && !psk.isEmpty()) {
            loadKey(psk);
            Log.i(TAG, "Initialized with PSK (app-layer encryption enabled)");
        } else if (enabled) {
            Log.w(TAG, "App-layer encryption enabled but no PSK configured");
            enabled = false;
        } else {
            Log.d(TAG, "App-layer encryption disabled");
        }
    }

    // ========================================================================
    // Key Management
    // ========================================================================

    /**
     * Load encryption key from a pre-shared key string.
     * Derives a 256-bit AES key using SHA-256 hash of the PSK.
     *
     * @param psk The pre-shared key string
     */
    public void loadKey(String psk) {
        if (psk == null || psk.isEmpty()) {
            Log.w(TAG, "Cannot load null or empty PSK");
            currentKey = null;
            currentPsk = null;
            return;
        }

        // Avoid re-deriving if PSK hasn't changed
        if (psk.equals(currentPsk) && currentKey != null) {
            return;
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(psk.getBytes(StandardCharsets.UTF_8));
            currentKey = new SecretKeySpec(hash, "AES");
            currentPsk = psk;
            currentEpoch = 0;
            epochStartTimeMs = System.currentTimeMillis();
            Log.d(TAG, "Key derived from PSK");
        } catch (Exception e) {
            Log.e(TAG, "Failed to derive key from PSK", e);
            currentKey = null;
            currentPsk = null;
        }
    }

    /**
     * Load encryption key from raw key material (32 bytes for AES-256).
     *
     * @param keyMaterial Raw 256-bit key
     */
    public void loadKeyFromBytes(byte[] keyMaterial) {
        if (keyMaterial == null || keyMaterial.length != 32) {
            Log.w(TAG, "Invalid key material: must be exactly 32 bytes");
            currentKey = null;
            return;
        }

        currentKey = new SecretKeySpec(keyMaterial, "AES");
        currentPsk = null; // Raw key, not from PSK
        currentEpoch = 0;
        epochStartTimeMs = System.currentTimeMillis();
        Log.d(TAG, "Key loaded from raw bytes");
    }

    /**
     * Store the current key in Android Keystore for hardware-backed protection.
     * Falls back gracefully if Keystore is unavailable.
     *
     * @param context Application context
     * @return true if key was stored successfully
     */
    public boolean storeKeyInKeystore(Context context) {
        if (currentKey == null) {
            Log.w(TAG, "No key to store in Keystore");
            return false;
        }

        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);

            // Store the derived key as a Keystore entry
            KeyStore.SecretKeyEntry entry = new KeyStore.SecretKeyEntry(currentKey);
            KeyStore.ProtectionParameter protection =
                    new KeyStore.PasswordProtection(null);
            keyStore.setEntry(KEYSTORE_ALIAS, entry, protection);

            Log.i(TAG, "Key stored in Android Keystore");
            return true;
        } catch (Exception e) {
            // Keystore may not support importing arbitrary keys on all devices
            Log.w(TAG, "Failed to store key in Android Keystore (may not be supported): "
                    + e.getMessage());
            return false;
        }
    }

    /**
     * Load key from Android Keystore.
     *
     * @return true if key was loaded successfully
     */
    public boolean loadKeyFromKeystore() {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);

            KeyStore.Entry entry = keyStore.getEntry(KEYSTORE_ALIAS, null);
            if (entry instanceof KeyStore.SecretKeyEntry) {
                SecretKey secretKey = ((KeyStore.SecretKeyEntry) entry).getSecretKey();
                currentKey = new SecretKeySpec(secretKey.getEncoded(), "AES");
                Log.i(TAG, "Key loaded from Android Keystore");
                return true;
            } else {
                Log.d(TAG, "No key found in Android Keystore");
                return false;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to load key from Android Keystore: " + e.getMessage());
            return false;
        }
    }

    /**
     * Delete key from Android Keystore.
     */
    public void deleteKeyFromKeystore() {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            keyStore.deleteEntry(KEYSTORE_ALIAS);
            Log.i(TAG, "Key deleted from Android Keystore");
        } catch (Exception e) {
            Log.w(TAG, "Failed to delete key from Android Keystore: " + e.getMessage());
        }
    }

    // ========================================================================
    // Epoch-Based Key Rotation (Option D)
    // ========================================================================

    /**
     * Enable epoch-based key rotation.
     * When enabled, the key is automatically rotated every epochDurationMs milliseconds.
     * New epoch keys are derived forward-only: HKDF(previous_key, epoch_counter).
     *
     * @param epochDurationMs Duration of each epoch in milliseconds
     */
    public void enableEpochRotation(long epochDurationMs) {
        if (epochDurationMs <= 0) {
            Log.w(TAG, "Invalid epoch duration, using default");
            this.epochDurationMs = DEFAULT_EPOCH_DURATION_MS;
        } else {
            this.epochDurationMs = epochDurationMs;
        }
        this.epochRotationEnabled = true;
        this.epochStartTimeMs = System.currentTimeMillis();
        this.currentEpoch = 0;
        Log.i(TAG, "Epoch rotation enabled, duration: " + this.epochDurationMs + "ms");
    }

    /**
     * Disable epoch-based key rotation.
     */
    public void disableEpochRotation() {
        this.epochRotationEnabled = false;
        Log.i(TAG, "Epoch rotation disabled");
    }

    /**
     * Check if the current epoch has expired and rotate if needed.
     * Called before each encrypt operation.
     */
    private void checkAndRotateEpoch() {
        if (!epochRotationEnabled || currentKey == null) {
            return;
        }

        long now = System.currentTimeMillis();
        long elapsed = now - epochStartTimeMs;

        if (elapsed >= epochDurationMs) {
            int newEpoch = currentEpoch + (int) (elapsed / epochDurationMs);
            rotateToEpoch(newEpoch);
        }
    }

    /**
     * Rotate key to a specific epoch.
     * Derives the new key using a forward-only KDF chain:
     *   epoch_key[n] = SHA-256(epoch_key[n-1] || epoch_counter)
     *
     * @param targetEpoch The target epoch number
     */
    private void rotateToEpoch(int targetEpoch) {
        if (targetEpoch <= currentEpoch) {
            return; // Already at or past this epoch
        }

        try {
            SecretKeySpec key = currentKey;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            for (int epoch = currentEpoch + 1; epoch <= targetEpoch; epoch++) {
                // Forward-only derivation: new_key = SHA-256(current_key || epoch_bytes)
                byte[] epochBytes = ByteBuffer.allocate(4).putInt(epoch).array();
                digest.reset();
                digest.update(key.getEncoded());
                digest.update(epochBytes);
                byte[] newKeyBytes = digest.digest();
                key = new SecretKeySpec(newKeyBytes, "AES");
            }

            currentKey = key;
            currentEpoch = targetEpoch;
            epochStartTimeMs = System.currentTimeMillis();
            Log.i(TAG, "Key rotated to epoch " + targetEpoch);
        } catch (Exception e) {
            Log.e(TAG, "Failed to rotate key to epoch " + targetEpoch, e);
        }
    }

    /**
     * Get the current epoch number (for inclusion in message headers if needed).
     */
    public int getCurrentEpoch() {
        return currentEpoch;
    }

    // ========================================================================
    // Encrypt / Decrypt
    // ========================================================================

    /**
     * Encrypt a payload using AES-256-GCM.
     *
     * <p>Wire format:
     * <pre>
     * [0xFE marker (1)] [version (1)] [IV (12)] [ciphertext + auth_tag (N + 16)]
     * </pre>
     *
     * @param plaintext The payload to encrypt (protobuf bytes or compressed CoT)
     * @return Encrypted payload with header, or null on failure
     */
    public byte[] encrypt(byte[] plaintext) {
        if (plaintext == null || plaintext.length == 0) {
            Log.w(TAG, "Cannot encrypt null or empty plaintext");
            return null;
        }

        if (currentKey == null) {
            Log.w(TAG, "Cannot encrypt: no key loaded");
            return null;
        }

        // Check for epoch rotation before encrypting
        checkAndRotateEpoch();

        try {
            // Generate random 12-byte IV (nonce)
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            // Initialize AES-256-GCM cipher
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, currentKey, gcmSpec);

            // Encrypt (GCM appends 16-byte auth tag to ciphertext)
            byte[] ciphertext = cipher.doFinal(plaintext);

            // Assemble wire format: marker + version + IV + ciphertext
            ByteBuffer buffer = ByteBuffer.allocate(
                    1 + 1 + GCM_IV_LENGTH + ciphertext.length);
            buffer.put(APP_LAYER_MARKER);
            buffer.put(ENCRYPTION_VERSION_1);
            buffer.put(iv);
            buffer.put(ciphertext);

            Log.d(TAG, "Encrypted " + plaintext.length + " -> " + buffer.capacity()
                    + " bytes (overhead: " + (buffer.capacity() - plaintext.length) + ")");
            return buffer.array();

        } catch (Exception e) {
            Log.e(TAG, "Encryption failed", e);
            return null;
        }
    }

    /**
     * Decrypt an app-layer encrypted payload.
     *
     * @param encryptedData The full encrypted payload including header
     * @return Decrypted plaintext, or null on failure (wrong key, tampered data, etc.)
     */
    public byte[] decrypt(byte[] encryptedData) {
        if (encryptedData == null) {
            Log.w(TAG, "Cannot decrypt null data");
            return null;
        }

        // Minimum size: marker(1) + version(1) + IV(12) + auth_tag(16) = 30 bytes
        int minSize = 1 + 1 + GCM_IV_LENGTH + (GCM_TAG_LENGTH / 8);
        if (encryptedData.length < minSize) {
            Log.w(TAG, "Encrypted data too short: " + encryptedData.length
                    + " (minimum: " + minSize + ")");
            return null;
        }

        // Verify marker
        if (encryptedData[0] != APP_LAYER_MARKER) {
            Log.w(TAG, "Invalid app-layer encryption marker: 0x"
                    + String.format("%02X", encryptedData[0]));
            return null;
        }

        // Check version
        byte version = encryptedData[1];
        if (version != ENCRYPTION_VERSION_1) {
            Log.w(TAG, "Unsupported encryption version: " + version);
            return null;
        }

        if (currentKey == null) {
            Log.w(TAG, "Cannot decrypt: no key loaded");
            return null;
        }

        try {
            // Extract IV (bytes 2-13)
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(encryptedData, 2, iv, 0, GCM_IV_LENGTH);

            // Extract ciphertext + auth tag (bytes 14 onwards)
            int ciphertextOffset = 1 + 1 + GCM_IV_LENGTH;
            int ciphertextLength = encryptedData.length - ciphertextOffset;
            byte[] ciphertext = new byte[ciphertextLength];
            System.arraycopy(encryptedData, ciphertextOffset, ciphertext, 0, ciphertextLength);

            // Initialize AES-256-GCM cipher for decryption
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, currentKey, gcmSpec);

            // Decrypt and verify auth tag
            byte[] plaintext = cipher.doFinal(ciphertext);

            Log.d(TAG, "Decrypted " + encryptedData.length + " -> " + plaintext.length + " bytes");
            return plaintext;

        } catch (javax.crypto.AEADBadTagException e) {
            // Wrong key or tampered data - expected in mixed-key deployments
            Log.d(TAG, "Decryption failed: authentication tag mismatch (wrong key or tampered data)");
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Decryption failed", e);
            return null;
        }
    }

    // ========================================================================
    // Detection
    // ========================================================================

    /**
     * Check if data appears to be app-layer encrypted (starts with 0xFE marker).
     * This is a quick check for routing decisions - actual decryption may still fail.
     *
     * @param data The data to check
     * @return true if data starts with the app-layer encryption marker
     */
    public static boolean isAppLayerEncrypted(byte[] data) {
        return data != null && data.length >= 2 && data[0] == APP_LAYER_MARKER;
    }

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * Check if app-layer encryption is enabled.
     */
    public boolean isEnabled() {
        return enabled && currentKey != null;
    }

    /**
     * Enable or disable app-layer encryption.
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            Log.d(TAG, "App-layer encryption disabled");
        } else if (currentKey == null) {
            Log.w(TAG, "App-layer encryption enabled but no key loaded");
        } else {
            Log.i(TAG, "App-layer encryption enabled");
        }
    }

    /**
     * Check if a key is currently loaded.
     */
    public boolean hasKey() {
        return currentKey != null;
    }

    /**
     * Clear all key material from memory.
     * Call this when the plugin is destroyed or encryption is disabled.
     */
    public void clearKeys() {
        if (currentKey != null) {
            // Zero out key material
            byte[] encoded = currentKey.getEncoded();
            if (encoded != null) {
                java.util.Arrays.fill(encoded, (byte) 0);
            }
        }
        currentKey = null;
        currentPsk = null;
        currentEpoch = 0;
        epochStartTimeMs = 0;
        Log.d(TAG, "Key material cleared from memory");
    }

    /**
     * Get the encryption overhead in bytes.
     * Useful for message size budget calculations.
     */
    public static int getOverhead() {
        return ENCRYPTION_OVERHEAD;
    }
}
