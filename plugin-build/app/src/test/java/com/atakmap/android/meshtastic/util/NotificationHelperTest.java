package com.atakmap.android.meshtastic.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationCompat;

import com.atakmap.android.meshtastic.plugin.R;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

@ExtendWith(MockitoExtension.class)
class NotificationHelperTest {

    @Mock
    private Context context;

    @Mock
    private Context applicationContext;

    @Mock
    private NotificationManager notificationManager;

    @Mock
    private Notification mockNotification;

    private NotificationHelper notificationHelper;

    // Held open for the full test lifecycle so that every `new NotificationCompat.Builder(...)`
    // call — including ones inside showNotification() — is intercepted. MockedConstruction
    // cannot be nested for the same class (Mockito throws), so we keep a single scope per test.
    private MockedConstruction<NotificationCompat.Builder> mockBuilderCtor;
    private MockedConstruction<NotificationChannel> mockChannelCtor;

    @BeforeEach
    void setUp() throws Exception {
        when(context.getApplicationContext()).thenReturn(applicationContext);
        when(applicationContext.getSystemService(Context.NOTIFICATION_SERVICE))
                .thenReturn(notificationManager);

        // Clear singleton for each test
        java.lang.reflect.Field instance = NotificationHelper.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);

        // Open MockedConstruction scopes that live for the entire test method.
        //
        // NotificationCompat.Builder.build() NPEs through the Android stub chain — stubs do
        // nothing in constructors and return null from getters. We intercept every `new Builder`
        // call and stub the fluent API + build() so tests can call showNotification() etc.
        //
        // NotificationChannel stubs preserve constructor args so getId()/getName()/
        // getImportance() assertions work.
        mockBuilderCtor = Mockito.mockConstruction(NotificationCompat.Builder.class, (mock, ctx) -> {
            when(mock.setContentTitle(any())).thenReturn(mock);
            when(mock.setContentText(any())).thenReturn(mock);
            when(mock.setSmallIcon(anyInt())).thenReturn(mock);
            when(mock.setAutoCancel(anyBoolean())).thenReturn(mock);
            when(mock.setOngoing(anyBoolean())).thenReturn(mock);
            when(mock.setContentIntent(any())).thenReturn(mock);
            when(mock.setProgress(anyInt(), anyInt(), anyBoolean())).thenReturn(mock);
            when(mock.build()).thenReturn(mockNotification);
        });

        mockChannelCtor = Mockito.mockConstruction(NotificationChannel.class, (mock, ctx) -> {
            List<?> args = ctx.arguments();
            String id = (String) args.get(0);
            CharSequence name = (CharSequence) args.get(1);
            int importance = (Integer) args.get(2);
            when(mock.getId()).thenReturn(id);
            when(mock.getName()).thenReturn(name);
            when(mock.getImportance()).thenReturn(importance);
        });

