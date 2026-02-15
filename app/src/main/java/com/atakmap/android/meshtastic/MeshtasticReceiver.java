package com.atakmap.android.meshtastic;

import static android.content.Context.NOTIFICATION_SERVICE;

import static com.atakmap.android.maps.MapView.*;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import com.atakmap.android.meshtastic.cot.CotEventProcessor;
import com.atakmap.android.meshtastic.encryption.AppLayerEncryptionManager;
import com.atakmap.android.meshtastic.util.Constants;
import com.atakmap.android.meshtastic.util.CryptoUtils;
import com.atakmap.android.meshtastic.util.FileTransferManager;
import com.atakmap.android.meshtastic.util.NotificationHelper;
import com.atakmap.android.meshtastic.util.fountain.FountainChunkManager;
import com.atakmap.android.meshtastic.util.fountain.FountainPacket;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;

import android.os.Bundle;
import android.os.Environment;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.speech.tts.TextToSpeech;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.cot.CotMapComponent;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.meshtastic.plugin.R;
import com.atakmap.comms.CotServiceRemote;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotPoint;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.time.CoordinatedTime;

import okio.ByteString;
import org.meshtastic.core.model.Position;
import org.meshtastic.proto.GeoChat;
import org.meshtastic.proto.Group;
import org.meshtastic.proto.MemberRole;
import org.meshtastic.proto.PLI;
import org.meshtastic.proto.Status;
import org.meshtastic.proto.TAKPacket;
import org.meshtastic.proto.Team;
import org.meshtastic.core.model.DataPacket;

import org.meshtastic.core.model.MessageStatus;
import org.meshtastic.core.model.NodeInfo;
import org.meshtastic.proto.PortNum;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.zip.Inflater;
import java.util.zip.DataFormatException;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.ustadmobile.codec2.Codec2;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

public class MeshtasticReceiver extends BroadcastReceiver implements CotServiceRemote.CotEventListener {
    // constants
    private static final String TAG = "MeshtasticReceiver";
    private static NotificationManager mNotifyManager;
    private static NotificationCompat.Builder mBuilder;
    private static NotificationChannel mChannel;
    private static int id = Constants.NOTIFICATION_ID;
    private static int RECORDER_SAMPLERATE = Constants.AUDIO_SAMPLE_RATE;
    // shared prefs
    private static ProtectedSharedPreferences prefs = new ProtectedSharedPreferences(
            PreferenceManager.getDefaultSharedPreferences(MapView.getMapView().getContext())
    );
    private ProtectedSharedPreferences.Editor editor = prefs.edit();
    // audio playback
    private short playbackBuf[] = null;
    private AudioTrack track = null;
    private int samplesBufSize = 0;
    private boolean audioPermissionGranted = false;
    // misc
    private long c2 = 0;
    // Meshtastic external gps
    private final MeshtasticExternalGPS meshtasticExternalGPS;
    // Fountain code chunk manager for large transfers
    private final FountainChunkManager fountainChunkManager;
    // Lookup tables: Meshtastic node ID -> ATAK info (populated from PLI/GeoChat TAKPackets)
    private static final java.util.concurrent.ConcurrentHashMap<String, String> nodeIdToCallsign =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, String> nodeIdToDeviceUid =
            new java.util.concurrent.ConcurrentHashMap<>();

