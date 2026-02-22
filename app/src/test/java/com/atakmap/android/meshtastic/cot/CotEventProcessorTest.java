package com.atakmap.android.meshtastic.cot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotPoint;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.time.CoordinatedTime;
import org.meshtastic.proto.TAKPacket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CotEventProcessorTest {

    @Mock
    private MapView mapView;

    @Mock
    private Marker selfMarker;

    @Mock
    private GeoPoint geoPoint;

    private CotEventProcessor cotEventProcessor;

    @BeforeEach
    void setUp() {
        cotEventProcessor = new CotEventProcessor();
    }

    @Test
    void shouldParseContactFromCotEvent() {
        // Given
        CotEvent cotEvent = createCotEventWithContact("TestCallsign");

        // When
        CotEventProcessor.ParsedCotData result = cotEventProcessor.parseCotEvent(cotEvent);

        // Then
        assertThat(result.callsign).isEqualTo("TestCallsign");
    }

    @Test
    void shouldParseGroupFromCotEvent() {
        // Given
        CotEvent cotEvent = createCotEventWithGroup("TeamLead", "Blue");

        // When
        CotEventProcessor.ParsedCotData result = cotEventProcessor.parseCotEvent(cotEvent);

        // Then
        assertThat(result.role).isEqualTo("TeamLead");
        assertThat(result.teamName).isEqualTo("Blue");
    }

    @Test
    void shouldParseStatusFromCotEvent() {
        // Given
        CotEvent cotEvent = createCotEventWithStatus(75);

        // When
        CotEventProcessor.ParsedCotData result = cotEventProcessor.parseCotEvent(cotEvent);

        // Then
        assertThat(result.battery).isEqualTo(75);
    }

    @Test
    void shouldParseTrackFromCotEvent() {
        // Given
        CotEvent cotEvent = createCotEventWithTrack(90, 10);

        // When
        CotEventProcessor.ParsedCotData result = cotEventProcessor.parseCotEvent(cotEvent);

        // Then
        assertThat(result.course).isEqualTo(90);
        assertThat(result.speed).isEqualTo(10);
    }

    @Test
    void shouldParseRemarksFromCotEvent() {
        // Given
        CotEvent cotEvent = createCotEventWithRemarks("Test message");

        // When
        CotEventProcessor.ParsedCotData result = cotEventProcessor.parseCotEvent(cotEvent);

        // Then
        assertThat(result.message).isEqualTo("Test message");
    }

    @Test
    void shouldHandleNullCotEvent() {
        // When
        CotEventProcessor.ParsedCotData result = cotEventProcessor.parseCotEvent(null);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.callsign).isNull();
    }

    @Test
    void shouldHandleCotEventWithoutDetail() {
        // Given
        CotEvent cotEvent = new CotEvent();

        // When
        CotEventProcessor.ParsedCotData result = cotEventProcessor.parseCotEvent(cotEvent);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.callsign).isNull();
    }

    @Test
    void shouldBuildPLIPacket() {
        // Given
        CotEventProcessor.ParsedCotData data = new CotEventProcessor.ParsedCotData();
        data.callsign = "TestCallsign";
        data.deviceCallsign = "DeviceCallsign";
        data.role = "TeamLead";
        data.teamName = "Blue";
        data.battery = 80;
        data.altitude = 100.0;
        data.latitude = 40.7128;
        data.longitude = -74.0060;
        data.course = 180;
        data.speed = 20;

        // When
        TAKPacket packet = cotEventProcessor.buildPLIPacket(data);

        // Then
        assertThat(packet.getContact()).isNotNull();
        assertThat(packet.getContact().getCallsign()).isEqualTo("TestCallsign");
        assertThat(packet.getContact().getDevice_callsign()).isEqualTo("DeviceCallsign");
        assertThat(packet.getStatus()).isNotNull();
        assertThat(packet.getStatus().getBattery()).isEqualTo(80);
        assertThat(packet.getPli()).isNotNull();
        assertThat(packet.getPli().getAltitude()).isEqualTo(100);
        assertThat(packet.getPli().getCourse()).isEqualTo(180);
        assertThat(packet.getPli().getSpeed()).isEqualTo(20);
    }

    @Test
    void shouldBuildChatPacket() {
        // Given
        CotEventProcessor.ParsedCotData data = new CotEventProcessor.ParsedCotData();
        data.callsign = "Sender";
        data.deviceCallsign = "DeviceId";
        data.message = "Hello, team!";
        data.to = "TeamChat";

        // When
        TAKPacket packet = cotEventProcessor.buildChatPacket(data);

        // Then
        assertThat(packet.getContact()).isNotNull();
        assertThat(packet.getContact().getCallsign()).isEqualTo("Sender");
        assertThat(packet.getChat()).isNotNull();
        assertThat(packet.getChat().getMessage()).isEqualTo("Hello, team!");
        assertThat(packet.getChat().getTo()).isEqualTo("TeamChat");
    }

    @Test
    void shouldHandleNullValuesInBuildPLIPacket() {
        // Given
        CotEventProcessor.ParsedCotData data = new CotEventProcessor.ParsedCotData();

        // When
        TAKPacket packet = cotEventProcessor.buildPLIPacket(data);

        // Then
        assertThat(packet.getContact()).isNotNull();
        assertThat(packet.getContact().getCallsign()).isNullOrEmpty();
        assertThat(packet.getContact().getDevice_callsign()).isNullOrEmpty();
    }

    @Test
    void shouldHandleNullValuesInBuildChatPacket() {
        // Given
        CotEventProcessor.ParsedCotData data = new CotEventProcessor.ParsedCotData();

        // When
        TAKPacket packet = cotEventProcessor.buildChatPacket(data);

        // Then
        assertThat(packet.getContact()).isNotNull();
        assertThat(packet.getContact().getCallsign()).isNullOrEmpty();
        assertThat(packet.getChat()).isNotNull();
        assertThat(packet.getChat().getMessage()).isNullOrEmpty();
        assertThat(packet.getChat().getTo()).isEqualTo("All Chat Rooms");
    }

    @Test
    void shouldCreateGeoChatEvent() {
        // Given
        String from = "Sender";
        String to = "Receiver";
        String message = "Test message";

        // When
        CotEvent event = cotEventProcessor.createGeoChatEvent(from, to, message, false);

        // Then
        assertThat(event).isNotNull();
        assertThat(event.getUID()).startsWith("GeoChat.Sender.Receiver.");
        assertThat(event.getType()).isEqualTo("b-t-f");
        assertThat(event.getHow()).isEqualTo("m-g");
        assertThat(event.getDetail()).isNotNull();
    }

    @Test
    void shouldCreateAllChatGeoChatEvent() {
        // Given
        String from = "Sender";
        String to = "ignored";
        String message = "Broadcast message";

        // When
        CotEvent event = cotEventProcessor.createGeoChatEvent(from, to, message, true);

        // Then
        assertThat(event).isNotNull();
        assertThat(event.getUID()).startsWith("GeoChat.Sender.All Chat Rooms.");
    }

    @Test
    void shouldParseLocationFromSelfMarker() {
        // Given
        try (MockedStatic<MapView> mapViewMockedStatic = Mockito.mockStatic(MapView.class)) {
            mapViewMockedStatic.when(MapView::getMapView).thenReturn(mapView);
            when(mapView.getSelfMarker()).thenReturn(selfMarker);
            when(selfMarker.getPoint()).thenReturn(geoPoint);
            when(selfMarker.getUID()).thenReturn("SELF-MARKER-UID");
            when(geoPoint.getAltitude()).thenReturn(150.0);
            when(geoPoint.getLatitude()).thenReturn(40.7128);
            when(geoPoint.getLongitude()).thenReturn(-74.0060);

            CotEvent cotEvent = createCotEventWithContact("Test");

            // When
            CotEventProcessor.ParsedCotData result = cotEventProcessor.parseCotEvent(cotEvent);

            // Then
            assertThat(result.altitude).isEqualTo(150.0);
            assertThat(result.latitude).isEqualTo(40.7128);
            assertThat(result.longitude).isEqualTo(-74.0060);
            assertThat(result.deviceCallsign).isEqualTo("SELF-MARKER-UID");
        }
    }

    // Helper methods to create test CoT events
    private CotEvent createCotEventWithContact(String callsign) {
        CotEvent cotEvent = new CotEvent();
        CotDetail detail = new CotDetail("detail");
        CotDetail contact = new CotDetail("contact");
        contact.setAttribute("callsign", callsign);
        detail.addChild(contact);
        cotEvent.setDetail(detail);
        return cotEvent;
    }

    private CotEvent createCotEventWithGroup(String role, String team) {
        CotEvent cotEvent = new CotEvent();
        CotDetail detail = new CotDetail("detail");
        CotDetail group = new CotDetail("__group");
        group.setAttribute("role", role);
        group.setAttribute("name", team);
        detail.addChild(group);
        cotEvent.setDetail(detail);
        return cotEvent;
    }

    private CotEvent createCotEventWithStatus(int battery) {
        CotEvent cotEvent = new CotEvent();
        CotDetail detail = new CotDetail("detail");
        CotDetail status = new CotDetail("status");
        status.setAttribute("battery", String.valueOf(battery));
        detail.addChild(status);
        cotEvent.setDetail(detail);
        return cotEvent;
    }

    private CotEvent createCotEventWithTrack(int course, int speed) {
        CotEvent cotEvent = new CotEvent();
        CotDetail detail = new CotDetail("detail");
        CotDetail track = new CotDetail("track");
        track.setAttribute("course", String.valueOf(course));
        track.setAttribute("speed", String.valueOf(speed));
        detail.addChild(track);
        cotEvent.setDetail(detail);
        return cotEvent;
    }

    private CotEvent createCotEventWithRemarks(String message) {
        CotEvent cotEvent = new CotEvent();
        CotDetail detail = new CotDetail("detail");
        CotDetail remarks = new CotDetail("remarks");
        remarks.setInnerText(message);
        detail.addChild(remarks);
        cotEvent.setDetail(detail);
        return cotEvent;
    }
}