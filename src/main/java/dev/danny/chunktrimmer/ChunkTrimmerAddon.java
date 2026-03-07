package dev.danny.chunktrimmer;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapWorld;
import dev.danny.chunktrimmer.overlay.OverlayManager;
import dev.danny.chunktrimmer.scanner.ChunkAnalysis;
import dev.danny.chunktrimmer.scanner.ChunkScanner;
import dev.danny.chunktrimmer.scanner.ScanCache;
import dev.danny.chunktrimmer.scanner.ScanResult;
import dev.danny.chunktrimmer.web.DataExporter;
import dev.danny.chunktrimmer.web.WebAddonInstaller;
import net.querz.nbt.io.NBTDeserializer;
import net.querz.nbt.tag.CompoundTag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * BlueMap native addon entrypoint.
 * Scans Minecraft region files for all worlds and creates per-dimension chunk analysis overlays.
 */
public class ChunkTrimmerAddon implements Runnable {

    private static final String ADDON_ID = "chunk-trimmer";

    @Override
    public void run() {
        BlueMapAPI.onEnable(this::onEnable);
        BlueMapAPI.onDisable(this::onDisable);
    }

    private void onEnable(BlueMapAPI api) {
        System.out.println("[ChunkTrimmer] Addon enabled");

        Path blueMapRoot = api.getWebApp().getWebRoot().getParent();
        Path configDir = blueMapRoot.resolve("addons").resolve(ADDON_ID);

        Config config = Config.load(configDir);
        ScanCache cache = new ScanCache(configDir);
        OverlayManager overlays = new OverlayManager(config.getOverlayY());
        DataExporter exporter = new DataExporter();

        // Install web addon (JS/CSS)
        WebAddonInstaller.install(api);

        // Load cached results immediately (fast) — maps world IDs back to BlueMapWorlds
        Map<String, ScanResult> cachedByKey = cache.loadAll();
        if (!cachedByKey.isEmpty()) {
            Map<BlueMapWorld, ScanResult> cached = resolveWorlds(api, cachedByKey);
            if (!cached.isEmpty()) {
                for (Map.Entry<BlueMapWorld, ScanResult> entry : cached.entrySet()) {
                    logScanSummary(entry.getKey().getId(), entry.getValue());
                }
                overlays.update(api, cached);
                exporter.export(api, cached);
            }
        }

        // Kick off background rescan of all worlds
        Thread scanThread = new Thread(() -> {
            try {
                Map<BlueMapWorld, Path> worldRegions = resolveAllRegionDirs(api);
                if (worldRegions.isEmpty()) {
                    System.err.println("[ChunkTrimmer] No world region directories found");
                    return;
                }

                System.out.println("[ChunkTrimmer] Starting background scan of " +
                        worldRegions.size() + " world(s)");
                long startTime = System.currentTimeMillis();

                ChunkScanner scanner = new ChunkScanner();
                Map<BlueMapWorld, ScanResult> results = new LinkedHashMap<>();

                for (Map.Entry<BlueMapWorld, Path> entry : worldRegions.entrySet()) {
                    BlueMapWorld world = entry.getKey();
                    Path regionDir = entry.getValue();

                    System.out.println("[ChunkTrimmer] Scanning world '" + world.getId() +
                            "' at " + regionDir);

                    Map<String, ChunkAnalysis> chunks = scanner.scan(regionDir);
                    Long seed = readWorldSeed(regionDir.getParent());

                    ScanResult result = new ScanResult(
                            regionDir.getParent().getFileName().toString(),
                            System.currentTimeMillis(),
                            seed,
                            chunks
                    );

                    cache.save(world.getId(), result);
                    results.put(world, result);
                    logScanSummary(world.getId(), result);
                }

                long elapsed = System.currentTimeMillis() - startTime;
                System.out.println("[ChunkTrimmer] Background scan complete in " + elapsed + "ms");

                overlays.update(api, results);
                exporter.export(api, results);

            } catch (IOException e) {
                System.err.println("[ChunkTrimmer] Scan failed: " + e.getMessage());
                e.printStackTrace();
            }
        }, "ChunkTrimmer-Scanner");
        scanThread.setDaemon(true);
        scanThread.start();
    }

    private void onDisable(BlueMapAPI api) {
        System.out.println("[ChunkTrimmer] Addon disabled");
    }

    /**
     * Reads the world seed from level.dat.
     * Checks the given directory first, then its parent (handles DIM-1/DIM1 subdirs).
     */
    private Long readWorldSeed(Path saveDir) {
        for (Path dir = saveDir; dir != null; dir = dir.getParent()) {
            Path levelDat = dir.resolve("level.dat");
            if (Files.isRegularFile(levelDat)) {
                try {
                    CompoundTag root = (CompoundTag) new NBTDeserializer(true)
                            .fromFile(levelDat.toFile()).getTag();
                    CompoundTag data = root.getCompoundTag("Data");
                    if (data == null) return null;
                    // 1.16+: Data.WorldGenSettings.seed
                    CompoundTag wgs = data.getCompoundTag("WorldGenSettings");
                    if (wgs != null && wgs.containsKey("seed")) {
                        return wgs.getLong("seed");
                    }
                    // Older: Data.RandomSeed
                    if (data.containsKey("RandomSeed")) {
                        return data.getLong("RandomSeed");
                    }
                } catch (Exception e) {
                    System.err.println("[ChunkTrimmer] Warning: Could not read seed from " +
                            levelDat + ": " + e.getMessage());
                }
                return null;
            }
            // Don't traverse above two levels (saveDir and its parent)
            if (!dir.equals(saveDir)) break;
        }
        return null;
    }

    /**
     * Discovers region directories for all BlueMap worlds.
     */
    @SuppressWarnings("deprecation")
    private Map<BlueMapWorld, Path> resolveAllRegionDirs(BlueMapAPI api) {
        Map<BlueMapWorld, Path> result = new LinkedHashMap<>();

        for (BlueMapWorld world : api.getWorlds()) {
            try {
                Path saveFolder = world.getSaveFolder();
                Path regionDir = saveFolder.resolve("region");
                if (Files.isDirectory(regionDir)) {
                    result.put(world, regionDir);
                    System.out.println("[ChunkTrimmer] Found region dir for '" + world.getId() +
                            "': " + regionDir);
                }
            } catch (Exception e) {
                System.err.println("[ChunkTrimmer] Warning: Could not access save folder for world '" +
                        world.getId() + "': " + e.getMessage());
            }
        }

        return result;
    }

    /**
     * Maps cached ScanResults (keyed by world ID string) back to BlueMapWorld objects.
     */
    private Map<BlueMapWorld, ScanResult> resolveWorlds(BlueMapAPI api, Map<String, ScanResult> cached) {
        Map<BlueMapWorld, ScanResult> resolved = new LinkedHashMap<>();

        for (BlueMapWorld world : api.getWorlds()) {
            ScanResult result = cached.get(world.getId());
            if (result != null) {
                resolved.put(world, result);
            }
        }

        return resolved;
    }

    private void logScanSummary(String worldId, ScanResult result) {
        System.out.println("[ChunkTrimmer] World '" + worldId + "': " +
                result.totalChunks() + " chunks, " +
                result.chunksWithActivity() + " with activity");
    }
}
