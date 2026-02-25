package com.atakmap.android.meshtastic;

import android.util.Log;

import org.meshtastic.core.model.Position;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class MeshtasticExternalGPS {

    private static final String TAG = "MeshtasticExternalGPS";
    private final AtomicReference<Position> currentPosition = new AtomicReference<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicInteger port = new AtomicInteger(-1);
    private final ScheduledExecutorService updaterThread;
    private final PositionToNMEAMapper positionToNMEAMapper;

    public MeshtasticExternalGPS(PositionToNMEAMapper positionToNMEAMapper) {
        this.updaterThread = Executors.newSingleThreadScheduledExecutor();
        this.positionToNMEAMapper = positionToNMEAMapper;
    }

    public void updatePosition(Position position) {
        if (!started.get()) {
            return;
        }
        currentPosition.set(position);
        if (position != null) {
            Log.d(TAG, "Current position updated: Lat: " + position.getLatitude() + ", Lon: " + position.getLongitude() + ", Alt: " + position.getAltitude() + ", Time: " + position.getTime() + ", Speed: " + position.getGroundSpeed() + ", Head: " + position.getGroundTrack() + ", Sat: " + position.getSatellitesInView());
        } else {
            Log.d(TAG, "Current position updated to null");
        }
    }

    public void start(int port) {
        if (started.get()) {
            return;
        }
        started.set(true);
        this.port.updateAndGet(i -> {
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("Can't start Meshtastic External GPS without port");
            } else {
                return port;
            }
        });
        updaterThread.scheduleWithFixedDelay(this::refreshPosition, 1, 5, TimeUnit.SECONDS);
        Log.i(TAG, "Meshtastic External GPS started");
    }

    public void stop() {
        if (!started.get()) {
            return;
        }
        started.set(false);
        try {
            updaterThread.shutdown();
            if (!updaterThread.awaitTermination(10, TimeUnit.SECONDS)) {
                throw new RuntimeException("Could not terminate Meshtastic External GPS updater thread");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        currentPosition.set(null);
        port.set(-1);
        Log.i(TAG, "Meshtastic External GPS stopped");
    }

    private void refreshPosition() {
        if (!started.get()) {
            return;
        }
        Position position = this.currentPosition.get();
        int port = this.port.get();
        if (port != -1 && position != null) {
            String gaaString = positionToNMEAMapper.createGAANmeaString(position);
            String rmcString = positionToNMEAMapper.createRMCNmeaString(position);
            String message = gaaString.concat("\r\n").concat(rmcString);

            try {
                DatagramSocket socket = new DatagramSocket();
                byte[] data = message.getBytes(StandardCharsets.UTF_8);
                InetAddress address = InetAddress.getLocalHost();

                DatagramPacket packet = new DatagramPacket(data, data.length, address, port);
                socket.send(packet);
                socket.close();
            } catch (Exception e) {
                Log.e(TAG, "Could not refresh position using Meshtastic GPS", e);
            }
        }
    }
}
