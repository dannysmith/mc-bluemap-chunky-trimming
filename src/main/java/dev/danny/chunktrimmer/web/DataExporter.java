package dev.danny.chunktrimmer.web;

import com.google.gson.JsonObject;
import de.bluecolored.bluemap.api.AssetStorage;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import dev.danny.chunktrimmer.scanner.ChunkAnalysis;
import dev.danny.chunktrimmer.scanner.ScanResult;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Writes compact scan data JSON to BlueMap's AssetStorage for the web addon to fetch.
 */
public class DataExporter {

    private static final String ASSET_NAME = "chunk-trimmer-data.json";

    /**
     * Writes scan results to every map's AssetStorage.
     */
    public void export(BlueMapAPI api, ScanResult result) {
        String json = buildJson(result);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        int mapCount = 0;
        for (BlueMapMap map : api.getMaps()) {
            AssetStorage storage = map.getAssetStorage();
            try (OutputStream out = storage.writeAsset(ASSET_NAME)) {
                out.write(bytes);
                mapCount++;
            } catch (IOException e) {
                System.err.println("[ChunkTrimmer] Failed to write data to map "
                        + map.getName() + ": " + e.getMessage());
            }
        }

        System.out.println("[ChunkTrimmer] Exported scan data to " + mapCount + " map(s) ("
                + bytes.length + " bytes)");
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
