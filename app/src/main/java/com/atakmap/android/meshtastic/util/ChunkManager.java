package com.atakmap.android.meshtastic.util;

import android.content.SharedPreferences;
import com.atakmap.android.meshtastic.MeshtasticReceiver;
import com.atakmap.coremap.log.Log;
import org.meshtastic.core.model.DataPacket;
import org.meshtastic.core.service.IMeshService;
import org.meshtastic.core.model.MessageStatus;
import org.meshtastic.proto.Portnums;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

public class ChunkManager {
    private static final String TAG = "ChunkManager";
    private static final int MAX_TOTAL_SIZE = 56 * 1024; // 56KB max
    private static final int MAX_CHUNKS = 286; // Maximum number of chunks
    private final int chunkSize;
    private final HashMap<Integer, byte[]> receivedChunks;
    private boolean isReceivingChunks;
    private int expectedChunkCount;
    private int receivedChunkCount;
    
    public ChunkManager() {
        this(Constants.DEFAULT_CHUNK_SIZE);
    }
    
    public ChunkManager(int chunkSize) {
        this.chunkSize = chunkSize;
        this.receivedChunks = new HashMap<>();
        this.isReceivingChunks = false;
        this.expectedChunkCount = 0;
        this.receivedChunkCount = 0;
    }
    
    public List<byte[]> divideIntoChunks(byte[] data) {
        List<byte[]> chunks = new ArrayList<>();
        int start = 0;
        
        while (start < data.length) {
            int end = Math.min(data.length, start + chunkSize);
            try {
                chunks.add(Arrays.copyOfRange(data, start, end));
            } catch (Exception e) {
                Log.e(TAG, "Failed to create chunk", e);
                return new ArrayList<>();
            }
            start += chunkSize;
        }
        
        return chunks;
    }
    
    public byte[] createChunkHeader(int totalSize) {
        return String.format(Locale.US, Constants.CHUNK_HEADER_FORMAT, totalSize).getBytes();
    }
    
    public byte[] combineHeaderAndChunk(byte[] header, byte[] chunk) {
        byte[] combined = new byte[header.length + chunk.length];
        System.arraycopy(header, 0, combined, 0, header.length);
        System.arraycopy(chunk, 0, combined, header.length, chunk.length);
        return combined;
    }
    
    public boolean sendChunkedData(byte[] data, IMeshService meshService, SharedPreferences prefs, 
                                  int hopLimit, int channel) throws Exception {
        if (meshService == null) {
            Log.e(TAG, "Mesh service is null");
            return false;
        }
        
        if (data == null || data.length == 0) {
            Log.e(TAG, "Invalid data to send");
            return false;
        }
        
        if (data.length > MAX_TOTAL_SIZE) {
            Log.e(TAG, "Data too large to send: " + data.length + " bytes (max: " + MAX_TOTAL_SIZE + ")");
            return false;
        }
        
        List<byte[]> chunks = divideIntoChunks(data);
        if (chunks.isEmpty()) {
            return false;
        }
        
        if (chunks.size() > MAX_CHUNKS) {
            Log.e(TAG, "Too many chunks: " + chunks.size() + " (max: " + MAX_CHUNKS + ")");
            return false;
        }
        
        byte[] header = createChunkHeader(data.length);
        SharedPreferences.Editor editor = prefs.edit();
        
        for (byte[] chunk : chunks) {
            byte[] combined = combineHeaderAndChunk(header, chunk);
            
            if (!sendSingleChunk(combined, meshService, prefs, editor, hopLimit, channel)) {
                Log.e(TAG, "Failed to send chunk");
                return false;
            }
        }
        
        // Send end marker
        DataPacket endPacket = new DataPacket(
            DataPacket.ID_BROADCAST,
            Constants.CHUNK_END_MARKER,
            Portnums.PortNum.ATAK_FORWARDER_VALUE,
            DataPacket.ID_LOCAL,
            System.currentTimeMillis(),
            0,
            MessageStatus.UNKNOWN,
            3,
            channel,
            true, // wantAck must be true for chunks
            0,  // hopStart
            0f, // snr
            0,  // rssi
            null // replyId
        );
        
        meshService.send(endPacket);
        return true;
    }
    
