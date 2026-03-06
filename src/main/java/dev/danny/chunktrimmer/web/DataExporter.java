package dev.danny.chunktrimmer.web;

import de.bluecolored.bluemap.api.BlueMapAPI;
import dev.danny.chunktrimmer.scanner.ChunkAnalysis;
import dev.danny.chunktrimmer.scanner.ScanResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes compact scan data JSON to BlueMap's web root for the web addon to fetch.
 */
public class DataExporter {

    private static final String DATA_PATH = "assets/chunk-trimmer/data.json";

    /**
     * Writes scan results to the web root so the web addon can fetch it.
     */
    public void export(BlueMapAPI api, ScanResult result) {
        Path target = api.getWebApp().getWebRoot().resolve(DATA_PATH);

        try {
            Files.createDirectories(target.getParent());
            byte[] bytes = buildJson(result).getBytes(StandardCharsets.UTF_8);
            Files.write(target, bytes);
            System.out.println("[ChunkTrimmer] Exported scan data (" + bytes.length + " bytes)");
        } catch (IOException e) {
            System.err.println("[ChunkTrimmer] Failed to export scan data: " + e.getMessage());
        }
    }

    /**
     * Builds compact JSON. Uses short keys to minimize file size.
     * Format: { "scanTimestamp": ..., "world": "...", "chunks": { "x,z": { "it": ticks, "te": count, "pb": bool }, ... } }
     */
    private String buildJson(ScanResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"scanTimestamp\":").append(result.scanTimestamp());
        sb.append(",\"world\":\"").append(escapeJson(result.worldName())).append("\"");
        sb.append(",\"chunks\":{");

        boolean first = true;
        for (ChunkAnalysis chunk : result.chunks().values()) {
            // Skip chunks with zero activity — web addon doesn't need them
            if (chunk.inhabitedTime() == 0 && !chunk.hasPlayerBlocks() && chunk.tileEntityCount() == 0) {
                continue;
            }

            if (!first) sb.append(',');
            first = false;

            sb.append("\"").append(chunk.chunkX()).append(",").append(chunk.chunkZ()).append("\":{");
            sb.append("\"it\":").append(chunk.inhabitedTime());
            sb.append(",\"te\":").append(chunk.tileEntityCount());
            sb.append(",\"pb\":").append(chunk.hasPlayerBlocks());
            sb.append('}');
        }

        sb.append("}}");
        return sb.toString();
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
