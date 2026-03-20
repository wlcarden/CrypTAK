package android.util;

/**
 * Test stub for android.util.Base64.
 * Delegates to java.util.Base64 so that unit tests can exercise
 * code paths that use Android's Base64 API.
 */
public class Base64 {
    public static final int DEFAULT = 0;
    public static final int NO_WRAP = 2;
    public static final int URL_SAFE = 8;

    public static byte[] decode(String str, int flags) {
        if (str == null) return null;
        try {
            return java.util.Base64.getDecoder().decode(str.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bad base-64", e);
        }
    }

    public static byte[] decode(byte[] input, int flags) {
        if (input == null) return null;
        return java.util.Base64.getDecoder().decode(input);
    }

    public static String encodeToString(byte[] input, int flags) {
        if (input == null) return null;
        return java.util.Base64.getEncoder().encodeToString(input);
    }

    public static byte[] encode(byte[] input, int flags) {
        if (input == null) return null;
        return java.util.Base64.getEncoder().encode(input);
    }
}
