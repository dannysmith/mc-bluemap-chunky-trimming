package dev.danny.chunktrimmer;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapWorld;
import dev.danny.chunktrimmer.overlay.OverlayManager;
import dev.danny.chunktrimmer.scanner.*;
import dev.danny.chunktrimmer.web.DataExporter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * BlueMap native addon entrypoint.
 * Scans Minecraft region files and creates chunk analysis overlays.
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

        // Resolve config directory alongside the addon in BlueMap's config area
        // Use the web root's parent as a base — typically <server>/bluemap/
        Path blueMapRoot = api.getWebApp().getWebRoot().getParent();
        Path configDir = blueMapRoot.resolve("addons").resolve(ADDON_ID);

        Config config = Config.load(configDir);
        ScanCache cache = new ScanCache(configDir);
        BlockClassifier classifier = new BlockClassifier();
        OverlayManager overlays = new OverlayManager(config.getOverlayY());
        DataExporter exporter = new DataExporter();

        // Load cached results immediately (fast)
        ScanResult cachedResult = cache.load();
        if (cachedResult != null) {
            logScanSummary(cachedResult);
            overlays.update(api, cachedResult);
            exporter.export(api, cachedResult);
        }

        // Kick off background rescan
        Thread scanThread = new Thread(() -> {
            try {
                Path regionDir = resolveRegionDir(api, config);
                if (regionDir == null) {
                    System.err.println("[ChunkTrimmer] Could not find world region directory. " +
                            "Set 'worldRegionPath' in " + configDir.resolve("config.json"));
                    return;
                }

                System.out.println("[ChunkTrimmer] Starting background scan of " + regionDir);
                long startTime = System.currentTimeMillis();

                ChunkScanner scanner = new ChunkScanner(classifier);
                Map<String, ChunkAnalysis> chunks = scanner.scan(regionDir);

                ScanResult result = new ScanResult(
                        regionDir.getParent().getFileName().toString(),
                        System.currentTimeMillis(),
                        chunks
                );

                cache.save(result);

                long elapsed = System.currentTimeMillis() - startTime;
                System.out.println("[ChunkTrimmer] Background scan complete in " + elapsed + "ms");
                logScanSummary(result);

                overlays.update(api, result);
                exporter.export(api, result);

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
     * Resolves the world's region/ directory.
     * Priority: config override > auto-detect from BlueMap worlds.
     */
    @SuppressWarnings("deprecation")
    private Path resolveRegionDir(BlueMapAPI api, Config config) {
        // 1. Config override
        String configPath = config.getWorldRegionPath();
        if (configPath != null && !configPath.isBlank()) {
            Path p = Path.of(configPath);
            if (Files.isDirectory(p)) return p;
            System.err.println("[ChunkTrimmer] Configured region path not found: " + configPath);
        }

        // 2. Auto-detect from BlueMap's world list
        for (BlueMapWorld world : api.getWorlds()) {
            try {
                Path saveFolder = world.getSaveFolder();
                Path regionDir = saveFolder.resolve("region");
                if (Files.isDirectory(regionDir)) {
                    System.out.println("[ChunkTrimmer] Auto-detected region dir: " + regionDir);
                    return regionDir;
                }
            } catch (Exception e) {
                // getSaveFolder() may throw if world has no save folder
            }
        }

        return null;
    }

    private void logScanSummary(ScanResult result) {
        System.out.println("[ChunkTrimmer] World: " + result.worldName());
        System.out.println("[ChunkTrimmer]   Total chunks: " + result.totalChunks());
        System.out.println("[ChunkTrimmer]   Chunks with activity: " + result.chunksWithActivity());
        System.out.println("[ChunkTrimmer]   Chunks with player blocks: " + result.chunksWithPlayerBlocks());
    }
}
