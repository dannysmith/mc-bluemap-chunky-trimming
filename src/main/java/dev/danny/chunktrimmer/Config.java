package dev.danny.chunktrimmer;

import com.google.gson.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Addon configuration. Stored as JSON in the config directory.
 */
public class Config {

    private static final String CONFIG_FILE = "config.json";

    /** Y level at which to render overlay markers. */
    private float overlayY = 64f;

    public float getOverlayY() { return overlayY; }

    /**
     * Loads config from the given directory, creating a default if none exists.
     */
    public static Config load(Path configDir) {
        Path configFile = configDir.resolve(CONFIG_FILE);

        if (!Files.exists(configFile)) {
            Config defaults = new Config();
            defaults.save(configDir);
            return defaults;
        }

        try (Reader reader = Files.newBufferedReader(configFile)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            Config config = new Config();

            if (root.has("overlayY")) {
                config.overlayY = root.get("overlayY").getAsFloat();
            }

            return config;
        } catch (Exception e) {
            System.err.println("[ChunkTrimmer] Warning: Failed to load config, using defaults: " + e.getMessage());
            return new Config();
        }
    }

    public void save(Path configDir) {
        try {
            Files.createDirectories(configDir);
            Path configFile = configDir.resolve(CONFIG_FILE);

            JsonObject root = new JsonObject();
            root.addProperty("overlayY", overlayY);

            try (Writer writer = Files.newBufferedWriter(configFile)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(root, writer);
            }
        } catch (IOException e) {
            System.err.println("[ChunkTrimmer] Warning: Failed to save config: " + e.getMessage());
        }
    }
}
