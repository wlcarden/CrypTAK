package com.atakmap.android.meshtastic.util.fountain;

import com.atakmap.android.meshtastic.MeshtasticMapComponent;
import com.atakmap.android.meshtastic.service.MeshServiceManager;
import com.atakmap.coremap.log.Log;
import okio.ByteString;
import org.meshtastic.core.model.DataPacket;
import org.meshtastic.core.model.MessageStatus;
import org.meshtastic.proto.Config;
import org.meshtastic.proto.LocalConfig;
import org.meshtastic.proto.PortNum;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fountain code-based chunk manager for reliable large message transfer
 * over lossy mesh networks.
 *
 * Features:
 * - Fountain codes allow any K of N blocks to reconstruct data
 * - Minimal feedback (single ACK after transfer)
 * - Automatic loss recovery without explicit retransmit requests
 * - Support for multiple concurrent transfers
 * - Timeout-based cleanup of stale transfers
 */
public class FountainChunkManager {
    private static final String TAG = "FountainChunkManager";

    // Configuration
    private static final int BLOCK_SIZE = FountainPacket.MAX_PAYLOAD_SIZE;
    private static final int MAX_RETRIES = 3;
    private static final long BASE_ACK_TIMEOUT_MS = 10000;  // 10 second base timeout for processing
    private static final long MAX_ACK_TIMEOUT_MS = 300000;  // 5 minute max timeout for very slow presets
    private static final long RECEIVE_TIMEOUT_MS = 300000;  // 5 minute receive timeout
    private static final long CLEANUP_INTERVAL_MS = 10000;  // 10 second cleanup interval
    private static final int MAX_DATA_SIZE = 64 * 1024;  // 64KB max

    // Per-packet TX times (ms) for different modem presets
    // These account for airtime + processing + mesh propagation overhead
    // Values are approximate and include safety margin
    private static final long TX_TIME_SHORT_TURBO = 100;    // ~50ms airtime + overhead
    private static final long TX_TIME_SHORT_FAST = 200;     // ~100ms airtime + overhead
    private static final long TX_TIME_SHORT_SLOW = 800;     // ~400ms airtime + overhead
    private static final long TX_TIME_MEDIUM_FAST = 800;    // ~400ms airtime + overhead
    private static final long TX_TIME_MEDIUM_SLOW = 1600;   // ~800ms airtime + overhead
    private static final long TX_TIME_LONG_MODERATE = 2000; // ~1s airtime + overhead
    private static final long TX_TIME_LONG_FAST = 3200;     // ~1.6s airtime + overhead
    private static final long TX_TIME_LONG_SLOW = 6400;     // ~3.2s airtime + overhead
    private static final long TX_TIME_DEFAULT = 1000;       // Default fallback

    /**
     * Get adaptive overhead based on K (number of source blocks).
     * Smaller K needs more overhead because Robust Soliton distribution
     * is less effective and there's less natural redundancy.
     */
    private static double getAdaptiveOverhead(int K) {
        if (K <= 10) {
            return 0.50;  // 50% overhead for very small transfers
        } else if (K <= 50) {
            return 0.25;  // 25% overhead for small transfers
        } else {
            return 0.15;  // 15% overhead for larger transfers
        }
    }

    /**
     * Get the current modem preset from Meshtastic config.
     * @return ModemPreset enum value, or LONG_FAST (0) as default
     */
    private static int getCurrentModemPreset() {
        try {
            byte[] config = MeshtasticMapComponent.getConfig();
            if (config == null || config.length == 0) {
                Log.w(TAG, "Cannot get radio config, using default timing");
                return Config.LoRaConfig.ModemPreset.LONG_FAST.getValue();
            }

            LocalConfig c = LocalConfig.ADAPTER.decode(config);
            Config.LoRaConfig lc = c.getLora();
            int preset = lc.getModem_preset().getValue();
            Log.d(TAG, "Current modem preset: " + lc.getModem_preset().name() + " (" + preset + ")");
            return preset;
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse config for modem preset", e);
            return Config.LoRaConfig.ModemPreset.LONG_FAST.getValue();
        }
    }

