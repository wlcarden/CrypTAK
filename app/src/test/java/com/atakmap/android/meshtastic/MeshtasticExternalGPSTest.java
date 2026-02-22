package com.atakmap.android.meshtastic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import org.meshtastic.core.model.Position;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

@ExtendWith(MockitoExtension.class)
class MeshtasticExternalGPSTest {

    @Mock
    private PositionToNMEAMapper positionToNMEAMapper;

    @InjectMocks
    private MeshtasticExternalGPS meshtasticExternalGPS;

    @BeforeEach
    public void setUp() {
        meshtasticExternalGPS.start(14349);
    }

    @AfterEach
    public void tearDown() {
        meshtasticExternalGPS.stop();
    }

    @Test
    void shouldUpdatePosition() {
        Position position = new Position(53.012, 63.012, 100, (int) System.currentTimeMillis(), 5, 3, 7, 0);
        when(positionToNMEAMapper.createGAANmeaString(position)).thenReturn("GAANmeaString");
        when(positionToNMEAMapper.createRMCNmeaString(position)).thenReturn("RMCNmeaString");
        meshtasticExternalGPS.updatePosition(position);

        String gpsNmeaString = receiveNmeaViaUDP(14349);

        assertThat(gpsNmeaString).isEqualTo("GAANmeaString\r\nRMCNmeaString");
    }

    @Test
    void shouldThrowExceptionIfPortIsIncorrect() {
        assertThatThrownBy(() -> new MeshtasticExternalGPS(positionToNMEAMapper).start(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldStopMeshtasticExternalGPS() {
        Position position = new Position(53.012, 63.012, 100, (int) System.currentTimeMillis(), 5, 3, 7, 0);
        when(positionToNMEAMapper.createGAANmeaString(position)).thenReturn("GAANmeaString");
        when(positionToNMEAMapper.createRMCNmeaString(position)).thenReturn("RMCNmeaString");
        meshtasticExternalGPS.updatePosition(position);
        receiveNmeaViaUDP(14349);

        meshtasticExternalGPS.stop();

        assertNoUDPPacketsSent(14349);
    }

    private String receiveNmeaViaUDP(int port) {
        try (DatagramSocket socket = new DatagramSocket(port)) {
            socket.setSoTimeout(10000);
            byte[] buffer = new byte[65536];

            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);

            return new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void assertNoUDPPacketsSent(int port) {
        try (DatagramSocket socket = new DatagramSocket(port)) {
            socket.setSoTimeout(10000);
            byte[] buffer = new byte[65536];

            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);
        } catch (SocketTimeoutException ignored) {
            // Expected behavior
        } catch (Exception e) {
            throw new AssertionError("Expected to exit due to timeout but got different exception", e);
        }
    }

}