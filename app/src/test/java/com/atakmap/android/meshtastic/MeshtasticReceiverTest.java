package com.atakmap.android.meshtastic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.atakmap.android.meshtastic.util.Constants;
import com.atakmap.android.meshtastic.util.fountain.FountainChunkManager;
import com.atakmap.comms.CotServiceRemote;
import com.atakmap.coremap.cot.event.CotEvent;

import android.preference.PreferenceManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

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
    }

    @AfterEach
    void tearDown() {
        // Reset the static stub field to avoid cross-test contamination.
        MapView._mapView = null;
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
}
