package com.atakmap.android.meshtastic;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.DragEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;

import androidx.annotation.NonNull;

import com.atakmap.android.cot.CotMapAdapter;
import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.cot.importer.MapItemImporter;
import com.atakmap.android.cotdetails.CoTInfoView;
import com.atakmap.android.data.URIContentManager;
import com.atakmap.android.dropdown.DropDownMapComponent;
import com.atakmap.android.importexport.CotEventFactory;
import com.atakmap.android.importexport.ImporterManager;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.items.MapItemsDatabase;
import com.atakmap.android.maps.MapComponent;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapTouchController;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.maps.visibility.MapItemVisibilityListener;
import com.atakmap.android.meshtastic.cot.CotEventProcessor;
import com.atakmap.android.meshtastic.encryption.AppLayerEncryptionManager;
import com.atakmap.android.meshtastic.plugin.R;
import com.atakmap.android.meshtastic.service.MeshServiceManager;
import com.atakmap.android.meshtastic.util.fountain.FountainChunkManager;
import com.atakmap.android.meshtastic.util.fountain.FountainPacket;
import com.atakmap.android.meshtastic.util.Constants;
import com.atakmap.android.meshtastic.util.NotificationHelper;
import com.atakmap.app.preferences.ToolsPreferenceFragment;
import com.atakmap.commoncommo.CoTMessageListener;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.comms.CotService;
import com.atakmap.comms.CotServiceRemote;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import org.meshtastic.proto.Contact;
import org.meshtastic.proto.GeoChat;
import org.meshtastic.proto.Group;
import org.meshtastic.proto.MemberRole;
import org.meshtastic.proto.PLI;
import org.meshtastic.proto.Status;
import org.meshtastic.proto.TAKPacket;
import org.meshtastic.proto.Team;
import org.meshtastic.core.model.DataPacket;
import org.meshtastic.core.model.MessageStatus;
import org.meshtastic.proto.Data;
import org.meshtastic.core.model.MeshUser;
import org.meshtastic.core.model.MyNodeInfo;
import org.meshtastic.core.model.NodeInfo;
import org.meshtastic.proto.PortNum;

import com.atakmap.map.AtakMapView;
import com.atakmap.map.DefaultMapTouchHandler;
import okio.ByteString;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.Deflater;

