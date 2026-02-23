package com.atakmap.android.meshtastic.encryption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;

@ExtendWith(MockitoExtension.class)
class KeyImportReceiverTest {

    // Valid 32-byte AES-256 key, base64-encoded (from KeyImportReceiver Javadoc)
    private static final String VALID_BASE64_KEY =
            "K7gNU3sdo+OL0wNhqoVWhr3g6s1xYv72ol/pe/Unols=";

    private KeyImportReceiver receiver;
    private Context context;

    @BeforeEach
    void setUp() throws Exception {
        receiver = new KeyImportReceiver();
        context = mock(Context.class);

        // Reset singleton so each test starts with a clean, unconfigured manager
        Field f = AppLayerEncryptionManager.class.getDeclaredField("instance");
        f.setAccessible(true);
        f.set(null, null);
    }

    @AfterEach
    void tearDown() throws Exception {
        // Defensive reset — prevents any state leaking to subsequent test classes
        Field f = AppLayerEncryptionManager.class.getDeclaredField("instance");
        f.setAccessible(true);
        f.set(null, null);
    }

    @Test
    void import_validBase64Intent_loadsKey() {
        // importKeyAndSave() persists to SharedPreferences; mock the preference store
        SharedPreferences mockPrefs = mock(SharedPreferences.class);
        SharedPreferences.Editor mockEditor = mock(SharedPreferences.Editor.class);
        when(mockPrefs.edit()).thenReturn(mockEditor);
        when(mockEditor.putString(anyString(), anyString())).thenReturn(mockEditor);
        when(mockEditor.putBoolean(anyString(), anyBoolean())).thenReturn(mockEditor);

        Intent intent = mock(Intent.class);
        when(intent.getAction()).thenReturn(KeyImportReceiver.ACTION_IMPORT_KEY);
        when(intent.getStringExtra(KeyImportReceiver.EXTRA_KEY)).thenReturn(VALID_BASE64_KEY);

        try (MockedStatic<PreferenceManager> prefStatic =
                     Mockito.mockStatic(PreferenceManager.class)) {
            prefStatic.when(() -> PreferenceManager.getDefaultSharedPreferences(any()))
                    .thenReturn(mockPrefs);

            receiver.onReceive(context, intent);
        }

        assertThat(AppLayerEncryptionManager.getInstance().isEnabled()).isTrue();
    }

    @Test
    void import_invalidBase64Intent_doesNotCrash() {
        // importKeyFromBase64() returns false before reaching PreferenceManager — no stubs needed
        Intent intent = mock(Intent.class);
        when(intent.getAction()).thenReturn(KeyImportReceiver.ACTION_IMPORT_KEY);
        when(intent.getStringExtra(KeyImportReceiver.EXTRA_KEY))
                .thenReturn("not-valid-base64!!!");

        // No exception expected; invalid base64 caught and handled gracefully
        receiver.onReceive(context, intent);

        assertThat(AppLayerEncryptionManager.getInstance().isEnabled()).isFalse();
    }

}
