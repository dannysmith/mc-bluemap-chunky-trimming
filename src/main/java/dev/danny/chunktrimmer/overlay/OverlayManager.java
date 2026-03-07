package dev.danny.chunktrimmer.overlay;

import com.flowpowered.math.vector.Vector2d;
import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import dev.danny.chunktrimmer.scanner.ChunkAnalysis;
import dev.danny.chunktrimmer.scanner.ScanResult;

import java.util.Map;

/**
 * Creates and manages BlueMap marker overlays for chunk analysis data.
 * Renders an InhabitedTime heatmap MarkerSet per world.
 * Each map only receives markers for its own world.
 */
public class OverlayManager {

    private static final String HEATMAP_SET_ID = "chunk-trimmer-heatmap";
    private final float overlayY;

    public OverlayManager(float overlayY) {
        this.overlayY = overlayY;
    }

    /**
     * Creates/updates overlay markers, filtered per world.
     * Each BlueMapMap only receives markers from the ScanResult matching its world.
     */
    public void update(BlueMapAPI api, Map<BlueMapWorld, ScanResult> results) {
        int totalHeatmap = 0;

        for (Map.Entry<BlueMapWorld, ScanResult> entry : results.entrySet()) {
            BlueMapWorld world = entry.getKey();
            ScanResult result = entry.getValue();

            MarkerSet heatmapSet = MarkerSet.builder()
                    .label("\uD83D\uDCCA Inhabited Time")
                    .toggleable(true)
                    .defaultHidden(true)
                    .sorting(1000)
                    .build();

            int heatmapCount = 0;

            for (ChunkAnalysis chunk : result.chunks().values()) {
                Color heatFill = HeatmapColors.colorFor(chunk.inhabitedTime());
                if (heatFill != null) {
                    Color heatLine = HeatmapColors.lineColorFor(chunk.inhabitedTime());
                    ShapeMarker marker = createChunkMarker(
                            chunk,
                            "Chunk " + chunk.chunkX() + ", " + chunk.chunkZ()
                                    + " (" + chunk.inhabitedTimeFormatted() + ")",
                            heatFill,
                            heatLine
                    );
                    heatmapSet.put(chunk.key(), marker);
                    heatmapCount++;
                }
            }

            for (BlueMapMap map : world.getMaps()) {
                map.getMarkerSets().put(HEATMAP_SET_ID, heatmapSet);
            }

            totalHeatmap += heatmapCount;

            System.out.println("[ChunkTrimmer] World '" + world.getId() + "': " +
                    heatmapCount + " heatmap markers");
        }

        System.out.println("[ChunkTrimmer] Overlays updated: " +
                totalHeatmap + " heatmap markers across " +
                results.size() + " world(s)");
    }

    private ShapeMarker createChunkMarker(ChunkAnalysis chunk, String label,
                                          Color fillColor, Color lineColor) {
        int x1 = chunk.chunkX() * 16;
        int z1 = chunk.chunkZ() * 16;
        int x2 = x1 + 16;
        int z2 = z1 + 16;

        Shape rect = Shape.createRect(
                new Vector2d(x1, z1),
                new Vector2d(x2, z2)
        );

        return ShapeMarker.builder()
                .label(label)
                .position(new Vector3d((x1 + x2) / 2.0, overlayY, (z1 + z2) / 2.0))
                .shape(rect, overlayY)
                .fillColor(fillColor)
                .lineColor(lineColor)
                .lineWidth(1)
                .depthTestEnabled(false)
                .build();
    }
}
