package com.atakmap.android.meshtastic.encryption;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.ResultPoint;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.BarcodeView;

import java.util.List;

/**
 * Displays an in-app QR code scanner dialog for importing an encryption key.
 *
 * <p>Uses {@link BarcodeView} (not CompoundBarcodeView) because CompoundBarcodeView
 * inflates zxing_barcode_scanner.xml using the Activity context. ATAK's Activity
 * context only has ATAK's resources, not the plugin's, causing Resources$NotFoundException.
 * BarcodeView builds its SurfaceView programmatically — no layout inflation, no
 * resource ID lookup — so it works correctly inside ATAK's Activity window.
 */
public class KeyQrScanDialog {

    public interface OnKeyScanned {
        void onKeyScanned(String psk);
    }

    public static void show(Activity activity, OnKeyScanned callback) {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getMetrics(dm);
        int shortSidePx = Math.min(dm.widthPixels, dm.heightPixels);

        // BarcodeView builds its SurfaceView programmatically — no layout XML inflation,
        // so plugin resource IDs don't need to resolve in ATAK's resource context.
        BarcodeView barcodeView = new BarcodeView(activity);
        LinearLayout.LayoutParams barcodeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (shortSidePx * 0.7f));
        barcodeView.setLayoutParams(barcodeParams);

        TextView hint = new TextView(activity);
        hint.setText("Point at team member's encryption key QR code");
        hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        hint.setPadding(0, dp(activity, 8), 0, 0);

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 12);
        layout.setPadding(pad, pad, pad, pad);
        layout.addView(barcodeView);
        layout.addView(hint);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Scan Encryption Key")
                .setView(layout)
                .setNegativeButton("Cancel", null)
                .create();

        barcodeView.decodeContinuous(new BarcodeCallback() {
            @Override
            public void barcodeResult(BarcodeResult result) {
                if (result.getText() != null && !result.getText().isEmpty()) {
                    barcodeView.pause();
                    dialog.dismiss();
                    callback.onKeyScanned(result.getText().trim());
                }
            }

            @Override
            public void possibleResultPoints(List<ResultPoint> resultPoints) {}
        });

        dialog.setOnShowListener(d -> barcodeView.resume());
        dialog.setOnDismissListener(d -> barcodeView.pause());
        dialog.show();

        dialog.getWindow().setLayout(
                (int) (dm.widthPixels * 0.90f),
                (int) (dm.heightPixels * 0.90f));
    }

    private static int dp(Activity activity, int dp) {
        return Math.round(dp * activity.getResources().getDisplayMetrics().density);
    }
}
