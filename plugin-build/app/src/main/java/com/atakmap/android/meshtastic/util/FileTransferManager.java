package com.atakmap.android.meshtastic.util;

import com.atakmap.coremap.log.Log;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class FileTransferManager {
    private static final String TAG = "FileTransferManager";
    private static final long TRANSFER_TIMEOUT_MS = 120000; // 2 minutes for file transfer
    private static final long REBOOT_WAIT_MS = 5000; // 5 seconds for reboot
    
    private final AtomicBoolean transferInProgress = new AtomicBoolean(false);
    private CountDownLatch transferCompleteLatch;
    private CompletableFuture<Boolean> currentTransfer;
    
    private static FileTransferManager instance;
    
    public static synchronized FileTransferManager getInstance() {
        if (instance == null) {
            instance = new FileTransferManager();
        }
        return instance;
    }
    
    private FileTransferManager() {
        // Private constructor for singleton
    }
    
    /**
     * Start a file transfer operation
     * @return CompletableFuture that completes when transfer is done
     */
    public CompletableFuture<Boolean> startTransfer() {
        if (transferInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "Starting file transfer");
            transferCompleteLatch = new CountDownLatch(1);
            currentTransfer = CompletableFuture.supplyAsync(() -> {
                try {
                    boolean success = transferCompleteLatch.await(TRANSFER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    if (!success) {
                        Log.w(TAG, "File transfer timed out");
                    }
                    return success;
                } catch (InterruptedException e) {
                    Log.e(TAG, "File transfer interrupted", e);
                    Thread.currentThread().interrupt();
                    return false;
                } finally {
                    transferInProgress.set(false);
                }
            });
            return currentTransfer;
        } else {
            Log.w(TAG, "Transfer already in progress");
            return CompletableFuture.completedFuture(false);
        }
    }
    
    /**
     * Signal that the file transfer is complete
     */
    public void completeTransfer() {
        Log.d(TAG, "File transfer completed");
        if (transferCompleteLatch != null) {
            transferCompleteLatch.countDown();
        }
    }
    
    /**
     * Cancel the current file transfer
     */
    public void cancelTransfer() {
        Log.d(TAG, "Cancelling file transfer");
        if (transferCompleteLatch != null) {
            transferCompleteLatch.countDown();
        }
        if (currentTransfer != null && !currentTransfer.isDone()) {
            currentTransfer.cancel(true);
        }
        transferInProgress.set(false);
    }
    
    /**
     * Check if a transfer is in progress
     * @return true if transfer is active
     */
    public boolean isTransferInProgress() {
        return transferInProgress.get();
    }
    
    /**
     * Wait for modem reboot with proper delay
     * @return CompletableFuture that completes after reboot delay
     */
    public static CompletableFuture<Void> waitForReboot() {
        return CompletableFuture.runAsync(() -> {
            try {
                Log.d(TAG, "Waiting for modem reboot");
                Thread.sleep(REBOOT_WAIT_MS);
            } catch (InterruptedException e) {
                Log.e(TAG, "Reboot wait interrupted", e);
                Thread.currentThread().interrupt();
            }
        });
    }
}