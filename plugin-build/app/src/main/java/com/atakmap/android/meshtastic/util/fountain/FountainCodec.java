package com.atakmap.android.meshtastic.util.fountain;

import com.atakmap.coremap.log.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Luby Transform (LT) Fountain Code implementation.
 *
 * Fountain codes allow reconstruction of original data from any K+ encoded blocks,
 * making them ideal for lossy networks where specific packet retransmission is costly.
 */
public class FountainCodec {
    private static final String TAG = "FountainCodec";

    private final int blockSize;
    private final double c;  // Robust Soliton parameter
    private final double delta;  // Failure probability bound

    /**
     * Create a fountain codec with specified block size.
     *
     * @param blockSize Size of each encoded block in bytes
     */
    public FountainCodec(int blockSize) {
        this(blockSize, 0.1, 0.5);
    }

    /**
     * Create a fountain codec with custom parameters.
     *
     * @param blockSize Size of each encoded block in bytes
     * @param c Robust Soliton constant (typically 0.03-0.5)
     * @param delta Failure probability bound (typically 0.05-0.5)
     */
    public FountainCodec(int blockSize, double c, double delta) {
        this.blockSize = blockSize;
        this.c = c;
        this.delta = delta;
    }

    /**
     * Encode data into fountain-coded blocks.
     *
     * @param data Original data to encode
     * @param numBlocks Number of encoded blocks to generate
     * @param transferId Unique identifier for this transfer
     * @return List of encoded blocks
     */
    public List<EncodedBlock> encode(byte[] data, int numBlocks, int transferId) {
        int K = getSourceBlockCount(data.length);
        byte[][] sourceBlocks = splitIntoSourceBlocks(data, K);

        List<EncodedBlock> encodedBlocks = new ArrayList<>();

        for (int i = 0; i < numBlocks; i++) {
            int seed = generateSeed(transferId, i);
            EncodedBlock block;

            // Force first block to have degree 1 to guarantee peeling decoder can start
            // This is especially important for small K where random sampling may not
            // produce any degree-1 blocks in the first few blocks
            if (i == 0) {
                block = encodeBlockWithDegree(sourceBlocks, K, seed, data.length, 1);
            } else {
                block = encodeBlock(sourceBlocks, K, seed, data.length);
            }
            encodedBlocks.add(block);
        }

        return encodedBlocks;
    }

    /**
     * Encode a single block with a specific seed.
     * Used for generating additional blocks on demand.
     */
    public EncodedBlock encodeBlock(byte[] data, int seed, int transferId) {
        int K = getSourceBlockCount(data.length);
        byte[][] sourceBlocks = splitIntoSourceBlocks(data, K);
        return encodeBlock(sourceBlocks, K, seed, data.length);
    }

    private EncodedBlock encodeBlock(byte[][] sourceBlocks, int K, int seed, int totalLength) {
        Random rng = new Random(seed);

        // Sample degree from Robust Soliton distribution
        int degree = sampleDegree(rng, K);

        // Select which source blocks to XOR
        int[] indices = selectIndices(rng, K, degree);

        // XOR selected blocks
        byte[] payload = new byte[blockSize];
        for (int idx : indices) {
            xorInPlace(payload, sourceBlocks[idx]);
        }

        return new EncodedBlock(seed, K, totalLength, indices, payload);
    }

    /**
     * Encode a block with a specific forced degree.
     * Used to guarantee degree-1 blocks for reliable peeling decoder startup.
     */
    private EncodedBlock encodeBlockWithDegree(byte[][] sourceBlocks, int K, int seed, int totalLength, int forcedDegree) {
        Random rng = new Random(seed);

        // Skip the normal degree sampling but consume the random value to keep RNG in sync
        sampleDegree(rng, K);

        // Use forced degree instead
        int degree = Math.min(forcedDegree, K);

        // Select which source blocks to XOR
        int[] indices = selectIndices(rng, K, degree);

        // XOR selected blocks
        byte[] payload = new byte[blockSize];
        for (int idx : indices) {
            xorInPlace(payload, sourceBlocks[idx]);
        }

        return new EncodedBlock(seed, K, totalLength, indices, payload);
    }

