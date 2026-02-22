package com.atakmap.android.meshtastic.service;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.widget.Toast;

import com.atakmap.android.meshtastic.util.Constants;
import com.atakmap.coremap.log.Log;
import org.meshtastic.core.model.DataPacket;
import org.meshtastic.core.service.IMeshService;
import org.meshtastic.core.model.MeshUser;
import org.meshtastic.core.model.MyNodeInfo;
import org.meshtastic.core.model.NodeInfo;

import java.util.List;

public class MeshServiceManager {
    private static final String TAG = "MeshServiceManager";
    private static volatile MeshServiceManager instance;
    private static final Object INSTANCE_LOCK = new Object();

    private final Context context;
    private volatile IMeshService meshService;
    private ServiceConnection serviceConnection;
    private Intent serviceIntent;
    private volatile ServiceConnectionState connectionState;
    private ConnectionListener connectionListener;

    // Cached MyNodeInfo - populated when mesh is fully connected
    private volatile MyNodeInfo cachedMyNodeInfo = null;
    
    public enum ServiceConnectionState {
        DISCONNECTED,
        CONNECTED
    }
    
    public interface ConnectionListener {
        void onServiceConnected();
        void onServiceDisconnected();
    }
    
    private MeshServiceManager(Context context) {
        this.context = context.getApplicationContext();
        this.connectionState = ServiceConnectionState.DISCONNECTED;
        initializeServiceIntent();
        createServiceConnection();
    }
    
    public static MeshServiceManager getInstance(Context context) {
        if (instance == null) {
            synchronized (INSTANCE_LOCK) {
                if (instance == null) {
                    instance = new MeshServiceManager(context);
                }
            }
        }
        return instance;
    }
    
    private void initializeServiceIntent() {
        serviceIntent = new Intent();
        serviceIntent.setClassName(Constants.PACKAGE_NAME, Constants.CLASS_NAME);
    }
    
    private void createServiceConnection() {
        serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName className, IBinder service) {
                Log.v(TAG, "Service connected");
                meshService = IMeshService.Stub.asInterface(service);
                connectionState = ServiceConnectionState.CONNECTED;

                // Register for explicit broadcasts to bypass Android implicit broadcast restrictions
                try {
                    String packageName = context.getPackageName();
                    String receiverName = "com.atakmap.android.meshtastic.MeshtasticReceiver";
                    meshService.subscribeReceiver(packageName, receiverName);
                    Log.i(TAG, "Registered receiver for explicit broadcasts: " + packageName + "/" + receiverName);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to register receiver for explicit broadcasts", e);
                }

                if (connectionListener != null) {
                    connectionListener.onServiceConnected();
                }
            }
            
