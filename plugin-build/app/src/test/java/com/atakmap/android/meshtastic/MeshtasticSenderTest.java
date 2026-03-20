package com.atakmap.android.meshtastic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.graphics.drawable.Drawable;

import com.atakmap.android.importexport.send.MissionPackageSender;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.missionpackage.MissionPackageMapComponent;
import com.atakmap.android.missionpackage.file.MissionPackageFileIO;
import com.atakmap.android.missionpackage.file.MissionPackageManifest;
import com.atakmap.android.missionpackage.file.task.MissionPackageBaseTask;
import com.atakmap.android.meshtastic.plugin.R;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MeshtasticSenderTest {

    @Mock
    private MapView mapView;

    @Mock
    private Context pluginContext;

    @Mock
    private MissionPackageManifest missionPackageManifest;

    @Mock
    private MissionPackageBaseTask.Callback callback;

    @Mock
    private MissionPackageSender.Callback senderCallback;

    @Mock
    private Drawable drawable;

    private MeshtasticSender meshtasticSender;

    @BeforeEach
    void setUp() {
        meshtasticSender = new MeshtasticSender(mapView, pluginContext);
    }

    @Test
    void shouldSendMissionPackage() {
        // Given
        MissionPackageMapComponent mockComponent = mock(MissionPackageMapComponent.class);
        MissionPackageFileIO mockFileIO = mock(MissionPackageFileIO.class);
        
        try (MockedStatic<MissionPackageMapComponent> mockedStatic = 
                Mockito.mockStatic(MissionPackageMapComponent.class)) {
            
            mockedStatic.when(MissionPackageMapComponent::getInstance)
                    .thenReturn(mockComponent);
            when(mockComponent.getFileIO()).thenReturn(mockFileIO);
            
            // When
            boolean result = meshtasticSender.sendMissionPackage(
                    missionPackageManifest, callback, senderCallback);
            
            // Then
            assertThat(result).isTrue();
            verify(mockFileIO).save(eq(missionPackageManifest), eq(true), 
                    any(MeshtasticCallback.class));
        }
    }

    @Test
    void shouldReturnCorrectName() {
        // When
        String name = meshtasticSender.getName();
        
        // Then
        assertThat(name).isEqualTo("Meshtastic");
    }

    @Test
    void shouldReturnIconFromPluginContext() {
        // Given
        when(pluginContext.getDrawable(R.drawable.ic_launcher)).thenReturn(drawable);
        
        // When
        Drawable icon = meshtasticSender.getIcon();
        
        // Then
        assertThat(icon).isEqualTo(drawable);
        verify(pluginContext).getDrawable(R.drawable.ic_launcher);
    }

    @Test
    void shouldHandleNullManifest() {
        // Given
        MissionPackageMapComponent mockComponent = mock(MissionPackageMapComponent.class);
        MissionPackageFileIO mockFileIO = mock(MissionPackageFileIO.class);
        
        try (MockedStatic<MissionPackageMapComponent> mockedStatic = 
                Mockito.mockStatic(MissionPackageMapComponent.class)) {
            
            mockedStatic.when(MissionPackageMapComponent::getInstance)
                    .thenReturn(mockComponent);
            when(mockComponent.getFileIO()).thenReturn(mockFileIO);
            
            // When
            boolean result = meshtasticSender.sendMissionPackage(
                    null, callback, senderCallback);
            
            // Then
            assertThat(result).isTrue();
            verify(mockFileIO).save(eq(null), eq(true), 
                    any(MeshtasticCallback.class));
        }
    }
}