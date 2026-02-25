package com.atakmap.android.meshtastic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.atakmap.android.maps.MapView;
import com.atakmap.android.meshtastic.encryption.AppLayerEncryptionManager;
import com.atakmap.android.meshtastic.service.MeshServiceManager;
import com.atakmap.android.meshtastic.util.Constants;
import com.atakmap.android.meshtastic.util.fountain.FountainChunkManager;
import com.atakmap.comms.CotServiceRemote;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;

import android.preference.PreferenceManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.meshtastic.core.model.DataPacket;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;

@ExtendWith(MockitoExtension.class)
class MeshtasticReceiverTest {

    @Mock
    private MeshtasticExternalGPS meshtasticExternalGPS;

    @Mock
    private FountainChunkManager fountainChunkManager;

    @Mock
    private Context context;

    @Mock
    private Intent intent;

    @Mock
    private MapView mapView;

    @Mock
    private NotificationManager notificationManager;

    @Mock
    private SharedPreferences sharedPreferences;

    @Mock
    private SharedPreferences.Editor editor;

    private MeshtasticReceiver meshtasticReceiver;

    @BeforeEach
    void setUp() {
        // MeshtasticReceiver has a static field initializer:
        //   private static ProtectedSharedPreferences prefs =
        //       new ProtectedSharedPreferences(
        //           PreferenceManager.getDefaultSharedPreferences(MapView.getMapView().getContext()));
        // This runs when the class is first loaded (inside the MockedStatic scope below).
        // Without mocking PreferenceManager, getDefaultSharedPreferences() returns null via
        // Android stubs, causing a Kotlin non-null NPE in ProtectedSharedPreferences.<init>.
        //
        // MeshtasticReceiver also uses `_mapView` (from `import static MapView.*`) directly
        // in its constructor — a static FIELD access that MockedStatic does not intercept.
        // We must set MapView._mapView manually.
        MapView._mapView = mapView;

        when(mapView.getContext()).thenReturn(context);
        when(context.getSystemService(Context.NOTIFICATION_SERVICE))
                .thenReturn(notificationManager);
        // NOTE: Do NOT stub context.checkSelfPermission() here. Production code calls
        // ContextCompat.checkSelfPermission() (static utility), not context.checkSelfPermission().
        // Stubbing the instance method causes UnnecessaryStubbingException in strict mode.
        //
        // Do NOT stub sharedPreferences.edit() here. prefs.edit() is only called in the
        // async worker thread spawned by ACTION_RECEIVED_ATAK_FORWARDER, so it is never
        // invoked in synchronous test paths and would cause UnnecessaryStubbingException.

        try (MockedStatic<MapView> mapViewMockedStatic = Mockito.mockStatic(MapView.class);
             MockedStatic<PreferenceManager> prefMockedStatic =
                     Mockito.mockStatic(PreferenceManager.class)) {
            mapViewMockedStatic.when(MapView::getMapView).thenReturn(mapView);
            prefMockedStatic.when(() ->
                    PreferenceManager.getDefaultSharedPreferences(any(Context.class)))
                    .thenReturn(sharedPreferences);

            meshtasticReceiver = new MeshtasticReceiver(meshtasticExternalGPS, fountainChunkManager);
        }

        // Reset the static prefs field to wrap the current test's sharedPreferences mock.
        // prefs is initialized once at class load time, so without this reset each test
        // after the first would be reading from a stale mock reference.
        try {
            Field prefsField = MeshtasticReceiver.class.getDeclaredField("prefs");
            prefsField.setAccessible(true);
            prefsField.set(null, new ProtectedSharedPreferences(sharedPreferences));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    void tearDown() {
        // Reset the static stub field to avoid cross-test contamination.
        MapView._mapView = null;

        // Reset AppLayerEncryptionManager singleton so DM relay tests don't pollute others.
        try {
            Field encField = AppLayerEncryptionManager.class.getDeclaredField("instance");
            encField.setAccessible(true);
            encField.set(null, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void shouldHandleMeshConnectedAction() {
        // Given
        when(intent.getAction()).thenReturn(Constants.ACTION_MESH_CONNECTED);
        when(intent.getStringExtra(Constants.EXTRA_CONNECTED))
                .thenReturn(Constants.STATE_CONNECTED);

        try (MockedStatic<MapView> mapViewMockedStatic = Mockito.mockStatic(MapView.class);
             MockedStatic<MeshtasticMapComponent> componentMockedStatic =
                     Mockito.mockStatic(MeshtasticMapComponent.class)) {

            mapViewMockedStatic.when(MapView::getMapView).thenReturn(mapView);
            componentMockedStatic.when(MeshtasticMapComponent::reconnect).thenReturn(true);

            // When
            meshtasticReceiver.onReceive(context, intent);

            // Then
            componentMockedStatic.verify(MeshtasticMapComponent::reconnect);
        }
    }

    @Test
    void shouldHandleMeshDisconnectedAction() {
        // Given - production calls getStringExtra(EXTRA_DISCONNECTED), not getBooleanExtra
        when(intent.getAction()).thenReturn(Constants.ACTION_MESH_DISCONNECTED);

        try (MockedStatic<MapView> mapViewMockedStatic = Mockito.mockStatic(MapView.class);
             MockedStatic<MeshtasticMapComponent> componentMockedStatic =
                     Mockito.mockStatic(MeshtasticMapComponent.class)) {

            mapViewMockedStatic.when(MapView::getMapView).thenReturn(mapView);

            // When
            meshtasticReceiver.onReceive(context, intent);

            // Then - verify the action was dispatched and string extra was read
            verify(intent).getStringExtra(Constants.EXTRA_DISCONNECTED);
        }
    }

    @Test
    void shouldIgnoreNullAction() {
        // Given
        when(intent.getAction()).thenReturn(null);

        // When
        meshtasticReceiver.onReceive(context, intent);

        // Then - should return early, no exceptions
        verify(intent).getAction();
        verify(intent, never()).getStringExtra(anyString());
    }

    @Test
    void shouldHandleTextMessageAction() {
        // Given - production uses getParcelableExtra, not getByteArrayExtra
        when(intent.getAction()).thenReturn(Constants.ACTION_TEXT_MESSAGE_APP);

        try (MockedStatic<MapView> mapViewMockedStatic = Mockito.mockStatic(MapView.class)) {
            mapViewMockedStatic.when(MapView::getMapView).thenReturn(mapView);

            // When
            meshtasticReceiver.onReceive(context, intent);

            // Then - production calls getParcelableExtra(EXTRA_PAYLOAD) and returns early on null
            verify(intent).getParcelableExtra(Constants.EXTRA_PAYLOAD);
        }
    }

    @Test
    void shouldHandlePositionAction() {
        // Given - ACTION_RECEIVED_POSITION_APP is not in the onReceive switch; dispatches silently
        when(intent.getAction()).thenReturn(Constants.ACTION_RECEIVED_POSITION_APP);

        try (MockedStatic<MapView> mapViewMockedStatic = Mockito.mockStatic(MapView.class)) {
            mapViewMockedStatic.when(MapView::getMapView).thenReturn(mapView);

            // When
            meshtasticReceiver.onReceive(context, intent);

            // Then - action is read; no payload extraction since action is unhandled
            verify(intent).getAction();
        }
    }

    @Test
    void shouldHandleNodeInfoAction() {
        // Given - ACTION_RECEIVED_NODEINFO_APP is not in the onReceive switch; dispatches silently
        when(intent.getAction()).thenReturn(Constants.ACTION_RECEIVED_NODEINFO_APP);

        try (MockedStatic<MapView> mapViewMockedStatic = Mockito.mockStatic(MapView.class)) {
            mapViewMockedStatic.when(MapView::getMapView).thenReturn(mapView);

            // When
            meshtasticReceiver.onReceive(context, intent);

            // Then - action is read; no payload extraction since action is unhandled
            verify(intent).getAction();
        }
    }

    @Test
    void shouldHandleAtakForwarderAction() {
        // Given - ATAK_FORWARDER spawns a worker thread calling receive(intent) asynchronously.
        // Thread-internal behavior (getParcelableExtra) cannot be reliably verified here.
        when(intent.getAction()).thenReturn(Constants.ACTION_RECEIVED_ATAK_FORWARDER);

        try (MockedStatic<MapView> mapViewMockedStatic = Mockito.mockStatic(MapView.class)) {
            mapViewMockedStatic.when(MapView::getMapView).thenReturn(mapView);

            // When
            meshtasticReceiver.onReceive(context, intent);

            // Then - action is read and dispatched to the worker thread without exception
            verify(intent).getAction();
        }
    }

    @Test
    void shouldHandleAudioAppAction() {
        // Given - ACTION_RECEIVED_AUDIO_APP is not in the onReceive switch; dispatches silently
        when(intent.getAction()).thenReturn(Constants.ACTION_RECEIVED_AUDIO_APP);

        try (MockedStatic<MapView> mapViewMockedStatic = Mockito.mockStatic(MapView.class)) {
            mapViewMockedStatic.when(MapView::getMapView).thenReturn(mapView);

            // When
            meshtasticReceiver.onReceive(context, intent);

            // Then - action is read; no payload extraction since action is unhandled
            verify(intent).getAction();
        }
    }

    @Test
    void shouldHandleMessageStatusAction() {
        // Given - production uses getParcelableExtra for status, not getStringExtra
        when(intent.getAction()).thenReturn(Constants.ACTION_MESSAGE_STATUS);
        when(intent.getIntExtra(eq(Constants.EXTRA_PACKET_ID), eq(0))).thenReturn(123);

        try (MockedStatic<MapView> mapViewMockedStatic = Mockito.mockStatic(MapView.class)) {
            mapViewMockedStatic.when(MapView::getMapView).thenReturn(mapView);

            // When
            meshtasticReceiver.onReceive(context, intent);

            // Then
            verify(intent).getIntExtra(eq(Constants.EXTRA_PACKET_ID), eq(0));
            verify(intent).getParcelableExtra(Constants.EXTRA_STATUS);
        }
    }

    @Test
    void shouldImplementCotEventListener() {
        // Given
        CotEvent cotEvent = mock(CotEvent.class);

        // When
        meshtasticReceiver.onCotEvent(cotEvent, null);

        // Then - verify it implements the interface
        assertThat(meshtasticReceiver).isInstanceOf(CotServiceRemote.CotEventListener.class);
    }

    @Test
    void onCotEvent_dmRelay_sendsEncryptedPacket() throws Exception {
        // Reset AppLayerEncryptionManager singleton and configure with encryption enabled
        Field encField = AppLayerEncryptionManager.class.getDeclaredField("instance");
        encField.setAccessible(true);
        encField.set(null, null);
        AppLayerEncryptionManager encManager = AppLayerEncryptionManager.getInstance();
        encManager.setEnabled(true);
        encManager.loadKey("test-psk-for-dm-relay");

        // Enable server relay via preference
        when(sharedPreferences.getBoolean(eq(Constants.PREF_PLUGIN_FROM_SERVER), eq(false)))
                .thenReturn(true);

        // Build DM CoT detail with __chat, link, and remarks elements
        CotDetail detail = new CotDetail("detail");
        CotDetail chat = new CotDetail("__chat");
        chat.setAttribute("senderCallsign", "Alice");
        chat.setAttribute("to", "Bob");
        detail.addChild(chat);
        CotDetail link = new CotDetail("link");
        link.setAttribute("uid", "ANDROID-abc123");
        detail.addChild(link);
        CotDetail remarks = new CotDetail("remarks");
        remarks.setInnerText("Hi Bob");
        detail.addChild(remarks);

        // Mock CotEvent as a direct message (b-t-f type, UID without "All Chat Rooms")
        CotEvent cotEvent = mock(CotEvent.class);
        when(cotEvent.isValid()).thenReturn(true);
        when(cotEvent.getType()).thenReturn("b-t-f");
        when(cotEvent.getUID()).thenReturn("GeoChat.Alice.Bob.UUID123");
        when(cotEvent.getDetail()).thenReturn(detail);

        // Capture the DataPacket sent to the mesh layer
        MeshServiceManager mockMeshService = mock(MeshServiceManager.class);
        ArgumentCaptor<DataPacket> captor = ArgumentCaptor.forClass(DataPacket.class);

        try (MockedStatic<MeshtasticMapComponent> componentMockedStatic =
                     Mockito.mockStatic(MeshtasticMapComponent.class)) {
            componentMockedStatic.when(MeshtasticMapComponent::getMeshService)
                    .thenReturn(mockMeshService);

            // When
            meshtasticReceiver.onCotEvent(cotEvent, null);

            // Then - payload sent and starts with app-layer encryption marker
            verify(mockMeshService).sendToMesh(captor.capture());
            byte[] payload = captor.getValue().getBytes().toByteArray();
            assertThat(payload[0]).isEqualTo(AppLayerEncryptionManager.APP_LAYER_MARKER);
        }
    }

    @Test
    void onCotEvent_dmRelay_serverRelayDisabled_drops() {
        // PREF_PLUGIN_FROM_SERVER defaults to false — no stub needed
        CotEvent cotEvent = mock(CotEvent.class);

        try (MockedStatic<MeshtasticMapComponent> componentMockedStatic =
                     Mockito.mockStatic(MeshtasticMapComponent.class)) {
            // When
            meshtasticReceiver.onCotEvent(cotEvent, null);

            // Then - getMeshService() never called, proving sendToMesh was never invoked
            componentMockedStatic.verify(
                    () -> MeshtasticMapComponent.getMeshService(), Mockito.never());
        }
    }

    @Test
    void relay_underMTU_sendsDirect() throws Exception {
        // Encryption adds 34-byte V2 overhead; "Hi" message keeps payload well under the 231-byte MTU
        AppLayerEncryptionManager encManager = AppLayerEncryptionManager.getInstance();
        encManager.setEnabled(true);
        encManager.loadKey("test-psk-for-relay-routing");
        when(sharedPreferences.getBoolean(eq(Constants.PREF_PLUGIN_FROM_SERVER), eq(false)))
                .thenReturn(true);

        CotDetail detail = new CotDetail("detail");
        CotDetail chat = new CotDetail("__chat");
        chat.setAttribute("senderCallsign", "Alice");
        detail.addChild(chat);
        CotDetail link = new CotDetail("link");
        link.setAttribute("uid", "ANDROID-abc123");
        detail.addChild(link);
        CotDetail remarks = new CotDetail("remarks");
        remarks.setInnerText("Hi");
        detail.addChild(remarks);

        CotEvent cotEvent = mock(CotEvent.class);
        when(cotEvent.isValid()).thenReturn(true);
        when(cotEvent.getType()).thenReturn("b-t-f");
        when(cotEvent.getUID()).thenReturn("GeoChat.Alice.All Chat Rooms.UUID");
        when(cotEvent.getDetail()).thenReturn(detail);

        MeshServiceManager mockMeshService = mock(MeshServiceManager.class);
        try (MockedStatic<MeshtasticMapComponent> componentMockedStatic =
                     Mockito.mockStatic(MeshtasticMapComponent.class)) {
            componentMockedStatic.when(MeshtasticMapComponent::getMeshService)
                    .thenReturn(mockMeshService);

            meshtasticReceiver.onCotEvent(cotEvent, null);

            // Under-MTU: sent as single DataPacket; fountain coding never invoked
            verify(mockMeshService).sendToMesh(any(DataPacket.class));
            verify(fountainChunkManager, never()).send(
                    any(byte[].class), anyInt(), anyInt(), anyByte());
        }
    }

    @Test
    void relay_overMTU_usesFountainCoding() throws Exception {
        // 200-char message encodes to a TAKPacket >> 197 bytes; encrypted payload >> 231-byte MTU
        AppLayerEncryptionManager encManager = AppLayerEncryptionManager.getInstance();
        encManager.setEnabled(true);
        encManager.loadKey("test-psk-for-relay-routing");
        when(sharedPreferences.getBoolean(eq(Constants.PREF_PLUGIN_FROM_SERVER), eq(false)))
                .thenReturn(true);

        CotDetail detail = new CotDetail("detail");
        CotDetail chat = new CotDetail("__chat");
        chat.setAttribute("senderCallsign", "Alice");
        detail.addChild(chat);
        CotDetail link = new CotDetail("link");
        link.setAttribute("uid", "ANDROID-abc123");
        detail.addChild(link);
        CotDetail remarks = new CotDetail("remarks");
        remarks.setInnerText("X".repeat(200));
        detail.addChild(remarks);

        CotEvent cotEvent = mock(CotEvent.class);
        when(cotEvent.isValid()).thenReturn(true);
        when(cotEvent.getType()).thenReturn("b-t-f");
        when(cotEvent.getUID()).thenReturn("GeoChat.Alice.All Chat Rooms.UUID");
        when(cotEvent.getDetail()).thenReturn(detail);

        try (MockedStatic<MeshtasticMapComponent> componentMockedStatic =
                     Mockito.mockStatic(MeshtasticMapComponent.class)) {
            meshtasticReceiver.onCotEvent(cotEvent, null);

            // Over-MTU: routed to fountain coding; sendToMesh never invoked
            componentMockedStatic.verify(
                    () -> MeshtasticMapComponent.getMeshService(), Mockito.never());
            verify(fountainChunkManager).send(
                    any(byte[].class), anyInt(), anyInt(), anyByte());
        }
    }

    @Test
    void relay_noFountainManager_dropsWithError() throws Exception {
        // Over-MTU payload with null fountain manager: dropped gracefully, no send at all
        AppLayerEncryptionManager encManager = AppLayerEncryptionManager.getInstance();
        encManager.setEnabled(true);
        encManager.loadKey("test-psk-for-relay-routing");
        when(sharedPreferences.getBoolean(eq(Constants.PREF_PLUGIN_FROM_SERVER), eq(false)))
                .thenReturn(true);

        CotDetail detail = new CotDetail("detail");
        CotDetail chat = new CotDetail("__chat");
        chat.setAttribute("senderCallsign", "Alice");
        detail.addChild(chat);
        CotDetail link = new CotDetail("link");
        link.setAttribute("uid", "ANDROID-abc123");
        detail.addChild(link);
        CotDetail remarks = new CotDetail("remarks");
        remarks.setInnerText("X".repeat(200));
        detail.addChild(remarks);

        CotEvent cotEvent = mock(CotEvent.class);
        when(cotEvent.isValid()).thenReturn(true);
        when(cotEvent.getType()).thenReturn("b-t-f");
        when(cotEvent.getUID()).thenReturn("GeoChat.Alice.All Chat Rooms.UUID");
        when(cotEvent.getDetail()).thenReturn(detail);

        // Class is already loaded; static initializer won't re-run. MapView._mapView is set
        // from setUp(), so the constructor's getMapView() calls resolve correctly.
        MeshtasticReceiver nullFountainReceiver;
        try (MockedStatic<MapView> mapViewStatic = Mockito.mockStatic(MapView.class)) {
            mapViewStatic.when(MapView::getMapView).thenReturn(mapView);
            nullFountainReceiver = new MeshtasticReceiver(meshtasticExternalGPS, null);
        }

        try (MockedStatic<MeshtasticMapComponent> componentMockedStatic =
                     Mockito.mockStatic(MeshtasticMapComponent.class)) {
            nullFountainReceiver.onCotEvent(cotEvent, null);

            // Null fountain + oversized payload: message dropped, no transmission attempted
            componentMockedStatic.verify(
                    () -> MeshtasticMapComponent.getMeshService(), Mockito.never());
        }
    }
}
