package dev.danny.chunktrimmer.scanner;

import net.querz.nbt.tag.CompoundTag;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scans Minecraft region files to extract per-chunk analysis data.
 * Handles both pre-1.18 and 1.18+ chunk NBT formats.
 */
public class ChunkScanner {

    // DataVersion 2844 = 1.18 (21w43a) — the snapshot that removed the Level wrapper
    private static final int DATA_VERSION_1_18 = 2844;

    /**
     * Scans all region files in the given directory and returns analysis data
     * for every chunk found. Uses a thread pool for parallel region file processing.
     *
     * If previousResults is provided, chunks whose region timestamp hasn't changed
     * since the last scan are reused from cache instead of being re-parsed.
     *
     * @param regionDir       path to the world's region/ directory
     * @param previousResults cached results from a prior scan (null for fresh scan)
     * @return map of "chunkX,chunkZ" -> ChunkAnalysis
     */
    public Map<String, ChunkAnalysis> scan(Path regionDir, Map<String, ChunkAnalysis> previousResults) throws IOException {
        if (!Files.isDirectory(regionDir)) {
            throw new IOException("Region directory does not exist: " + regionDir);
        }

        Map<String, ChunkAnalysis> results = new ConcurrentHashMap<>();
        List<Path> regionFiles = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir, "r.*.*.mca")) {
            for (Path file : stream) {
                regionFiles.add(file);
            }
        }

        System.out.println("[ChunkTrimmer] Found " + regionFiles.size() + " region files to scan");

        // Build a lookup of cached chunk timestamps for incremental scanning
        Map<String, Integer> cachedTimestamps = new HashMap<>();
        if (previousResults != null) {
            for (ChunkAnalysis chunk : previousResults.values()) {
                if (chunk.regionTimestamp() != 0) {
                    cachedTimestamps.put(chunk.key(), chunk.regionTimestamp());
                }
            }
        }

        int threadCount = Math.min(regionFiles.size(), Runtime.getRuntime().availableProcessors());
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        AtomicInteger scannedCount = new AtomicInteger(0);
        AtomicInteger skippedChunks = new AtomicInteger(0);
        int totalFiles = regionFiles.size();

        List<Future<?>> futures = new ArrayList<>();

        for (Path regionFile : regionFiles) {
            int[] coords = RegionFileReader.parseRegionCoords(regionFile.getFileName().toString());
            if (coords == null) continue;

            futures.add(executor.submit(() -> {
                try {
                    // Read timestamps from this region file for incremental comparison
                    int[] timestamps = RegionFileReader.readTimestamps(regionFile);

                    RegionFileReader.readRegion(regionFile, coords[0], coords[1], (chunkX, chunkZ, data) -> {
                        int localX = chunkX & 31;
                        int localZ = chunkZ & 31;
                        int timestamp = timestamps[localZ * 32 + localX];

                        String key = chunkX + "," + chunkZ;

                        // Skip if timestamp matches cached value
                        Integer cachedTs = cachedTimestamps.get(key);
                        if (cachedTs != null && cachedTs == timestamp && previousResults.containsKey(key)) {
                            results.put(key, previousResults.get(key));
                            skippedChunks.incrementAndGet();
                            return;
                        }

                        ChunkAnalysis analysis = analyzeChunk(chunkX, chunkZ, data, timestamp);
                        if (analysis != null) {
                            results.put(analysis.key(), analysis);
                        }
                    });
                } catch (IOException e) {
                    System.err.println("[ChunkTrimmer] Warning: Failed to read region file " +
                            regionFile.getFileName() + ": " + e.getMessage());
                }

                int done = scannedCount.incrementAndGet();
                if (done % 50 == 0) {
                    System.out.println("[ChunkTrimmer] Scanned " + done + "/" +
                            totalFiles + " region files (" + results.size() + " chunks)");
                }
            }));
        }

        // Wait for all tasks to complete
        executor.shutdown();
        try {
            executor.awaitTermination(1, TimeUnit.HOURS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Scan interrupted", e);
        }

        if (skippedChunks.get() > 0) {
            System.out.println("[ChunkTrimmer] Incremental scan: " + skippedChunks.get() +
                    " chunks unchanged, " + (results.size() - skippedChunks.get()) + " re-scanned");
        }

        System.out.println("[ChunkTrimmer] Scan complete: " + results.size() +
                " chunks across " + regionFiles.size() + " region files");

        return results;
    }

    /**
     * Analyzes a single chunk from its raw NBT data.
     * Handles both pre-1.18 (Level wrapper) and 1.18+ (flat) formats.
     */
    private ChunkAnalysis analyzeChunk(int chunkX, int chunkZ, CompoundTag data, int regionTimestamp) {
        try {
            int dataVersion = data.getInt("DataVersion");
            boolean is118Plus = dataVersion >= DATA_VERSION_1_18;

            long inhabitedTime = extractInhabitedTime(data, is118Plus);

            return new ChunkAnalysis(chunkX, chunkZ, inhabitedTime, regionTimestamp);
        } catch (Exception e) {
            System.err.println("[ChunkTrimmer] Warning: Failed to analyze chunk (" +
                    chunkX + ", " + chunkZ + "): " + e.getMessage());
            return null;
        }
    }

    private long extractInhabitedTime(CompoundTag data, boolean is118Plus) {
        if (is118Plus) {
            return data.getLong("InhabitedTime");
        } else {
            CompoundTag level = data.getCompoundTag("Level");
            return level != null ? level.getLong("InhabitedTime") : 0;
        }
    }
}