    public MeshtasticReceiver(MeshtasticExternalGPS meshtasticExternalGPS, FountainChunkManager fountainChunkManager) {
        this.meshtasticExternalGPS = meshtasticExternalGPS;
        this.fountainChunkManager = fountainChunkManager;
        int permissionCheck = ContextCompat.checkSelfPermission(MapView.getMapView().getContext(), Manifest.permission.RECORD_AUDIO);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "REC AUDIO DENIED");
        } else {
            this.audioPermissionGranted = true;
        }

        this.mNotifyManager = (NotificationManager) getMapView().getContext().getSystemService(NOTIFICATION_SERVICE);
        this.mChannel = new NotificationChannel("com.atakmap.android.meshtastic", "Meshtastic Notifications", NotificationManager.IMPORTANCE_DEFAULT); // correct Constant
        this.mChannel.setSound(null, null);
        this.mNotifyManager.createNotificationChannel(mChannel);

        Intent atakFrontIntent = new Intent();
        atakFrontIntent.setComponent(new ComponentName("com.atakmap.app.civ", "com.atakmap.app.ATAKActivity"));
        atakFrontIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        atakFrontIntent.putExtra("internalIntent", new Intent("com.atakmap.android.meshtastic.SHOW_PLUGIN"));
        PendingIntent appIntent = PendingIntent.getActivity(getMapView().getContext(), 0, atakFrontIntent, PendingIntent.FLAG_IMMUTABLE);

        mBuilder = new NotificationCompat.Builder(_mapView.getContext(), "com.atakmap.android.meshtastic");
        mBuilder.setContentTitle("Meshtastic File Transfer")
                .setContentText("Transfer in progress")
                .setSmallIcon(R.drawable.ic_launcher)
                .setAutoCancel(true)
                .setOngoing(false)
                .setContentIntent(appIntent);

        // codec2 recorder/playback - hardcoded to 700C for compatibility
        // NOTE: All devices must use the same codec mode for audio to work properly
        try {
            String arch = System.getProperty("os.arch");
            if (arch != null) {
                String a = arch.toLowerCase();
                if (a.contains("64") || a.contains("aarch64") || a.contains("arm64")) {
                    // Codec2 Recorder/Playback - hardcoded to 700C for compatibility
                    // NOTE: All devices must use the same codec mode for audio to work properly
                    c2 = Codec2.create(Codec2.CODEC2_MODE_700C);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (c2 == 0) {
            Log.e(TAG, "Failed to create Codec2 with mode 700C");
        }
        this.samplesBufSize = Codec2.getSamplesPerFrame(c2);
        Log.d(TAG, "Codec2 initialized with mode 700C, samples per frame: " + samplesBufSize);

        // Set up fountain chunk manager callback to process received data
        if (fountainChunkManager != null) {
            fountainChunkManager.setCallback(new FountainChunkManager.TransferCallback() {
                // Track if we've shown a notification for this transfer (for >5 block transfers)
                private int activeReceiveTransferId = -1;
                // Track transfers we've already shown failure for (prevent duplicate toasts)
                private final java.util.Set<Integer> failedTransfers = new java.util.HashSet<>();

                @Override
                public void onTransferComplete(int transferId, byte[] data, String senderNodeId, byte transferType) {
                    // Clean up failure tracking
                    failedTransfers.remove(transferId);

                    if (data == null) {
                        // This is a send completion, not receive
                        Log.d(TAG, "Fountain send transfer " + transferId + " completed");

                        // Show success toast if wantAck is enabled (user cares about ACKs)
                        boolean wantAck = prefs.getBoolean(Constants.PREF_PLUGIN_WANT_ACK, true);
                        if (wantAck) {
                            MapView.getMapView().post(() ->
                                Toast.makeText(MapView.getMapView().getContext(),
                                    "Meshtastic: Message delivered", Toast.LENGTH_SHORT).show()
                            );
                        }
                        return;
                    }
                    Log.d(TAG, "Fountain receive transfer " + transferId + " completed, " +
                              data.length + " bytes from " + senderNodeId + ", type=" + transferType);

                    // Show completion notification if we had progress notification
                    if (activeReceiveTransferId == transferId) {
                        NotificationHelper.getInstance(MapView.getMapView().getContext())
                            .showReceiveCompletionNotification();
                        activeReceiveTransferId = -1;
                    }

                    processFountainData(data, senderNodeId, transferType);
                }

                @Override
                public void onTransferFailed(int transferId, String reason) {
                    Log.e(TAG, "Fountain transfer " + transferId + " failed: " + reason);

                    // Check if we've already shown failure for this transfer
                    if (failedTransfers.contains(transferId)) {
                        Log.d(TAG, "Already showed failure for transfer " + transferId + ", skipping");
                        return;
                    }
                    failedTransfers.add(transferId);

                    // Only show failure notifications if wantAck is enabled
                    // When wantAck is disabled, users accept the lack of acknowledgments
                    boolean wantAck = prefs.getBoolean(Constants.PREF_PLUGIN_WANT_ACK, true);
                    if (!wantAck) {
                        Log.d(TAG, "Suppressing transfer failure notification (wantAck disabled)");
                        return;
                    }

                    // Show failure notification/toast
                    if (activeReceiveTransferId == transferId) {
                        NotificationHelper.getInstance(MapView.getMapView().getContext())
                            .showReceiveFailedNotification(reason);
                        activeReceiveTransferId = -1;
                    } else {
                        // Show toast for failures
                        MapView.getMapView().post(() ->
                            Toast.makeText(MapView.getMapView().getContext(),
                                "Meshtastic: Transfer failed - " + reason, Toast.LENGTH_SHORT).show()
                        );
                    }
                }

                @Override
                public void onProgress(int transferId, int received, int total, boolean isSending) {
                    Log.v(TAG, "Fountain transfer " + transferId + ": " + received + "/" + total +
                              (isSending ? " (sending)" : " (receiving)"));

                    // For receiving transfers with >5 blocks, show notification with progress
                    if (!isSending && total > 5) {
                        activeReceiveTransferId = transferId;
                        NotificationHelper.getInstance(MapView.getMapView().getContext())
                            .showReceiveProgressNotification(received, total);
                    }
                }
            });
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        Log.d(TAG, "ACTION: " + action);
        switch (action) {
            case Constants.ACTION_MESH_CONNECTED: {
                String extraConnected = intent.getStringExtra(Constants.EXTRA_CONNECTED);
                Log.d(TAG, "Received ACTION_MESH_CONNECTED: " + extraConnected);
                if (extraConnected == null) {
                    break;
                }
                // Use case-insensitive comparison - Meshtastic app sends "Connected"/"Disconnected"
                if (extraConnected.equalsIgnoreCase(Constants.STATE_CONNECTED)) {
                    // Radio connected - update state and reconnect IPC service
                    MeshtasticMapComponent.setRadioConnected(true);
                    MeshtasticMapComponent.reconnect();
                    // Option A: Fetch MyNodeInfo now that mesh is fully connected and DB is populated
                    try {
                        MeshtasticMapComponent.getMeshService().onMeshConnected();
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to notify mesh service of connection", e);
                    }
                } else if (extraConnected.equalsIgnoreCase(Constants.STATE_DISCONNECTED)) {
                    // Radio disconnected - clear cached data
                    MeshtasticMapComponent.setRadioConnected(false);
                    try {
                        MeshtasticMapComponent.getMeshService().onMeshDisconnected();
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to notify mesh service of disconnection", e);
                    }
                }
                // Ignore other intermediate states
                break;
            }
            case Constants.ACTION_MESH_DISCONNECTED: {
                String extraConnected = intent.getStringExtra(Constants.EXTRA_DISCONNECTED);
                Log.d(TAG, "Received ACTION_MESH_DISCONNECTED: " + extraConnected);
                if (extraConnected == null) {
                    break;
                }
                // Use case-insensitive comparison
                if (extraConnected.equalsIgnoreCase(Constants.STATE_DISCONNECTED)) {
                    // Radio disconnected - clear cached data
                    MeshtasticMapComponent.setRadioConnected(false);
                    try {
                        MeshtasticMapComponent.getMeshService().onMeshDisconnected();
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to notify mesh service of disconnection", e);
                    }
                }
                break;
            }
            case Constants.ACTION_MESSAGE_STATUS:
                int id = intent.getIntExtra(Constants.EXTRA_PACKET_ID, 0);
                MessageStatus status = intent.getParcelableExtra(Constants.EXTRA_STATUS);
                Log.d(TAG, "Message Status ID: " + id + " Status: " + status);
                break;
            case Constants.ACTION_RECEIVED_ATAK_FORWARDER:
            case Constants.ACTION_RECEIVED_ATAK_PLUGIN: {
                Thread thread = new Thread(() -> {
                    try {
                        receive(intent);
                    } catch (Exception e) {
                        e.printStackTrace();
                        return;
                    }
                });
                thread.setName("MeshtasticReceiver.Worker");
                thread.start();
                break;
            }
            case Constants.ACTION_ALERT_APP:
            case Constants.ACTION_TEXT_MESSAGE_APP:
                Log.d(TAG, "Got a meshtastic text message");
                DataPacket payload = intent.getParcelableExtra(Constants.EXTRA_PAYLOAD);
                if (payload == null) return;

                // Apply channel filter if enabled
                if (prefs.getBoolean(Constants.PREF_PLUGIN_FILTER_BY_CHANNEL, false)) {
                    int preferredChannel;
                    try {
                        preferredChannel = Integer.parseInt(prefs.getString(Constants.PREF_PLUGIN_CHANNEL, "0"));
                    } catch (NumberFormatException e) {
                        preferredChannel = 0;
                    }
                    int packetChannel = payload.getChannel();
                    if (packetChannel != preferredChannel) {
                        Log.d(TAG, "Ignoring text message on channel " + packetChannel + ", preferred: " + preferredChannel);
                        return;
                    }
                }
                String message = payload.getBytes().utf8();
                Log.d(TAG, "Message: " + message);
                Log.d(TAG, payload.getTo());

                if (prefs.getBoolean(Constants.PREF_PLUGIN_VOICE, false)) {
                    MeshtasticDropDownReceiver.t1.speak(message, TextToSpeech.QUEUE_FLUSH, null);
                }

                String myNodeID = MeshtasticMapComponent.getMyNodeID();
                if (myNodeID == null) return;

                if (myNodeID.equals(payload.getFrom())) {
                    Log.d(TAG, "Ignoring message from self");
                    return;
                }

                if (payload.getTo().equals("^all")) {
                    Log.d(TAG, "Sending CoT for Text Message");
                    // Look up sender's ATAK info from lookup table (populated from PLI/GeoChat)
                    String senderNodeId = payload.getFrom();
                    String senderCallsign = getNodeLongName(senderNodeId);
                    // Use ATAK device UID if known, otherwise fall back to node ID
                    String senderUid = nodeIdToDeviceUid.getOrDefault(senderNodeId, senderNodeId);
                    Log.d(TAG, "Sender: " + senderNodeId + " -> callsign=" + senderCallsign + ", uid=" + senderUid);

                    CotEvent cotEvent = new CotEvent();
                    CoordinatedTime time = new CoordinatedTime();
                    cotEvent.setTime(time);
                    cotEvent.setStart(time);
                    cotEvent.setStale(time.addMinutes(10));

                    cotEvent.setUID("GeoChat." + senderUid + ".All Chat Rooms." + UUID.randomUUID());
                    CotPoint gp = new CotPoint(0, 0, 0, 0, 0);
                    cotEvent.setPoint(gp);
                    cotEvent.setHow("m-g");
                    cotEvent.setType("b-t-f");

                    CotDetail cotDetail = new CotDetail("detail");
                    cotEvent.setDetail(cotDetail);

                    CotDetail chatDetail = new CotDetail("__chat");
                    chatDetail.setAttribute("parent", "RootContactGroup");
                    chatDetail.setAttribute("groupOwner", "false");
                    chatDetail.setAttribute("messageId", UUID.randomUUID().toString());
                    chatDetail.setAttribute("chatroom", "All Chat Rooms");
                    chatDetail.setAttribute("id", "All Chat Rooms");
                    chatDetail.setAttribute("senderCallsign", senderCallsign);
                    cotDetail.addChild(chatDetail);

                    CotDetail chatgrp = new CotDetail("chatgrp");
                    chatgrp.setAttribute("uid0", senderUid);
                    chatgrp.setAttribute("uid1", "All Chat Rooms");
                    chatgrp.setAttribute("id", "All Chat Rooms");
                    chatDetail.addChild(chatgrp);

                    CotDetail linkDetail = new CotDetail("link");
                    linkDetail.setAttribute("uid", senderUid);
                    linkDetail.setAttribute("type", "a-f-G-U-C");
                    linkDetail.setAttribute("relation", "p-p");
                    cotDetail.addChild(linkDetail);

                    CotDetail serverDestinationDetail = new CotDetail("__serverdestination");
                    serverDestinationDetail.setAttribute("destination", "0.0.0.0:4242:tcp");
                    cotDetail.addChild(serverDestinationDetail);

                    CotDetail remarksDetail = new CotDetail("remarks");
                    remarksDetail.setAttribute("source", "BAO.F.ATAK." + senderUid);
                    remarksDetail.setAttribute("to", "All Chat Rooms");
                    remarksDetail.setAttribute("time", time.toString());
                    remarksDetail.setInnerText(payload.getBytes().utf8());
                    cotDetail.addChild(remarksDetail);

                    CotDetail meshDetail = new CotDetail("__meshtastic");
                    cotDetail.addChild(meshDetail);

                    if (cotEvent.isValid()) {
                        CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                        if (prefs.getBoolean(Constants.PREF_PLUGIN_SERVER, false)) {
                            CotMapComponent.getExternalDispatcher().dispatch(cotEvent);
                        }
                    }
                }

                if (myNodeID.equals(payload.getTo())) {
                    Log.d(TAG, "Sending CoT for DM Text Message");
                    // Look up sender's ATAK info from lookup table (populated from PLI/GeoChat)
                    String senderNodeId = payload.getFrom();
                    String senderCallsign = getNodeLongName(senderNodeId);
                    // Use ATAK device UID if known, otherwise fall back to node ID
                    String senderUid = nodeIdToDeviceUid.getOrDefault(senderNodeId, senderNodeId);
                    Log.d(TAG, "DM Sender: " + senderNodeId + " -> callsign=" + senderCallsign + ", uid=" + senderUid);

                    CotEvent cotEvent = new CotEvent();
                    CoordinatedTime time = new CoordinatedTime();
                    cotEvent.setTime(time);
                    cotEvent.setStart(time);
                    cotEvent.setStale(time.addMinutes(10));

                    cotEvent.setUID("GeoChat." + senderUid + "." + myNodeID + "." + UUID.randomUUID());
                    CotPoint gp = new CotPoint(0, 0, 0, 0, 0);
                    cotEvent.setPoint(gp);
                    cotEvent.setHow("m-g");
                    cotEvent.setType("b-t-f");

                    CotDetail cotDetail = new CotDetail("detail");
                    cotEvent.setDetail(cotDetail);

                    CotDetail chatDetail = new CotDetail("__chat");
                    chatDetail.setAttribute("parent", "RootContactGroup");
                    chatDetail.setAttribute("groupOwner", "false");
                    chatDetail.setAttribute("messageId", UUID.randomUUID().toString());
                    // Use sender's node ID as chatroom so messages appear in their chat window,
                    // not the local node's window. This allows proper conversation threading
                    // and ensures replies go to the correct remote device.
                    chatDetail.setAttribute("chatroom", senderNodeId);
                    chatDetail.setAttribute("id", senderNodeId);
                    chatDetail.setAttribute("senderCallsign", senderCallsign);
                    cotDetail.addChild(chatDetail);

                    CotDetail chatgrp = new CotDetail("chatgrp");
                    chatgrp.setAttribute("uid0", senderUid);
                    chatgrp.setAttribute("uid1", myNodeID);
                    chatgrp.setAttribute("id", senderNodeId);
                    chatDetail.addChild(chatgrp);

                    CotDetail linkDetail = new CotDetail("link");
                    linkDetail.setAttribute("uid", senderUid);
                    linkDetail.setAttribute("type", "a-f-G-U-C");
                    linkDetail.setAttribute("relation", "p-p");
                    cotDetail.addChild(linkDetail);

                    CotDetail serverDestinationDetail = new CotDetail("__serverdestination");
                    serverDestinationDetail.setAttribute("destination", "0.0.0.0:4242:tcp");
                    cotDetail.addChild(serverDestinationDetail);

                    CotDetail remarksDetail = new CotDetail("remarks");
                    remarksDetail.setAttribute("source", "BAO.F.ATAK." + senderUid);
                    remarksDetail.setAttribute("to", myNodeID);
                    remarksDetail.setAttribute("time", time.toString());
                    remarksDetail.setInnerText(payload.getBytes().utf8());
                    cotDetail.addChild(remarksDetail);

                    CotDetail meshDetail = new CotDetail("__meshtastic");
                    cotDetail.addChild(meshDetail);

                    if (cotEvent.isValid()) {
                        CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                    } else
                        Log.e(TAG, "cotEvent was not valid");
                }
                break;
            case Constants.ACTION_NODE_CHANGE:
                NodeInfo ni = null;
                try {
                    ni = intent.getParcelableExtra("com.geeksville.mesh.NodeInfo");
                    Log.d(TAG, "NodeInfo: " + ni);
                } catch (Exception e) {
                    e.printStackTrace();
                    return;
                }

                if (ni == null) {
                    Log.d(TAG, "NodeInfo was null");
                    return;
                } else if (ni.getUser() == null) {
                    Log.d(TAG, "getUser was null");
                    return;
                }

                Position pos = ni.getPosition();
                if (pos == null) {
                    pos = new Position(0, 0, 0,0,0,0,0,0);
                }

                if (prefs.getBoolean(Constants.PREF_PLUGIN_FILTER_BY_CHANNEL, false)) {
                    int preferredChannel;
                    try {
                        preferredChannel = Integer.parseInt(prefs.getString(Constants.PREF_PLUGIN_CHANNEL, "0"));
                    } catch (NumberFormatException e) {
                        preferredChannel = 0;
                    }
                    if (ni.getChannel() != preferredChannel) {
                        Log.d(TAG, "Ignoring NodeInfo on channel " + ni.getChannel() + ", preferred: " + preferredChannel);
                        return;
                    }
                }

                if (prefs.getBoolean(Constants.PREF_PLUGIN_NOGPS, false)) {
                    if (pos.getLatitude() == 0 && pos.getLongitude() == 0) {
                        Log.d(TAG, "Ignoring NodeInfo with 0,0 GPS");
                        return;
                    }
                    Log.d(TAG, "NodeInfo GPS: " + pos.getLatitude() + ", " + pos.getLongitude() + ", Ignoring due to preferences");
                }

                String myId = MeshtasticMapComponent.getMyNodeID();
                if (myId == null) {
                    Log.d(TAG, "myId was null");
                    return;
                }
                boolean shouldUseMeshtasticExternalGPS = prefs.getBoolean(Constants.PREF_PLUGIN_EXTERNAL_GPS, false);
                if (shouldUseMeshtasticExternalGPS && ni.getUser().getId().equals(myId)) {
                    Log.d(TAG, "Sending self coordinates to network GPS");

                    meshtasticExternalGPS.updatePosition(pos);
                }

                if (ni.getUser().getId().equals(myId) && prefs.getBoolean(Constants.PREF_PLUGIN_SELF, false)) {
                    Log.d(TAG, "Ignoring self");
                    return;
                }

                if (prefs.getBoolean(Constants.PREF_PLUGIN_TRACKER, true)) {
                    String nodeName = ni.getUser().getLongName();
                    Log.i(TAG, "Node name: " + nodeName);
                    CotDetail groupDetail = new CotDetail("__group");
                    groupDetail.setAttribute("role", "Team Member");
                    String[] teamColor = {"Unknown", " -0"};
                    try {
                        teamColor = nodeName.split("((?= -[0-9]*$))");
                        Log.d(TAG, String.valueOf(teamColor.length));
                        for (int i=0; i<teamColor.length; i++) {
                            Log.d(TAG, "teamColor[" + i + "]: " + teamColor[i]);
                        }
                        if (teamColor.length < 2) {
                            teamColor = new String[]{nodeName, " -10"};
                        }

                        switch (teamColor[1]) {
                            case " -0":
                            case " -1":
                                groupDetail.setAttribute("name", "White");
                                break;
                            case " -2":
                                groupDetail.setAttribute("name", "Yellow");
                                break;
                            case " -3":
                                groupDetail.setAttribute("name", "Orange");
                                break;
                            case " -4":
                                groupDetail.setAttribute("name", "Magenta");
                                break;
                            case " -5":
                                groupDetail.setAttribute("name", "Red");
                                break;
                            case " -6":
                                groupDetail.setAttribute("name", "Maroon");
                                break;
                            case " -7":
                                groupDetail.setAttribute("name", "Purple");
                                break;
                            case " -8":
                                groupDetail.setAttribute("name", "Dark Blue");
                                break;
                            case " -9":
                                groupDetail.setAttribute("name", "Blue");
                                break;
                            case " -10":
                                groupDetail.setAttribute("name", "Cyan");
                                break;
                            case " -11":
                                groupDetail.setAttribute("name", "Teal");
                                break;
                            case " -12":
                                groupDetail.setAttribute("name", "Green");
                                break;
                            case " -13":
                                groupDetail.setAttribute("name", "Dark Green");
                                break;
                            case " -14":
                                groupDetail.setAttribute("name", "Brown");
                                break;
                            default:
                                groupDetail.setAttribute("name", "Black");
                                break;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        return;
                    }
                    Log.d(TAG, "Creating CoT for Sensor NodeInfo");
                    CotEvent cotEvent = new CotEvent();
                    CoordinatedTime time = new CoordinatedTime();
                    cotEvent.setTime(time);
                    cotEvent.setStart(time);
                    cotEvent.setStale(time.addMinutes(10));

                    cotEvent.setUID(ni.getUser().getId());
                    CotPoint gp = new CotPoint(pos.getLatitude(), pos.getLongitude(), pos.getAltitude(), CotPoint.UNKNOWN, CotPoint.UNKNOWN);
                    cotEvent.setPoint(gp);
                    cotEvent.setHow("m-g");
                    cotEvent.setType("a-f-G-E-S");

                    CotDetail cotDetail = new CotDetail("detail");
                    cotEvent.setDetail(cotDetail);
                    cotDetail.addChild(groupDetail);

                    if (ni.getDeviceMetrics() != null) {
                        CotDetail batteryDetail = new CotDetail("status");
                        batteryDetail.setAttribute("battery", String.valueOf(ni.getDeviceMetrics().getBatteryLevel()));
                        cotDetail.addChild(batteryDetail);
                    }

                    CotDetail takvDetail = new CotDetail("takv");
                    takvDetail.setAttribute("platform", "Meshtastic Plugin");
                    takvDetail.setAttribute("version", "\n----NodeInfo----\n" + ni.toString());
                    takvDetail.setAttribute("device", ni.getUser().getHwModelString());
                    takvDetail.setAttribute("os", "1");
                    cotDetail.addChild(takvDetail);

                    CotDetail uidDetail = new CotDetail("uid");
                    uidDetail.setAttribute("Droid", teamColor[0]);
                    cotDetail.addChild(uidDetail);

                    CotDetail contactDetail = new CotDetail("contact");
                    contactDetail.setAttribute("callsign", teamColor[0]);
                    contactDetail.setAttribute("endpoint", "0.0.0.0:4242:tcp");
                    cotDetail.addChild(contactDetail);

                    CotDetail meshDetail = new CotDetail("__meshtastic");
                    cotDetail.addChild(meshDetail);

                    if (cotEvent.isValid()) {
                        CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                        if (prefs.getBoolean(Constants.PREF_PLUGIN_SERVER, false)) {
                            CotMapComponent.getExternalDispatcher().dispatch(cotEvent);
                        }
                    } else
                        Log.e(TAG, "cotEvent was not valid");
                }
                break;
        }
    }

    public byte[] slice(byte[] array, int start, int end) {
        if (start < 0) {
            start = array.length + start;
        }
        if (end < 0) {
            end = array.length + end;
        }
        int length = end - start;
        byte[] result = new byte[length];
        for (int i = 0; i < length; i++) {
            result[i] = array[start + i];
        }
        return result;
    }

    public short[] append(short[] a, short[] b) {
        short[] result = new short[a.length + b.length];
        for (int i = 0; i < a.length; i++)
            result[i] = a[i];
        for (int i = 0; i < b.length; i++)
            result[a.length + i] = b[i];
        return result;
    }

    public static List<byte[]> extractChunks(byte[] byteArray, int chunkSize) {
        List<byte[]> chunks = new ArrayList<>();

        for (int i = 0; i < byteArray.length; i += chunkSize) {
            int endIndex = Math.min(i + chunkSize, byteArray.length);
            byte[] chunk = Arrays.copyOfRange(byteArray, i, endIndex);
            chunks.add(chunk);
        }

        return chunks;
    }
    private final ConcurrentLinkedQueue<short[]> playbackQueue = new ConcurrentLinkedQueue<>();
    private boolean isPlaying = false;

    public void playAudio(short[] audioData) {
        playbackQueue.add(audioData);
        processQueue();
    }

    private synchronized void processQueue() {
        if (isPlaying) return; // Prevent multiple simultaneous playbacks
        
        AudioTrack audioTrack = null;
        try {
            int minAudioBufSize = AudioRecord.getMinBufferSize(
                    RECORDER_SAMPLERATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );

            // Ensure buffer size is a proper multiple of samplesBufSize * 2
            int requiredSize = samplesBufSize * 2;
            if (minAudioBufSize % requiredSize != 0) {
                minAudioBufSize = ((minAudioBufSize / requiredSize) + 1) * requiredSize;
            }

            // for voice playback
            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RECORDER_SAMPLERATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(minAudioBufSize)
                    .build();

            audioTrack.setVolume(AudioTrack.getMaxVolume());
            audioTrack.play();
            isPlaying = true;
            
            // Store reference for cleanup
            this.track = audioTrack;
            
            final AudioTrack finalTrack = audioTrack;
            new Thread(() -> {
                try {
                    while (!playbackQueue.isEmpty()) {
                        short[] audioData = playbackQueue.poll();
                        if (audioData == null) continue;
                        finalTrack.write(audioData, 0, audioData.length); // Blocking call
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error during playback: " + e.getMessage());
                } finally {
                    // Ensure playback is fully stopped and resources are released
                    try {
                        if (finalTrack != null) {
                            if (finalTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                                Log.d(TAG, "Stopping playback");
                                finalTrack.stop();
                            }
                            finalTrack.flush();
                            finalTrack.release();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error releasing AudioTrack: " + e.getMessage());
                    } finally {
                        isPlaying = false;
                        this.track = null;
                    }
                }
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize AudioTrack: " + e.getMessage());
            isPlaying = false;
            if (audioTrack != null) {
                try {
                    audioTrack.release();
                } catch (Exception releaseError) {
                    Log.e(TAG, "Error releasing AudioTrack on initialization failure: " + releaseError.getMessage());
                }
            }
        }
    }

    protected void receive(Intent intent) {
        DataPacket payload = intent.getParcelableExtra(Constants.EXTRA_PAYLOAD);
        if (payload == null) return;
        int dataType = payload.getDataType();
        Log.v(TAG, "handleReceive(), dataType: " + dataType + " size: " + payload.getBytes().size());

        // Apply channel filter if enabled
        if (prefs.getBoolean(Constants.PREF_PLUGIN_FILTER_BY_CHANNEL, false)) {
            int preferredChannel;
            try {
                preferredChannel = Integer.parseInt(prefs.getString(Constants.PREF_PLUGIN_CHANNEL, "0"));
            } catch (NumberFormatException e) {
                preferredChannel = 0;
            }
            int packetChannel = payload.getChannel();
            if (packetChannel != preferredChannel) {
                Log.d(TAG, "Ignoring packet on channel " + packetChannel + ", preferred: " + preferredChannel);
                return;
            }
        }

        SharedPreferences.Editor editor = prefs.edit();

        if (dataType == PortNum.ATAK_FORWARDER.getValue()) {
            byte[] raw = payload.getBytes().toByteArray();

            // codec2 frame, short-circuit the rest of the data processing
            if (audioPermissionGranted  && (raw[0] & 0xFF) == 0xC2) {
                Log.d(TAG, "Received codec2 frame");

                // skip 0xC2 from data and split into frames
                // 700C mode uses 8 bytes per frame
                int frameSize = Codec2.getBitsSize(c2);
                List<byte[]> frames = extractChunks(slice(raw, 1, raw.length), frameSize);
                Log.d(TAG, "Frames: " + frames.size() + ", frame size: " + frameSize);

                for (byte[] frame : frames) {
                    if (frame.length == frameSize) {
                        playbackBuf = new short[samplesBufSize];
                        Codec2.decode(c2, playbackBuf, frame);
                        playAudio(playbackBuf);
                    } else {
                        Log.w(TAG, "Skipping incomplete frame of size " + frame.length);
                    }
                }

                return;

            }

            // Fountain code packet - handle large message transfer
            if (FountainPacket.isFountainPacket(raw)) {
                Log.d(TAG, "Received fountain packet");
                String senderNodeId = payload.getFrom();
                int channel = payload.getChannel();
                int hopLimit = payload.getHopLimit();
                fountainChunkManager.handlePacket(raw, senderNodeId, channel, hopLimit);
                return;
            }

            String message = payload.getBytes().utf8();
            if (message.startsWith("MFT")) {
                // Sender side - received file transfer acknowledgment
                Log.d(TAG, "Received MFT - file transfer complete");
                FileTransferManager.getInstance().completeTransfer();
            } else if (AppLayerEncryptionManager.isAppLayerEncrypted(raw)) {
                // App-layer encrypted message (0xFE marker) - decrypt then process
                Log.d(TAG, "Received app-layer encrypted message on FORWARDER port");
                AppLayerEncryptionManager encManager = AppLayerEncryptionManager.getInstance();
                byte[] decrypted = encManager.decrypt(raw);
                if (decrypted == null) {
                    Log.w(TAG, "Failed to decrypt app-layer message (wrong key or no key)");
                    return;
                }

                // Decrypted data is zlib-compressed CoT
                String xmlStr = decompressToXml(decrypted);
                if (xmlStr != null) {
                    Log.d(TAG, "Decrypted app-layer to XML: " + xmlStr.length() + " chars");
                    CotEvent cotEvent = CotEvent.parse(xmlStr);
                    if (cotEvent != null && cotEvent.isValid()) {
                        CotDetail cotDetail = cotEvent.getDetail();
                        if (cotDetail == null) {
                            cotDetail = new CotDetail("detail");
                            cotEvent.setDetail(cotDetail);
                        }
                        CotDetail meshDetail = new CotDetail("__meshtastic");
                        cotDetail.addChild(meshDetail);
                        cotEvent.setDetail(cotDetail);

                        Log.d(TAG, "Dispatching app-layer decrypted CoT event");
                        CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                        if (prefs.getBoolean(Constants.PREF_PLUGIN_SERVER, false)) {
                            CotMapComponent.getExternalDispatcher().dispatch(cotEvent);
                        }
                    } else {
                        Log.w(TAG, "Decrypted CoT event is not valid");
                    }
                } else {
                    Log.e(TAG, "Failed to decompress app-layer decrypted data");
                }
            } else if (CryptoUtils.isEncrypted(raw)) {
                // Encrypted message - try to decrypt
                Log.d(TAG, "Received encrypted message");
                String psk = prefs.getString(Constants.PREF_PLUGIN_ENCRYPTION_PSK, "");
                if (psk == null || psk.isEmpty()) {
                    Log.w(TAG, "Received encrypted message but no PSK configured - cannot decrypt");
                    return;
                }

                byte[] decrypted = CryptoUtils.decrypt(raw, psk);
                if (decrypted == null) {
                    Log.w(TAG, "Failed to decrypt message - wrong PSK or corrupted data");
                    return;
                }

                // Decrypted data is zlib-compressed CoT - decode it
                String xmlStr = decompressToXml(decrypted);
                if (xmlStr != null) {
                    Log.d(TAG, "Decrypted zlib to XML: " + xmlStr.length() + " chars");
                    CotEvent cotEvent = CotEvent.parse(xmlStr);
                    if (cotEvent != null && cotEvent.isValid()) {
                        // Add meshtastic marker to prevent re-forwarding
                        CotDetail cotDetail = cotEvent.getDetail();
                        if (cotDetail == null) {
                            cotDetail = new CotDetail("detail");
                            cotEvent.setDetail(cotDetail);
                        }
                        CotDetail meshDetail = new CotDetail("__meshtastic");
                        cotDetail.addChild(meshDetail);
                        cotEvent.setDetail(cotDetail);

                        Log.d(TAG, "Dispatching decrypted CoT event");
                        CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                        if (prefs.getBoolean(Constants.PREF_PLUGIN_SERVER, false)) {
                            CotMapComponent.getExternalDispatcher().dispatch(cotEvent);
                        }
                    } else {
                        Log.w(TAG, "Decrypted CoT event is not valid");
                    }
                } else {
                    Log.e(TAG, "Failed to decompress decrypted data");
                }
            } else {
                // Try to decode as zlib or raw XML (unencrypted)
                byte[] rawData = payload.getBytes().toByteArray();
                String xmlStr = decompressToXml(rawData);

                // Parse and dispatch the CoT event
                if (xmlStr != null) {
                    try {
                        CotEvent cotEvent = CotEvent.parse(xmlStr);
                        if (cotEvent != null && cotEvent.isValid()) {
                            // Add meshtastic marker to prevent re-forwarding
                            CotDetail cotDetail = cotEvent.getDetail();
                            if (cotDetail == null) {
                                cotDetail = new CotDetail("detail");
                                cotEvent.setDetail(cotDetail);
                            }
                            CotDetail meshDetail = new CotDetail("__meshtastic");
                            cotDetail.addChild(meshDetail);
                            cotEvent.setDetail(cotDetail);

                            CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                            if (prefs.getBoolean(Constants.PREF_PLUGIN_SERVER, false)) {
                                CotMapComponent.getExternalDispatcher().dispatch(cotEvent);
                            }
                        }
                    } catch (Throwable e) {
                        Log.e(TAG, "Failed to parse CoT XML: " + e.getMessage());
                    }
                }
            }
        } else if (dataType == 72) {
            Log.d(TAG, "Got TAK_PACKET");
            Log.d(TAG, "Payload: " + payload);

            try {
                // Check for app-layer encryption before protobuf decode
                byte[] takBytes = payload.getBytes().toByteArray();
                if (AppLayerEncryptionManager.isAppLayerEncrypted(takBytes)) {
                    Log.d(TAG, "TAK_PACKET is app-layer encrypted, decrypting");
                    AppLayerEncryptionManager encManager = AppLayerEncryptionManager.getInstance();
                    byte[] decrypted = encManager.decrypt(takBytes);
                    if (decrypted == null) {
                        Log.w(TAG, "Failed to decrypt app-layer encrypted TAK_PACKET "
                                + "(wrong key or no key configured)");
                        return;
                    }
                    takBytes = decrypted;
                    Log.d(TAG, "TAK_PACKET decrypted: " + takBytes.length + " bytes");
                }
                TAKPacket tp = TAKPacket.ADAPTER.decode(takBytes);
                if (tp.is_compressed()) {
                    Log.d(TAG, "TAK_PACKET is compressed");
                    return;
                }
                Log.d(TAG, "TAK_PACKET: " + tp.toString());
                if (tp.getPli() != null) {
                    Log.d(TAG, "TAK_PACKET PLI");
                    org.meshtastic.proto.Contact contact = tp.getContact();
                    Group group = tp.getGroup();
                    Status status = tp.getStatus();
                    PLI pli = tp.getPli();

                    double lat = pli.getLatitude_i() * 1e-7;
                    double lng = pli.getLongitude_i() * 1e-7;
                    double alt = pli.getAltitude();
                    int course = pli.getCourse();
                    int speed = pli.getSpeed();

                    Log.d(TAG, String.format("GPS: %f %f %f", alt, lat, lng));

                    String callsign = contact.getCallsign();
                    String deviceCallsign = contact.getDevice_callsign();

                    // Store nodeId -> ATAK info mapping for TEXT_MESSAGE_APP sender lookup
                    String senderNodeId = payload.getFrom();
                    if (senderNodeId != null && callsign != null && !callsign.isEmpty()) {
                        String prevCallsign = nodeIdToCallsign.put(senderNodeId, callsign);
                        if (prevCallsign == null || !prevCallsign.equals(callsign)) {
                            Log.d(TAG, "Stored nodeId->callsign mapping: " + senderNodeId + " -> " + callsign);
                        }
                        if (deviceCallsign != null && !deviceCallsign.isEmpty()) {
                            nodeIdToDeviceUid.put(senderNodeId, deviceCallsign);
                        }
                    }

                    CotDetail cotDetail = new CotDetail("detail");

                    CotDetail uidDetail = new CotDetail("uid");
                    uidDetail.setAttribute("Droid", callsign);

                    CotDetail contactDetail = new CotDetail("contact");
                    contactDetail.setAttribute("callsign", callsign);
                    contactDetail.setAttribute("endpoint", "0.0.0.0:4242:tcp");
                    cotDetail.addChild(contactDetail);

                    CotDetail groupDetail = new CotDetail("__group");

                    String role = MemberRole.fromValue(group.getRole().getValue()).name();
                    switch (role) {
                        case "TeamMember":
                            role = "Team Member";
                            break;
                        case "TeamLead":
                            role = "Team Lead";
                            break;
                        case "ForwardObserver":
                            role = "Forward Observer";
                            break;
                    }
                    groupDetail.setAttribute("role", role);

                    String team = Team.fromValue(group.getTeam().getValue()).name();
                    if (team.equals("DarkBlue"))
                        team = "Dark Blue";
                    else if (team.equals("DarkGreen"))
                        team = "Dark Green";
                    groupDetail.setAttribute("name", team);

                    cotDetail.addChild(groupDetail);

                    CotDetail statusDetail = new CotDetail("status");
                    statusDetail.setAttribute("battery", String.valueOf(status.getBattery()));
                    cotDetail.addChild(statusDetail);

                    CotDetail trackDetail = new CotDetail("track");
                    trackDetail.setAttribute("speed", String.valueOf(speed));
                    trackDetail.setAttribute("course", String.valueOf(course));
                    cotDetail.addChild(trackDetail);

                    CotEvent cotEvent = new CotEvent();
                    cotEvent.setDetail(cotDetail);
                    cotEvent.setUID(deviceCallsign);

                    CoordinatedTime time = new CoordinatedTime();
                    cotEvent.setTime(time);
                    cotEvent.setStart(time);
                    cotEvent.setStale(time.addMinutes(10));

                    cotEvent.setType("a-f-G-U-C");

                    cotEvent.setHow("m-g");

                    CotDetail meshDetail = new CotDetail("__meshtastic");
                    cotDetail.addChild(meshDetail);
                    cotEvent.setDetail(cotDetail);

                    CotPoint cotPoint = new CotPoint(lat, lng, CotPoint.UNKNOWN,
                            CotPoint.UNKNOWN, CotPoint.UNKNOWN);
                    cotEvent.setPoint(cotPoint);

                    if (cotEvent.isValid()) {
                        CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                        if (prefs.getBoolean(Constants.PREF_PLUGIN_SERVER, false)) {
                            CotMapComponent.getExternalDispatcher().dispatch(cotEvent);
                        }
                    } else
                        Log.e(TAG, "cotEvent was not valid");

                } else if (tp.getChat() != null && tp.getChat().getTo().equals("All Chat Rooms")) {
                    Log.d(TAG, "TAK_PACKET GEOCHAT - All Chat Rooms");

                    org.meshtastic.proto.Contact contact = tp.getContact();
                    GeoChat geoChat = tp.getChat();

                    String callsign = contact.getCallsign();
                    // Parse deviceCallsign which may contain smuggled messageId: "deviceCallsign|messageId"
                    String[] parsed = CotEventProcessor.parseDeviceCallsignAndMessageId(contact.getDevice_callsign());
                    String deviceCallsign = parsed[0];
                    String originalMsgId = parsed[1];

                    // Use original messageId if available, otherwise generate a UUID
                    String msgId;
                    if (originalMsgId != null && !originalMsgId.isEmpty()) {
                        msgId = originalMsgId;
                        Log.d(TAG, "Using original messageId: " + msgId);
                    } else {
                        msgId = UUID.randomUUID().toString();
                        Log.d(TAG, "Generated fallback UUID messageId: " + msgId);
                    }

                    // Store nodeId -> ATAK info mapping for TEXT_MESSAGE_APP sender lookup
                    String senderNodeId = payload.getFrom();
                    if (senderNodeId != null && callsign != null && !callsign.isEmpty()) {
                        nodeIdToCallsign.putIfAbsent(senderNodeId, callsign);
                        if (deviceCallsign != null && !deviceCallsign.isEmpty()) {
                            nodeIdToDeviceUid.putIfAbsent(senderNodeId, deviceCallsign);
                        }
                    }

                    //Bundle chatMessage = ChatDatabase.getInstance(_mapView.getContext()).getChatMessage(msgId);
                    //if (chatMessage != null) {
                    //    Log.d(TAG, "Duplicate message");
                    //    return;
                    //}

                    if (prefs.getBoolean(Constants.PREF_PLUGIN_VOICE, false)) {
                        StringBuilder message = new StringBuilder();
                        message.append(" GeoChat from ");
                        message.append(callsign);
                        message.append(" Message Reads ");
                        message.append(geoChat.getMessage());
                        MeshtasticDropDownReceiver.t1.speak(message.toString(), TextToSpeech.QUEUE_FLUSH, null);
                    }

                    CotDetail cotDetail = new CotDetail("detail");

                    CoordinatedTime time = new CoordinatedTime();

                    CotDetail chatDetail = new CotDetail("__chat");
                    chatDetail.setAttribute("parent", "RootContactGroup");
                    chatDetail.setAttribute("groupOwner", "false");
                    chatDetail.setAttribute("messageId", msgId);
                    chatDetail.setAttribute("chatroom", "All Chat Rooms");
                    chatDetail.setAttribute("id", "All Chat Rooms");
                    chatDetail.setAttribute("senderCallsign", callsign);
                    cotDetail.addChild(chatDetail);

                    CotDetail chatgrp = new CotDetail("chatgrp");
                    chatgrp.setAttribute("uid0", deviceCallsign);
                    chatgrp.setAttribute("uid1", "All Chat Rooms");
                    chatgrp.setAttribute("id", "All Chat Rooms");
                    chatDetail.addChild(chatgrp);

                    CotDetail linkDetail = new CotDetail("link");
                    linkDetail.setAttribute("uid", deviceCallsign);
                    linkDetail.setAttribute("type", "a-f-G-U-C");
                    linkDetail.setAttribute("relation", "p-p");
                    cotDetail.addChild(linkDetail);

                    CotDetail serverDestinationDetail = new CotDetail("__serverdestination");
                    serverDestinationDetail.setAttribute("destination", "0.0.0.0:4242:tcp");
                    cotDetail.addChild(serverDestinationDetail);

                    CotDetail meshDetail = new CotDetail("__meshtastic");
                    cotDetail.addChild(meshDetail);

                    CotDetail remarksDetail = new CotDetail("remarks");
                    remarksDetail.setAttribute("source", String.format("BAO.F.ATAK.%s", deviceCallsign));
                    remarksDetail.setAttribute("to", "All Chat Rooms");
                    remarksDetail.setAttribute("time", time.toString());
                    remarksDetail.setInnerText(geoChat.getMessage());
                    cotDetail.addChild(remarksDetail);

                    CotEvent cotEvent = new CotEvent();
                    cotEvent.setDetail(cotDetail);
                    // Reconstruct the proper UID format: GeoChat.<deviceCallsign>.All Chat Rooms.<messageId>
                    cotEvent.setUID("GeoChat." + deviceCallsign + ".All Chat Rooms." + msgId);
                    cotEvent.setTime(time);
                    cotEvent.setStart(time);
                    cotEvent.setStale(time.addMinutes(10));
                    cotEvent.setType("b-t-f");
                    cotEvent.setHow("h-g-i-g-o");

                    CotPoint cotPoint = new CotPoint(0, 0, CotPoint.UNKNOWN,
                            CotPoint.UNKNOWN, CotPoint.UNKNOWN);
                    cotEvent.setPoint(cotPoint);

                    if (cotEvent.isValid()) {
                        CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                        if (prefs.getBoolean(Constants.PREF_PLUGIN_SERVER, false)) {
                            CotMapComponent.getExternalDispatcher().dispatch(cotEvent);
                        }
                    } else
                        Log.e(TAG, "cotEvent was not valid");

                } else if (tp.getChat() != null && tp.getChat().getTo().equals(getMapView().getSelfMarker().getUID())) {
                    org.meshtastic.proto.Contact contact = tp.getContact();
                    GeoChat geoChat = tp.getChat();
                    String message = geoChat.getMessage();

                    // Check if this is a chat receipt (ACK:D:<messageId> or ACK:R:<messageId>)
                    if (message != null && message.startsWith("ACK:")) {
                        Log.d(TAG, "TAK_PACKET GEOCHAT - Receipt: " + message);
                        handleChatReceiptPacket(contact, message);
                        return;
                    }

                    Log.d(TAG, "TAK_PACKET GEOCHAT - DM");

                    String to = geoChat.getTo();
                    String callsign = contact.getCallsign();
                    // Parse deviceCallsign which may contain smuggled messageId: "deviceCallsign|messageId"
                    String[] parsed = CotEventProcessor.parseDeviceCallsignAndMessageId(contact.getDevice_callsign());
                    String deviceCallsign = parsed[0];
                    String originalMsgId = parsed[1];

                    // Use original messageId if available, otherwise generate a UUID
                    String msgId;
                    if (originalMsgId != null && !originalMsgId.isEmpty()) {
                        msgId = originalMsgId;
                        Log.d(TAG, "Using original messageId: " + msgId);
                    } else {
                        msgId = UUID.randomUUID().toString();
                        Log.d(TAG, "Generated fallback UUID messageId: " + msgId);
                    }

                    // Store nodeId -> ATAK info mapping for TEXT_MESSAGE_APP sender lookup
                    String senderNodeId = payload.getFrom();
                    if (senderNodeId != null && callsign != null && !callsign.isEmpty()) {
                        nodeIdToCallsign.putIfAbsent(senderNodeId, callsign);
                        if (deviceCallsign != null && !deviceCallsign.isEmpty()) {
                            nodeIdToDeviceUid.putIfAbsent(senderNodeId, deviceCallsign);
                        }
                    }

                    //Bundle chatMessage = ChatDatabase.getInstance(_mapView.getContext()).getChatMessage(msgId);
                    //if (chatMessage != null) {
                    //    Log.d(TAG, "Duplicate message");
                    //    return;
                    //}

                    if (prefs.getBoolean(Constants.PREF_PLUGIN_VOICE, false)) {
                        StringBuilder voiceMessage = new StringBuilder();
                        voiceMessage.append(" GeoChat from ");
                        voiceMessage.append(callsign);
                        voiceMessage.append(" Message Reads ");
                        voiceMessage.append(message);
                        MeshtasticDropDownReceiver.t1.speak(voiceMessage.toString(), TextToSpeech.QUEUE_FLUSH, null);
                    }

                    CoordinatedTime time = new CoordinatedTime();

                    CotDetail cotDetail = new CotDetail("detail");
                    CotDetail chatDetail = new CotDetail("__chat");
                    chatDetail.setAttribute("parent", "RootContactGroup");
                    chatDetail.setAttribute("groupOwner", "false");
                    chatDetail.setAttribute("messageId", msgId);
                    // Use sender's callsign/UID as chatroom so messages appear in their chat window,
                    // not the local device's window. This allows proper conversation threading.
                    chatDetail.setAttribute("chatroom", callsign);
                    chatDetail.setAttribute("id", deviceCallsign);
                    chatDetail.setAttribute("senderCallsign", callsign);
                    cotDetail.addChild(chatDetail);

                    CotDetail chatgrp = new CotDetail("chatgrp");
                    chatgrp.setAttribute("uid0", deviceCallsign);
                    chatgrp.setAttribute("uid1", getMapView().getSelfMarker().getUID());
                    chatgrp.setAttribute("id", deviceCallsign);
                    chatDetail.addChild(chatgrp);

                    CotDetail linkDetail = new CotDetail("link");
                    linkDetail.setAttribute("uid", deviceCallsign);
                    linkDetail.setAttribute("type", "a-f-G-U-C");
                    linkDetail.setAttribute("relation", "p-p");
                    cotDetail.addChild(linkDetail);

                    CotDetail serverDestinationDetail = new CotDetail("__serverdestination");
                    serverDestinationDetail.setAttribute("destination", "0.0.0.0:4242:tcp");
                    cotDetail.addChild(serverDestinationDetail);

                    CotDetail remarksDetail = new CotDetail("remarks");
                    remarksDetail.setAttribute("source", String.format("BAO.F.ATAK.%s", deviceCallsign));
                    remarksDetail.setAttribute("to", to);
                    remarksDetail.setAttribute("time", time.toString());
                    remarksDetail.setInnerText(message);
                    cotDetail.addChild(remarksDetail);

                    CotDetail meshDetail = new CotDetail("__meshtastic");
                    cotDetail.addChild(meshDetail);

                    CotEvent cotEvent = new CotEvent();
                    cotEvent.setDetail(cotDetail);
                    // Reconstruct proper UID format for DM: GeoChat.<senderUid>.<recipientUid>.<messageId>
                    cotEvent.setUID("GeoChat." + deviceCallsign + "." + getMapView().getSelfMarker().getUID() + "." + msgId);
                    cotEvent.setTime(time);
                    cotEvent.setStart(time);
                    cotEvent.setStale(time.addMinutes(10));
                    cotEvent.setType("b-t-f");
                    cotEvent.setHow("h-g-i-g-o");

                    CotPoint cotPoint = new CotPoint(0, 0, CotPoint.UNKNOWN,
                            CotPoint.UNKNOWN, CotPoint.UNKNOWN);
                    cotEvent.setPoint(cotPoint);

                    if (cotEvent.isValid()) {
                        CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                    } else
                        Log.e(TAG, "cotEvent was not valid");
                } else {
                    Log.d(TAG, "TAK_PACKET - unknown content");
                    Log.d(TAG, String.valueOf(tp));
                }

            } catch (Exception e) {
                Log.e(TAG, "Could not parse TAK packet", e);
            }
        }
    }

    public static boolean getWantsAck() {
        try {
            return prefs.getBoolean(Constants.PREF_PLUGIN_WANT_ACK, false);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static int getHopLimit() {
        try {
            int hopLimit;
            try {
                hopLimit = Integer.parseInt(prefs.getString(Constants.PREF_PLUGIN_HOP_LIMIT, "3"));
            } catch (NumberFormatException e) {
                hopLimit = 3;
            }
            Log.d(TAG, "Hop Limit: " + hopLimit);
            if (hopLimit > 8) {
                hopLimit = 8;
            }
            return hopLimit;
        } catch (Exception e) {
            e.printStackTrace();
            return 3;
        }
    }

    public static int getChannelIndex() {
        try {
            int channel;
            try {
                channel = Integer.parseInt(prefs.getString(Constants.PREF_PLUGIN_CHANNEL, "0"));
            } catch (NumberFormatException e) {
                channel = 0;
            }
            Log.d(TAG, "Channel: " + channel);
            return channel;
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * Look up Meshtastic node ID for an ATAK device UID (reverse lookup).
     */
    public static String getNodeIdForDeviceUid(String deviceUid) {
        if (deviceUid == null) return null;
        for (java.util.Map.Entry<String, String> entry : nodeIdToDeviceUid.entrySet()) {
            if (deviceUid.equals(entry.getValue())) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Get the count of nodes that have sent ATAK_PLUGIN or ATAK_FORWARDER packets.
     * This represents the number of ATAK nodes we've actually received data from.
     */
    public static int getAtakNodeCount() {
        return nodeIdToCallsign.size();
    }

    @Override
    public void onCotEvent(CotEvent cotEvent, Bundle bundle) {
        Log.d(TAG, "onCotEvent called: " + cotEvent.toString());
        if (prefs.getBoolean(Constants.PREF_PLUGIN_FROM_SERVER, false)) {
            if (cotEvent.isValid()) {
                CotDetail cotDetail = cotEvent.getDetail();

                if (cotDetail.getChild("__meshtastic") != null) {
                    Log.d(TAG, "Meshtastic CoT from server");
                    return;
                }

                Log.d(TAG, "onCotEvent");
                Log.d(TAG, cotEvent.toString());

                int hopLimit = getHopLimit();
                int channel = getChannelIndex();

                int eventType = -1;
                double divisor = 1e-7;
                XmlPullParserFactory factory = null;
                XmlPullParser xpp = null;
                String callsign = null;
                String deviceCallsign = null;
                String message = null;

                try {
                    factory = XmlPullParserFactory.newInstance();
                    factory.setNamespaceAware(true);
                    xpp = factory.newPullParser();
                    xpp.setInput(new StringReader(cotDetail.toString()));
                    eventType = xpp.getEventType();
                } catch (XmlPullParserException e) {
                    e.printStackTrace();
                    return;
                }

                // All Chat Rooms
                if (cotEvent.getType().startsWith("b-t-f") && cotEvent.getUID().contains("All Chat Rooms")) {
                    try {
                        while (eventType != XmlPullParser.END_DOCUMENT) {
                            if (eventType == XmlPullParser.START_TAG) {
                                Log.d(TAG, xpp.getName());
                                if (xpp.getName().equalsIgnoreCase("remarks")) {
                                    if (xpp.next() == XmlPullParser.TEXT)
                                        message = xpp.getText();
                                } else if (xpp.getName().equalsIgnoreCase("__chat")) {
                                    int attributeCount = xpp.getAttributeCount();
                                    Log.d(TAG, "__chat has " + +attributeCount);
                                    for (int i = 0; i < attributeCount; i++) {
                                        if (xpp.getAttributeName(i).equalsIgnoreCase("senderCallsign"))
                                            callsign = xpp.getAttributeValue(i);
                                    }
                                } else if (xpp.getName().equalsIgnoreCase("link")) {
                                    int attributeCount = xpp.getAttributeCount();
                                    Log.d(TAG, "link has " + +attributeCount);
                                    for (int i = 0; i < attributeCount; i++) {
                                        if (xpp.getAttributeName(i).equalsIgnoreCase("uid"))
                                            deviceCallsign = xpp.getAttributeValue(i);
                                    }
                                }
                            }
                            eventType = xpp.next();
                        }

                    } catch (XmlPullParserException | IOException e) {
                        e.printStackTrace();
                    }

                    org.meshtastic.proto.Contact contact = new org.meshtastic.proto.Contact(
                        callsign,
                        deviceCallsign,
                        ByteString.EMPTY
                    );

                    GeoChat geochat = new GeoChat(
                        message,
                        "All Chat Rooms",
                        null,  // from
                        ByteString.EMPTY
                    );

                    TAKPacket tak_packet = new TAKPacket(
                        false,  // is_compressed
                        contact,
                        null,  // group
                        null,  // status
                        null,  // pli
                        geochat,
                        null,  // detail (oneof - must be null when chat is set)
                        ByteString.EMPTY   // unknownFields
                    );

                    byte[] takPacketBytes = TAKPacket.ADAPTER.encode(tak_packet);
                    Log.d(TAG, "Total wire size for TAKPacket: " + takPacketBytes.length);

                    // Apply app-layer encryption if enabled (server relay path)
                    byte[] payload = encryptForRelay(takPacketBytes);
                    if (payload == null) return;

                    sendOrChunkRelayPayload(payload, hopLimit, channel);
                } else if (cotDetail.getAttribute("contact") != null) {
                    for (Contact c : Contacts.getInstance().getAllContacts()) {
                        if (cotEvent.getUID().equals(c.getUid())) {

                            int battery = 0, course = 0, speed = 0;
                            String role = null, name = null;
                            double lat = 0, lng = 0, alt = 0;

                            lat = cotEvent.getGeoPoint().getLatitude();
                            lng = cotEvent.getGeoPoint().getLongitude();
                            alt = cotEvent.getGeoPoint().getAltitude();

                            try {
                                while (eventType != XmlPullParser.END_DOCUMENT) {
                                    if (eventType == XmlPullParser.START_TAG) {
                                        if (xpp.getName().equalsIgnoreCase("contact")) {
                                            int attributeCount = xpp.getAttributeCount();
                                            Log.d(TAG, "Contact has " + attributeCount);
                                            for (int i = 0; i < attributeCount; i++) {
                                                if (xpp.getAttributeName(i).equalsIgnoreCase("callsign"))
                                                    callsign = xpp.getAttributeValue(i);
                                            }
                                        } else if (xpp.getName().equalsIgnoreCase("__group")) {
                                            int attributeCount = xpp.getAttributeCount();
                                            Log.d(TAG, "__group has " + attributeCount);
                                            for (int i = 0; i < attributeCount; i++) {
                                                if (xpp.getAttributeName(i).equalsIgnoreCase("role"))
                                                    role = xpp.getAttributeValue(i);
                                                else if (xpp.getAttributeName(i).equalsIgnoreCase("name"))
                                                    name = xpp.getAttributeValue(i);
                                            }
                                        } else if (xpp.getName().equalsIgnoreCase("status")) {
                                            int attributeCount = xpp.getAttributeCount();
                                            Log.d(TAG, "status has " + attributeCount);
                                            for (int i = 0; i < attributeCount; i++) {
                                                if (xpp.getAttributeName(i).equalsIgnoreCase("battery"))
                                                    battery = Integer.parseInt(xpp.getAttributeValue(i));
                                            }
                                        } else if (xpp.getName().equalsIgnoreCase("track")) {
                                            int attributeCount = xpp.getAttributeCount();
                                            Log.d(TAG, "track has " + attributeCount);
                                            for (int i = 0; i < attributeCount; i++) {
                                                if (xpp.getAttributeName(i).equalsIgnoreCase("course"))
                                                    course = Double.valueOf(xpp.getAttributeValue(i)).intValue();
                                                else if (xpp.getAttributeName(i).equalsIgnoreCase("speed"))
                                                    speed = Double.valueOf(xpp.getAttributeValue(i)).intValue();
                                            }
                                        }
                                    }
                                    eventType = xpp.next();
                                }
                            } catch (XmlPullParserException | IOException e) {
                                e.printStackTrace();
                                return;
                            }

                            org.meshtastic.proto.Contact contact = new org.meshtastic.proto.Contact(
                                callsign,
                                cotEvent.getUID(),
                                ByteString.EMPTY
                            );

                            Group group = new Group(
                                MemberRole.valueOf(role.replace(" ", "")),
                                Team.valueOf(name.replace(" ", "")),
                                ByteString.EMPTY
                            );

                            Status status = new Status(
                                battery,
                                ByteString.EMPTY
                            );

                            PLI pli = new PLI(
                                (int) (lat / divisor),
                                (int) (lng / divisor),
                                Double.valueOf(alt).intValue(),
                                course,
                                speed,
                                ByteString.EMPTY
                            );

                            TAKPacket tak_packet = new TAKPacket(
                                false,  // is_compressed
                                contact,
                                group,
                                status,
                                pli,
                                null,  // chat
                                null,  // detail (oneof - must be null when pli is set)
                                ByteString.EMPTY   // unknownFields
                            );

                            byte[] takPacketBytes = TAKPacket.ADAPTER.encode(tak_packet);
                            Log.d(TAG, "Total wire size for TAKPacket: " + takPacketBytes.length);

                            // Apply app-layer encryption if enabled (server relay path)
                            byte[] payload = encryptForRelay(takPacketBytes);
                            if (payload == null) return;

                            sendOrChunkRelayPayload(payload, hopLimit, channel);
                        }
                    }
                }
            }
        }
    }

    /**
     * Encrypt payload for the server relay path (onCotEvent).
     * Uses the AppLayerEncryptionManager singleton if encryption is enabled.
     * Returns the original payload if encryption is disabled, or null if encryption fails.
     *
     * @param takPacketBytes The raw TAKPacket bytes to potentially encrypt
     * @return Encrypted or original payload, or null if encryption failed
     */
    private byte[] encryptForRelay(byte[] takPacketBytes) {
        AppLayerEncryptionManager encManager = AppLayerEncryptionManager.getInstance();
        if (!encManager.isEnabled()) {
            return takPacketBytes;
        }

        byte[] encrypted = encManager.encrypt(takPacketBytes);
        if (encrypted == null) {
            Log.e(TAG, "App-layer encryption failed for server relay, message not sent");
            return null;
        }

        Log.d(TAG, "Server relay: app-layer encrypted " + takPacketBytes.length
                + " -> " + encrypted.length + " bytes");
        return encrypted;
    }

    /**
     * Send relay payload via Meshtastic, using fountain coding if it exceeds the
     * single-packet limit (231 bytes). This mirrors the logic in
     * MeshtasticMapComponent.sendOrChunkPayload but is used by the server relay path.
     *
     * @param payload   The payload bytes to send (possibly encrypted)
     * @param hopLimit  Hop limit for the packet
     * @param channel   Meshtastic channel index
     */
    private void sendOrChunkRelayPayload(byte[] payload, int hopLimit, int channel) {
        final int MAX_SINGLE_PACKET = 231;

        if (payload.length <= MAX_SINGLE_PACKET) {
            DataPacket dp = new DataPacket(
                    DataPacket.ID_BROADCAST,
                    ByteString.of(payload, 0, payload.length),
                    PortNum.ATAK_PLUGIN.getValue(),
                    DataPacket.ID_LOCAL,
                    System.currentTimeMillis(),
                    0,
                    MessageStatus.UNKNOWN,
                    hopLimit,
                    channel,
                    getWantsAck(),
                    0, 0f, 0, null, null, 0, false, 0, 0, null
            );
            if (MeshtasticMapComponent.getMeshService() != null) {
                MeshtasticMapComponent.getMeshService().sendToMesh(dp);
            }
        } else {
            Log.d(TAG, "Server relay: payload too large for single packet ("
                    + payload.length + " > " + MAX_SINGLE_PACKET
                    + "), using fountain coding");
            if (fountainChunkManager != null) {
                int transferId = fountainChunkManager.send(
                        payload, channel, hopLimit, Constants.TRANSFER_TYPE_COT);
                if (transferId < 0) {
                    Log.e(TAG, "Server relay: failed to start fountain transfer");
                } else {
                    Log.d(TAG, "Server relay: started fountain transfer " + transferId);
                }
            } else {
                Log.e(TAG, "Server relay: oversized encrypted payload ("
                        + payload.length + " bytes) but fountain coding unavailable. "
                        + "Message dropped to avoid silent truncation on LoRa.");
            }
        }
    }

    /**
     * Reinitialize codec with new settings
     */
    public void reinitializeCodec() {
        // Destroy old codec instance if it exists
        if (c2 != 0) {
            try {
                Codec2.destroy(c2);
            } catch (Exception e) {
                Log.e(TAG, "Error destroying old Codec2 instance", e);
            }
        }
        
        // Create new codec - hardcoded to 700C for compatibility
        try {
            String arch = System.getProperty("os.arch");
            if (arch != null) {
                String a = arch.toLowerCase();
                if (a.contains("64") || a.contains("aarch64") || a.contains("arm64")) {
                    // Codec2 Recorder/Playback - hardcoded to 700C for compatibility
                    // NOTE: All devices must use the same codec mode for audio to work properly
                    c2 = Codec2.create(Codec2.CODEC2_MODE_700C);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (c2 == 0) {
            Log.e(TAG, "Failed to create Codec2 with mode 700C");
        }
        this.samplesBufSize = Codec2.getSamplesPerFrame(c2);
        Log.d(TAG, "Codec2 reinitialized with mode 700C, samples per frame: " + samplesBufSize);
    }
    
    /**
     * Process data received via fountain codes.
     * Uses transferType to determine how to handle the data.
     *
     * @param data The decoded data
     * @param senderNodeId The sender's node ID (for sending MFT acknowledgment)
     * @param transferType The type of transfer (Constants.TRANSFER_TYPE_COT or TRANSFER_TYPE_FILE)
     */
    private void processFountainData(byte[] data, String senderNodeId, byte transferType) {
        // Normalize transfer type (iOS uses ASCII '0'/'1' instead of 0x00/0x01)
        if (transferType == Constants.TRANSFER_TYPE_COT_ASCII) {
            transferType = Constants.TRANSFER_TYPE_COT;
        } else if (transferType == Constants.TRANSFER_TYPE_FILE_ASCII) {
            transferType = Constants.TRANSFER_TYPE_FILE;
        }

        // Handle file transfer
        if (transferType == Constants.TRANSFER_TYPE_FILE) {
            Log.d(TAG, "Fountain File Received: " + data.length + " bytes from " + senderNodeId);

            mBuilder.setContentText("Transfer complete")
                    .setProgress(0, 0, false);
            mNotifyManager.notify(id, mBuilder.build());

            try {
                String path = String.format(Locale.US, "%s/%s/%s.zip",
                        Environment.getExternalStorageDirectory().getAbsolutePath(),
                        "atak/tools/datapackage", UUID.randomUUID().toString());
                Log.d(TAG, "Writing to: " + path);
                Files.write(new File(path).toPath(), data);
            } catch (IOException e) {
                Log.e(TAG, "Failed to write file", e);
            }

            // Send MFT (file transfer complete) back to sender
            if (senderNodeId != null) {
                Log.d(TAG, "Sending MFT to " + senderNodeId);
                DataPacket dp = new DataPacket(
                        senderNodeId,
                        ByteString.of(new byte[]{'M', 'F', 'T'}, 0, 3),
                        PortNum.ATAK_FORWARDER.getValue(),
                        DataPacket.ID_LOCAL,
                        System.currentTimeMillis(),
                        0,
                        MessageStatus.UNKNOWN,
                        getHopLimit(),
                        getChannelIndex(),
                        getWantsAck(),
                        0, 0f, 0, null, null, 0, false, 0, 0, null
                );
                MeshtasticMapComponent.sendToMesh(dp);
            }
            return;
        }

        // Handle CoT data - check if encrypted first
        Log.d(TAG, "Processing fountain CoT data: " + data.length + " bytes from " + senderNodeId);

        byte[] exiData = data;

        // Check for app-layer encryption (0xFE marker) first
        if (AppLayerEncryptionManager.isAppLayerEncrypted(data)) {
            Log.d(TAG, "Fountain data is app-layer encrypted - attempting decryption");
            AppLayerEncryptionManager encManager = AppLayerEncryptionManager.getInstance();
            byte[] decrypted = encManager.decrypt(data);
            if (decrypted == null) {
                Log.w(TAG, "Failed to decrypt app-layer fountain data (wrong key or no key)");
                return;
            }
            Log.d(TAG, "Fountain data app-layer decrypted: " + decrypted.length + " bytes");
            exiData = decrypted;
        }
        // Check for legacy encryption (0xEE marker)
        else if (CryptoUtils.isEncrypted(data)) {
            Log.d(TAG, "Fountain data is encrypted - attempting decryption");
            String psk = prefs.getString(Constants.PREF_PLUGIN_ENCRYPTION_PSK, "");
            if (psk == null || psk.isEmpty()) {
                Log.w(TAG, "Received encrypted fountain data but no PSK configured - cannot decrypt");
                return;
            }

            byte[] decrypted = CryptoUtils.decrypt(data, psk);
            if (decrypted == null) {
                Log.w(TAG, "Failed to decrypt fountain data - wrong PSK or corrupted data");
                return;
            }
            Log.d(TAG, "Fountain data decrypted: " + decrypted.length + " bytes");
            exiData = decrypted;
        }

        // Decompress zlib data to XML (standardized format for cross-platform compatibility)
        String xmlStr = decompressToXml(exiData);
        if (xmlStr == null) {
            Log.e(TAG, "Failed to decompress fountain data, first bytes: " +
                      bytesToHex(Arrays.copyOf(exiData, Math.min(16, exiData.length))));
            return;
        }

        // Parse and dispatch the CoT event
        try {
            CotEvent cotEvent = CotEvent.parse(xmlStr);

            if (cotEvent != null && cotEvent.isValid()) {
                CotDetail cotDetail = cotEvent.getDetail();
                if (cotDetail == null) {
                    cotDetail = new CotDetail("detail");
                    cotEvent.setDetail(cotDetail);
                }
                CotDetail meshDetail = new CotDetail("__meshtastic");
                cotDetail.addChild(meshDetail);

                Log.d(TAG, "Fountain CoT Received and dispatched");
                CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
                if (prefs.getBoolean(Constants.PREF_PLUGIN_SERVER, false)) {
                    CotMapComponent.getExternalDispatcher().dispatch(cotEvent);
                }
            } else {
                Log.w(TAG, "Fountain data not valid CoT: " + exiData.length + " bytes");
            }
        } catch (Throwable e) {
            Log.e(TAG, "Failed to parse CoT XML: " + e.getMessage());
        }
    }

    /**
     * Decompress data to XML string.
     * Tries zlib first, then raw XML as fallback.
     * This is the standardized format for cross-platform compatibility.
     *
     * @param data The compressed or raw data
     * @return XML string, or null on failure
     */
    private String decompressToXml(byte[] data) {
        // Try zlib decompression first (standard format)
        byte[] decompressed = zlibDecompress(data);
        if (decompressed != null) {
            String xmlStr = new String(decompressed, StandardCharsets.UTF_8);
            Log.d(TAG, "Zlib decompressed to XML: " + xmlStr.length() + " chars");
            return xmlStr;
        }

        // Try raw XML (uncompressed fallback)
        try {
            String xmlStr = new String(data, StandardCharsets.UTF_8);
            if (xmlStr.trim().startsWith("<")) {
                Log.d(TAG, "Treating data as raw XML: " + xmlStr.length() + " chars");
                return xmlStr;
            }
        } catch (Throwable e) {
            Log.d(TAG, "Raw XML decode failed: " + e.getMessage());
        }

        return null;
    }

    /**
     * Decompress zlib or raw deflate compressed data.
     * Tries standard zlib first, then raw deflate (nowrap mode) for iOS compatibility.
     *
     * @param compressed The compressed data
     * @return Decompressed data, or null on failure
     */
    private byte[] zlibDecompress(byte[] compressed) {
        // Try standard zlib first (with header)
        byte[] result = tryInflate(compressed, false);
        if (result != null) {
            Log.d(TAG, "Zlib decompressed " + compressed.length + " -> " + result.length + " bytes");
            return result;
        }

        // Try raw deflate (no zlib header - iOS may use this)
        result = tryInflate(compressed, true);
        if (result != null) {
            Log.d(TAG, "Raw deflate decompressed " + compressed.length + " -> " + result.length + " bytes");
            return result;
        }

        Log.d(TAG, "Neither zlib nor raw deflate worked");
        return null;
    }

    /**
     * Try to inflate compressed data.
     *
     * @param compressed The compressed data
     * @param nowrap If true, use raw deflate mode (no zlib header/checksum)
     * @return Decompressed data, or null on failure
     */
    private byte[] tryInflate(byte[] compressed, boolean nowrap) {
        try {
            Inflater inflater = new Inflater(nowrap);
            inflater.setInput(compressed);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream(compressed.length * 4);
            byte[] buffer = new byte[1024];

            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0 && inflater.needsInput()) {
                    break;
                }
                outputStream.write(buffer, 0, count);
            }

            inflater.end();
            outputStream.close();

            byte[] result = outputStream.toByteArray();
            if (result.length == 0) {
                return null;
            }

            return result;
        } catch (DataFormatException e) {
            Log.d(TAG, (nowrap ? "Raw deflate" : "Zlib") + " failed: " + e.getMessage());
            return null;
        } catch (IOException e) {
            Log.e(TAG, "Decompression IO error: " + e.getMessage());
            return null;
        }
    }

    // Helper to convert bytes to hex string for logging
    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    /**
     * Look up a sender's callsign by their Meshtastic node ID (e.g., "!12345abc").
     *
     * Priority order:
     * 1. ATAK callsign from lookup table (populated when receiving PLI/GeoChat TAKPackets)
     * 2. Meshtastic node's configured long name
     * 3. Raw node ID as fallback
     *
     * @param nodeId The Meshtastic node ID (e.g., "!12345abc")
     * @return The sender's callsign/name, or the node ID if not found
     */
    private static String getNodeLongName(String nodeId) {
        if (nodeId == null) return "Unknown";

        // First, check if we have an ATAK callsign from a previous TAKPacket
        String atakCallsign = nodeIdToCallsign.get(nodeId);
        if (atakCallsign != null && !atakCallsign.isEmpty()) {
            return atakCallsign;
        }

        // Fall back to Meshtastic node's long name
        try {
            List<NodeInfo> nodes = MeshtasticMapComponent.getNodes();
            if (nodes != null) {
                for (NodeInfo node : nodes) {
                    if (node.getUser() != null && nodeId.equals(node.getUser().getId())) {
                        String longName = node.getUser().getLongName();
                        if (longName != null && !longName.isEmpty()) {
                            return longName;
                        }
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to look up node name for " + nodeId, e);
        }

        // Fallback to node ID
        return nodeId;
    }

    /**
     * Handle a chat receipt packet received over Meshtastic.
     * Receipt format: "ACK:D:<messageId>" for delivered, "ACK:R:<messageId>" for read
     *
     * @param contact The sender contact info
     * @param receiptMessage The receipt message in format "ACK:D:<messageId>" or "ACK:R:<messageId>"
     */
    private void handleChatReceiptPacket(org.meshtastic.proto.Contact contact, String receiptMessage) {
        // Parse receipt: "ACK:D:<messageId>" or "ACK:R:<messageId>"
        String[] parts = receiptMessage.split(":", 3);
        if (parts.length != 3) {
            Log.w(TAG, "Invalid receipt format: " + receiptMessage);
            return;
        }

        String receiptType = parts[1];  // "D" or "R"
        String messageId = parts[2];

        String cotType;
        if ("D".equals(receiptType)) {
            cotType = "b-t-f-d";  // Delivered
        } else if ("R".equals(receiptType)) {
            cotType = "b-t-f-r";  // Read
        } else {
            Log.w(TAG, "Unknown receipt type: " + receiptType);
            return;
        }

        String senderCallsign = contact.getCallsign();
        String senderUid = contact.getDevice_callsign();

        Log.d(TAG, "Processing chat receipt: type=" + cotType + ", messageId=" + messageId +
                  ", from=" + senderCallsign + " (" + senderUid + ")");

        // Build CoT event for the receipt
        CoordinatedTime time = new CoordinatedTime();

        CotDetail cotDetail = new CotDetail("detail");

        // __chatreceipt detail with messageId (receipts use __chatreceipt, not __chat)
        CotDetail chatDetail = new CotDetail("__chatreceipt");
        chatDetail.setAttribute("messageId", messageId);
        chatDetail.setAttribute("parent", "RootContactGroup");
        chatDetail.setAttribute("groupOwner", "false");
        chatDetail.setAttribute("senderCallsign", senderCallsign);
        cotDetail.addChild(chatDetail);

        // chatgrp with sender info
        CotDetail chatgrp = new CotDetail("chatgrp");
        chatgrp.setAttribute("uid0", senderUid);
        chatgrp.setAttribute("uid1", getMapView().getSelfMarker().getUID());
        chatDetail.addChild(chatgrp);

        // __meshtastic marker
        CotDetail meshDetail = new CotDetail("__meshtastic");
        cotDetail.addChild(meshDetail);

        CotEvent cotEvent = new CotEvent();
        cotEvent.setDetail(cotDetail);
        // Receipt UID format from ATAK: typically same as the message being acknowledged
        cotEvent.setUID(messageId);
        cotEvent.setTime(time);
        cotEvent.setStart(time);
        cotEvent.setStale(time.addMinutes(1));  // Short stale time for receipts
        cotEvent.setType(cotType);
        cotEvent.setHow("h-g-i-g-o");

        CotPoint cotPoint = new CotPoint(0, 0, CotPoint.UNKNOWN,
                CotPoint.UNKNOWN, CotPoint.UNKNOWN);
        cotEvent.setPoint(cotPoint);

        if (cotEvent.isValid()) {
            Log.d(TAG, "Dispatching chat receipt CoT event: " + cotType);
            CotMapComponent.getInternalDispatcher().dispatch(cotEvent);
        } else {
            Log.e(TAG, "Chat receipt CoT event was not valid");
        }
    }

    /**
     * Clean up resources when the receiver is unregistered
     */
    public void cleanup() {
        // Destroy Codec2 instance
        if (c2 != 0) {
            try {
                Codec2.destroy(c2);
                c2 = 0;
            } catch (Exception e) {
                Log.e(TAG, "Error destroying Codec2 instance", e);
            }
        }
        
        // Stop and release audio track if playing
        if (track != null) {
            try {
                if (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop();
                }
                track.release();
                track = null;
            } catch (Exception e) {
                Log.e(TAG, "Error releasing audio track", e);
            }
        }
        
        isPlaying = false;
    }
}
