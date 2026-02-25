package com.atakmap.android.meshtastic;

import com.atakmap.android.meshtastic.util.Constants;
import com.atakmap.android.meshtastic.util.FileTransferManager;
import java.util.concurrent.CompletableFuture;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.widget.Toast;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.missionpackage.api.SaveAndSendCallback;
import com.atakmap.android.missionpackage.file.MissionPackageManifest;
import com.atakmap.android.missionpackage.file.task.MissionPackageBaseTask;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import org.meshtastic.proto.Config;
import org.meshtastic.proto.LocalConfig;

import java.io.File;

public class MeshtasticCallback implements SaveAndSendCallback {
    private final static String TAG = "MeshtasticCallback";

    @Override
    public void onMissionPackageTaskComplete(MissionPackageBaseTask missionPackageBaseTask, boolean success) {

        Log.d(TAG, "onMissionPackageTaskComplete: " + success);

        MissionPackageManifest missionPackageManifest = missionPackageBaseTask.getManifest();

        File file = new File(missionPackageManifest.getPath());
        Log.d(TAG, file.getAbsolutePath());

        SharedPreferences prefs = new ProtectedSharedPreferences(PreferenceManager.getDefaultSharedPreferences(MapView.getMapView().getContext()));
        SharedPreferences.Editor editor = prefs.edit();

        if (FileSystemUtils.isFile(file)) {
            // Check if file transfer is enabled in preferences
            if (!prefs.getBoolean(Constants.PREF_PLUGIN_FILE_TRANSFER, false)) {
                showToast("File transfer is disabled.\nEnable in Meshtastic plugin settings.");
                Log.w(TAG, "File transfer blocked - disabled in preferences");
                return;
            }

            // check file size
            if (FileSystemUtils.getFileSize(file) > 1024 * 56) {
                showToast("File is too large to send, 56KB Max");
                return;
            }

            Log.d(TAG, "File is small enough to send: " + FileSystemUtils.getFileSize(file));

            // Check if we're on Short_Turbo modem preset
            byte[] config = MeshtasticMapComponent.getConfig();
            if (config == null || config.length == 0) {
                showToast("Cannot get radio config. Is Meshtastic connected?");
                return;
            }

            LocalConfig c;
            try {
                c = LocalConfig.ADAPTER.decode(config);
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse config", e);
                showToast("Failed to read radio config");
                return;
            }

            Config.LoRaConfig lc = c.getLora();
            int currentModemPreset = lc.getModem_preset().getValue();

            // Check if on Short_Turbo (value 0)
            if (currentModemPreset != 0) {
                String presetName = lc.getModem_preset().name();
                showToast("File transfer requires Short_Turbo preset.\nCurrently on: " + presetName);
                Log.w(TAG, "File transfer blocked - not on Short_Turbo. Current preset: " + presetName);
                return;
            }

            // We're on Short_Turbo, proceed with file transfer
            Log.d(TAG, "On Short_Turbo preset, proceeding with file transfer");

            // Block other transfers while file transfer is in progress
            editor.putBoolean(Constants.PREF_PLUGIN_CHUNKING, true);
            editor.apply();

            CompletableFuture.runAsync(() -> {
                // Start file transfer with proper tracking
                FileTransferManager transferManager = FileTransferManager.getInstance();
                CompletableFuture<Boolean> transferFuture = transferManager.startTransfer();

                if (MeshtasticMapComponent.sendFile(file)) {
                    Log.d(TAG, "File sending initiated");

                    // Wait for transfer completion with timeout
                    try {
                        boolean transferSuccess = transferFuture.get();
                        if (transferSuccess) {
                            Log.d(TAG, "File transfer completed successfully");
                        } else {
                            Log.w(TAG, "File transfer failed or timed out");
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error during file transfer", e);
                        transferManager.cancelTransfer();
                    }
                } else {
                    Log.d(TAG, "File send initiation failed");
                    transferManager.cancelTransfer();
                }

                // Clear chunking flag when done
                editor.putBoolean(Constants.PREF_PLUGIN_CHUNKING, false);
                editor.apply();
            }).exceptionally(ex -> {
                Log.e(TAG, "Error in file transfer operation", ex);
                editor.putBoolean(Constants.PREF_PLUGIN_CHUNKING, false);
                editor.apply();
                return null;
            });
        } else {
            Log.d(TAG, "Invalid file");
        }
    }

    /**
     * Show a toast on the UI thread.
     * onMissionPackageTaskComplete is called from a background thread,
     * so we need to post Toast to the UI thread.
     */
    private void showToast(String message) {
        MapView.getMapView().post(() ->
            Toast.makeText(MapView.getMapView().getContext(), message, Toast.LENGTH_LONG).show()
        );
    }
}
