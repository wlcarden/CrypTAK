package com.atakmap.android.meshtastic.encryption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class KeyQrDialogTest {

    private static final String VALID_KEY = "K7gNU3sdo+OL0wNhqoVWhr3g6s1xYv72ol/pe/Unols=";

    @Test
    void buildQrPixels_validKey_returnsNonEmptyArray() throws Exception {
        int[] pixels = KeyQrDialog.buildQrPixels(VALID_KEY, 200);
        assertThat(pixels).isNotNull().isNotEmpty();
    }

    @Test
    void buildQrPixels_pixelCountMatchesRequestedSize() throws Exception {
        int size = 300;
        int[] pixels = KeyQrDialog.buildQrPixels(VALID_KEY, size);
        assertThat(pixels.length).isEqualTo(size * size);
    }

    @Test
    void buildQrPixels_nullContent_throwsIllegalArgument() {
        assertThatThrownBy(() -> KeyQrDialog.buildQrPixels(null, 200))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildQrPixels_emptyContent_throwsIllegalArgument() {
        assertThatThrownBy(() -> KeyQrDialog.buildQrPixels("", 200))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildQrPixels_passphrase_encodesSameAsDerivedKey() throws Exception {
        // Verifies the dialog encodes the raw PSK string (not a derived key),
        // so two phones encoding the same passphrase get identical QR codes.
        int[] pixels1 = KeyQrDialog.buildQrPixels("my-team-passphrase", 200);
        int[] pixels2 = KeyQrDialog.buildQrPixels("my-team-passphrase", 200);
        assertThat(pixels1).isEqualTo(pixels2);
    }
}
