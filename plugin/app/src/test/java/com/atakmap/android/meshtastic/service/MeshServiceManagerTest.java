package com.atakmap.android.meshtastic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;

import org.meshtastic.core.model.DataPacket;
import org.meshtastic.core.service.IMeshService;
import org.meshtastic.core.model.MeshUser;
import org.meshtastic.core.model.MyNodeInfo;
import org.meshtastic.core.model.NodeInfo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;

import okio.ByteString;

@ExtendWith(MockitoExtension.class)
class MeshServiceManagerTest {

    @Mock
    private Context context;

    @Mock
    private Context applicationContext;

    @Mock
    private IMeshService meshService;

    @Mock
    private IBinder binder;

    @Mock
    private MeshServiceManager.ConnectionListener connectionListener;

    private MeshServiceManager meshServiceManager;
    private ServiceConnection capturedServiceConnection;

    @BeforeEach
    void setUp() throws Exception {
        // Reset singleton so each test gets a fresh instance bound to fresh mocks.
        // Without this, the singleton holds the first test's mock references and
        // subsequent tests' ArgumentCaptors never fire.
        java.lang.reflect.Field instanceField =
                MeshServiceManager.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);

        when(context.getApplicationContext()).thenReturn(applicationContext);
        
        // Capture the ServiceConnection when bindService is called
        ArgumentCaptor<ServiceConnection> serviceConnectionCaptor = 
                ArgumentCaptor.forClass(ServiceConnection.class);
        when(applicationContext.bindService(any(Intent.class), 
                serviceConnectionCaptor.capture(), anyInt())).thenReturn(true);
        
        meshServiceManager = MeshServiceManager.getInstance(context);
        meshServiceManager.connect();
        
        capturedServiceConnection = serviceConnectionCaptor.getValue();
        
