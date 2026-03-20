package com.atakmap.android.meshtastic.util.fountain;

import com.atakmap.coremap.log.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Random;

/**
 * Packet formats for fountain code transfer protocol.
 *
 * Data Block Header (8 bytes):
 * ┌─────────────────────────────────────────────────────────┐
 * │ xfer_id │ seed   │ K    │ total_len │     payload      │
 * │  3 B    │  2 B   │ 1 B  │   2 B     │    ≤225 B        │
 * └─────────────────────────────────────────────────────────┘
 *
 * ACK Packet (16 bytes):
 * ┌─────────────────────────────────────────────────────────┐
 * │ xfer_id │ type │ received │ needed │    data_hash      │
 * │  3 B    │ 1 B  │   2 B    │  2 B   │       8 B         │
 * └─────────────────────────────────────────────────────────┘
 */
public class FountainPacket {
    private static final String TAG = "FountainPacket";

    // Packet type identifiers (first byte after magic)
    public static final byte TYPE_DATA = 0x01;
    public static final byte TYPE_COMPLETE = 0x02;
    public static final byte TYPE_NEED_MORE = 0x03;

    // Magic bytes to identify fountain packets
    public static final byte[] MAGIC = {'F', 'T', 'N'};

    // Header sizes
    public static final int DATA_HEADER_SIZE = 11;  // MAGIC(3) + xfer(3) + seed(2) + K(1) + len(2)
    public static final int ACK_PACKET_SIZE = 19;   // MAGIC(3) + xfer(3) + type(1) + recv(2) + need(2) + hash(8)

    // Maximum payload size (Meshtastic limit is 233, minus 11-byte header and 6-byte protobuf overhead = 216)
    // Using 214 for additional safety margin
    public static final int MAX_PAYLOAD_SIZE = 214;

    /**
     * Represents a data block packet.
     */
    public static class DataBlock {
        public final int transferId;    // 24-bit transfer identifier
        public final int seed;          // 16-bit encoding seed
        public final int sourceBlockCount;  // K (8-bit, max 255 source blocks)
        public final int totalLength;   // Original data length (16-bit, max 64KB)
        public final byte[] payload;

        public DataBlock(int transferId, int seed, int sourceBlockCount,
                        int totalLength, byte[] payload) {
            this.transferId = transferId & 0xFFFFFF;
            this.seed = seed & 0xFFFF;
            this.sourceBlockCount = sourceBlockCount & 0xFF;
            this.totalLength = totalLength & 0xFFFF;
            this.payload = payload;
        }

        /**
         * Serialize to wire format.
         */
        public byte[] toBytes() {
            ByteBuffer buf = ByteBuffer.allocate(DATA_HEADER_SIZE + payload.length);
            buf.order(ByteOrder.BIG_ENDIAN);

            // Magic
            buf.put(MAGIC);

            // Transfer ID (24-bit, big-endian)
            buf.put((byte) ((transferId >> 16) & 0xFF));
            buf.put((byte) ((transferId >> 8) & 0xFF));
            buf.put((byte) (transferId & 0xFF));

            // Seed (16-bit)
            buf.putShort((short) seed);

            // K (8-bit)
            buf.put((byte) sourceBlockCount);

            // Total length (16-bit)
            buf.putShort((short) totalLength);

            // Payload
            buf.put(payload);

            return buf.array();
        }

        /**
         * Deserialize from wire format.
         */
        public static DataBlock fromBytes(byte[] data) {
            if (data == null || data.length < DATA_HEADER_SIZE) {
                return null;
            }

            // Check magic
            if (data[0] != MAGIC[0] || data[1] != MAGIC[1] || data[2] != MAGIC[2]) {
                return null;
            }

            ByteBuffer buf = ByteBuffer.wrap(data);
            buf.order(ByteOrder.BIG_ENDIAN);
            buf.position(3);  // Skip magic

            // Transfer ID (24-bit)
            int transferId = ((buf.get() & 0xFF) << 16)
                           | ((buf.get() & 0xFF) << 8)
                           | (buf.get() & 0xFF);

            // Seed
            int seed = buf.getShort() & 0xFFFF;

            // K
            int sourceBlockCount = buf.get() & 0xFF;

            // Total length
            int totalLength = buf.getShort() & 0xFFFF;

            // Payload
            byte[] payload = new byte[data.length - DATA_HEADER_SIZE];
            buf.get(payload);

            return new DataBlock(transferId, seed, sourceBlockCount, totalLength, payload);
        }
    }

