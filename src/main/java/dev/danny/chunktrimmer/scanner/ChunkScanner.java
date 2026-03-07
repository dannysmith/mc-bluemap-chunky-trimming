package dev.danny.chunktrimmer.scanner;

import net.querz.nbt.tag.CompoundTag;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scans Minecraft region files to extract per-chunk analysis data.
 * Handles both pre-1.18 and 1.18+ chunk NBT formats.
 */
public class ChunkScanner {

    // DataVersion 2844 = 1.18 (21w43a) — the snapshot that removed the Level wrapper
    private static final int DATA_VERSION_1_18 = 2844;

    /**
     * Scans all region files in the given directory and returns analysis data
     * for every chunk found. Thread-safe — results are collected into a ConcurrentHashMap.
     *
     * @param regionDir path to the world's region/ directory
     * @return map of "chunkX,chunkZ" -> ChunkAnalysis
     */
    public Map<String, ChunkAnalysis> scan(Path regionDir) throws IOException {
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

        int scanned = 0;
        for (Path regionFile : regionFiles) {
            int[] coords = RegionFileReader.parseRegionCoords(regionFile.getFileName().toString());
            if (coords == null) continue;

            try {
                RegionFileReader.readRegion(regionFile, coords[0], coords[1], (chunkX, chunkZ, data) -> {
                    ChunkAnalysis analysis = analyzeChunk(chunkX, chunkZ, data);
                    if (analysis != null) {
                        results.put(analysis.key(), analysis);
                    }
                });
            } catch (IOException e) {
                System.err.println("[ChunkTrimmer] Warning: Failed to read region file " +
                        regionFile.getFileName() + ": " + e.getMessage());
            }

            scanned++;
            if (scanned % 50 == 0) {
                System.out.println("[ChunkTrimmer] Scanned " + scanned + "/" +
                        regionFiles.size() + " region files (" + results.size() + " chunks)");
            }
        }

        System.out.println("[ChunkTrimmer] Scan complete: " + results.size() +
                " chunks across " + regionFiles.size() + " region files");

        return results;
    }

    /**
     * Analyzes a single chunk from its raw NBT data.
     * Handles both pre-1.18 (Level wrapper) and 1.18+ (flat) formats.
     */
    private ChunkAnalysis analyzeChunk(int chunkX, int chunkZ, CompoundTag data) {
        try {
            int dataVersion = data.getInt("DataVersion");
            boolean is118Plus = dataVersion >= DATA_VERSION_1_18;

            long inhabitedTime = extractInhabitedTime(data, is118Plus);

            return new ChunkAnalysis(chunkX, chunkZ, inhabitedTime);
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
