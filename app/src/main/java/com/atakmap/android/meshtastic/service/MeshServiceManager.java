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
            Toast.makeText(context, "Failed to bind to Meshtastic IMeshService", Toast.LENGTH_LONG).show();
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
    
    public void sendToMesh(DataPacket dataPacket) {
        if (dataPacket == null) {
            Log.w(TAG, "Cannot send null packet to mesh");
            return;
        }
        
        if (!isConnected()) {
            Log.w(TAG, "Cannot send to mesh - service not connected");
            return;
        }
        
        try {
            Log.d(TAG, "Sending to mesh: " + dataPacket.getTo());
            meshService.send(dataPacket);
        } catch (RemoteException e) {
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
        } catch (RemoteException e) {
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
        } catch (RemoteException e) {
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
        } catch (RemoteException e) {
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
        } catch (RemoteException e) {
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
        } catch (RemoteException e) {
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
    
    public MyNodeInfo getMyNodeInfo() {
        if (!isConnected()) {
            Log.w(TAG, "Cannot get my node info - service not connected");
            return null;
        }
        
        try {
            return meshService.getMyNodeInfo();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get my node info", e);
            return null;
        }
    }
    
    public String getMyNodeID() {
        if (!isConnected()) {
            Log.w(TAG, "Cannot get my node ID - service not connected");
            return "";
        }
        
        try {
            return meshService.getMyId();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get my node ID", e);
            return "";
        }
    }
    
    public int getPacketId() throws RemoteException {
        if (!isConnected()) {
            throw new IllegalStateException("Service not connected");
        }
        return meshService.getPacketId();
    }
    
    public IMeshService getService() {
        return meshService;
    }
}