    /**
     * Represents an acknowledgment packet.
     */
    public static class AckPacket {
        public final int transferId;
        public final byte type;  // TYPE_COMPLETE or TYPE_NEED_MORE
        public final int receivedBlocks;
        public final int neededBlocks;
        public final byte[] dataHash;  // 8-byte truncated hash

        public AckPacket(int transferId, byte type, int receivedBlocks,
                        int neededBlocks, byte[] dataHash) {
            this.transferId = transferId & 0xFFFFFF;
            this.type = type;
            this.receivedBlocks = receivedBlocks & 0xFFFF;
            this.neededBlocks = neededBlocks & 0xFFFF;
            this.dataHash = dataHash != null ? Arrays.copyOf(dataHash, 8) : new byte[8];
        }

        /**
         * Serialize to wire format.
         */
        public byte[] toBytes() {
            ByteBuffer buf = ByteBuffer.allocate(ACK_PACKET_SIZE);
            buf.order(ByteOrder.BIG_ENDIAN);

            // Magic
            buf.put(MAGIC);

            // Transfer ID (24-bit)
            buf.put((byte) ((transferId >> 16) & 0xFF));
            buf.put((byte) ((transferId >> 8) & 0xFF));
            buf.put((byte) (transferId & 0xFF));

            // Type
            buf.put(type);

            // Received blocks
            buf.putShort((short) receivedBlocks);

            // Needed blocks
            buf.putShort((short) neededBlocks);

            // Data hash (8 bytes)
            buf.put(dataHash, 0, 8);

            return buf.array();
        }

        /**
         * Deserialize from wire format.
         */
        public static AckPacket fromBytes(byte[] data) {
            if (data == null || data.length < ACK_PACKET_SIZE) {
                return null;
            }

            // Check magic
            if (data[0] != MAGIC[0] || data[1] != MAGIC[1] || data[2] != MAGIC[2]) {
                return null;
            }

            ByteBuffer buf = ByteBuffer.wrap(data);
            buf.order(ByteOrder.BIG_ENDIAN);
            buf.position(3);  // Skip magic

            // Transfer ID
            int transferId = ((buf.get() & 0xFF) << 16)
                           | ((buf.get() & 0xFF) << 8)
                           | (buf.get() & 0xFF);

            // Type
            byte type = buf.get();

            // Received blocks
            int receivedBlocks = buf.getShort() & 0xFFFF;

            // Needed blocks
            int neededBlocks = buf.getShort() & 0xFFFF;

            // Data hash
            byte[] dataHash = new byte[8];
            buf.get(dataHash);

            return new AckPacket(transferId, type, receivedBlocks, neededBlocks, dataHash);
        }

        /**
         * Check if this is a COMPLETE ack.
         */
        public boolean isComplete() {
            return type == TYPE_COMPLETE;
        }

        /**
         * Check if this is a NEED_MORE ack.
         */
        public boolean isNeedMore() {
            return type == TYPE_NEED_MORE;
        }
    }

    /**
     * Generate a unique transfer ID.
     */
    public static int generateTransferId(String senderNodeId) {
        Random rng = new Random();
        int random = rng.nextInt();
        int time = (int) (System.currentTimeMillis() & 0xFFFF);
        int nodeHash = senderNodeId != null ? senderNodeId.hashCode() : 0;

        // Combine and truncate to 24 bits
        return ((nodeHash ^ random ^ time) & 0xFFFFFF);
    }

    /**
     * Compute truncated hash of data (8 bytes).
     */
    public static byte[] computeHash(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] fullHash = md.digest(data);
            return Arrays.copyOf(fullHash, 8);
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "SHA-256 not available", e);
            // Fallback: simple checksum
            byte[] hash = new byte[8];
            for (int i = 0; i < data.length; i++) {
                hash[i % 8] ^= data[i];
            }
            return hash;
        }
    }

    /**
     * Check if data starts with fountain packet magic bytes.
     */
    public static boolean isFountainPacket(byte[] data) {
        return data != null && data.length >= 3
            && data[0] == MAGIC[0]
            && data[1] == MAGIC[1]
            && data[2] == MAGIC[2];
    }

    /**
     * Determine packet type from raw bytes.
     */
    public static byte getPacketType(byte[] data) {
        if (!isFountainPacket(data) || data.length < 7) {
            return 0;
        }

        // For ACK packets, type is at position 6
        // For data packets, we check length to differentiate
        if (data.length == ACK_PACKET_SIZE) {
            return data[6];  // ACK type field
        } else if (data.length >= DATA_HEADER_SIZE) {
            return TYPE_DATA;
        }

        return 0;
    }
}
