package com.atakmap.android.meshtastic.encryption;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

import com.atakmap.coremap.log.Log;


/**
 * BroadcastReceiver that handles encryption key import via broadcast intent.
 *
 * <h3>Supported intents:</h3>
 * <ul>
 *   <li>{@code com.atakmap.android.meshtastic.IMPORT_ENCRYPTION_KEY} — direct key import.
 *       Expects extra {@code "key"} containing a base64-encoded 32-byte key.</li>
 * </ul>
 *
 * <h3>Usage:</h3>
 * <pre>
 *   Intent intent = new Intent("com.atakmap.android.meshtastic.IMPORT_ENCRYPTION_KEY");
 *   intent.putExtra("key", "K7gNU3sdo+OL0wNhqoVWhr3g6s1xYv72ol/pe/Unols=");
 *   context.sendBroadcast(intent);
 * </pre>
 */
public class KeyImportReceiver extends BroadcastReceiver {
    private static final String TAG = "KeyImportReceiver";

    public static final String ACTION_IMPORT_KEY =
            "com.atakmap.android.meshtastic.IMPORT_ENCRYPTION_KEY";
    public static final String EXTRA_KEY = "key";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;

        String action = intent.getAction();
        Log.d(TAG, "Received intent: " + action);

        String base64Key = null;

        if (ACTION_IMPORT_KEY.equals(action)) {
            base64Key = intent.getStringExtra(EXTRA_KEY);
        }

        if (base64Key == null || base64Key.trim().isEmpty()) {
            Log.w(TAG, "No key data in intent");
            showToast(context, "Key import failed: no key data received");
            return;
        }

        AppLayerEncryptionManager encManager = AppLayerEncryptionManager.getInstance();
        boolean success = encManager.importKeyAndSave(context, base64Key, true);

        if (success) {
            Log.i(TAG, "Encryption key imported successfully");
            showToast(context, "Encryption key imported and enabled");
        } else {
            Log.w(TAG, "Key import failed: invalid key format");
            showToast(context,
                    "Key import failed: key must be 32 bytes base64-encoded "
                            + "(generate with: openssl rand -base64 32)");
        }
    }

    private void showToast(Context context, String message) {
        try {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.w(TAG, "Could not show toast: " + e.getMessage());
        }
    }
}
