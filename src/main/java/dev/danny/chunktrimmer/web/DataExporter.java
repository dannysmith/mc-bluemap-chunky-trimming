package dev.danny.chunktrimmer.web;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapWorld;
import dev.danny.chunktrimmer.scanner.ChunkAnalysis;
import dev.danny.chunktrimmer.scanner.ScanResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Writes compact scan data JSON to BlueMap's web root for the web addon to fetch.
 * Data is keyed by world ID so the web addon can filter per dimension.
 */
public class DataExporter {

    private static final String DATA_PATH = "assets/chunk-trimmer/data.json";

    /**
     * Writes scan results for all worlds to the web root.
     */
    public void export(BlueMapAPI api, Map<BlueMapWorld, ScanResult> results) {
        Path target = api.getWebApp().getWebRoot().resolve(DATA_PATH);

        try {
            Files.createDirectories(target.getParent());
            byte[] bytes = buildJson(results).getBytes(StandardCharsets.UTF_8);
            Files.write(target, bytes);
            System.out.println("[ChunkTrimmer] Exported scan data (" + bytes.length + " bytes, " +
                    results.size() + " world(s))");
        } catch (IOException e) {
            System.err.println("[ChunkTrimmer] Failed to export scan data: " + e.getMessage());
        }
    }

    /**
     * Builds compact JSON with per-world data.
     * Format: { "worlds": { "worldId": { "name": "...", "scanTimestamp": ..., "chunks": { ... } }, ... } }
     */
    private String buildJson(Map<BlueMapWorld, ScanResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"worlds\":{");

        boolean firstWorld = true;
        for (Map.Entry<BlueMapWorld, ScanResult> entry : results.entrySet()) {
            BlueMapWorld world = entry.getKey();
            ScanResult result = entry.getValue();

            if (!firstWorld) sb.append(',');
            firstWorld = false;

            sb.append("\"").append(escapeJson(world.getId())).append("\":{");
            sb.append("\"name\":\"").append(escapeJson(result.worldName())).append("\"");
            sb.append(",\"scanTimestamp\":").append(result.scanTimestamp());
            sb.append(",\"chunks\":{");

            boolean firstChunk = true;
            for (ChunkAnalysis chunk : result.chunks().values()) {
                // Skip chunks with zero activity — web addon doesn't need them
                if (chunk.inhabitedTime() == 0 && !chunk.hasPlayerBlocks() && chunk.tileEntityCount() == 0) {
                    continue;
                }

                if (!firstChunk) sb.append(',');
                firstChunk = false;

                sb.append("\"").append(chunk.chunkX()).append(",").append(chunk.chunkZ()).append("\":{");
                sb.append("\"it\":").append(chunk.inhabitedTime());
                sb.append(",\"te\":").append(chunk.tileEntityCount());
                sb.append(",\"pb\":").append(chunk.hasPlayerBlocks());
                sb.append('}');
            }

            sb.append("}}");
        }

        sb.append("}}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