public class MeshtasticMapComponent extends DropDownMapComponent
        implements CommsMapComponent.PreSendProcessor,
        SharedPreferences.OnSharedPreferenceChangeListener,
        CotServiceRemote.ConnectionListener,
        MeshServiceManager.ConnectionListener {

    private static final String TAG = "MeshtasticMapComponent";

    // Components
    private Context pluginContext;
    private MeshtasticDropDownReceiver ddr;
    private MeshtasticReceiver mr;
    private final MeshtasticExternalGPS meshtasticExternalGPS;
    private MeshtasticSender meshtasticSender;

    // Services and Managers
    private MeshServiceManager meshServiceManager;
    private NotificationHelper notificationHelper;
    private FountainChunkManager fountainChunkManager;
    private AppLayerEncryptionManager encryptionManager;

    // Static reference to the singleton instance
    private static MeshtasticMapComponent instance;
    private CotEventProcessor cotEventProcessor;

    // UI Components
    public static MeshtasticWidget mw;

    // Preferences
    private SharedPreferences prefs;
    private SharedPreferences.Editor editor;

    // Thread pool for background operations
    private ExecutorService executorService;

    // Track radio connection state separately from IPC service connection
    private static boolean radioConnected = false;
    private static boolean serviceConnected = false;

    // Health check timer
    private static final long HEALTH_CHECK_INTERVAL_MS = 30000; // 30 seconds
    private Timer healthCheckTimer;

    public MeshtasticMapComponent() {
        meshtasticExternalGPS = new MeshtasticExternalGPS(new PositionToNMEAMapper());
        cotEventProcessor = new CotEventProcessor();
        executorService = Executors.newCachedThreadPool();
    }

    @Override
    public void onCotServiceConnected(Bundle bundle) {
        // Implementation if needed
    }

    @Override
    public void onCotServiceDisconnected() {
        // Implementation if needed
    }

    @Override
    public void onServiceConnected() {
        serviceConnected = true;
        // Assume radio is connected when IPC service connects
        // We'll get a broadcast if it disconnects later
        radioConnected = true;
        updateWidgetState();
        startHealthCheckTimer();
    }

    @Override
    public void onServiceDisconnected() {
        serviceConnected = false;
        stopHealthCheckTimer();
        updateWidgetState();
    }

    /**
     * Start periodic health check timer
     */
    private void startHealthCheckTimer() {
        stopHealthCheckTimer(); // Cancel any existing timer
        healthCheckTimer = new Timer("MeshtasticHealthCheck", true);
        healthCheckTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                performHealthCheck();
            }
        }, HEALTH_CHECK_INTERVAL_MS, HEALTH_CHECK_INTERVAL_MS);
        Log.d(TAG, "Health check timer started (interval: " + HEALTH_CHECK_INTERVAL_MS + "ms)");
    }

    /**
     * Stop the health check timer
     */
    private void stopHealthCheckTimer() {
        if (healthCheckTimer != null) {
            healthCheckTimer.cancel();
            healthCheckTimer = null;
            Log.d(TAG, "Health check timer stopped");
        }
    }

    /**
     * Perform a health check by querying the mesh service.
     * If we can successfully communicate with the service and get node info,
     * we consider the connection healthy.
     */
    private void performHealthCheck() {
        Log.d(TAG, "Performing health check...");

        if (!meshServiceManager.isConnected()) {
            Log.w(TAG, "Health check: IPC service not connected");
            serviceConnected = false;
            updateWidgetState();
            // Try to reconnect
            meshServiceManager.connect();
            return;
        }

        // Try to query node info to verify the connection is actually working
        try {
            String myNodeId = meshServiceManager.getMyNodeID();
            if (myNodeId == null || myNodeId.isEmpty()) {
                Log.w(TAG, "Health check: Could not get node ID, assuming radio disconnected");
                radioConnected = false;
            } else {
                Log.d(TAG, "Health check: OK (node: " + myNodeId + ")");
                // Service is responsive and we got a valid node ID - connection is healthy
                serviceConnected = true;
                radioConnected = true;
            }
        } catch (Exception e) {
            Log.w(TAG, "Health check: Service query failed", e);
            serviceConnected = false;
        }

        updateWidgetState();
    }

    /**
     * Called when radio connection state changes (from broadcast receiver)
     */
    public static void setRadioConnected(boolean connected) {
        radioConnected = connected;
        updateWidgetState();
    }

    /**
     * Update widget icon based on both IPC service and radio connection state
     */
    private static void updateWidgetState() {
        if (mw != null) {
            // Only show green if both IPC service AND radio are connected
            if (serviceConnected && radioConnected) {
                mw.setIcon("green");
            } else {
                mw.setIcon("red");
            }
        }
    }

    @Override
    public void onCreate(final Context context, Intent intent, MapView view) {
        instance = this;
        context.setTheme(R.style.ATAKPluginTheme);
        pluginContext = context;

        // Initialize helpers
        notificationHelper = NotificationHelper.getInstance(view.getContext());
        meshServiceManager = MeshServiceManager.getInstance(view.getContext());
        meshServiceManager.setConnectionListener(this);

        // Setup hook for Meshtastic
        CommsMapComponent.getInstance().registerPreSendProcessor(this);

        // Setup dropdown receiver
        Log.d(TAG, "registering the plugin filter");
        ddr = new MeshtasticDropDownReceiver(view, context);
        AtakBroadcast.DocumentedIntentFilter ddFilter = new AtakBroadcast.DocumentedIntentFilter();
        ddFilter.addAction(MeshtasticDropDownReceiver.SHOW_PLUGIN);
        registerDropDownReceiver(ddr, ddFilter);

        // Setup preferences
        prefs = new ProtectedSharedPreferences(
                PreferenceManager.getDefaultSharedPreferences(MapView.getMapView().getContext())
        );
        editor = prefs.edit();
        editor.putBoolean(Constants.PREF_PLUGIN_CHUNKING, false);
        editor.apply();
        prefs.registerOnSharedPreferenceChangeListener(this);

        // Setup GPS if enabled
        int gpsPort = prefs.getInt(Constants.PREF_LISTEN_PORT, Constants.DEFAULT_GPS_PORT);
        boolean shouldUseMeshtasticExternalGPS = prefs.getBoolean(Constants.PREF_PLUGIN_EXTERNAL_GPS, false);
        if (shouldUseMeshtasticExternalGPS) {
            meshtasticExternalGPS.start(gpsPort);
        }

        // Setup fountain chunk manager for large message transfers
        String localNodeId = prefs.getString(Constants.PREF_PLUGIN_LOCAL_NODE_ID, "local");
        fountainChunkManager = new FountainChunkManager(meshServiceManager, localNodeId);

        // Setup receiver
        mr = new MeshtasticReceiver(meshtasticExternalGPS, fountainChunkManager);
        IntentFilter intentFilter = getIntentFilter();
        view.getContext().registerReceiver(mr, intentFilter, Context.RECEIVER_EXPORTED);

        // Setup CoT service
        CotServiceRemote cotService = new CotServiceRemote();
        cotService.setCotEventListener(mr);
        cotService.connect(this);

        // Setup URI content manager
        URIContentManager.getInstance().registerSender(
                meshtasticSender = new MeshtasticSender(view, pluginContext)
        );

        // Initialize app-layer encryption manager
        encryptionManager = AppLayerEncryptionManager.getInstance();
        encryptionManager.initialize(view.getContext());

        // Connect to mesh service
        meshServiceManager.connect();

        // Setup widget
        mw = new MeshtasticWidget(context, view);

        // Register preferences fragment
        ToolsPreferenceFragment.register(
                new ToolsPreferenceFragment.ToolPreference(
                        pluginContext.getString(R.string.preferences_title),
                        pluginContext.getString(R.string.preferences_summary),
                        pluginContext.getString(R.string.meshtastic_preferences),
                        pluginContext.getResources().getDrawable(R.drawable.ic_launcher),
                        new PluginPreferencesFragment(pluginContext)
                )
        );
    }

    @Override
    protected void onDestroyImpl(Context context, MapView view) {
        // Stop health check timer
        stopHealthCheckTimer();

        // Clean up MeshtasticReceiver resources
        if (mr != null) {
            mr.cleanup();
        }

        // Shutdown fountain chunk manager
        if (fountainChunkManager != null) {
            fountainChunkManager.shutdown();
        }

        // Clear encryption keys from memory
        if (encryptionManager != null) {
            encryptionManager.clearKeys();
        }

        meshServiceManager.disconnect();
        view.getContext().unregisterReceiver(mr);
        if (mw != null) {
            mw.destroy();
        }
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        ToolsPreferenceFragment.unregister(pluginContext.getString(R.string.meshtastic_preferences));
        URIContentManager.getInstance().unregisterSender(meshtasticSender);
        meshtasticExternalGPS.stop();

        // Shutdown executor service
        if (executorService != null) {
            executorService.shutdown();
        }
    }

    @Override
    public void processCotEvent(CotEvent cotEvent, String[] strings) {
        Log.d(TAG, "processCotEvent");
        Log.d(TAG, "CotEvent: " + cotEvent.toString());

        // Check if this is a Meshtastic message (don't forward back)
        CotDetail cotDetail = cotEvent.getDetail();
        CotDetail meshtasticDetail = cotDetail != null ? cotDetail.getChild("__meshtastic") : null;

        if (meshtasticDetail != null) {
            Log.d(TAG, "Meshtastic message, don't forward");
            for (MapItem mi:MapView.getMapView().getRootGroup().getAllItems()) {
                if (mi.getUID().equals(cotEvent.getUID())) {
                   mi.removeMetaData("__meshtastic");
                   mi.persist(MapView.getMapView().getMapEventDispatcher(), null, this.getClass());
                }
            }
            return;
        }

        // Check service connection
        if (!meshServiceManager.isConnected()) {
            Log.d(TAG, "Service not connected");
            return;
        }

        // Check if transfer in progress
        if (prefs.getBoolean(Constants.PREF_PLUGIN_CHUNKING, false)) {
            Log.d(TAG, "Transfer in progress");
            return;
        }

        int hopLimit = MeshtasticReceiver.getHopLimit();
        int channel = MeshtasticReceiver.getChannelIndex();

        Log.d(TAG, cotEvent.toString());
        if (cotDetail != null) {
            Log.d(TAG, cotDetail.toString());
        }

        // Refresh encryption state from preferences (key may have changed)
        if (prefs.getBoolean(Constants.PREF_PLUGIN_EXTRA_ENCRYPTION, false)) {
            String psk = prefs.getString(Constants.PREF_PLUGIN_ENCRYPTION_PSK, "");
            if (psk != null && !psk.isEmpty()) {
                encryptionManager.loadKey(psk);
                encryptionManager.setEnabled(true);
            } else {
                Log.w(TAG, "App-layer encryption enabled but PSK is empty");
                encryptionManager.setEnabled(false);
            }
        } else {
            encryptionManager.setEnabled(false);
        }

        // Parse the CoT event
        CotEventProcessor.ParsedCotData parsedData = cotEventProcessor.parseCotEvent(cotEvent);

        // Handle different event types
        String uid = cotEvent.getUID();
        String type = cotEvent.getType();

        if (uid.equals(MapView.getMapView().getSelfMarker().getUID())) {
            long currentTime = System.currentTimeMillis();

            if (prefs.getBoolean(Constants.PREF_PLI_RATE_ENABLED, true)) {
                long lastPLITime = cotEventProcessor.getLastPLITime();
                int rateLimitSeconds;
                try {
                    rateLimitSeconds = Integer.parseInt(prefs.getString(Constants.PREF_PLI_RATE_VALUE, "300"));
                } catch (NumberFormatException e) {
                    rateLimitSeconds = 300; // Default to 5 minutes
                }
                long rateLimitMs = rateLimitSeconds * 1000L;
                if (currentTime - lastPLITime < rateLimitMs) {
                    Log.d(TAG, "PLI rate limit - skipping self PLI (limit: " + rateLimitSeconds + "s)");
                    return;
                }
            }
            // Self PLI report
            handleSelfPLI(parsedData, hopLimit, channel);

            // Update last PLI time
            cotEventProcessor.setLastPLITime(currentTime);

        } else if (uid.contains("All Chat Rooms")) {
            // All Chat Rooms message
            handleAllChatMessage(parsedData, hopLimit, channel);
        } else if (type.equalsIgnoreCase("b-t-f")) {
            // Direct message chat (pending)
            handleDirectMessage(parsedData, hopLimit, channel);
        } else if (type.equalsIgnoreCase("b-t-f-d") || type.equalsIgnoreCase("b-t-f-r")) {
            // Chat receipts: b-t-f-d = delivered, b-t-f-r = read
            handleChatReceipt(cotEvent, hopLimit, channel);
        } else {
            // Other CoT events
            if (prefs.getBoolean(Constants.PREF_PLUGIN_PLICHAT_ONLY, false)) {
                Log.d(TAG, "PLI/Chat Only mode - ignoring other events");
                return;
            }
            handleGenericCotEvent(cotEvent, hopLimit, channel);
        }
    }

    private void handleSelfPLI(CotEventProcessor.ParsedCotData data, int hopLimit, int channel) {
        Log.d(TAG, "Sending self marker PLI to Meshtastic");

        TAKPacket takPacket = cotEventProcessor.buildPLIPacket(data);

        byte[] takPacketBytes = TAKPacket.ADAPTER.encode(takPacket);
        Log.d(TAG, "Total wire size for TAKPacket: " + takPacketBytes.length);

        // Apply app-layer encryption if enabled
        byte[] payload = encryptPayloadIfEnabled(takPacketBytes);
        if (payload == null) return;

        sendOrChunkPayload(payload, DataPacket.ID_BROADCAST, PortNum.ATAK_PLUGIN.getValue(),
                hopLimit, channel, MeshtasticReceiver.getWantsAck());
    }

    private void handleAllChatMessage(CotEventProcessor.ParsedCotData data, int hopLimit, int channel) {
        Log.d(TAG, "Sending All Chat Rooms to Meshtastic");

        TAKPacket takPacket = cotEventProcessor.buildChatPacket(data);

        byte[] takPacketBytes = TAKPacket.ADAPTER.encode(takPacket);
        Log.d(TAG, "Total wire size for TAKPacket: " + takPacketBytes.length);

        // Apply app-layer encryption if enabled
        byte[] payload = encryptPayloadIfEnabled(takPacketBytes);
        if (payload == null) return;

        sendOrChunkPayload(payload, DataPacket.ID_BROADCAST, PortNum.ATAK_PLUGIN.getValue(),
                hopLimit, channel, MeshtasticReceiver.getWantsAck());
    }

    private void handleDirectMessage(CotEventProcessor.ParsedCotData data, int hopLimit, int channel) {
        Log.d(TAG, "Sending DM Chat to Meshtastic");

        if (data.to == null) {
            return;
        }

        if (data.to.startsWith("!")) {
            // Meshtastic ID - send as text message (no app-layer encryption for native Meshtastic text)
            Log.d(TAG, "Sending to Meshtastic ID: " + data.to);
            Data dataProto = new Data(
                    PortNum.TEXT_MESSAGE_APP,
                    ByteString.of(data.message.getBytes(StandardCharsets.UTF_8), 0, data.message.getBytes(StandardCharsets.UTF_8).length),
                    false,  // want_response
                    0,  // dest
                    0,  // source
                    0,  // request_id
                    0,  // reply_id
                    0,  // emoji
                    null,  // bitfield
                    ByteString.EMPTY
            );
            byte[] dataBytes = Data.ADAPTER.encode(dataProto);
            DataPacket dp = new DataPacket(
                    data.to,
                    ByteString.of(dataBytes, 0, dataBytes.length),
                    PortNum.TEXT_MESSAGE_APP.getValue(),
                    DataPacket.ID_LOCAL,
                    System.currentTimeMillis(),
                    0,
                    MessageStatus.UNKNOWN,
                    hopLimit,
                    channel,
                    MeshtasticReceiver.getWantsAck(),
                    0,  // hopStart
                    0f, // snr
                    0,  // rssi
                    null, // replyId,
                    null, // relayNode
                    0,    // relays
                    false, // viaMqtt
                    0,    // retryCount
                    0,    // emoji
                    null  // sfppHash
            );
            meshServiceManager.sendToMesh(dp);
        } else {
            // Regular ATAK device - encrypt TAKPacket if enabled
            TAKPacket takPacket = cotEventProcessor.buildChatPacket(data);

            byte[] takPacketBytes = TAKPacket.ADAPTER.encode(takPacket);
            Log.d(TAG, "Total wire size for TAKPacket: " + takPacketBytes.length);

            // Apply app-layer encryption if enabled
            byte[] payload = encryptPayloadIfEnabled(takPacketBytes);
            if (payload == null) return;

            sendOrChunkPayload(payload, DataPacket.ID_BROADCAST, PortNum.ATAK_PLUGIN.getValue(),
                    hopLimit, channel, MeshtasticReceiver.getWantsAck());
        }
    }

    private void handleChatReceipt(CotEvent cotEvent, int hopLimit, int channel) {
        // Check if chat receipts are enabled
        if (!prefs.getBoolean(Constants.PREF_PLUGIN_CHAT_RECEIPTS, true)) {
            Log.d(TAG, "Chat receipts disabled, skipping");
            return;
        }

        // Chat receipts (delivered/read) need to be forwarded to maintain read receipt functionality
        // We use a special message format: "ACK:<type>:<messageId>" to send receipts compactly
        // Type is "D" for delivered, "R" for read
        String type = cotEvent.getType();
        Log.d(TAG, "Sending chat receipt (" + type + ") to Meshtastic");

        CotDetail cotDetail = cotEvent.getDetail();
        if (cotDetail == null) {
            Log.w(TAG, "Chat receipt has no detail, skipping");
            return;
        }

        // Get the messageId from __chatreceipt detail (receipts use __chatreceipt, not __chat)
        CotDetail chatDetail = cotDetail.getFirstChildByName(0, "__chatreceipt");
        if (chatDetail == null) {
            // Fall back to __chat in case format varies
            chatDetail = cotDetail.getFirstChildByName(0, "__chat");
        }
        if (chatDetail == null) {
            Log.w(TAG, "Chat receipt has no __chatreceipt or __chat detail, skipping");
            return;
        }

        String messageId = chatDetail.getAttribute("messageId");
        if (messageId == null || messageId.isEmpty()) {
            Log.w(TAG, "Chat receipt has no messageId, skipping");
            return;
        }

        // Get recipient (who we're sending the receipt to - the original message sender)
        // In chatgrp for receipts: uid0 = me (receipt sender), uid1 = original message sender (receipt recipient)
        // The receipt goes to uid1 (the person who sent the original message)
        String to = null;
        CotDetail chatgrp = chatDetail.getFirstChildByName(0, "chatgrp");
        if (chatgrp != null) {
            to = chatgrp.getAttribute("uid1");  // Original message sender receives the receipt
        }

        if (to == null || to.isEmpty()) {
            Log.w(TAG, "Chat receipt has no recipient (uid1), skipping");
            return;
        }

        // Look up Meshtastic node ID for the ATAK device UID
        String targetNodeId = MeshtasticReceiver.getNodeIdForDeviceUid(to);
        if (targetNodeId == null) {
            Log.w(TAG, "No Meshtastic node ID found for ATAK UID: " + to + ", using broadcast");
            targetNodeId = DataPacket.ID_BROADCAST;
        }

        Log.d(TAG, "Sending receipt to original sender: " + to + " (node: " + targetNodeId + ")");

        // Build receipt message: "ACK:D:<messageId>" or "ACK:R:<messageId>"
        String receiptType = type.equalsIgnoreCase("b-t-f-d") ? "D" : "R";
        String receiptMessage = "ACK:" + receiptType + ":" + messageId;

        // Build TAKPacket with the receipt message
        // Get sender info
        String selfCallsign = MapView.getMapView().getDeviceCallsign();
        String selfUid = MapView.getMapView().getSelfMarker().getUID();

        GeoChat geoChat = new GeoChat(
                receiptMessage,
                to,
                null,  // from
                ByteString.EMPTY
        );

        org.meshtastic.proto.Contact contact = new org.meshtastic.proto.Contact(
                selfCallsign,
                selfUid,
                ByteString.EMPTY
        );

        TAKPacket takPacket = new TAKPacket(
                false,  // is_compressed
                contact,
                null,  // group
                null,  // status
                null,  // pli
                geoChat,
                null,  // detail (oneof - must be null when chat is set)
                ByteString.EMPTY   // unknownFields
        );

        byte[] takPacketBytes = TAKPacket.ADAPTER.encode(takPacket);
        Log.d(TAG, "Chat receipt TAKPacket size: " + takPacketBytes.length + " bytes");

        // Apply app-layer encryption if enabled
        byte[] payload = encryptPayloadIfEnabled(takPacketBytes);
        if (payload == null) return;

        sendOrChunkPayload(payload, DataPacket.ID_BROADCAST, PortNum.ATAK_PLUGIN.getValue(),
                hopLimit, channel, false);
    }

    private void handleGenericCotEvent(CotEvent cotEvent, int hopLimit, int channel) {
        if (prefs.getBoolean(Constants.PREF_PLUGIN_CHUNKING, false)) {
            Log.d(TAG, "Chunking already in progress");
            return;
        }

        executorService.execute(() -> {
            Log.d(TAG, "Sending generic CoT");

            byte[] cotAsBytes;
            try {
                // Compress CoT XML to zlib format for cross-platform compatibility
                byte[] xmlBytes = cotEvent.toString().getBytes(StandardCharsets.UTF_8);
                cotAsBytes = zlibCompress(xmlBytes);
                if (cotAsBytes == null) {
                    Log.e(TAG, "Failed to compress CoT event");
                    return;
                }
                Log.d(TAG, "Compressed " + xmlBytes.length + " -> " + cotAsBytes.length + " bytes");
            } catch (Exception e) {
                Log.e(TAG, "Failed to compress CoT event", e);
                return;
            }

            // Apply app-layer encryption if enabled
            byte[] payload = encryptPayloadIfEnabled(cotAsBytes);
            if (payload == null) return;

            sendOrChunkPayload(payload, DataPacket.ID_BROADCAST, PortNum.ATAK_FORWARDER.getValue(),
                    hopLimit, channel, MeshtasticReceiver.getWantsAck());
        });
    }

    /**
     * Compress data using zlib (standard deflate with zlib header).
     * This format is compatible with iOS and other platforms.
     *
     * @param data The data to compress
     * @return Compressed data, or null on failure
     */
    private byte[] zlibCompress(byte[] data) {
        try {
            Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
            deflater.setInput(data);
            deflater.finish();

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream(data.length);
            byte[] buffer = new byte[1024];

            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                outputStream.write(buffer, 0, count);
            }

            deflater.end();
            outputStream.close();

            return outputStream.toByteArray();
        } catch (IOException e) {
            Log.e(TAG, "Zlib compression failed", e);
            return null;
        }
    }

    /**
     * Encrypt payload bytes using AppLayerEncryptionManager if encryption is enabled.
     * Returns the original payload if encryption is disabled, or the encrypted payload if enabled.
     *
     * @param payload The raw payload bytes (protobuf or compressed CoT)
     * @return Encrypted or original payload, or null if encryption failed
     */
    private byte[] encryptPayloadIfEnabled(byte[] payload) {
        if (!encryptionManager.isEnabled()) {
            return payload;
        }

        byte[] encrypted = encryptionManager.encrypt(payload);
        if (encrypted == null) {
            Log.e(TAG, "App-layer encryption failed, message not sent");
            return null;
        }

        Log.d(TAG, "App-layer encrypted: " + payload.length + " -> " + encrypted.length + " bytes");
        return encrypted;
    }

    /**
     * Send payload via Meshtastic, using fountain coding if it exceeds the single-packet limit.
     *
     * @param payload   The payload bytes to send
     * @param to        Destination node ID or DataPacket.ID_BROADCAST
     * @param portNum   Meshtastic port number
     * @param hopLimit  Hop limit for the packet
     * @param channel   Meshtastic channel index
     * @param wantAck   Whether to request acknowledgment
     */
    private void sendOrChunkPayload(byte[] payload, String to, int portNum,
                                     int hopLimit, int channel, boolean wantAck) {
        // Max payload that Meshtastic accepts in a single packet
        final int MAX_SINGLE_PACKET = 231;

        if (payload.length <= MAX_SINGLE_PACKET) {
            // Fits in a single packet - send directly
            Log.d(TAG, "Sending payload directly: " + payload.length + " bytes");
            DataPacket dp = new DataPacket(
                    to,
                    ByteString.of(payload, 0, payload.length),
                    portNum,
                    DataPacket.ID_LOCAL,
                    System.currentTimeMillis(),
                    0,
                    MessageStatus.UNKNOWN,
                    hopLimit,
                    channel,
                    wantAck,
                    0,  // hopStart
                    0f, // snr
                    0,  // rssi
                    null, // replyId
                    null, // relayNode
                    0,    // relays
                    false, // viaMqtt
                    0,    // retryCount
                    0,    // emoji
                    null  // sfppHash
            );
            meshServiceManager.sendToMesh(dp);
        } else {
            // Too large for single packet - use fountain coding
            Log.d(TAG, "Payload too large for single packet (" + payload.length
                    + " > " + MAX_SINGLE_PACKET + "), using fountain coding");

            if (prefs.getBoolean(Constants.PREF_PLUGIN_CHUNKING, false)) {
                Log.d(TAG, "Chunking already in progress, dropping message");
                return;
            }

            editor.putBoolean(Constants.PREF_PLUGIN_CHUNKING, true);
            editor.apply();

            int transferId = fountainChunkManager.send(payload, channel, hopLimit,
                    Constants.TRANSFER_TYPE_COT);
            if (transferId < 0) {
                Log.e(TAG, "Failed to start fountain transfer");
            } else {
                Log.d(TAG, "Started fountain transfer " + transferId);
            }

            editor.putBoolean(Constants.PREF_PLUGIN_CHUNKING, false);
            editor.apply();
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (key == null) return;

        if (FileSystemUtils.isEquals(key, Constants.PREF_PLUGIN_RATE_VALUE)) {
            String rate = prefs.getString(Constants.PREF_PLUGIN_RATE_VALUE, "0");
            Log.d(TAG, "Rate: " + rate);
            editor.putString("locationReportingStrategy", "Constant");
            editor.putString("constantReportingRateUnreliable", rate);
            editor.putString("constantReportingRateReliable", rate);
            editor.apply();
        }

        // Handle encryption preference changes
        if (Constants.PREF_PLUGIN_EXTRA_ENCRYPTION.equals(key)
                || Constants.PREF_PLUGIN_ENCRYPTION_PSK.equals(key)) {
            boolean encEnabled = prefs.getBoolean(Constants.PREF_PLUGIN_EXTRA_ENCRYPTION, false);
            String psk = prefs.getString(Constants.PREF_PLUGIN_ENCRYPTION_PSK, "");
            if (encEnabled && psk != null && !psk.isEmpty()) {
                encryptionManager.loadKey(psk);
                encryptionManager.setEnabled(true);
                Log.i(TAG, "App-layer encryption enabled via preference change");
            } else {
                encryptionManager.setEnabled(false);
                if (encEnabled) {
                    Log.w(TAG, "App-layer encryption enabled but no PSK configured");
                } else {
                    Log.d(TAG, "App-layer encryption disabled via preference change");
                }
            }
        }

        if (Constants.PREF_PLUGIN_EXTERNAL_GPS.equals(key)) {
            boolean shouldUseMeshtasticExternalGPS = prefs.getBoolean(Constants.PREF_PLUGIN_EXTERNAL_GPS, false);
            if (shouldUseMeshtasticExternalGPS) {
                int gpsPort = prefs.getInt(Constants.PREF_LISTEN_PORT, Constants.DEFAULT_GPS_PORT);
                meshtasticExternalGPS.start(gpsPort);
            } else {
                meshtasticExternalGPS.stop();
            }
        }
    }

    @NonNull
    private static IntentFilter getIntentFilter() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.ACTION_RECEIVED_ATAK_FORWARDER);
        intentFilter.addAction("com.geeksville.mesh.RECEIVED.257");
        intentFilter.addAction(Constants.ACTION_RECEIVED_ATAK_PLUGIN);
        intentFilter.addAction("com.geeksville.mesh.RECEIVED.72");
        intentFilter.addAction(Constants.ACTION_NODE_CHANGE);
        intentFilter.addAction(Constants.ACTION_MESH_CONNECTED);
        intentFilter.addAction(Constants.ACTION_MESH_DISCONNECTED);
        intentFilter.addAction(Constants.ACTION_RECEIVED_NODEINFO_APP);
        intentFilter.addAction(Constants.ACTION_RECEIVED_POSITION_APP);
        intentFilter.addAction(Constants.ACTION_MESSAGE_STATUS);
        intentFilter.addAction(Constants.ACTION_TEXT_MESSAGE_APP);
        return intentFilter;
    }

    // Static helper methods for backward compatibility
    public static void sendToMesh(DataPacket dp) {
        MeshServiceManager manager = MeshServiceManager.getInstance(MapView.getMapView().getContext());
        manager.sendToMesh(dp);
    }

    public static boolean sendFile(File f) {
        try {
            byte[] fileBytes = FileSystemUtils.read(f);

            NotificationHelper notificationHelper = NotificationHelper.getInstance(MapView.getMapView().getContext());

            // Use the singleton FountainChunkManager instance
            FountainChunkManager fcm = getFountainChunkManager();
            if (fcm == null) {
                Log.e(TAG, "FountainChunkManager not initialized");
                return false;
            }

            int transferId = fcm.send(
                    fileBytes,
                    MeshtasticReceiver.getChannelIndex(),
                    MeshtasticReceiver.getHopLimit(),
                    Constants.TRANSFER_TYPE_FILE
            );

            if (transferId >= 0) {
                notificationHelper.showCompletionNotification();
                return true;
            }

            return false;
        } catch (Exception e) {
            Log.e(TAG, "Failed to send file", e);
            return false;
        }
    }

    public static void setOwner(MeshUser meshUser) {
        MeshServiceManager manager = MeshServiceManager.getInstance(MapView.getMapView().getContext());
        manager.setOwner(meshUser);
    }

    public static void setChannel(byte[] channel) {
        MeshServiceManager manager = MeshServiceManager.getInstance(MapView.getMapView().getContext());
        manager.setChannel(channel);
    }

    public static void setConfig(byte[] config) {
        MeshServiceManager manager = MeshServiceManager.getInstance(MapView.getMapView().getContext());
        manager.setConfig(config);
    }

    public static byte[] getChannelSet() {
        MeshServiceManager manager = MeshServiceManager.getInstance(MapView.getMapView().getContext());
        return manager.getChannelSet();
    }

    public static byte[] getConfig() {
        MeshServiceManager manager = MeshServiceManager.getInstance(MapView.getMapView().getContext());
        return manager.getConfig();
    }

    public static List<NodeInfo> getNodes() {
        MeshServiceManager manager = MeshServiceManager.getInstance(MapView.getMapView().getContext());
        return manager.getNodes();
    }

    public static MyNodeInfo getMyNodeInfo() {
        MeshServiceManager manager = MeshServiceManager.getInstance(MapView.getMapView().getContext());
        return manager.getMyNodeInfo();
    }

    public static String getMyNodeID() {
        MeshServiceManager manager = MeshServiceManager.getInstance(MapView.getMapView().getContext());
        return manager.getMyNodeID();
    }

    public static boolean reconnect() {
        MeshServiceManager manager = MeshServiceManager.getInstance(MapView.getMapView().getContext());
        return manager.reconnect();
    }

    public static MeshServiceManager getMeshService() {
        return MeshServiceManager.getInstance(MapView.getMapView().getContext());
    }

    public static FountainChunkManager getFountainChunkManager() {
        return instance != null ? instance.fountainChunkManager : null;
    }
}