        // Simulate service connection
        when(IMeshService.Stub.asInterface(binder)).thenReturn(meshService);
        capturedServiceConnection.onServiceConnected(
                new ComponentName("test.package", "test.class"), binder);
    }

    @Test
    void shouldConnectToService() {
        // Given
        MeshServiceManager manager = MeshServiceManager.getInstance(context);
        
        // When
        boolean result = manager.connect();
        
        // Then
        assertThat(result).isTrue();
        verify(applicationContext, times(2)).bindService(any(Intent.class), 
                any(ServiceConnection.class), eq(Context.BIND_AUTO_CREATE));
    }

    @Test
    void shouldHandleFailedConnection() {
        // Given
        when(applicationContext.bindService(any(Intent.class), 
                any(ServiceConnection.class), anyInt())).thenReturn(false);
        MeshServiceManager manager = MeshServiceManager.getInstance(context);
        
        // When
        boolean result = manager.connect();
        
        // Then
        assertThat(result).isFalse();
    }

    @Test
    void shouldDisconnectFromService() {
        // When
        meshServiceManager.disconnect();
        
        // Then
        verify(applicationContext).unbindService(capturedServiceConnection);
        assertThat(meshServiceManager.getConnectionState())
                .isEqualTo(MeshServiceManager.ServiceConnectionState.DISCONNECTED);
    }

    @Test
    void shouldReconnectToService() {
        // When
        boolean result = meshServiceManager.reconnect();
        
        // Then
        assertThat(result).isTrue();
        verify(applicationContext).unbindService(any(ServiceConnection.class));
        verify(applicationContext, times(2)).bindService(any(Intent.class), 
                any(ServiceConnection.class), anyInt());
    }

    @Test
    void shouldNotifyConnectionListener() {
        // Given
        meshServiceManager.setConnectionListener(connectionListener);
        
        // When service connects
        capturedServiceConnection.onServiceConnected(
                new ComponentName("test", "test"), binder);
        
        // Then
        verify(connectionListener).onServiceConnected();
        
        // When service disconnects
        capturedServiceConnection.onServiceDisconnected(
                new ComponentName("test", "test"));
        
        // Then
        verify(connectionListener).onServiceDisconnected();
    }

    @Test
    void shouldSendDataPacketToMesh() throws RemoteException {
        // Given
        DataPacket packet = mock(DataPacket.class);
        when(packet.getTo()).thenReturn("recipient");
        // sendToMesh validates getBytes().toByteArray() != null and getDataType() != 0
        when(packet.getBytes()).thenReturn(ByteString.of((byte) 1));
        when(packet.getDataType()).thenReturn(1);

        // When
        meshServiceManager.sendToMesh(packet);

        // Then
        verify(meshService).send(packet);
    }

    @Test
    void shouldNotSendNullPacket() throws RemoteException {
        // When
        meshServiceManager.sendToMesh(null);
        
        // Then
        verify(meshService, never()).send(any());
    }

    @Test
    void shouldHandleRemoteExceptionWhenSending() throws RemoteException {
        // Given
        DataPacket packet = mock(DataPacket.class);
        // Must pass sendToMesh validation before the RemoteException can be triggered
        when(packet.getBytes()).thenReturn(ByteString.of((byte) 1));
        when(packet.getDataType()).thenReturn(1);
        doThrow(new RemoteException()).when(meshService).send(any());

        // When
        meshServiceManager.sendToMesh(packet);

        // Then - should not throw, just log error
        verify(meshService).send(packet);
    }

    @Test
    void shouldSetOwner() throws RemoteException {
        // Given
        MeshUser user = mock(MeshUser.class);
        
        // When
        meshServiceManager.setOwner(user);
        
        // Then
        verify(meshService).setOwner(user);
    }

    @Test
    void shouldSetChannel() throws RemoteException {
        // Given
        byte[] channel = new byte[]{1, 2, 3};
        
        // When
        meshServiceManager.setChannel(channel);
        
        // Then
        verify(meshService).setChannel(channel);
    }

    @Test
    void shouldSetConfig() throws RemoteException {
        // Given
        byte[] config = new byte[]{4, 5, 6};
        
        // When
        meshServiceManager.setConfig(config);
        
        // Then
        verify(meshService).setConfig(config);
    }

    @Test
    void shouldGetChannelSet() throws RemoteException {
        // Given
        byte[] expectedChannelSet = new byte[]{7, 8, 9};
        when(meshService.getChannelSet()).thenReturn(expectedChannelSet);
        
        // When
        byte[] result = meshServiceManager.getChannelSet();
        
        // Then
        assertThat(result).isEqualTo(expectedChannelSet);
    }

    @Test
    void shouldGetConfig() throws RemoteException {
        // Given
        byte[] expectedConfig = new byte[]{10, 11, 12};
        when(meshService.getConfig()).thenReturn(expectedConfig);
        
        // When
        byte[] result = meshServiceManager.getConfig();
        
        // Then
        assertThat(result).isEqualTo(expectedConfig);
    }

    @Test
    void shouldGetNodes() throws Exception {
        // Given
        NodeInfo node1 = mock(NodeInfo.class);
        NodeInfo node2 = mock(NodeInfo.class);
        List<NodeInfo> expectedNodes = Arrays.asList(node1, node2);
        when(meshService.getNodes()).thenReturn(expectedNodes);
        
        // When
        List<NodeInfo> result = meshServiceManager.getNodes();
        
        // Then
        assertThat(result).isEqualTo(expectedNodes);
    }

    @Test
    void shouldGetMyNodeInfo() throws RemoteException {
        // Given
        MyNodeInfo expectedNodeInfo = mock(MyNodeInfo.class);
        when(meshService.getMyNodeInfo()).thenReturn(expectedNodeInfo);
        
        // When
        MyNodeInfo result = meshServiceManager.getMyNodeInfo();
        
        // Then
        assertThat(result).isEqualTo(expectedNodeInfo);
    }

    @Test
    void shouldGetMyNodeID() throws RemoteException {
        // Given
        String expectedId = "NODE123";
        when(meshService.getMyId()).thenReturn(expectedId);
        
        // When
        String result = meshServiceManager.getMyNodeID();
        
        // Then
        assertThat(result).isEqualTo(expectedId);
    }

    @Test
    void shouldGetPacketId() throws Exception {
        // Given
        int expectedId = 42;
        when(meshService.getPacketId()).thenReturn(expectedId);
        
        // When
        int result = meshServiceManager.getPacketId();
        
        // Then
        assertThat(result).isEqualTo(expectedId);
    }

    @Test
    void shouldThrowWhenGettingPacketIdWithoutConnection() {
        // Given
        meshServiceManager.disconnect();
        
        // When & Then
        assertThatThrownBy(() -> meshServiceManager.getPacketId())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Service not connected");
    }

    @Test
    void shouldReturnServiceInstance() {
        // When
        IMeshService service = meshServiceManager.getService();
        
        // Then
        assertThat(service).isEqualTo(meshService);
    }

    @Test
    void shouldReportConnectedState() {
        // Then
        assertThat(meshServiceManager.isConnected()).isTrue();
        assertThat(meshServiceManager.getConnectionState())
                .isEqualTo(MeshServiceManager.ServiceConnectionState.CONNECTED);
    }

    @Test
    void shouldReportDisconnectedState() {
        // When
        meshServiceManager.disconnect();
        
        // Then
        assertThat(meshServiceManager.isConnected()).isFalse();
        assertThat(meshServiceManager.getConnectionState())
                .isEqualTo(MeshServiceManager.ServiceConnectionState.DISCONNECTED);
    }

    @Test
    void shouldUseSingletonPattern() {
        // When
        MeshServiceManager instance1 = MeshServiceManager.getInstance(context);
        MeshServiceManager instance2 = MeshServiceManager.getInstance(context);
        
        // Then
        assertThat(instance1).isSameAs(instance2);
    }
}