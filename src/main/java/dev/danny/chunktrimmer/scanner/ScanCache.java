package dev.danny.chunktrimmer.scanner;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Persists scan results to JSON for fast reload without re-scanning.
 */
public class ScanCache {

    private static final String CACHE_FILE = "scan-cache.json";
    private static final Gson GSON = new GsonBuilder()
            .registerTypeAdapter(ChunkAnalysis.class, new ChunkAnalysisAdapter())
            .create();

    private final Path cacheDir;

    public ScanCache(Path cacheDir) {
        this.cacheDir = cacheDir;
    }

    public ScanResult load() {
        Path cacheFile = cacheDir.resolve(CACHE_FILE);
        if (!Files.exists(cacheFile)) return null;

        try (Reader reader = Files.newBufferedReader(cacheFile)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

            String worldName = root.get("worldName").getAsString();
            long scanTimestamp = root.get("scanTimestamp").getAsLong();

            Type mapType = new TypeToken<Map<String, ChunkAnalysis>>() {}.getType();
            Map<String, ChunkAnalysis> chunks = GSON.fromJson(root.get("chunks"), mapType);
            if (chunks == null) chunks = new HashMap<>();

            System.out.println("[ChunkTrimmer] Loaded cached scan: " + chunks.size() + " chunks");
            return new ScanResult(worldName, scanTimestamp, chunks);
        } catch (Exception e) {
            System.err.println("[ChunkTrimmer] Warning: Failed to load scan cache: " + e.getMessage());
            return null;
        }
    }

    public void save(ScanResult result) {
        try {
            Files.createDirectories(cacheDir);
            Path cacheFile = cacheDir.resolve(CACHE_FILE);

            JsonObject root = new JsonObject();
            root.addProperty("worldName", result.worldName());
            root.addProperty("scanTimestamp", result.scanTimestamp());
            root.add("chunks", GSON.toJsonTree(result.chunks()));

            try (Writer writer = Files.newBufferedWriter(cacheFile)) {
                GSON.toJson(root, writer);
            }

            System.out.println("[ChunkTrimmer] Saved scan cache: " + result.totalChunks() + " chunks");
        } catch (IOException e) {
            System.err.println("[ChunkTrimmer] Warning: Failed to save scan cache: " + e.getMessage());
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