    /**
     * Decode received blocks back to original data.
     *
     * @param blocks Received encoded blocks
     * @return Decoded data, or null if insufficient blocks
     */
    public byte[] decode(List<EncodedBlock> blocks) {
        if (blocks.isEmpty()) {
            return null;
        }

        int K = blocks.get(0).sourceBlockCount;
        int totalLength = blocks.get(0).totalLength;

        // Peeling decoder state
        byte[][] decoded = new byte[K][];
        boolean[] isDecoded = new boolean[K];
        int decodedCount = 0;

        // Working copies of blocks for peeling
        List<PeelingBlock> workingBlocks = new ArrayList<>();
        Log.d(TAG, "Decode starting with " + blocks.size() + " blocks for K=" + K);
        for (EncodedBlock block : blocks) {
            Set<Integer> indices = new HashSet<>();
            StringBuilder indicesStr = new StringBuilder();
            for (int idx : block.sourceIndices) {
                indices.add(idx);
                if (indicesStr.length() > 0) indicesStr.append(",");
                indicesStr.append(idx);
            }
            Log.d(TAG, "  Block seed=" + block.seed + " indices=[" + indicesStr + "]" +
                      " payload[0-3]=" + String.format("%02X %02X %02X %02X",
                          block.payload[0], block.payload[1], block.payload[2], block.payload[3]));
            workingBlocks.add(new PeelingBlock(block.payload.clone(), indices));
        }

        // Peeling decoder - iteratively decode
        boolean progress = true;
        int iteration = 0;
        while (progress && decodedCount < K) {
            progress = false;
            iteration++;

            for (int i = 0; i < workingBlocks.size(); i++) {
                PeelingBlock wb = workingBlocks.get(i);
                if (wb == null) continue;

                // Remove already decoded indices from this block
                Set<Integer> remaining = new HashSet<>();
                for (int idx : wb.indices) {
                    if (!isDecoded[idx]) {
                        remaining.add(idx);
                    } else {
                        // XOR out the known block
                        xorInPlace(wb.payload, decoded[idx]);
                    }
                }
                wb.indices = remaining;

                // If only one unknown remains, we can decode it
                if (remaining.size() == 1) {
                    int idx = remaining.iterator().next();
                    decoded[idx] = wb.payload.clone();
                    isDecoded[idx] = true;
                    decodedCount++;
                    workingBlocks.set(i, null);  // Mark as used
                    progress = true;
                    Log.d(TAG, "Peeling iter=" + iteration + ": decoded block " + idx +
                              ", first 4 bytes: " + String.format("%02X %02X %02X %02X",
                                  decoded[idx][0], decoded[idx][1], decoded[idx][2], decoded[idx][3]));
                } else if (remaining.isEmpty()) {
                    workingBlocks.set(i, null);  // Redundant block
                }
            }
        }

        Log.d(TAG, "Peeling complete: " + decodedCount + "/" + K + " blocks decoded");

        // Check if we decoded everything
        if (decodedCount < K) {
            Log.w(TAG, "Decoding incomplete: " + decodedCount + "/" + K + " blocks decoded");
            return null;
        }

        // Reassemble original data
        return reassemble(decoded, totalLength);
    }

    /**
     * Check if a set of blocks is likely decodable.
     */
    public boolean isLikelyDecodable(List<EncodedBlock> blocks) {
        if (blocks.isEmpty()) return false;
        int K = blocks.get(0).sourceBlockCount;
        // Need at least K blocks, plus some overhead for decoding
        return blocks.size() >= K;
    }

    /**
     * Get the number of source blocks needed for given data length.
     */
    public int getSourceBlockCount(int dataLength) {
        return (int) Math.ceil((double) dataLength / blockSize);
    }

    /**
     * Get recommended number of encoded blocks to send.
     *
     * @param dataLength Length of original data
     * @param overhead Overhead factor (e.g., 0.15 for 15% overhead)
     */
    public int getRecommendedBlockCount(int dataLength, double overhead) {
        int K = getSourceBlockCount(dataLength);
        return (int) Math.ceil(K * (1 + overhead));
    }

    // ==================== Private Helper Methods ====================

    private byte[][] splitIntoSourceBlocks(byte[] data, int K) {
        byte[][] blocks = new byte[K][blockSize];

        for (int i = 0; i < K; i++) {
            int start = i * blockSize;
            int len = Math.min(blockSize, data.length - start);
            if (len > 0) {
                System.arraycopy(data, start, blocks[i], 0, len);
            }
            // Remaining bytes stay as zeros (padding)
        }

        return blocks;
    }

    private byte[] reassemble(byte[][] blocks, int totalLength) {
        byte[] result = new byte[totalLength];
        int pos = 0;

        for (byte[] block : blocks) {
            int len = Math.min(blockSize, totalLength - pos);
            if (len > 0) {
                System.arraycopy(block, 0, result, pos, len);
                pos += len;
            }
        }

        return result;
    }

    /**
     * Sample degree from Robust Soliton distribution.
     * This distribution is designed to minimize the number of encoded blocks
     * needed while ensuring high probability of successful decoding.
     */
    private int sampleDegree(Random rng, int K) {
        double[] cdf = buildRobustSolitonCDF(K);
        double u = rng.nextDouble();

        for (int d = 1; d <= K; d++) {
            if (u <= cdf[d]) {
                return d;
            }
        }
        return K;
    }

