package dev.danny.chunktrimmer.overlay;

import com.flowpowered.math.vector.Vector2d;
import com.flowpowered.math.vector.Vector3d;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import dev.danny.chunktrimmer.scanner.ChunkAnalysis;
import dev.danny.chunktrimmer.scanner.ScanResult;

/**
 * Creates and manages BlueMap marker overlays for chunk analysis data.
 * Three MarkerSets: heatmap (InhabitedTime), modified (player blocks), selection (for trimming).
 */
public class OverlayManager {

    private static final String HEATMAP_SET_ID = "chunk-trimmer-heatmap";
    private static final String MODIFIED_SET_ID = "chunk-trimmer-modified";
    private static final String SELECTION_SET_ID = "chunk-trimmer-selection";

    private final float overlayY;

    public OverlayManager(float overlayY) {
        this.overlayY = overlayY;
    }

    /**
     * Creates/updates all overlay markers on every map from the given scan result.
     */
    public void update(BlueMapAPI api, ScanResult result) {
        MarkerSet heatmapSet = MarkerSet.builder()
                .label("Inhabited Time")
                .toggleable(true)
                .defaultHidden(true)
                .build();

        MarkerSet modifiedSet = MarkerSet.builder()
                .label("Player Modified")
                .toggleable(true)
                .defaultHidden(true)
                .build();

        // Populate markers from scan data
        int heatmapCount = 0;
        int modifiedCount = 0;

        for (ChunkAnalysis chunk : result.chunks().values()) {
            // Heatmap markers — skip chunks with no activity
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

            // Modified markers — skip chunks with no modification signals
            Color modFill = ModifiedColors.fillColorFor(chunk);
            if (modFill != null) {
                Color modLine = ModifiedColors.lineColorFor(chunk);
                String label = "Chunk " + chunk.chunkX() + ", " + chunk.chunkZ();
                if (chunk.hasPlayerBlocks()) {
                    label += " [player blocks]";
                }
                if (chunk.tileEntityCount() > 0) {
                    label += " [" + chunk.tileEntityCount() + " tile entities]";
                }
                ShapeMarker marker = createChunkMarker(chunk, label, modFill, modLine);
                modifiedSet.put(chunk.key(), marker);
                modifiedCount++;
            }
        }

        // Selection set is always created (empty) — web addon populates it
        MarkerSet selectionSet = MarkerSet.builder()
                .label("Selected for Trimming")
                .toggleable(true)
                .defaultHidden(false)
                .build();

        // Apply to all maps
        for (BlueMapMap map : api.getMaps()) {
            map.getMarkerSets().put(HEATMAP_SET_ID, heatmapSet);
            map.getMarkerSets().put(MODIFIED_SET_ID, modifiedSet);
            map.getMarkerSets().put(SELECTION_SET_ID, selectionSet);
        }

        System.out.println("[ChunkTrimmer] Overlays updated: "
                + heatmapCount + " heatmap, " + modifiedCount + " modified markers");
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
