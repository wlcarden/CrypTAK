package com.atakmap.android.meshtastic;

import static com.atakmap.android.meshtastic.util.Constants.PREF_PLUGIN_SHORTTURBO;
import com.atakmap.android.meshtastic.util.Constants;
import com.atakmap.android.meshtastic.util.AckManager;
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
import org.meshtastic.proto.ConfigProtos;
import org.meshtastic.core.model.DataPacket;
import org.meshtastic.proto.LocalOnlyProtos;
import org.meshtastic.core.model.MessageStatus;
import org.meshtastic.proto.Portnums;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.File;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

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
            // check file size
            if (FileSystemUtils.getFileSize(file) > 1024 * 56) {
                Toast.makeText(MapView.getMapView().getContext(), "File is too large to send, 56KB Max", Toast.LENGTH_LONG).show();
                editor.putBoolean(Constants.PREF_PLUGIN_FILE_TRANSFER, false);
                return;
            } else {
                Log.d(TAG, "File is small enough to send: " + FileSystemUtils.getFileSize(file));

                // flag to indicate we are in a file transfer mode
                editor.putBoolean(Constants.PREF_PLUGIN_FILE_TRANSFER, true);
                editor.apply();

                // capture node's config
                byte[] config = MeshtasticMapComponent.getConfig();
                Log.d(TAG, "Config Size: " + config.length);
                LocalOnlyProtos.LocalConfig c;
                try {
                    c = LocalOnlyProtos.LocalConfig.parseFrom(config);
                } catch (InvalidProtocolBufferException e) {
                    throw new RuntimeException(e);
                }
                Log.d(TAG, "Config: " + c.toString());
                ConfigProtos.Config.LoRaConfig lc = c.getLora();

                // retain old modem preset
                int oldModemPreset = lc.getModemPreset().getNumber();

                // configure short/fast mode
                ConfigProtos.Config.Builder configBuilder = ConfigProtos.Config.newBuilder();
                AtomicReference<ConfigProtos.Config.LoRaConfig.Builder> loRaConfigBuilder = new AtomicReference<>(lc.toBuilder());
                AtomicReference<ConfigProtos.Config.LoRaConfig.ModemPreset> modemPreset = new AtomicReference<>(ConfigProtos.Config.LoRaConfig.ModemPreset.forNumber(ConfigProtos.Config.LoRaConfig.ModemPreset.SHORT_TURBO_VALUE));
                loRaConfigBuilder.get().setModemPreset(modemPreset.get());
                configBuilder.setLora(loRaConfigBuilder.get());
                boolean needReboot;

                // if not already in short/fast mode, switch to it
                if (oldModemPreset != ConfigProtos.Config.LoRaConfig.ModemPreset.SHORT_TURBO_VALUE) {
                    Toast.makeText(MapView.getMapView().getContext(), "Rebooting to Short/TURBO for file transfer", Toast.LENGTH_LONG).show();
                    needReboot = true;
                } else {
                    needReboot = false;
                }

                CompletableFuture.runAsync(() -> {

                    // send out file transfer command
                    int channel = MeshtasticReceiver.getChannelIndex();
                    int messageId = ThreadLocalRandom.current().nextInt(0x10000000, 0x7fffff00);
                    Log.d(TAG, "Switch Message ID: " + messageId);
                    editor.putInt(Constants.PREF_PLUGIN_SWITCH_ID, messageId);
                    editor.apply();

                    // Register for ACK tracking
                    AckManager ackManager = AckManager.getInstance();
                    ackManager.registerForAck(messageId);

                    Log.d(TAG, "Broadcasting switch command");
                    DataPacket dp = new DataPacket(DataPacket.ID_BROADCAST, new byte[]{'S', 'W', 'T'}, Portnums.PortNum.ATAK_FORWARDER_VALUE, DataPacket.ID_LOCAL, System.currentTimeMillis(), messageId, MessageStatus.UNKNOWN, 3, channel, true, 0, 0f, 0, null);
                    MeshtasticMapComponent.sendToMesh(dp);

                    // Wait for ACK with timeout
                    AckManager.AckResult result = ackManager.waitForAck(messageId, 10000); // 10 second timeout
                    if (result.timeout) {
                        Log.w(TAG, "Switch command ACK timed out");
                        return; // Exit early on timeout
                    } else if (!result.success) {
                        Log.w(TAG, "Switch command failed: " + result.status);
                        return; // Exit early on failure
                    } else {
                        Log.d(TAG, "Switch command acknowledged");
                    }

                    // Handle reboot if needed
                    if (needReboot) {
                        // Wait for remote nodes to reboot
                        try {
                            Thread.sleep(2000); // Give remote nodes time to process
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            Log.e(TAG, "Interrupted during reboot wait", e);
                            return;
                        }
                        
                        // flag to indicate we are waiting for a reboot into short/fast
                        editor.putBoolean(PREF_PLUGIN_SHORTTURBO, true);
                        editor.apply();

                        MeshtasticMapComponent.setConfig(configBuilder.build().toByteArray());

                        // Wait for config change with timeout
                        long startTime = System.currentTimeMillis();
                        while (prefs.getBoolean(PREF_PLUGIN_SHORTTURBO, false)) {
                            if (System.currentTimeMillis() - startTime > 30000) { // 30 second timeout
                                Log.e(TAG, "Config change timeout");
                                break;
                            }
                            try {
                                Thread.sleep(100); // Shorter sleep for more responsive checking
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                Log.e(TAG, "Interrupted while waiting for config change", e);
                                return;
                            }
                        }
                    }

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

                    if (needReboot) {
                        // restore config
                        Log.d(TAG, "Restoring previous modem preset");
                        loRaConfigBuilder.set(lc.toBuilder());
                        modemPreset.set(ConfigProtos.Config.LoRaConfig.ModemPreset.forNumber(oldModemPreset));
                        loRaConfigBuilder.get().setModemPreset(modemPreset.get());
                        configBuilder.setLora(loRaConfigBuilder.get());
                        MeshtasticMapComponent.setConfig(configBuilder.build().toByteArray());
                    }
                }).exceptionally(ex -> {
                    Log.e(TAG, "Error in file transfer operation", ex);
                    return null;
                });
            }
        } else {
            Log.d(TAG, "Invalid file");
        }
    }
}