    private boolean sendSingleChunk(byte[] chunkData, IMeshService meshService, 
                                   SharedPreferences prefs, SharedPreferences.Editor editor,
                                   int hopLimit, int channel) throws Exception {
        int packetId = meshService.getPacketId();
        editor.putInt(Constants.PREF_PLUGIN_CHUNK_ID, packetId);
        editor.putBoolean(Constants.PREF_PLUGIN_CHUNK_ACK, true);
        editor.apply();
        
        int retries = 0;
        while (retries < Constants.CHUNK_MAX_RETRIES) {
            DataPacket dp = new DataPacket(
                DataPacket.ID_BROADCAST,
                chunkData,
                Portnums.PortNum.ATAK_FORWARDER_VALUE,
                DataPacket.ID_LOCAL,
                System.currentTimeMillis(),
                packetId,
                MessageStatus.UNKNOWN,
                hopLimit,
                channel,
                true, // wantAck must be true for chunks
                0,  // hopStart
                0f, // snr
                0,  // rssi
                null // replyId
            );
            
            meshService.send(dp);
            
            // Wait for acknowledgment with timeout
            long startTime = System.currentTimeMillis();
            long timeout = Constants.CHUNK_ACK_TIMEOUT_MS * Constants.CHUNK_MAX_RETRIES;
            
            while (prefs.getBoolean(Constants.PREF_PLUGIN_CHUNK_ACK, false)) {
                // Check for timeout
                if (System.currentTimeMillis() - startTime > timeout) {
                    throw new TimeoutException("Chunk acknowledgment timeout");
                }
                
                // Check for error
                if (prefs.getBoolean(Constants.PREF_PLUGIN_CHUNK_ERR, false)) {
                    Log.d(TAG, "Chunk error received, retrying");
                    editor.putBoolean(Constants.PREF_PLUGIN_CHUNK_ERR, false);
                    editor.apply();
                    retries++;
                    break;
                }
                
                // Small sleep to avoid busy waiting
                try {
                    Thread.sleep(50); // Check every 50ms instead of CHUNK_ACK_TIMEOUT_MS
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedException("Chunk sending interrupted");
                }
            }
            
            if (!prefs.getBoolean(Constants.PREF_PLUGIN_CHUNK_ACK, false)) {
                return true; // Successfully acknowledged
            }
        }
        
        return false;
    }
    
    public void startReceiving(int totalSize) {
        if (totalSize <= 0 || totalSize > MAX_TOTAL_SIZE) {
            Log.e(TAG, "Invalid total size for receiving: " + totalSize);
            return;
        }
        
        isReceivingChunks = true;
        expectedChunkCount = (int) Math.ceil((double) totalSize / chunkSize);
        
        if (expectedChunkCount > MAX_CHUNKS) {
            Log.e(TAG, "Too many expected chunks: " + expectedChunkCount);
            reset();
            return;
        }
        
        receivedChunkCount = 0;
        receivedChunks.clear();
    }
    
    public boolean addReceivedChunk(int index, byte[] data) {
        if (!isReceivingChunks) {
            return false;
        }
        
        receivedChunks.put(index, data);
        receivedChunkCount++;
        
        return receivedChunkCount >= expectedChunkCount;
    }
    
    public byte[] assembleChunks() {
        if (!isReceivingChunks || receivedChunkCount < expectedChunkCount) {
            return null;
        }
        
        int totalSize = 0;
        for (byte[] chunk : receivedChunks.values()) {
            totalSize += chunk.length;
        }
        
        byte[] result = new byte[totalSize];
        int position = 0;
        
        for (int i = 0; i < receivedChunks.size(); i++) {
            byte[] chunk = receivedChunks.get(i);
            if (chunk != null) {
                System.arraycopy(chunk, 0, result, position, chunk.length);
                position += chunk.length;
            }
        }
        
        reset();
        return result;
    }
    
    public void reset() {
        isReceivingChunks = false;
        expectedChunkCount = 0;
        receivedChunkCount = 0;
        receivedChunks.clear();
    }
    
    public boolean isReceiving() {
        return isReceivingChunks;
    }
}