    /**
     * Get per-packet TX time based on modem preset.
     * These times account for airtime, processing, and mesh propagation.
     *
     * Meshtastic ModemPreset enum values:
     * LONG_FAST = 0, LONG_SLOW = 1, VERY_LONG_SLOW = 2 (deprecated),
     * MEDIUM_SLOW = 3, MEDIUM_FAST = 4, SHORT_SLOW = 5, SHORT_FAST = 6,
     * LONG_MODERATE = 7, SHORT_TURBO = 8
     */
    private static long getPerPacketTxTime(int modemPreset) {
        switch (modemPreset) {
            case 0: // LONG_FAST
                return TX_TIME_LONG_FAST;
            case 1: // LONG_SLOW (deprecated)
                return TX_TIME_LONG_SLOW;
            case 2: // VERY_LONG_SLOW (deprecated)
                return TX_TIME_LONG_SLOW;  // Use LONG_SLOW timing
            case 3: // MEDIUM_SLOW
                return TX_TIME_MEDIUM_SLOW;
            case 4: // MEDIUM_FAST
                return TX_TIME_MEDIUM_FAST;
            case 5: // SHORT_SLOW
                return TX_TIME_SHORT_SLOW;
            case 6: // SHORT_FAST
                return TX_TIME_SHORT_FAST;
            case 7: // LONG_MODERATE
                return TX_TIME_LONG_MODERATE;
            case 8: // SHORT_TURBO
                return TX_TIME_SHORT_TURBO;
            default:
                Log.w(TAG, "Unknown modem preset " + modemPreset + ", using default timing");
                return TX_TIME_DEFAULT;
        }
    }

    /**
     * Get inter-packet delay based on modem preset.
     * Faster presets can use shorter delays between packets.
     */
    private static long getInterPacketDelay(int modemPreset) {
        // Use 50% of TX time as inter-packet delay to respect duty cycle
        // and allow mesh to process packets
        return Math.max(50, getPerPacketTxTime(modemPreset) / 2);
    }

    /**
     * Get adaptive ACK timeout based on number of blocks and modem preset.
     * Accounts for TX time, mesh propagation, RX processing, and ACK return.
     */
    private static long getAdaptiveAckTimeout(int blocksToSend, int modemPreset) {
        long perPacketTime = getPerPacketTxTime(modemPreset);

        // Total time = base processing + (blocks * TX time) + ACK return time
        // Multiply by 2 to account for multi-hop propagation delays
        long txTime = blocksToSend * perPacketTime * 2;
        long ackReturnTime = perPacketTime * 4;  // ACK travels back through mesh

        long timeout = BASE_ACK_TIMEOUT_MS + txTime + ackReturnTime;

        Log.d(TAG, "ACK timeout for " + blocksToSend + " blocks @ preset " + modemPreset +
                  ": " + (timeout / 1000) + "s (per-pkt=" + perPacketTime + "ms)");

        return Math.min(timeout, MAX_ACK_TIMEOUT_MS);
    }

    /**
     * Legacy method for backward compatibility.
     * @deprecated Use getAdaptiveAckTimeout(blocksToSend, modemPreset) instead
     */
    @Deprecated
    private static long getAdaptiveAckTimeout(int blocksToSend) {
        return getAdaptiveAckTimeout(blocksToSend, getCurrentModemPreset());
    }

    private final FountainCodec codec;
    private final MeshServiceManager meshServiceManager;
    private final String localNodeId;

    // Receiver state - tracks incoming transfers
    private final ConcurrentHashMap<Integer, ReceiveState> receiveStates;

    // Sender state - tracks outgoing transfers
    private final ConcurrentHashMap<Integer, SendState> sendStates;

    // Callback for completed transfers
    private TransferCallback callback;

    // Background cleanup
    private final ScheduledExecutorService scheduler;

    /**
     * Callback interface for transfer events.
     */
    public interface TransferCallback {
        /**
         * Called when a transfer completes successfully.
         * @param transferId The transfer ID
         * @param data The received data (null for send completions)
         * @param senderNodeId The sender's node ID (null for send completions)
         * @param transferType The type of transfer (Constants.TRANSFER_TYPE_COT or TRANSFER_TYPE_FILE)
         */
        void onTransferComplete(int transferId, byte[] data, String senderNodeId, byte transferType);
        void onTransferFailed(int transferId, String reason);
        void onProgress(int transferId, int received, int total, boolean isSending);
    }

