package dev.danny.chunktrimmer.scanner;

import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.ListTag;
import net.querz.nbt.tag.Tag;

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

    private final BlockClassifier classifier;

    public ChunkScanner(BlockClassifier classifier) {
        this.classifier = classifier;
    }

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

            // Extract InhabitedTime
            long inhabitedTime = extractInhabitedTime(data, is118Plus);

            // Extract block palette data
            Set<String> playerBlockTypes = new HashSet<>();
            boolean hasPlayerBlocks = scanPalettes(data, is118Plus, playerBlockTypes);

            // Count tile entities
            int tileEntityCount = countTileEntities(data, is118Plus);

            return new ChunkAnalysis(
                    chunkX, chunkZ,
                    inhabitedTime,
                    hasPlayerBlocks,
                    playerBlockTypes,
                    tileEntityCount
            );
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

    @SuppressWarnings("unchecked")
    private boolean scanPalettes(CompoundTag data, boolean is118Plus, Set<String> detectedPlayerBlocks) {
        ListTag<?> sections = getSections(data, is118Plus);
        if (sections == null) return false;

        boolean found = false;
        for (Tag<?> sectionTag : sections) {
            if (!(sectionTag instanceof CompoundTag section)) continue;

            ListTag<?> palette = getPalette(section, is118Plus);
            if (palette == null) continue;

            for (Tag<?> entry : palette) {
                if (!(entry instanceof CompoundTag blockEntry)) continue;
                String name = blockEntry.getString("Name");
                if (name != null && classifier.isPlayerBlock(name)) {
                    detectedPlayerBlocks.add(name);
                    found = true;
                }
            }
        }

        return found;
    }

    private ListTag<?> getSections(CompoundTag data, boolean is118Plus) {
        if (is118Plus) {
            return data.getListTag("sections");
        } else {
            CompoundTag level = data.getCompoundTag("Level");
            return level != null ? level.getListTag("Sections") : null;
        }
    }

    private ListTag<?> getPalette(CompoundTag section, boolean is118Plus) {
        if (is118Plus) {
            CompoundTag blockStates = section.getCompoundTag("block_states");
            return blockStates != null ? blockStates.getListTag("palette") : null;
        } else {
            return section.getListTag("Palette");
        }
    }

    private int countTileEntities(CompoundTag data, boolean is118Plus) {
        ListTag<?> entities;
        if (is118Plus) {
            entities = data.getListTag("block_entities");
        } else {
            CompoundTag level = data.getCompoundTag("Level");
            entities = level != null ? level.getListTag("TileEntities") : null;
        }
        return entities != null ? entities.size() : 0;
    }
}
