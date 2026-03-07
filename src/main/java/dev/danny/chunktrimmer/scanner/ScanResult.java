package dev.danny.chunktrimmer.scanner;

import java.util.Map;

/**
 * Complete scan output — all chunk analysis data plus metadata.
 */
public record ScanResult(
        String worldName,
        long scanTimestamp,
        Long seed,
        Map<String, ChunkAnalysis> chunks
) {
    public int totalChunks() {
        return chunks.size();
    }

    public long chunksWithActivity() {
        return chunks.values().stream()
                .filter(c -> c.inhabitedTime() > 0)
                .count();
    }

    public long chunksWithPlayerBlocks() {
        return chunks.values().stream()
                .filter(ChunkAnalysis::hasPlayerBlocks)
                .count();
    }
}
