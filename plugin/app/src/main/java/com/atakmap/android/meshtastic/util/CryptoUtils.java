package com.atakmap.android.meshtastic.util;

import com.atakmap.coremap.log.Log;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cryptographic utilities for encrypting/decrypting messages using AES-256-GCM.
 *
 * Message format:
 * [MARKER (1 byte)] [IV (12 bytes)] [Ciphertext + Auth Tag (variable)]
 *
 * The PSK is hashed with SHA-256 to derive a 256-bit key.
 */
public class CryptoUtils {
    private static final String TAG = "CryptoUtils";

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;  // 96 bits recommended for GCM
    private static final int GCM_TAG_LENGTH = 128; // 128 bits auth tag

    private static final SecureRandom secureRandom = new SecureRandom();

    /**
     * Derive a 256-bit AES key from a PSK string using SHA-256.
     *
     * @param psk The pre-shared key string
     * @return SecretKeySpec for AES-256
     */
    private static SecretKeySpec deriveKey(String psk) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(psk.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(hash, "AES");
        } catch (Exception e) {
            Log.e(TAG, "Failed to derive key", e);
            return null;
        }
    }

    /**
     * Encrypt data using AES-256-GCM with the given PSK.
     *
     * @param plaintext The data to encrypt
     * @param psk The pre-shared key
     * @return Encrypted data with marker, IV prepended, or null on failure
     */
    public static byte[] encrypt(byte[] plaintext, String psk) {
        if (plaintext == null || psk == null || psk.isEmpty()) {
            Log.w(TAG, "Cannot encrypt: null plaintext or empty PSK");
            return null;
        }

        try {
            SecretKeySpec key = deriveKey(psk);
            if (key == null) return null;

            // Generate random IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            // Initialize cipher
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec);

            // Encrypt
            byte[] ciphertext = cipher.doFinal(plaintext);

            // Combine: marker + IV + ciphertext
            ByteBuffer buffer = ByteBuffer.allocate(1 + GCM_IV_LENGTH + ciphertext.length);
            buffer.put(Constants.ENCRYPTED_MESSAGE_MARKER);
            buffer.put(iv);
            buffer.put(ciphertext);

            Log.d(TAG, "Encrypted " + plaintext.length + " bytes -> " + buffer.capacity() + " bytes");
            return buffer.array();

        } catch (Exception e) {
            Log.e(TAG, "Encryption failed", e);
            return null;
        }
    }

    /**
     * Decrypt data using AES-256-GCM with the given PSK.
     *
     * @param encryptedData The encrypted data (with marker and IV prepended)
     * @param psk The pre-shared key
     * @return Decrypted plaintext, or null on failure (wrong key, tampered data, etc.)
     */
    public static byte[] decrypt(byte[] encryptedData, String psk) {
        if (encryptedData == null || psk == null || psk.isEmpty()) {
            Log.w(TAG, "Cannot decrypt: null data or empty PSK");
            return null;
        }

        // Minimum size: marker (1) + IV (12) + tag (16) = 29 bytes
        if (encryptedData.length < 1 + GCM_IV_LENGTH + 16) {
            Log.w(TAG, "Encrypted data too short: " + encryptedData.length);
            return null;
        }

        // Check marker
        if (encryptedData[0] != Constants.ENCRYPTED_MESSAGE_MARKER) {
            Log.w(TAG, "Invalid encryption marker");
            return null;
        }

        try {
            SecretKeySpec key = deriveKey(psk);
            if (key == null) return null;

            // Extract IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(encryptedData, 1, iv, 0, GCM_IV_LENGTH);

            // Extract ciphertext
            int ciphertextLength = encryptedData.length - 1 - GCM_IV_LENGTH;
            byte[] ciphertext = new byte[ciphertextLength];
            System.arraycopy(encryptedData, 1 + GCM_IV_LENGTH, ciphertext, 0, ciphertextLength);

            // Initialize cipher
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec);

            // Decrypt
            byte[] plaintext = cipher.doFinal(ciphertext);

            Log.d(TAG, "Decrypted " + encryptedData.length + " bytes -> " + plaintext.length + " bytes");
            return plaintext;

        } catch (javax.crypto.AEADBadTagException e) {
            // Wrong key or tampered data - this is expected when PSK doesn't match
            Log.d(TAG, "Decryption failed: wrong key or tampered data");
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Decryption failed", e);
            return null;
        }
    }

    /**
     * Check if data appears to be encrypted (has the marker byte).
     *
     * @param data The data to check
     * @return true if data starts with the encryption marker
     */
    public static boolean isEncrypted(byte[] data) {
        return data != null && data.length > 0 && data[0] == Constants.ENCRYPTED_MESSAGE_MARKER;
    }
}