        notificationHelper = NotificationHelper.getInstance(context);
    }

    @AfterEach
    void tearDown() {
        mockBuilderCtor.close();
        mockChannelCtor.close();
    }

    @Test
    void shouldUseSingletonPattern() {
        // When
        NotificationHelper instance1 = NotificationHelper.getInstance(context);
        NotificationHelper instance2 = NotificationHelper.getInstance(context);

        // Then
        assertThat(instance1).isSameAs(instance2);
    }

    @Test
    void shouldCreateNotificationChannel() {
        // Given
        ArgumentCaptor<NotificationChannel> channelCaptor =
                ArgumentCaptor.forClass(NotificationChannel.class);

        // When - Already created in setUp via getInstance

        // Then
        verify(notificationManager).createNotificationChannel(channelCaptor.capture());
        NotificationChannel channel = channelCaptor.getValue();
        assertThat(channel.getId()).isEqualTo(Constants.NOTIFICATION_CHANNEL_ID);
        assertThat(channel.getName().toString()).isEqualTo(Constants.NOTIFICATION_CHANNEL_NAME);
        assertThat(channel.getImportance()).isEqualTo(NotificationManager.IMPORTANCE_DEFAULT);
    }

    @Test
    void shouldShowProgressNotification() {
        // Given
        int progress = 50;
        ArgumentCaptor<Notification> notificationCaptor =
                ArgumentCaptor.forClass(Notification.class);

        // When
        notificationHelper.showProgressNotification(progress);

        // Then
        verify(notificationManager).notify(eq(Constants.NOTIFICATION_ID),
                notificationCaptor.capture());
        assertThat(notificationCaptor.getValue()).isNotNull();
    }

    @Test
    void shouldShowCompletionNotification() {
        // Given
        ArgumentCaptor<Notification> notificationCaptor =
                ArgumentCaptor.forClass(Notification.class);

        // When
        notificationHelper.showCompletionNotification();

        // Then
        verify(notificationManager).notify(eq(Constants.NOTIFICATION_ID),
                notificationCaptor.capture());
        assertThat(notificationCaptor.getValue()).isNotNull();
    }

    @Test
    void shouldShowCustomNotification() {
        // Given
        String title = "Test Title";
        String message = "Test Message";
        ArgumentCaptor<Notification> notificationCaptor =
                ArgumentCaptor.forClass(Notification.class);

        // When
        notificationHelper.showNotification(title, message);

        // Then
        verify(notificationManager).notify(eq(Constants.NOTIFICATION_ID),
                notificationCaptor.capture());
        assertThat(notificationCaptor.getValue()).isNotNull();
    }

    @Test
    void shouldCancelNotification() {
        // When
        notificationHelper.cancelNotification();

        // Then
        verify(notificationManager).cancel(Constants.NOTIFICATION_ID);
    }

    @Test
    void shouldShowMultipleProgressUpdates() {
        // When
        notificationHelper.showProgressNotification(25);
        notificationHelper.showProgressNotification(50);
        notificationHelper.showProgressNotification(75);
        notificationHelper.showProgressNotification(100);

        // Then
        verify(notificationManager, times(4)).notify(eq(Constants.NOTIFICATION_ID),
                any(Notification.class));
    }

    @Test
    void shouldTransitionFromProgressToCompletion() {
        // When
        notificationHelper.showProgressNotification(50);
        notificationHelper.showCompletionNotification();

        // Then
        verify(notificationManager, times(2)).notify(eq(Constants.NOTIFICATION_ID),
                any(Notification.class));
    }

    @Test
    void shouldHandleMultipleNotificationTypes() {
        // When
        notificationHelper.showProgressNotification(30);
        notificationHelper.showNotification("Alert", "New message");
        notificationHelper.showCompletionNotification();
        notificationHelper.cancelNotification();

        // Then
        verify(notificationManager, times(3)).notify(eq(Constants.NOTIFICATION_ID),
                any(Notification.class));
        verify(notificationManager).cancel(Constants.NOTIFICATION_ID);
    }

    @Test
    void shouldInitializeWithProperPendingIntent() {
        // Reset the singleton so re-initialization triggers PendingIntent.getActivity()
        // inside the MockedStatic scope below. The field-level MockedConstruction
        // (mockBuilderCtor / mockChannelCtor) is already open, so Builder and Channel
        // construction during reinit is automatically intercepted — no inner
        // MockedConstruction blocks needed here.
        try {
            java.lang.reflect.Field instance = NotificationHelper.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);

        try (MockedStatic<PendingIntent> pendingIntentMockedStatic =
                     Mockito.mockStatic(PendingIntent.class)) {

            PendingIntent mockPendingIntent = mock(PendingIntent.class);
            pendingIntentMockedStatic.when(() ->
                PendingIntent.getActivity(any(Context.class), anyInt(),
                        intentCaptor.capture(), anyInt()))
                    .thenReturn(mockPendingIntent);

            // Re-initialize: triggers initializeProgressNotification() and
            // initializeReceiveProgressNotification(), both call PendingIntent.getActivity()
            NotificationHelper.getInstance(context);

            // Verify PendingIntent.getActivity() was called with an intent
            assertThat(intentCaptor.getAllValues()).isNotEmpty();
        }
    }
}
