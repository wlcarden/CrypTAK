package com.atakmap.android.meshtastic.encryption;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import com.atakmap.android.importexport.AbstractImporter;
import com.atakmap.android.importexport.ImporterManager;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.coremap.log.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;

/**
 * Importer skeleton for Meshtastic onboarding Data Packages (.mstcfg files inside
 * ATAK Mission Package ZIPs).
 *
 * TODO: Validate ATAK extension-based dispatch during hardware testing. ATAK's
 * ImporterManager should dispatch .mstcfg files found inside Mission Package ZIPs
 * to this importer. If dispatch does not occur, import falls back to manual:
 * extract meshtastic-config.mstcfg from ZIP -> open with any text editor ->
 * copy PSK value -> paste into ATAK plugin preferences.
 *
 * Register/unregister via ImporterManager in MeshtasticMapComponent.
 */
public class MeshtasticConfigImporter extends AbstractImporter {

    private static final String TAG = "MeshtasticConfigImporter";
    private final Context pluginContext;

    public MeshtasticConfigImporter(Context pluginContext) {
        super("Meshtastic Config Importer");
        this.pluginContext = pluginContext;
    }

    @Override
    public Set<String> getSupportedMIMETypes() {
        // application/octet-stream is the fallback for unknown extensions.
        // TODO: Update to a more specific type once hardware testing confirms dispatch behavior.
        return Collections.singleton("application/octet-stream");
    }

    @Override
    public CommsMapComponent.ImportResult importData(InputStream is, String mime, Bundle extras)
            throws IOException {
        try {
            String json = new String(readAllBytes(is), StandardCharsets.UTF_8).trim();
            return applyConfig(json);
        } catch (IOException e) {
            Log.e(TAG, "Failed to read config stream", e);
            return CommsMapComponent.ImportResult.FAILURE;
        }
    }

    @Override
    public CommsMapComponent.ImportResult importData(Uri uri, String mime, Bundle extras)
            throws IOException {
        try (InputStream is = pluginContext.getContentResolver().openInputStream(uri)) {
            if (is == null) return CommsMapComponent.ImportResult.FAILURE;
            return importData(is, mime, extras);
        } catch (IOException e) {
            Log.e(TAG, "Failed to open URI for import", e);
            return CommsMapComponent.ImportResult.FAILURE;
        }
    }

    private CommsMapComponent.ImportResult applyConfig(String json) {
        // TODO: Replace with proper JSON parser (org.json or Gson) once available.
        // Minimal extraction: find "psk" field value.
        try {
            String psk = extractJsonString(json, "psk");
            if (psk == null || psk.isEmpty()) {
                Log.w(TAG, "No PSK found in config");
                return CommsMapComponent.ImportResult.IGNORE;
            }
            boolean ok = AppLayerEncryptionManager.getInstance()
                    .importKeyAndSave(pluginContext, psk, true);
            if (ok) {
                Log.i(TAG, "Meshtastic config imported successfully");
                return CommsMapComponent.ImportResult.SUCCESS;
            } else {
                Log.w(TAG, "PSK import failed -- invalid format");
                return CommsMapComponent.ImportResult.FAILURE;
            }
        } catch (Exception e) {
            Log.e(TAG, "Config apply failed", e);
            return CommsMapComponent.ImportResult.FAILURE;
        }
    }

    /** Minimal JSON string field extractor -- no library dependency. */
    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return null;
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        int end = json.indexOf('"', start + 1);
        if (end < 0) return null;
        return json.substring(start + 1, end);
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        return baos.toByteArray();
    }
}
