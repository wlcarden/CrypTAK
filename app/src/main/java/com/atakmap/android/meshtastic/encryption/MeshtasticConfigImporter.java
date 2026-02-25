package com.atakmap.android.meshtastic.encryption;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;

import com.atakmap.android.importexport.AbstractImporter;
import com.atakmap.android.importexport.ImporterManager;
import com.atakmap.android.meshtastic.ProtectedSharedPreferences;
import com.atakmap.android.meshtastic.util.Constants;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.coremap.log.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Importer for Meshtastic onboarding Data Packages (.mstcfg files inside
 * ATAK Mission Package ZIPs).
 *
 * Two entry points:
 *  - {@link #applyFromZipUri}: called by the in-app Import button (PluginPreferencesFragment).
 *    Uses the ATAK activity context so prefs are written to the same file that
 *    AppLayerEncryptionManager.initialize(view.getContext()) reads.
 *  - {@link #importData}: called by ATAK's ImporterManager if/when it dispatches a .mstcfg
 *    file found inside a Mission Package ZIP (TODO: validate dispatch during hardware testing).
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

    /**
     * Opens a Mission Package ZIP from a content URI, extracts meshtastic-config.mstcfg,
     * and applies the PSK and TAK server settings to SharedPreferences.
     *
     * Pass the ATAK activity context (from getActivity() in PluginPreferencesFragment) so
     * prefs are written to the same file that AppLayerEncryptionManager reads on startup.
     */
    public static boolean applyFromZipUri(Context context, Uri uri) {
        try (InputStream raw = context.getContentResolver().openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (DataPackageExporter.CONFIG_FILENAME.equals(entry.getName())) {
                    String json = new String(readAllBytes(zip), StandardCharsets.UTF_8).trim();
                    zip.closeEntry();
                    return applyFullConfig(context, json);
                }
                zip.closeEntry();
            }
            Log.w(TAG, "No " + DataPackageExporter.CONFIG_FILENAME + " found in ZIP");
            return false;
        } catch (IOException e) {
            Log.e(TAG, "Failed to read ZIP", e);
            return false;
        }
    }

    private static boolean applyFullConfig(Context context, String json) {
        String psk = extractJsonString(json, "psk");
        if (psk == null || psk.isEmpty()) {
            Log.w(TAG, "No PSK in config");
            return false;
        }
        if (!AppLayerEncryptionManager.getInstance().importKeyAndSave(context, psk, true)) {
            Log.w(TAG, "PSK import failed");
            return false;
        }
        String host = extractJsonString(json, "host");
        int port = extractJsonInt(json, "port", 8087);
        SharedPreferences sp = new ProtectedSharedPreferences(
                PreferenceManager.getDefaultSharedPreferences(context));
        SharedPreferences.Editor editor = sp.edit()
                .putString(Constants.PREF_PLUGIN_ENCRYPTION_PSK_ORIGIN, "package");
        if (host != null && !host.isEmpty()) {
            editor.putString(Constants.PREF_TAK_SERVER_HOST, host)
                    .putString(Constants.PREF_TAK_SERVER_PORT, String.valueOf(port));
            Log.i(TAG, "TAK server set to " + host + ":" + port);
        }
        editor.apply();
        Log.i(TAG, "Config imported from ZIP");
        return true;
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

    /** Minimal JSON integer field extractor -- no library dependency. */
    private static int extractJsonInt(String json, String key, int defaultValue) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return defaultValue;
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return defaultValue;
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        if (end == start) return defaultValue;
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        return baos.toByteArray();
    }
}