    private double[] buildRobustSolitonCDF(int K) {
        double[] rho = new double[K + 1];  // Ideal Soliton
        double[] tau = new double[K + 1];  // Additional robustness
        double[] mu = new double[K + 1];   // Robust Soliton (normalized)
        double[] cdf = new double[K + 1];

        // Ideal Soliton distribution
        rho[1] = 1.0 / K;
        for (int d = 2; d <= K; d++) {
            rho[d] = 1.0 / (d * (d - 1));
        }

        // Robustness parameters
        double S = c * Math.log(K / delta) * Math.sqrt(K);
        int threshold = (int) Math.floor(K / S);

        // Tau distribution for robustness
        for (int d = 1; d <= K; d++) {
            if (d < threshold) {
                tau[d] = S / (K * d);
            } else if (d == threshold) {
                tau[d] = S * Math.log(S / delta) / K;
            } else {
                tau[d] = 0;
            }
        }

        // Combine and normalize
        double Z = 0;
        for (int d = 1; d <= K; d++) {
            mu[d] = rho[d] + tau[d];
            Z += mu[d];
        }

        // Build CDF
        double cumulative = 0;
        for (int d = 1; d <= K; d++) {
            cumulative += mu[d] / Z;
            cdf[d] = cumulative;
        }

        return cdf;
    }

    /**
     * Regenerate source indices from seed (for decoder).
     * Uses the same random sequence as encoding.
     *
     * @param seed The block's seed
     * @param K Number of source blocks
     * @param transferId The transfer ID (used to detect if this is block 0)
     */
    public int[] regenerateIndices(int seed, int K, int transferId) {
        Random rng = new Random(seed);

        // Check if this is block 0 (forced degree 1)
        // Block 0's seed = (transferId * 31337) & 0xFFFF
        int block0Seed = (transferId * 31337) & 0xFFFF;
        boolean isFirstBlock = (seed == block0Seed);

        // Consume the degree sample to keep RNG in sync
        sampleDegree(rng, K);

        int degree;
        if (isFirstBlock) {
            // First block always has forced degree 1
            degree = 1;
        } else {
            // Re-seed and sample again for non-first blocks
            rng = new Random(seed);
            degree = sampleDegree(rng, K);
        }

        return selectIndices(rng, K, degree);
    }

    /**
     * Regenerate source indices from seed (for decoder).
     * Legacy method without transfer ID - cannot detect forced degree blocks.
     * @deprecated Use regenerateIndices(seed, K, transferId) instead
     */
    public int[] regenerateIndices(int seed, int K) {
        Random rng = new Random(seed);
        int degree = sampleDegree(rng, K);
        return selectIndices(rng, K, degree);
    }

    /**
     * Select random indices without replacement.
     */
    private int[] selectIndices(Random rng, int K, int degree) {
        degree = Math.min(degree, K);
        Set<Integer> selected = new HashSet<>();

        while (selected.size() < degree) {
            selected.add(rng.nextInt(K));
        }

        int[] indices = new int[degree];
        int i = 0;
        for (int idx : selected) {
            indices[i++] = idx;
        }
        Arrays.sort(indices);
        return indices;
    }

    /**
     * XOR source into target in place.
     */
    private void xorInPlace(byte[] target, byte[] source) {
        int len = Math.min(target.length, source.length);
        for (int i = 0; i < len; i++) {
            target[i] ^= source[i];
        }
    }

    /**
     * Generate deterministic seed from transfer ID and block index.
     * NOTE: Seed must fit in 16 bits since that's the packet format limit.
     */
    private int generateSeed(int transferId, int blockIndex) {
        // Simple hash combining transfer ID and block index, masked to 16 bits
        return (transferId * 31337 + blockIndex * 7919) & 0xFFFF;
    }

    // ==================== Inner Classes ====================

    /**
     * Represents an encoded fountain block.
     */
    public static class EncodedBlock {
        public final int seed;
        public final int sourceBlockCount;  // K
        public final int totalLength;
        public final int[] sourceIndices;
        public final byte[] payload;

        public EncodedBlock(int seed, int sourceBlockCount, int totalLength,
                          int[] sourceIndices, byte[] payload) {
            this.seed = seed;
            this.sourceBlockCount = sourceBlockCount;
            this.totalLength = totalLength;
            this.sourceIndices = sourceIndices;
            this.payload = payload;
        }
    }

    /**
     * Working block for peeling decoder.
     */
    private static class PeelingBlock {
        byte[] payload;
        Set<Integer> indices;

        PeelingBlock(byte[] payload, Set<Integer> indices) {
            this.payload = payload;
            this.indices = indices;
        }
    }
}
