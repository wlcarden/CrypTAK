package com.atakmap.android.meshtastic.encryption;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import com.atakmap.android.meshtastic.ProtectedSharedPreferences;
import com.atakmap.android.meshtastic.util.Constants;
import com.atakmap.coremap.log.Log;

import android.util.Base64;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.Mac;
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
 *   <li><b>Option D (Epoch Rotation):</b> Epoch-based key rotation using HMAC-SHA256 chain.
 *       New epoch key = HMAC-SHA256(previous_key, "meshtastic-epoch-" || counter).
 *       Forward-only derivation.</li>
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

    /** Encryption format version 1: PSK only, no epoch.
     *  Wire: [0xFE][0x01][IV(12)][ciphertext+tag] = 30 bytes overhead */
    public static final byte ENCRYPTION_VERSION_1 = 0x01;

    /** Encryption format version 2: PSK with epoch rotation.
     *  Wire: [0xFE][0x02][epoch(4)][IV(12)][ciphertext+tag] = 34 bytes overhead */
    public static final byte ENCRYPTION_VERSION_2 = 0x02;

    // AES-256-GCM parameters
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;   // 96 bits, recommended for GCM
    private static final int GCM_TAG_LENGTH = 128;  // 128-bit authentication tag

    // V1 overhead: marker (1) + version (1) + IV (12) + auth tag (16) = 30 bytes
    public static final int ENCRYPTION_OVERHEAD = 1 + 1 + GCM_IV_LENGTH + (GCM_TAG_LENGTH / 8);

    // V2 overhead: marker (1) + version (1) + epoch (4) + IV (12) + auth tag (16) = 34 bytes
    public static final int ENCRYPTION_OVERHEAD_V2 = ENCRYPTION_OVERHEAD + 4;

    // Android Keystore alias for the derived key
    private static final String KEYSTORE_ALIAS = "meshtastic_app_layer_key";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

    // Epoch rotation constants
    private static final long DEFAULT_EPOCH_DURATION_MS = 6 * 60 * 60 * 1000L; // 6 hours
    private static final String EPOCH_KDF_INFO_PREFIX = "meshtastic-epoch-";

    // Maximum epoch number to prevent denial-of-service via crafted packets.
    // At 1-hour epochs, 8760 covers one year of continuous rotation.
    static final int MAX_EPOCH = 8760;

    // Singleton
    private static volatile AppLayerEncryptionManager instance;
    private static final Object INSTANCE_LOCK = new Object();

    // Crypto state
    private final SecureRandom secureRandom = new SecureRandom();
    private volatile SecretKeySpec currentKey;
    private volatile String currentPsk;
    private volatile SecretKeySpec baseKey; // Original PSK-derived key (epoch 0)

    // Epoch rotation state
    private volatile int currentEpoch = 0;
    private volatile long epochStartTimeMs = 0;
    private volatile long epochDurationMs = DEFAULT_EPOCH_DURATION_MS;
    private volatile boolean epochRotationEnabled = false;

    // Previous epoch key retention — allows decrypting in-flight messages during rotation
    private volatile SecretKeySpec previousEpochKey;
    private volatile int previousEpoch = -1;

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
            baseKey = currentKey;
            currentPsk = psk;
            currentEpoch = 0;
            previousEpochKey = null;
            previousEpoch = -1;
            epochStartTimeMs = System.currentTimeMillis();
            Log.d(TAG, "Key derived from PSK");
        } catch (Exception e) {
            Log.e(TAG, "Failed to derive key from PSK", e);
            currentKey = null;
            baseKey = null;
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
        baseKey = currentKey;
        currentPsk = null; // Raw key, not from PSK
        currentEpoch = 0;
        previousEpochKey = null;
        previousEpoch = -1;
        epochStartTimeMs = System.currentTimeMillis();

        // Zero the input array to reduce key material exposure in memory
        java.util.Arrays.fill(keyMaterial, (byte) 0);
        Log.d(TAG, "Key loaded from raw bytes");
    }

    /**
     * Import a key from a base64-encoded string, loading the raw decoded bytes directly.
     * Validates that the decoded key is exactly 32 bytes (AES-256).
     *
     * <p><b>Important:</b> This method loads the raw 32-byte key material. For persistent
     * key import (QR code scan, ATAK Import Manager), use {@link #importKeyAndSave} instead,
     * which ensures the in-memory key matches what {@link #initialize} produces on restart.</p>
     *
     * @param base64Key Base64-encoded key string (standard or URL-safe encoding)
     * @return true if the key was valid and loaded, false otherwise
     */
    public boolean importKeyFromBase64(String base64Key) {
        if (base64Key == null || base64Key.trim().isEmpty()) {
            Log.w(TAG, "Cannot import null or empty base64 key");
            return false;
        }

        try {
            byte[] keyBytes = Base64.decode(base64Key.trim(), Base64.DEFAULT);

            if (keyBytes.length != 32) {
                Log.w(TAG, "Invalid key length: " + keyBytes.length
                        + " bytes (expected 32). Check that the key was generated with: "
                        + "openssl rand -base64 32");
                // Zero out the decoded bytes
                java.util.Arrays.fill(keyBytes, (byte) 0);
                return false;
            }

            loadKeyFromBytes(keyBytes);
            // Zero the intermediate copy
            java.util.Arrays.fill(keyBytes, (byte) 0);

            Log.i(TAG, "Key imported from base64 (32 bytes)");
            return true;
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Invalid base64 encoding: " + e.getMessage());
            return false;
        }
    }

    /**
     * Import a key from base64 and persist it to SharedPreferences.
     * This is the full onboarding flow: decode, validate, load, and save.
     *
     * @param context    Application context for accessing preferences
     * @param base64Key  Base64-encoded 32-byte key
     * @param enableEncryption Whether to also enable encryption after import
     * @return true if key was imported and saved successfully
     */
    public boolean importKeyAndSave(Context context, String base64Key, boolean enableEncryption) {
        if (!importKeyFromBase64(base64Key)) {
            return false;
        }

        try {
            SharedPreferences prefs = new ProtectedSharedPreferences(
                    PreferenceManager.getDefaultSharedPreferences(context));
            SharedPreferences.Editor editor = prefs.edit();

            // Store the base64 key as the PSK value in preferences.
            // On reload, initialize() calls loadKey() which SHA-256 hashes this string.
            // To ensure the same key is used after restart, we also call loadKey() here
            // so the in-memory key matches what initialize() will produce.
            editor.putString(Constants.PREF_PLUGIN_ENCRYPTION_PSK, base64Key.trim());

            if (enableEncryption) {
                editor.putBoolean(Constants.PREF_PLUGIN_EXTRA_ENCRYPTION, true);
            }

            editor.apply();

            // Derive key from the string PSK (SHA-256 hash) so that the in-memory key
            // matches what initialize() will produce on next app startup. This ensures
            // that messages encrypted now can be decrypted after a restart, and vice versa.
            loadKey(base64Key.trim());
            if (enableEncryption) {
                setEnabled(true);
            }

            Log.i(TAG, "Key imported and saved to preferences"
                    + (enableEncryption ? " (encryption enabled)" : ""));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to save imported key to preferences", e);
            return false;
        }
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
     * New epoch keys are derived forward-only using HMAC-SHA256:
     *   epoch_key[n] = HMAC-SHA256(epoch_key[n-1], "meshtastic-epoch-" || epoch_counter)
     *
     * <p>When epoch rotation is active, the encrypt() method uses version 0x02 wire format
     * which embeds the epoch number so receivers can derive the correct decryption key.</p>
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
        this.previousEpochKey = null;
        this.previousEpoch = -1;
        Log.i(TAG, "Epoch rotation enabled, duration: " + this.epochDurationMs + "ms");
    }

    /**
     * Disable epoch-based key rotation.
     * Reverts to base key (epoch 0) and v1 wire format.
     */
    public void disableEpochRotation() {
        this.epochRotationEnabled = false;
        if (baseKey != null) {
            this.currentKey = baseKey;
            this.currentEpoch = 0;
        }
        this.previousEpochKey = null;
        this.previousEpoch = -1;
        Log.i(TAG, "Epoch rotation disabled, reverted to base key");
    }

    /**
     * Check if epoch rotation is currently enabled.
     */
    public boolean isEpochRotationEnabled() {
        return epochRotationEnabled;
    }

    // Lock for epoch rotation to prevent concurrent encrypt() calls from
    // observing partially-updated key+epoch state
    private final Object epochLock = new Object();

    /**
     * Check if the current epoch has expired and rotate if needed.
     * Called before each encrypt operation. Thread-safe via epochLock.
     */
    private void checkAndRotateEpoch() {
        if (!epochRotationEnabled || currentKey == null) {
            return;
        }

        long now = System.currentTimeMillis();
        long elapsed = now - epochStartTimeMs;

        if (elapsed >= epochDurationMs) {
            synchronized (epochLock) {
                // Re-check under lock (another thread may have rotated already)
                elapsed = System.currentTimeMillis() - epochStartTimeMs;
                if (elapsed >= epochDurationMs) {
                    int newEpoch = currentEpoch + (int) (elapsed / epochDurationMs);
                    if (newEpoch > MAX_EPOCH) {
                        Log.w(TAG, "Epoch overflow clamped: " + newEpoch
                                + " -> " + MAX_EPOCH);
                        newEpoch = MAX_EPOCH;
                    }
                    rotateToEpoch(newEpoch);
                }
            }
        }
    }

    /**
     * Rotate key to a specific epoch using HMAC-SHA256 forward-only KDF chain.
     * Retains the previous epoch key so in-flight messages can still be decrypted.
     * Must be called under epochLock.
     *
     * <p>Derivation: epoch_key[n] = HMAC-SHA256(epoch_key[n-1], "meshtastic-epoch-" || n)</p>
     *
     * @param targetEpoch The target epoch number
     */
    private void rotateToEpoch(int targetEpoch) {
        if (targetEpoch <= currentEpoch) {
            return; // Already at or past this epoch
        }

        try {
            // Retain the current key as previous (for in-flight message decryption)
            previousEpochKey = currentKey;
            previousEpoch = currentEpoch;

            SecretKeySpec key = currentKey;

            for (int epoch = currentEpoch + 1; epoch <= targetEpoch; epoch++) {
                key = deriveEpochKey(key, epoch);
            }

            // Update all state atomically (under epochLock)
            currentKey = key;
            currentEpoch = targetEpoch;
            epochStartTimeMs = System.currentTimeMillis();
            Log.i(TAG, "Key rotated to epoch " + targetEpoch
                    + " (previous epoch " + previousEpoch + " key retained)");
        } catch (Exception e) {
            Log.e(TAG, "Failed to rotate key to epoch " + targetEpoch, e);
        }
    }

    /**
     * Derive an epoch key from the given parent key and epoch number using HMAC-SHA256.
     *
     * @param parentKey The key from the previous epoch
     * @param epoch     The epoch number to derive for
     * @return The derived key for the given epoch
     */
    SecretKeySpec deriveEpochKey(SecretKeySpec parentKey, int epoch) throws Exception {
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(parentKey);

        // info = "meshtastic-epoch-" || epoch_number (4 bytes big-endian)
        byte[] info = ByteBuffer.allocate(EPOCH_KDF_INFO_PREFIX.length() + 4)
                .put(EPOCH_KDF_INFO_PREFIX.getBytes(StandardCharsets.UTF_8))
                .putInt(epoch)
                .array();
        byte[] derivedBytes = hmac.doFinal(info);
        return new SecretKeySpec(derivedBytes, "AES");
    }

    /**
     * Derive the key for a specific epoch from the base key (epoch 0).
     * This is used by the receiver to derive the correct key for a message
     * encrypted at a given epoch without needing to have seen every intermediate epoch.
     *
     * @param targetEpoch The epoch to derive the key for
     * @return The derived key, or null on failure
     */
    SecretKeySpec deriveKeyForEpoch(int targetEpoch) {
        if (baseKey == null) {
            Log.w(TAG, "Cannot derive epoch key: no base key");
            return null;
        }

        if (targetEpoch == 0) {
            return baseKey;
        }

        if (targetEpoch < 0 || targetEpoch > MAX_EPOCH) {
            Log.w(TAG, "Epoch out of bounds: " + targetEpoch
                    + " (max: " + MAX_EPOCH + ")");
            return null;
        }

        try {
            SecretKeySpec key = baseKey;
            for (int epoch = 1; epoch <= targetEpoch; epoch++) {
                key = deriveEpochKey(key, epoch);
            }
            return key;
        } catch (Exception e) {
            Log.e(TAG, "Failed to derive key for epoch " + targetEpoch, e);
            return null;
        }
    }

    /**
     * Load a new seed key from an external source (e.g., TAK Server).
     * This replaces the current key hierarchy and resets the epoch counter.
     * Use this when the TAK Server pushes a new encryption key to all clients.
     *
     * @param seedKey Raw 256-bit seed key from the external source
     * @param startEpoch The epoch number to start from (usually 0)
     */
    public void loadSeedKeyFromExternal(byte[] seedKey, int startEpoch) {
        if (seedKey == null || seedKey.length != 32) {
            Log.w(TAG, "Invalid external seed key: must be exactly 32 bytes");
            return;
        }

        currentKey = new SecretKeySpec(seedKey, "AES");
        baseKey = currentKey;
        currentPsk = null;
        currentEpoch = startEpoch;
        previousEpochKey = null;
        previousEpoch = -1;
        epochStartTimeMs = System.currentTimeMillis();

        // Zero the input array to reduce key material exposure in memory
        java.util.Arrays.fill(seedKey, (byte) 0);
        Log.i(TAG, "Seed key loaded from external source, starting at epoch " + startEpoch);
    }

    /**
     * Get the current epoch number.
     */
    public int getCurrentEpoch() {
        return currentEpoch;
    }

    /**
     * Get the previous epoch number (for diagnostics).
     * Returns -1 if no previous epoch key is retained.
     */
    public int getPreviousEpoch() {
        return previousEpoch;
    }

    // ========================================================================
    // Encrypt / Decrypt
    // ========================================================================

    /**
     * Encrypt a payload using AES-256-GCM.
     *
     * <p>Wire format (v1, epoch rotation disabled):
     * <pre>
     * [0xFE marker (1)] [0x01 version (1)] [IV (12)] [ciphertext + auth_tag (N + 16)]
     * </pre>
     *
     * <p>Wire format (v2, epoch rotation enabled):
     * <pre>
     * [0xFE marker (1)] [0x02 version (1)] [epoch (4 BE)] [IV (12)] [ciphertext + auth_tag (N + 16)]
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

            // Assemble wire format based on whether epoch rotation is active
            ByteBuffer buffer;
            if (epochRotationEnabled) {
                // V2: marker + version + epoch + IV + ciphertext
                buffer = ByteBuffer.allocate(1 + 1 + 4 + GCM_IV_LENGTH + ciphertext.length);
                buffer.put(APP_LAYER_MARKER);
                buffer.put(ENCRYPTION_VERSION_2);
                buffer.putInt(currentEpoch);
                buffer.put(iv);
                buffer.put(ciphertext);
            } else {
                // V1: marker + version + IV + ciphertext
                buffer = ByteBuffer.allocate(1 + 1 + GCM_IV_LENGTH + ciphertext.length);
                buffer.put(APP_LAYER_MARKER);
                buffer.put(ENCRYPTION_VERSION_1);
                buffer.put(iv);
                buffer.put(ciphertext);
            }

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
     * Supports both v1 (no epoch) and v2 (with epoch) wire formats.
     *
     * <p>For v2 messages, the receiver derives the correct epoch key from the base key.
     * If the derived key fails, falls back to the previous epoch key for in-flight messages.</p>
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

        // Route based on version
        byte version = encryptedData[1];
        if (version == ENCRYPTION_VERSION_1) {
            return decryptV1(encryptedData);
        } else if (version == ENCRYPTION_VERSION_2) {
            return decryptV2(encryptedData);
        } else {
            Log.w(TAG, "Unsupported encryption version: " + version);
            return null;
        }
    }

    /**
     * Decrypt a v1 payload (no epoch, uses current key).
     */
    private byte[] decryptV1(byte[] encryptedData) {
        if (currentKey == null) {
            Log.w(TAG, "Cannot decrypt v1: no key loaded");
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

            byte[] plaintext = decryptWithKey(currentKey, iv, ciphertext);
            if (plaintext != null) {
                Log.d(TAG, "Decrypted v1 " + encryptedData.length + " -> " + plaintext.length + " bytes");
                return plaintext;
            }

            // If current key fails and we have a previous epoch key, try that
            if (previousEpochKey != null) {
                plaintext = decryptWithKey(previousEpochKey, iv, ciphertext);
                if (plaintext != null) {
                    Log.d(TAG, "Decrypted v1 with previous epoch key ("
                            + previousEpoch + "): " + plaintext.length + " bytes");
                    return plaintext;
                }
            }

            Log.d(TAG, "Decryption failed v1: authentication tag mismatch");
            return null;

        } catch (Exception e) {
            Log.e(TAG, "Decryption failed v1", e);
            return null;
        }
    }

    /**
     * Decrypt a v2 payload (with epoch number embedded).
     * Derives the correct epoch key from the base key using the epoch in the header.
     */
    private byte[] decryptV2(byte[] encryptedData) {
        // V2 minimum: marker(1) + version(1) + epoch(4) + IV(12) + tag(16) = 34 bytes
        int minSizeV2 = 1 + 1 + 4 + GCM_IV_LENGTH + (GCM_TAG_LENGTH / 8);
        if (encryptedData.length < minSizeV2) {
            Log.w(TAG, "V2 encrypted data too short: " + encryptedData.length
                    + " (minimum: " + minSizeV2 + ")");
            return null;
        }

        if (baseKey == null && currentKey == null) {
            Log.w(TAG, "Cannot decrypt v2: no key loaded");
            return null;
        }

        // Extract epoch (bytes 2-5, big-endian)
        int messageEpoch = ByteBuffer.wrap(encryptedData, 2, 4).getInt();

        // Extract IV (bytes 6-17)
        byte[] iv = new byte[GCM_IV_LENGTH];
        System.arraycopy(encryptedData, 6, iv, 0, GCM_IV_LENGTH);

        // Extract ciphertext + auth tag (bytes 18 onwards)
        int ciphertextOffset = 1 + 1 + 4 + GCM_IV_LENGTH;
        int ciphertextLength = encryptedData.length - ciphertextOffset;
        byte[] ciphertext = new byte[ciphertextLength];
        System.arraycopy(encryptedData, ciphertextOffset, ciphertext, 0, ciphertextLength);

        // Try current key first (if we're at the same epoch)
        if (messageEpoch == currentEpoch) {
            byte[] plaintext = decryptWithKey(currentKey, iv, ciphertext);
            if (plaintext != null) {
                Log.d(TAG, "Decrypted v2 epoch " + messageEpoch + ": "
                        + encryptedData.length + " -> " + plaintext.length + " bytes");
                return plaintext;
            }
        }

        // Try previous epoch key (in-flight message during rotation)
        if (previousEpochKey != null && messageEpoch == previousEpoch) {
            byte[] plaintext = decryptWithKey(previousEpochKey, iv, ciphertext);
            if (plaintext != null) {
                Log.d(TAG, "Decrypted v2 with previous epoch key ("
                        + messageEpoch + "): " + plaintext.length + " bytes");
                return plaintext;
            }
        }

        // Derive key from base key for the message's epoch
        SecretKeySpec epochKey = deriveKeyForEpoch(messageEpoch);
        if (epochKey != null) {
            byte[] plaintext = decryptWithKey(epochKey, iv, ciphertext);
            if (plaintext != null) {
                Log.d(TAG, "Decrypted v2 by deriving key for epoch " + messageEpoch
                        + ": " + plaintext.length + " bytes");
                return plaintext;
            }
        }

        Log.d(TAG, "Decryption failed v2 epoch " + messageEpoch
                + ": no matching key found");
        return null;
    }

    /**
     * Attempt decryption with a specific key. Returns null on failure.
     */
    private byte[] decryptWithKey(SecretKeySpec key, byte[] iv, byte[] ciphertext) {
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec);
            return cipher.doFinal(ciphertext);
        } catch (javax.crypto.AEADBadTagException e) {
            // Wrong key or tampered data
            return null;
        } catch (Exception e) {
            Log.e(TAG, "decryptWithKey failed", e);
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
        zeroKey(currentKey);
        zeroKey(baseKey);
        zeroKey(previousEpochKey);
        currentKey = null;
        baseKey = null;
        previousEpochKey = null;
        previousEpoch = -1;
        currentPsk = null;
        currentEpoch = 0;
        epochStartTimeMs = 0;
        Log.d(TAG, "Key material cleared from memory");
    }

    /**
     * Best-effort zeroing of key material.
     * Note: SecretKeySpec.getEncoded() returns a copy, so this only zeros the copy.
     * The actual key bytes inside SecretKeySpec persist until GC. This is a known JCA
     * limitation. Setting the reference to null (done by callers) is the primary mitigation.
     */
    private void zeroKey(SecretKeySpec key) {
        if (key != null) {
            byte[] encoded = key.getEncoded();
            if (encoded != null) {
                java.util.Arrays.fill(encoded, (byte) 0);
            }
        }
    }

    /**
     * Get the encryption overhead in bytes for the current mode.
     * Returns 34 bytes when epoch rotation is active (V2), 30 bytes otherwise (V1).
     * Useful for message size budget calculations.
     */
    public int getActiveOverhead() {
        return epochRotationEnabled ? ENCRYPTION_OVERHEAD_V2 : ENCRYPTION_OVERHEAD;
    }

    /**
     * Get the V1 encryption overhead in bytes (always 30).
     * Use {@link #getActiveOverhead()} for mode-aware overhead.
     */
    public static int getOverhead() {
        return ENCRYPTION_OVERHEAD;
    }
}
