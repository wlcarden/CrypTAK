package com.atakmap.android.meshtastic;

import static org.assertj.core.api.Assertions.assertThat;

import org.meshtastic.core.model.Position;

import org.junit.jupiter.api.Test;

class PositionToNMEAMapperTest {

    private final PositionToNMEAMapper mapper = new PositionToNMEAMapper();

    @Test
    void shouldCreateValidGAANmeaString() {
        int timestamp = 1742725847;
        Position position = new Position(53.012, 63.012, 100, timestamp, 5, 3, 7, 0);

        String sentence = mapper.createGAANmeaString(position);

        assertThat(sentence).isEqualTo("$GPGGA,103047.00,5300.720,N,06300.720,E,1,5,0.9,100,M,46.9,M,,*46");
    }

    @Test
    void shouldCreateValidRMCNmeaString() {
        int timestamp = 1742725847;
        Position position = new Position(53.012, 63.012, 100, timestamp, 5, 3, 7, 0);

        String sentence = mapper.createRMCNmeaString(position);

        assertThat(sentence).isEqualTo("$GPRMC,103047.000,A,5300.720,N,06300.720,E,3,7,230325,,*00");
    }
}