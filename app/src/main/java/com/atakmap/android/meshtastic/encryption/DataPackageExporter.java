package com.atakmap.android.meshtastic.encryption;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.atakmap.android.meshtastic.util.Constants;
import com.atakmap.coremap.log.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class DataPackageExporter {

    private static final String TAG = "DataPackageExporter";
    static final String CONFIG_FILENAME = "meshtastic-config.mstcfg";
    static final String MANIFEST_FILENAME = "MANIFEST/manifest.xml";

    /** Escapes a string value for safe embedding in a JSON string literal. */
    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Pure Java -- no Context needed. Returns ZIP bytes as a byte array.
     * Testable without Android framework.
     */
    static byte[] buildPackageBytes(String pskValue, boolean epochEnabled,
                                     int epochIntervalHours,
                                     String serverHost, int serverPort) throws IOException {
        String json = "{\n"
                + "  \"version\": 1,\n"
                + "  \"psk\": \"" + escapeJson(pskValue) + "\",\n"
                + "  \"encryption_enabled\": true,\n"
                + "  \"epoch_rotation\": " + epochEnabled + ",\n"
                + "  \"epoch_interval_hours\": " + epochIntervalHours + ",\n"
                + "  \"tak_servers\": [\n"
                + "    {\"host\": \"" + escapeJson(serverHost) + "\", \"port\": " + serverPort
                + ", \"ssl\": false, \"label\": \"Primary\"}\n"
                + "  ]\n"
                + "}";

        String manifest = "<?xml version=\"1.0\"?>\n"
                + "<MissionPackageManifest version=\"2\">\n"
                + "  <Configuration>\n"
                + "    <Parameter name=\"name\" value=\"meshtastic-onboarding\"/>\n"
                + "    <Parameter name=\"onReceiveDelete\" value=\"true\"/>\n"
                + "  </Configuration>\n"
                + "  <Contents>\n"
                + "    <Content ignore=\"false\" zipEntry=\"" + CONFIG_FILENAME + "\"/>\n"
                + "  </Contents>\n"
                + "</MissionPackageManifest>\n";

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(baos)) {
            zip.putNextEntry(new ZipEntry(CONFIG_FILENAME));
            zip.write(json.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry(MANIFEST_FILENAME));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return baos.toByteArray();
    }

    /**
     * Generates the Data Package ZIP and fires a share intent.
     * Reads PSK, epoch settings, and server profile from SharedPreferences.
     */
    public static void generateAndShare(Activity activity, SharedPreferences prefs)
            throws IOException {
        String psk = prefs.getString(Constants.PREF_PLUGIN_ENCRYPTION_PSK, "").trim();
        if (psk.isEmpty()) {
            Toast.makeText(activity, "No encryption key configured", Toast.LENGTH_SHORT).show();
            return;
        }

        boolean epochEnabled = prefs.getBoolean(Constants.PREF_PLUGIN_EPOCH_ROTATION, false);
        int epochIntervalHours;
        try {
            epochIntervalHours = Integer.parseInt(
                    prefs.getString(Constants.PREF_PLUGIN_EPOCH_INTERVAL, "6"));
        } catch (NumberFormatException e) {
            epochIntervalHours = 6;
        }
        String serverHost = prefs.getString(Constants.PREF_TAK_SERVER_HOST, "").trim();
        int serverPort;
        try {
            serverPort = Integer.parseInt(
                    prefs.getString(Constants.PREF_TAK_SERVER_PORT, "8087").trim());
        } catch (NumberFormatException e) {
            serverPort = 8087;
        }

        byte[] zipBytes = buildPackageBytes(psk, epochEnabled, epochIntervalHours,
                serverHost, serverPort);

        Uri shareUri;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+: write to MediaStore Downloads.
            // The plugin's FileProvider authority is owned by the plugin package UID, but this
            // code runs inside ATAK's process (different UID). Android's URI grant check in
            // UriGrantsManagerService rejects FLAG_GRANT_READ_URI_PERMISSION when the calling
            // UID doesn't own the FileProvider authority. MediaStore URIs are owned by the
            // calling app's UID (ATAK here), so the grant check passes.
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, "meshtastic-onboarding.zip");
            cv.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
            Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            // Remove any stale previous version before inserting.
            activity.getContentResolver().delete(collection,
                    MediaStore.Downloads.DISPLAY_NAME + "=?",
                    new String[]{"meshtastic-onboarding.zip"});
            shareUri = activity.getContentResolver().insert(collection, cv);
            if (shareUri == null) {
                throw new IOException("MediaStore insert failed");
            }
            try (OutputStream os = activity.getContentResolver().openOutputStream(shareUri)) {
                if (os == null) throw new IOException("Could not open MediaStore output stream");
                os.write(zipBytes);
            }
            Log.i(TAG, "Data package written to MediaStore: " + shareUri);
        } else {
            // Fallback for Android < 10.
            File externalDir = activity.getExternalFilesDir(null);
            if (externalDir == null) {
                throw new IOException("External storage is unavailable");
            }
            File exportsDir = new File(externalDir, "exports");
            if (!exportsDir.exists() && !exportsDir.mkdirs()) {
                throw new IOException("Failed to create exports directory");
            }
            File outFile = new File(exportsDir, "meshtastic-onboarding.zip");
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(zipBytes);
            }
            Log.i(TAG, "Data package written to: " + outFile.getAbsolutePath());
            shareUri = FileProvider.getUriForFile(activity,
                    "com.atakmap.android.meshtastic.plugin.provider", outFile);
        }

        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("application/zip");
        shareIntent.putExtra(Intent.EXTRA_STREAM, shareUri);
        shareIntent.setClipData(ClipData.newRawUri("", shareUri));
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(Intent.createChooser(shareIntent, "Share Onboarding Package"));
    }
}
