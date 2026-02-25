package com.atakmap.android.meshtastic.encryption;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.atakmap.coremap.log.Log;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;

import java.util.EnumMap;
import java.util.Map;

/**
 * Displays the current encryption key as a scannable QR code dialog.
 *
 * <p>The QR code encodes the PSK string exactly as stored in SharedPreferences —
 * the same passphrase or raw base64 value the leader typed. This ensures the leader
 * and all team members see identical values in their PSK fields after import.
 *
 * <p>Recipient workflow: scan QR with camera app → camera copies text to clipboard
 * → paste into PSK field in Meshtastic preferences.
 *
 * <p>{@link #buildQrPixels(String, int)} is package-private and Android-free so
 * unit tests can exercise QR generation without the Android framework.
 */
public class KeyQrDialog {

    private static final String TAG = "KeyQrDialog";

    /** Pixel dimensions of the generated QR bitmap. */
    static final int QR_SIZE_PX = 600;

    /**
     * Generate QR code pixel data for the given content string.
     *
     * <p>Package-private and Context-free — suitable for unit tests.
     *
     * @param content the PSK value to encode
     * @param size    output dimensions in pixels (square)
     * @return ARGB pixel array, length == size * size
     * @throws IllegalArgumentException if content is null or empty
     * @throws WriterException          if ZXing cannot encode the content
     */
    static int[] buildQrPixels(String content, int size) throws WriterException {
        if (content == null || content.isEmpty()) {
            throw new IllegalArgumentException("QR content must not be empty");
        }

        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 1);

        BitMatrix matrix = new MultiFormatWriter()
                .encode(content, BarcodeFormat.QR_CODE, size, size, hints);

        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            int offset = y * size;
            for (int x = 0; x < size; x++) {
                // Avoid android.graphics.Color dependency in the pure-Java section.
                // 0xFF000000 = opaque black, 0xFFFFFFFF = opaque white.
                pixels[offset + x] = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            }
        }
        return pixels;
    }

    /**
     * Show the key QR code dialog.
     *
     * <p>If {@code pskValue} is empty, shows an explanatory error dialog instead.
     * All error paths are handled internally so callers do not need try/catch.
     *
     * @param activity calling Activity (required for AlertDialog)
     * @param pskValue PSK string from SharedPreferences
     */
    public static void show(Activity activity, String pskValue) {
        if (pskValue == null || pskValue.isEmpty()) {
            new AlertDialog.Builder(activity)
                    .setTitle("No Key Configured")
                    .setMessage("Configure an encryption key in the PSK field before displaying the QR code.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        Bitmap qrBitmap;
        try {
            int[] pixels = buildQrPixels(pskValue, QR_SIZE_PX);
            qrBitmap = Bitmap.createBitmap(pixels, QR_SIZE_PX, QR_SIZE_PX, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            Log.e(TAG, "QR generation failed", e);
            new AlertDialog.Builder(activity)
                    .setTitle("QR Generation Failed")
                    .setMessage("Could not generate QR code: " + e.getMessage())
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getMetrics(dm);

        // Root layout fills the dialog window — QR image takes all remaining height via weight.
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 12);
        layout.setPadding(pad, pad, pad, pad);
        layout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        TextView warning = new TextView(activity);
        warning.setText("KEEP SCREEN PRIVATE — Authorized team members only.");
        warning.setTextColor(0xFFCC0000);
        warning.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams warnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        warnParams.bottomMargin = dp(activity, 6);
        warning.setLayoutParams(warnParams);
        layout.addView(warning);

        // weight=1, height=0 — ImageView expands to fill all space between the text rows.
        ImageView imageView = new ImageView(activity);
        imageView.setImageBitmap(qrBitmap);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams imgParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        imageView.setLayoutParams(imgParams);
        layout.addView(imageView);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Encryption Key QR Code")
                .setView(layout)
                .setPositiveButton("Close", null)
                .create();
        dialog.show();
        // Size dialog to 90% of the screen in both dimensions so QR has maximum room.
        dialog.getWindow().setLayout(
                (int) (dm.widthPixels * 0.90f),
                (int) (dm.heightPixels * 0.90f));
    }

    private static int dp(Activity activity, int dp) {
        return Math.round(dp * activity.getResources().getDisplayMetrics().density);
    }
}
