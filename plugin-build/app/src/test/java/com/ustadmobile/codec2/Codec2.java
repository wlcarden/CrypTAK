package com.ustadmobile.codec2;

/**
 * Test stub for Codec2. The real class uses JNI native methods that cannot
 * run on the JVM. This stub provides safe no-op implementations so unit tests
 * can construct MeshtasticReceiver without loading the native library.
 */
public class Codec2 {

    public static final int CODEC2_MODE_3200 = 0;
    public static final int CODEC2_MODE_2400 = 1;
    public static final int CODEC2_MODE_1600 = 2;
    public static final int CODEC2_MODE_1400 = 3;
    public static final int CODEC2_MODE_1300 = 4;
    public static final int CODEC2_MODE_1200 = 5;
    public static final int CODEC2_MODE_700  = 6;
    public static final int CODEC2_MODE_700B = 7;
    public static final int CODEC2_MODE_700C = 8;
    public static final int CODEC2_MODE_WB   = 9;

    public static final int CODEC2_FILE_HEADER_SIZE = 7;

    public static long create(int mode) {
        return 1L; // non-zero sentinel so MeshtasticReceiver skips the "c2 == 0" error log
    }

    public static int getSamplesPerFrame(long con) {
        return 320;
    }

    public static int getBitsSize(long con) {
        return 8;
    }

    public static int destroy(long con) {
        return 0;
    }

    public static long encode(long con, short[] buf, char[] bits) {
        return 0;
    }

    public static long decode(long con, short[] outputBuffer, byte[] bits) {
        return 0;
    }
}