    public FountainChunkManager(MeshServiceManager meshServiceManager, String localNodeId) {
        this.meshServiceManager = meshServiceManager;
        this.localNodeId = localNodeId;
        this.codec = new FountainCodec(BLOCK_SIZE);
        this.receiveStates = new ConcurrentHashMap<>();
        this.sendStates = new ConcurrentHashMap<>();
        this.scheduler = Executors.newSingleThreadScheduledExecutor();

        // Start cleanup task
        scheduler.scheduleAtFixedRate(
            this::cleanupStaleTransfers,
            CLEANUP_INTERVAL_MS,
            CLEANUP_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
    }

    public void setCallback(TransferCallback callback) {
        this.callback = callback;
    }

    /**
     * Send data using fountain codes.
     *
     * @param data Data to send
     * @param channel Meshtastic channel index
     * @param hopLimit Hop limit for packets
     * @param transferType Type of transfer (Constants.TRANSFER_TYPE_COT or TRANSFER_TYPE_FILE)
     * @return Transfer ID, or -1 on failure
     */
    public int send(byte[] data, int channel, int hopLimit, byte transferType) {
        if (data == null || data.length == 0) {
            Log.e(TAG, "Cannot send null or empty data");
            return -1;
        }

        if (data.length > MAX_DATA_SIZE) {
            Log.e(TAG, "Data too large: " + data.length + " > " + MAX_DATA_SIZE);
            return -1;
        }

        if (!meshServiceManager.isConnected()) {
            Log.e(TAG, "Mesh service not connected");
            return -1;
        }

        // Prepend transfer type byte to data
        byte[] dataWithType = new byte[data.length + 1];
        dataWithType[0] = transferType;
        System.arraycopy(data, 0, dataWithType, 1, data.length);

        int transferId = FountainPacket.generateTransferId(localNodeId);
        byte[] dataHash = FountainPacket.computeHash(dataWithType);

        int K = codec.getSourceBlockCount(dataWithType.length);
        double overhead = getAdaptiveOverhead(K);
        int blocksToSend = codec.getRecommendedBlockCount(dataWithType.length, overhead);

        Log.d(TAG, "Starting fountain transfer " + transferId +
                  ": " + data.length + " bytes (type=" + transferType + "), K=" + K +
                  ", overhead=" + (int)(overhead * 100) + "%, sending " + blocksToSend + " blocks");

        // Log data details for debugging
        Log.d(TAG, "Transfer " + transferId + " dataWithType hash: " + bytesToHex(dataHash));

        SendState state = new SendState(transferId, dataWithType, dataHash, K, channel, hopLimit);
        sendStates.put(transferId, state);

        // Send in background thread
        new Thread(() -> sendTransfer(state, blocksToSend)).start();

        return transferId;
    }

    private void sendTransfer(SendState state, int blocksToSend) {
        int attempt = 0;

        // Get modem preset once at start of transfer for consistent timing
        int modemPreset = getCurrentModemPreset();
        long interPacketDelay = getInterPacketDelay(modemPreset);

        while (attempt < MAX_RETRIES && !state.isComplete) {
            attempt++;
            long ackTimeout = getAdaptiveAckTimeout(blocksToSend, modemPreset);
            Log.d(TAG, "Transfer " + state.transferId + " attempt " + attempt +
                      ", sending " + blocksToSend + " blocks, inter-pkt=" + interPacketDelay +
                      "ms, ACK timeout " + (ackTimeout / 1000) + "s");

            // Generate and send blocks
            List<FountainCodec.EncodedBlock> blocks = codec.encode(
                state.data, blocksToSend, state.transferId
            );

            for (int i = 0; i < blocks.size() && !state.isComplete; i++) {
                FountainCodec.EncodedBlock block = blocks.get(i);

                FountainPacket.DataBlock dataBlock = new FountainPacket.DataBlock(
                    state.transferId,
                    block.seed,
                    block.sourceBlockCount,
                    block.totalLength,
                    block.payload
                );

                sendPacket(dataBlock.toBytes(), state.channel, state.hopLimit);
                state.blocksSent++;

                if (callback != null) {
                    callback.onProgress(state.transferId, i + 1, blocksToSend, true);
                }

                // Inter-packet delay based on modem preset to respect duty cycle
                try {
                    Thread.sleep(interPacketDelay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            // Wait for ACK with adaptive timeout
            long waitStart = System.currentTimeMillis();
            while (!state.isComplete &&
                   System.currentTimeMillis() - waitStart < ackTimeout) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                // Check if we received an ACK
                if (state.ackReceived) {
                    if (state.isComplete) {
                        Log.d(TAG, "Transfer " + state.transferId + " completed successfully");
                        sendStates.remove(state.transferId);
                        if (callback != null) {
                            callback.onTransferComplete(state.transferId, null, null, (byte) 0);
                        }
                        return;
                    } else if (state.needMoreBlocks > 0) {
                        // Receiver needs more blocks
                        Log.d(TAG, "Transfer " + state.transferId +
                                  " needs " + state.needMoreBlocks + " more blocks");
                        blocksToSend = state.needMoreBlocks;
                        state.ackReceived = false;
                        break;  // Send more blocks
                    }
                }
            }

            if (!state.ackReceived) {
                Log.w(TAG, "Transfer " + state.transferId + " ACK timeout, attempt " + attempt);
                // Send fewer additional blocks on retry
                blocksToSend = Math.max(5, state.K / 5);
            }
        }

        if (!state.isComplete) {
            Log.e(TAG, "Transfer " + state.transferId + " failed after " + MAX_RETRIES + " attempts");
            sendStates.remove(state.transferId);
            if (callback != null) {
                callback.onTransferFailed(state.transferId, "Max retries exceeded");
            }
        }
    }

    /**
     * Handle incoming packet. Call this for all received fountain packets.
     *
     * @param packetData Raw packet data
     * @param senderNodeId Node ID of sender
     * @param channel Channel packet was received on
     * @param hopLimit Hop limit for responses
     */
    public void handlePacket(byte[] packetData, String senderNodeId, int channel, int hopLimit) {
        if (!FountainPacket.isFountainPacket(packetData)) {
            return;
        }

        byte packetType = FountainPacket.getPacketType(packetData);

        if (packetType == FountainPacket.TYPE_DATA) {
            handleDataBlock(packetData, senderNodeId, channel, hopLimit);
        } else if (packetType == FountainPacket.TYPE_COMPLETE ||
                   packetType == FountainPacket.TYPE_NEED_MORE) {
            handleAck(packetData);
        }
    }

    private void handleDataBlock(byte[] packetData, String senderNodeId,
                                  int channel, int hopLimit) {
        FountainPacket.DataBlock dataBlock = FountainPacket.DataBlock.fromBytes(packetData);
        if (dataBlock == null) {
            Log.w(TAG, "Failed to parse data block");
            return;
        }

        int transferId = dataBlock.transferId;

        // Get or create receive state
        ReceiveState existingState = receiveStates.get(transferId);

        // Check if existing state has mismatched parameters (indicates a new transfer with same ID)
        if (existingState != null) {
            boolean parameterMismatch = existingState.K != dataBlock.sourceBlockCount
                                     || existingState.totalLength != dataBlock.totalLength
                                     || !senderNodeId.equals(existingState.senderNodeId);

            if (parameterMismatch) {
                Log.w(TAG, "Transfer " + transferId + " parameter mismatch detected! " +
                          "Old: K=" + existingState.K + " len=" + existingState.totalLength + " from=" + existingState.senderNodeId +
                          ", New: K=" + dataBlock.sourceBlockCount + " len=" + dataBlock.totalLength + " from=" + senderNodeId +
                          ". Resetting state for new transfer.");
                receiveStates.remove(transferId);
                existingState = null;
            } else if (existingState.isComplete) {
                Log.d(TAG, "Transfer " + transferId + " already completed, ignoring duplicate block");
                return;
            }
        }

        ReceiveState state = existingState;
        if (state == null) {
            Log.d(TAG, "Starting receive for transfer " + transferId +
                      ", K=" + dataBlock.sourceBlockCount +
                      ", totalLen=" + dataBlock.totalLength +
                      ", from=" + senderNodeId);
            state = new ReceiveState(transferId, dataBlock.sourceBlockCount,
                                    dataBlock.totalLength, senderNodeId, channel, hopLimit);
            receiveStates.put(transferId, state);
        }

        // Regenerate source indices from seed (use codec to ensure same algorithm)
        // Pass transferId so codec can detect if this is block 0 (forced degree 1)
        int[] sourceIndices = codec.regenerateIndices(dataBlock.seed, dataBlock.sourceBlockCount, transferId);

        // Log block details for debugging
        StringBuilder indicesStr = new StringBuilder();
        for (int i = 0; i < sourceIndices.length; i++) {
            if (i > 0) indicesStr.append(",");
            indicesStr.append(sourceIndices[i]);
        }
        Log.d(TAG, "Transfer " + transferId + " block seed=" + dataBlock.seed +
                  ", degree=" + sourceIndices.length + ", indices=[" + indicesStr + "]" +
                  ", payload[0-3]=" + String.format("%02X %02X %02X %02X",
                      dataBlock.payload[0], dataBlock.payload[1],
                      dataBlock.payload[2], dataBlock.payload[3]));

        // Add block
        FountainCodec.EncodedBlock block = new FountainCodec.EncodedBlock(
            dataBlock.seed,
            dataBlock.sourceBlockCount,
            dataBlock.totalLength,
            sourceIndices,
            dataBlock.payload
        );

        // Check if transfer already completed (we keep state briefly for duplicate ACKs)
        if (state.isComplete) {
            return;  // Already completed, ignore late packets
        }

        // Check for duplicate
        if (state.hasBlock(dataBlock.seed)) {
            return;  // Already have this block
        }

        state.addBlock(block);
        state.lastActivityTime = System.currentTimeMillis();

        if (callback != null) {
            callback.onProgress(transferId, state.blocks.size(), state.K, false);
        }

        // Try to decode
        if (codec.isLikelyDecodable(state.blocks)) {
            byte[] decoded = codec.decode(state.blocks);

            if (decoded != null && decoded.length > 0) {
                // Log raw decoded data for debugging
                Log.d(TAG, "Transfer " + transferId + " raw decoded: " + decoded.length + " bytes from " + state.blocks.size() + " blocks");

                // Log first 32 bytes to help diagnose decoding issues
                int logLen = Math.min(32, decoded.length);
                StringBuilder hexDump = new StringBuilder();
                for (int i = 0; i < logLen; i++) {
                    hexDump.append(String.format("%02X ", decoded[i]));
                }
                Log.d(TAG, "Transfer " + transferId + " raw bytes: " + hexDump.toString());

                // Check if decoded data looks valid (type byte should be 0x00, 0x01, 0x30, or 0x31)
                byte firstByte = decoded[0];
                boolean validType = (firstByte == 0x00 || firstByte == 0x01 ||
                                    firstByte == 0x30 || firstByte == 0x31);
                if (!validType) {
                    Log.w(TAG, "Transfer " + transferId + " WARNING: First byte 0x" +
                              String.format("%02X", firstByte) + " is not a valid transfer type! " +
                              "Decoding may have failed.");
                }

                // Extract transfer type from first byte
                byte transferType = decoded[0];
                byte[] actualData = new byte[decoded.length - 1];
                System.arraycopy(decoded, 1, actualData, 0, actualData.length);

                Log.d(TAG, "Transfer " + transferId + " decoded successfully, " +
                          actualData.length + " bytes, type=" + transferType);

                // Log hash of actual data (without type byte) for comparison
                byte[] actualDataHash = FountainPacket.computeHash(actualData);
                Log.d(TAG, "Transfer " + transferId + " actual data hash: " + bytesToHex(actualDataHash));

                byte[] hash = FountainPacket.computeHash(decoded);
                state.decodedData = decoded;
                state.dataHash = hash;

                Log.d(TAG, "Transfer " + transferId + " computed hash: " + bytesToHex(hash));

                // Mark as complete to prevent further callbacks from late packets
                state.isComplete = true;

                // Send COMPLETE ACK (twice for redundancy)
                sendAck(transferId, FountainPacket.TYPE_COMPLETE,
                       state.blocks.size(), 0, hash, channel, hopLimit);

                try {
                    Thread.sleep(200);  // Small delay before duplicate
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                sendAck(transferId, FountainPacket.TYPE_COMPLETE,
                       state.blocks.size(), 0, hash, channel, hopLimit);

                // Notify callback with actual data (type byte stripped)
                if (callback != null) {
                    callback.onTransferComplete(transferId, actualData, state.senderNodeId, transferType);
                }

                // Keep state briefly for duplicate ACKs, then remove
                scheduler.schedule(() -> receiveStates.remove(transferId),
                                  5, TimeUnit.SECONDS);
            }
        }
    }

    private void handleAck(byte[] packetData) {
        FountainPacket.AckPacket ack = FountainPacket.AckPacket.fromBytes(packetData);
        if (ack == null) {
            Log.w(TAG, "Failed to parse ACK packet");
            return;
        }

        SendState state = sendStates.get(ack.transferId);
        if (state == null) {
            Log.d(TAG, "Received ACK for unknown transfer " + ack.transferId);
            return;
        }

        state.ackReceived = true;

        if (ack.isComplete()) {
            // Verify hash matches
            if (Arrays.equals(ack.dataHash, state.dataHash)) {
                Log.d(TAG, "Transfer " + ack.transferId + " confirmed complete");
                state.isComplete = true;
            } else {
                Log.w(TAG, "Transfer " + ack.transferId + " hash mismatch! " +
                          "expected=" + bytesToHex(state.dataHash) +
                          " received=" + bytesToHex(ack.dataHash));
                state.needMoreBlocks = state.K / 4;  // Send more blocks
            }
        } else if (ack.isNeedMore()) {
            Log.d(TAG, "Transfer " + ack.transferId + " needs more blocks: " + ack.neededBlocks);
            state.needMoreBlocks = ack.neededBlocks;
        }
    }

    private void sendPacket(byte[] data, int channel, int hopLimit) {
        DataPacket dp = new DataPacket(
            DataPacket.ID_BROADCAST,
            ByteString.of(data, 0, data.length),
            PortNum.ATAK_FORWARDER.getValue(),
            DataPacket.ID_LOCAL,
            System.currentTimeMillis(),
            0,
            MessageStatus.UNKNOWN,
            hopLimit,
            channel,
            false,  // wantAck - we handle our own ACKs
            0, 0f, 0, null, null, 0, false, 0, 0, null
        );
        meshServiceManager.sendToMesh(dp);
    }

    private void sendAck(int transferId, byte type, int received, int needed,
                         byte[] hash, int channel, int hopLimit) {
        FountainPacket.AckPacket ack = new FountainPacket.AckPacket(
            transferId, type, received, needed, hash
        );
        sendPacket(ack.toBytes(), channel, hopLimit);
    }

    private void cleanupStaleTransfers() {
        long now = System.currentTimeMillis();

        // Clean up stale receive states
        Iterator<Map.Entry<Integer, ReceiveState>> recvIt = receiveStates.entrySet().iterator();
        while (recvIt.hasNext()) {
            Map.Entry<Integer, ReceiveState> entry = recvIt.next();
            if (now - entry.getValue().lastActivityTime > RECEIVE_TIMEOUT_MS) {
                Log.w(TAG, "Receive transfer " + entry.getKey() + " timed out");
                recvIt.remove();
                if (callback != null) {
                    callback.onTransferFailed(entry.getKey(), "Receive timeout");
                }
            }
        }
    }

    /**
     * Shutdown the chunk manager.
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================== State Classes ====================

    private static class SendState {
        final int transferId;
        final byte[] data;
        final byte[] dataHash;
        final int K;
        final int channel;
        final int hopLimit;

        int blocksSent = 0;
        boolean ackReceived = false;
        boolean isComplete = false;
        int needMoreBlocks = 0;

        SendState(int transferId, byte[] data, byte[] dataHash, int K,
                 int channel, int hopLimit) {
            this.transferId = transferId;
            this.data = data;
            this.dataHash = dataHash;
            this.K = K;
            this.channel = channel;
            this.hopLimit = hopLimit;
        }
    }

    private static class ReceiveState {
        final int transferId;
        final int K;
        final int totalLength;
        final String senderNodeId;
        final int channel;
        final int hopLimit;
        final List<FountainCodec.EncodedBlock> blocks;
        final java.util.Set<Integer> receivedSeeds;

        long lastActivityTime;
        byte[] decodedData;
        byte[] dataHash;
        boolean isComplete;  // Flag to prevent multiple completions

        ReceiveState(int transferId, int K, int totalLength, String senderNodeId,
                    int channel, int hopLimit) {
            this.transferId = transferId;
            this.K = K;
            this.totalLength = totalLength;
            this.senderNodeId = senderNodeId;
            this.channel = channel;
            this.hopLimit = hopLimit;
            this.blocks = new ArrayList<>();
            this.receivedSeeds = new java.util.HashSet<>();
            this.lastActivityTime = System.currentTimeMillis();
            this.isComplete = false;
        }

        boolean hasBlock(int seed) {
            return receivedSeeds.contains(seed);
        }

        void addBlock(FountainCodec.EncodedBlock block) {
            blocks.add(block);
            receivedSeeds.add(block.seed);
        }
    }

    // Helper to convert bytes to hex string for logging
    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}
