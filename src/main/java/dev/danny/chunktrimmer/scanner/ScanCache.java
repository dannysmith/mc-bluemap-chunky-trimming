package dev.danny.chunktrimmer.scanner;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Persists scan results to JSON for fast reload without re-scanning.
 * Stores one cache file per world: scan-cache-{worldId}.json
 */
public class ScanCache {

    private static final String CACHE_PREFIX = "scan-cache-";
    private static final String CACHE_SUFFIX = ".json";
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(ChunkAnalysis.class, new ChunkAnalysisAdapter())
            .create();

    private final Path cacheDir;

    public ScanCache(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    /**
     * Loads all cached scan results, keyed by world ID.
     */
    public Map<String, ScanResult> loadAll() {
        Map<String, ScanResult> results = new HashMap<>();
        if (!Files.isDirectory(cacheDir)) return results;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(cacheDir, CACHE_PREFIX + "*" + CACHE_SUFFIX)) {
            for (Path file : stream) {
                String filename = file.getFileName().toString();
                String worldId = filename.substring(CACHE_PREFIX.length(), filename.length() - CACHE_SUFFIX.length());
                ScanResult result = loadFile(file);
                if (result != null) {
                    results.put(worldId, result);
                }
            }
        } catch (IOException e) {
            System.err.println("[ChunkTrimmer] Warning: Failed to scan cache directory: " + e.getMessage());
        }

        if (!results.isEmpty()) {
            System.out.println("[ChunkTrimmer] Loaded cached scans for " + results.size() + " world(s)");
        }
        return results;
    }

    /**
     * Saves a scan result for a specific world.
     */
    public void save(String worldId, ScanResult result) {
        try {
            Files.createDirectories(cacheDir);
            Path cacheFile = cacheDir.resolve(CACHE_PREFIX + worldId + CACHE_SUFFIX);

            JsonObject root = new JsonObject();
            root.addProperty("worldName", result.worldName());
            root.addProperty("scanTimestamp", result.scanTimestamp());
            if (result.seed() != null) root.addProperty("seed", result.seed());
            root.add("chunks", GSON.toJsonTree(result.chunks()));

            try (Writer writer = Files.newBufferedWriter(cacheFile)) {
                GSON.toJson(root, writer);
            }

            System.out.println("[ChunkTrimmer] Saved cache for world '" + worldId + "': " +
                    result.totalChunks() + " chunks");
        } catch (IOException e) {
            System.err.println("[ChunkTrimmer] Warning: Failed to save scan cache for " + worldId + ": " + e.getMessage());
        }
    }

    private ScanResult loadFile(Path cacheFile) {
        try (Reader reader = Files.newBufferedReader(cacheFile)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

            String worldName = root.get("worldName").getAsString();
            long scanTimestamp = root.get("scanTimestamp").getAsLong();
            Long seed = root.has("seed") ? root.get("seed").getAsLong() : null;

            Type mapType = new TypeToken<Map<String, ChunkAnalysis>>() {}.getType();
            Map<String, ChunkAnalysis> chunks = GSON.fromJson(root.get("chunks"), mapType);
            if (chunks == null) chunks = new HashMap<>();

            return new ScanResult(worldName, scanTimestamp, seed, chunks);
        } catch (Exception e) {
            System.err.println("[ChunkTrimmer] Warning: Failed to load cache file " +
                    cacheFile.getFileName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Compact JSON adapter for ChunkAnalysis — uses short keys to minimize file size.
     */
    private static class ChunkAnalysisAdapter implements JsonSerializer<ChunkAnalysis>, JsonDeserializer<ChunkAnalysis> {

        @Override
        public JsonElement serialize(ChunkAnalysis src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject obj = new JsonObject();
            obj.addProperty("x", src.chunkX());
            obj.addProperty("z", src.chunkZ());
            obj.addProperty("it", src.inhabitedTime());
            obj.addProperty("pb", src.hasPlayerBlocks());
            obj.addProperty("te", src.tileEntityCount());

            if (!src.playerBlockTypes().isEmpty()) {
                JsonArray types = new JsonArray();
                for (String type : src.playerBlockTypes()) {
                    // Strip "minecraft:" prefix for compactness
                    types.add(type.startsWith("minecraft:") ? type.substring(10) : type);
                }
                obj.add("pbt", types);
            }

            return obj;
        }

        @Override
        public ChunkAnalysis deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
                throws JsonParseException {
            JsonObject obj = json.getAsJsonObject();

            Set<String> playerBlockTypes = new HashSet<>();
            if (obj.has("pbt")) {
                for (JsonElement el : obj.getAsJsonArray("pbt")) {
                    String name = el.getAsString();
                    playerBlockTypes.add(name.contains(":") ? name : "minecraft:" + name);
                }
            }

            return new ChunkAnalysis(
                    obj.get("x").getAsInt(),
                    obj.get("z").getAsInt(),
                    obj.get("it").getAsLong(),
                    obj.get("pb").getAsBoolean(),
                    playerBlockTypes,
                    obj.get("te").getAsInt()
            );
        }
    }
}