            @Override
            public void onServiceDisconnected(ComponentName className) {
                Log.e(TAG, "Service disconnected");
                meshService = null;
                connectionState = ServiceConnectionState.DISCONNECTED;
                
                if (connectionListener != null) {
                    connectionListener.onServiceDisconnected();
                }
            }
        };
    }
    
    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }
    
    public boolean connect() {
        boolean result = context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        if (!result) {
            Toast toast = Toast.makeText(context, "Failed to bind to Meshtastic IMeshService", Toast.LENGTH_LONG);
            if (toast != null) toast.show();
        }
        return result;
    }
    
    public void disconnect() {
        if (connectionState == ServiceConnectionState.CONNECTED) {
            context.unbindService(serviceConnection);
            connectionState = ServiceConnectionState.DISCONNECTED;
        }
    }
    
    public boolean reconnect() {
        disconnect();
        return connect();
    }
    
    public ServiceConnectionState getConnectionState() {
        return connectionState;
    }
    
    public boolean isConnected() {
        return connectionState == ServiceConnectionState.CONNECTED && meshService != null;
    }
    
    // Maximum payload size (Meshtastic app rejects exactly 233 bytes, use 231 for safety)
    private static final int MAX_PAYLOAD_SIZE = 231;

    public void sendToMesh(DataPacket dataPacket) {
        if (dataPacket == null) {
            Log.w(TAG, "Cannot send null packet to mesh");
            return;
        }

        // Client-side validation to prevent service exceptions that cause
        // deserialization failures on some Android versions
        byte[] bytes = dataPacket.getBytes().toByteArray();
        if (bytes == null) {
            Log.w(TAG, "Cannot send packet with null bytes to mesh");
            return;
        }

        if (dataPacket.getDataType() == 0) {
            Log.w(TAG, "Cannot send packet with dataType 0 (port numbers must be non-zero)");
            return;
        }

        if (bytes.length > MAX_PAYLOAD_SIZE) {
            Log.w(TAG, "Cannot send packet - message too long (" + bytes.length + " > " + MAX_PAYLOAD_SIZE + " bytes)");
            return;
        }

        if (!isConnected()) {
            Log.w(TAG, "Cannot send to mesh - service not connected");
            return;
        }

        try {
            Log.d(TAG, "Sending to mesh: " + dataPacket.getTo());
            meshService.send(dataPacket);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send to mesh", e);
        }
    }
    
    public void setOwner(MeshUser meshUser) {
        if (!isConnected()) {
            Log.w(TAG, "Cannot set owner - service not connected");
            return;
        }

        try {
            meshService.setOwner(meshUser);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set owner", e);
        }
    }
    
    public void setChannel(byte[] channel) {
        if (!isConnected()) {
            Log.w(TAG, "Cannot set channel - service not connected");
            return;
        }

        try {
            meshService.setChannel(channel);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set channel", e);
        }
    }
    
    public void setConfig(byte[] config) {
        if (!isConnected()) {
            Log.w(TAG, "Cannot set config - service not connected");
            return;
        }

        try {
            meshService.setConfig(config);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set config", e);
        }
    }
    
    public byte[] getChannelSet() {
        if (!isConnected()) {
            Log.w(TAG, "Cannot get channel set - service not connected");
            return null;
        }

        try {
            return meshService.getChannelSet();
        } catch (Exception e) {
            Log.e(TAG, "Failed to get channel set", e);
            return null;
        }
    }
    
    public byte[] getConfig() {
        if (!isConnected()) {
            Log.w(TAG, "Cannot get config - service not connected");
            return null;
        }

        try {
            return meshService.getConfig();
        } catch (Exception e) {
            Log.e(TAG, "Failed to get config", e);
            return null;
        }
    }
    
    public List<NodeInfo> getNodes() {
        if (!isConnected()) {
            Log.w(TAG, "Cannot get nodes - service not connected");
            return null;
        }
        
        try {
            return meshService.getNodes();
        } catch (Exception e) {
            Log.e(TAG, "Failed to get nodes", e);
            return null;
        }
    }
    
    /**
     * Get MyNodeInfo, using cache if available.
     * Returns null if the mesh service is not fully connected (DB not ready).
     */
    public MyNodeInfo getMyNodeInfo() {
        if (!isConnected()) {
            Log.w(TAG, "Cannot get my node info - IPC service not connected");
            return cachedMyNodeInfo; // Return cache if available
        }

        // Return cached value if we have it
        if (cachedMyNodeInfo != null) {
            return cachedMyNodeInfo;
        }

        try {
            MyNodeInfo info = meshService.getMyNodeInfo();
            if (info == null) {
                Log.d(TAG, "getMyNodeInfo() returned null - Meshtastic DB not ready yet");
            } else {
                // Cache the successful result
                cachedMyNodeInfo = info;
                Log.d(TAG, "MyNodeInfo cached: nodeNum=" + info.getMyNodeNum() + ", firmware=" + info.getFirmwareVersion());
            }
            return info;
        } catch (Exception e) {
            Log.e(TAG, "Failed to get my node info", e);
            return null;
        }
    }

    /**
     * Called when ACTION_MESH_CONNECTED broadcast is received with "Connected" state.
     * Sets a flag to indicate we should try to fetch MyNodeInfo on next access.
     */
    public void onMeshConnected() {
        Log.d(TAG, "Mesh connected - will fetch MyNodeInfo on next access");
        // Clear cache so next getMyNodeInfo() call will fetch fresh data
        cachedMyNodeInfo = null;
        // Don't make AIDL calls here - let the normal access pattern handle it
    }

    /**
     * Called when mesh disconnects - clear cached data
     */
    public void onMeshDisconnected() {
        Log.d(TAG, "Mesh disconnected - clearing cached MyNodeInfo");
        cachedMyNodeInfo = null;
    }

    /**
     * Get cached MyNodeInfo without making an AIDL call.
     * Returns null if not yet cached.
     */
    public MyNodeInfo getCachedMyNodeInfo() {
        return cachedMyNodeInfo;
    }
    
    public String getMyNodeID() {
        if (!isConnected()) {
            Log.w(TAG, "Cannot get my node ID - service not connected");
            return "";
        }

        try {
            return meshService.getMyId();
        } catch (Exception e) {
            Log.e(TAG, "Failed to get my node ID", e);
            return "";
        }
    }
    
    public int getPacketId() throws Exception {
        if (!isConnected()) {
            throw new IllegalStateException("Service not connected");
        }
        return meshService.getPacketId();
    }
    
    public IMeshService getService() {
        return meshService;
    }
}