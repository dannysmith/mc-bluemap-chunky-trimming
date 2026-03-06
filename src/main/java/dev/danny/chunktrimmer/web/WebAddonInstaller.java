package dev.danny.chunktrimmer.web;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.WebApp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Copies web addon resources (JS/CSS) from the JAR to BlueMap's web root
 * and registers them with the WebApp API.
 */
public class WebAddonInstaller {

    private static final String JS_RESOURCE = "/web/js/chunk-trimmer.js";
    private static final String CSS_RESOURCE = "/web/css/chunk-trimmer.css";

    private static final String JS_ASSET = "assets/chunk-trimmer/chunk-trimmer.js";
    private static final String CSS_ASSET = "assets/chunk-trimmer/chunk-trimmer.css";

    /**
     * Copies JS/CSS to web root and registers them.
     */
    public static void install(BlueMapAPI api) {
        WebApp webApp = api.getWebApp();
        Path webRoot = webApp.getWebRoot();

        try {
            copyResource(JS_RESOURCE, webRoot.resolve(JS_ASSET));
            copyResource(CSS_RESOURCE, webRoot.resolve(CSS_ASSET));

            webApp.registerScript(JS_ASSET);
            webApp.registerStyle(CSS_ASSET);

            System.out.println("[ChunkTrimmer] Web addon installed");
        } catch (IOException e) {
            System.err.println("[ChunkTrimmer] Failed to install web addon: " + e.getMessage());
        }
    }

    private static void copyResource(String resourcePath, Path targetPath) throws IOException {
        Files.createDirectories(targetPath.getParent());
        try (InputStream in = WebAddonInstaller.class.getResourceAsStream(resourcePath);
             OutputStream out = Files.newOutputStream(targetPath)) {
            if (in == null) {
                throw new IOException("Resource not found in JAR: " + resourcePath);
            }
            in.transferTo(out);
        }
    }
}
