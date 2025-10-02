package com.atakmap.android.meshtastic.util;

import com.atakmap.coremap.log.Log;
import org.meshtastic.core.model.MessageStatus;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class AckManager {
    private static final String TAG = "AckManager";
    private static final long DEFAULT_TIMEOUT_MS = 30000; // 30 seconds default timeout
    
    private final ConcurrentHashMap<Integer, AckRequest> pendingAcks = new ConcurrentHashMap<>();
    
    public static class AckResult {
        public final boolean success;
        public final MessageStatus status;
        public final boolean timeout;
        
        private AckResult(boolean success, MessageStatus status, boolean timeout) {
            this.success = success;
            this.status = status;
            this.timeout = timeout;
        }
        
        public static AckResult success(MessageStatus status) {
            return new AckResult(true, status, false);
        }
        
        public static AckResult failure(MessageStatus status) {
            return new AckResult(false, status, false);
        }
        
        public static AckResult timeout() {
            return new AckResult(false, null, true);
        }
    }
    
    private static class AckRequest {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<MessageStatus> status = new AtomicReference<>();
        final long timestamp = System.currentTimeMillis();
    }
    
    private static AckManager instance;
    
    public static synchronized AckManager getInstance() {
        if (instance == null) {
            instance = new AckManager();
        }
        return instance;
    }
    
    private AckManager() {
        // Private constructor for singleton
    }
    
    /**
     * Register a message ID for ACK tracking
     * @param messageId The message ID to track
     */
    public void registerForAck(int messageId) {
        Log.d(TAG, "Registering for ACK: " + messageId);
        pendingAcks.put(messageId, new AckRequest());
    }
    
    /**
     * Wait for an ACK with default timeout
     * @param messageId The message ID to wait for
     * @return AckResult containing the result
     */
    public AckResult waitForAck(int messageId) {
        return waitForAck(messageId, DEFAULT_TIMEOUT_MS);
    }
    
    /**
     * Wait for an ACK with custom timeout
     * @param messageId The message ID to wait for
     * @param timeoutMs Timeout in milliseconds
     * @return AckResult containing the result
     */
    public AckResult waitForAck(int messageId, long timeoutMs) {
        AckRequest request = pendingAcks.get(messageId);
        if (request == null) {
            Log.w(TAG, "No pending ACK request for ID: " + messageId);
            return AckResult.failure(null);
        }
        
        try {
            boolean received = request.latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            pendingAcks.remove(messageId);
            
            if (!received) {
                Log.w(TAG, "ACK timeout for ID: " + messageId);
                return AckResult.timeout();
            }
            
            MessageStatus status = request.status.get();
            Log.d(TAG, "ACK received for ID: " + messageId + " Status: " + status);
            
            if (status == MessageStatus.DELIVERED) {
                return AckResult.success(status);
            } else {
                return AckResult.failure(status);
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while waiting for ACK: " + messageId, e);
            pendingAcks.remove(messageId);
            Thread.currentThread().interrupt();
            return AckResult.failure(null);
        }
    }
    
    /**
     * Process received ACK
     * @param messageId The message ID
     * @param status The message status
     */
    public void processAck(int messageId, MessageStatus status) {
        AckRequest request = pendingAcks.get(messageId);
        if (request != null) {
            Log.d(TAG, "Processing ACK for ID: " + messageId + " Status: " + status);
            request.status.set(status);
            request.latch.countDown();
        } else {
            Log.v(TAG, "Received ACK for untracked ID: " + messageId);
        }
    }
    
    /**
     * Cancel waiting for an ACK
     * @param messageId The message ID to cancel
     */
    public void cancelAck(int messageId) {
        AckRequest request = pendingAcks.remove(messageId);
        if (request != null) {
            Log.d(TAG, "Cancelled ACK for ID: " + messageId);
            request.latch.countDown();
        }
    }
    
    /**
     * Clean up old pending ACKs (older than 5 minutes)
     */
    public void cleanup() {
        long cutoff = System.currentTimeMillis() - (5 * 60 * 1000);
        pendingAcks.entrySet().removeIf(entry -> {
            boolean shouldRemove = entry.getValue().timestamp < cutoff;
            if (shouldRemove) {
                Log.v(TAG, "Cleaning up old ACK request: " + entry.getKey());
                entry.getValue().latch.countDown();
            }
            return shouldRemove;
        });
    }
    
    /**
     * Clear all pending ACKs
     */
    public void clearAll() {
        Log.d(TAG, "Clearing all pending ACKs");
        pendingAcks.forEach((id, request) -> request.latch.countDown());
        pendingAcks.clear();
    }
}