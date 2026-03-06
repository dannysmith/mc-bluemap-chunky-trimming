package dev.danny.chunktrimmer.overlay;

import de.bluecolored.bluemap.api.math.Color;
import dev.danny.chunktrimmer.scanner.ChunkAnalysis;

/**
 * Determines overlay color for the "Player Modified" layer.
 * Combines player block detection and tile entity count into confidence levels.
 */
public class ModifiedColors {

    /** InhabitedTime threshold (ticks) to confirm a chunk was intentionally visited. */
    private static final long INHABITED_THRESHOLD = 1200; // 1 minute

    /** Tile entity count threshold to consider a chunk modified even without player blocks. */
    private static final int TILE_ENTITY_THRESHOLD = 3;

    // Green: definitely player-modified (keep)
    private static final Color FILL_DEFINITE = new Color(51, 204, 51, 0.45f);
    private static final Color LINE_DEFINITE = new Color(51, 204, 51, 1f);

    // Yellow: possibly modified (uncertain)
    private static final Color FILL_UNCERTAIN = new Color(230, 230, 51, 0.35f);
    private static final Color LINE_UNCERTAIN = new Color(230, 230, 51, 1f);

    /**
     * Returns the fill color for a chunk's modification confidence.
     * Returns null if the chunk has no modification signals (skip rendering).
     */
    public static Color fillColorFor(ChunkAnalysis chunk) {
        return classify(chunk) == Confidence.DEFINITE ? FILL_DEFINITE
                : classify(chunk) == Confidence.UNCERTAIN ? FILL_UNCERTAIN
                : null;
    }

    /**
     * Returns the line color for a chunk's modification confidence.
     * Returns null if no overlay should be drawn.
     */
    public static Color lineColorFor(ChunkAnalysis chunk) {
        return classify(chunk) == Confidence.DEFINITE ? LINE_DEFINITE
                : classify(chunk) == Confidence.UNCERTAIN ? LINE_UNCERTAIN
                : null;
    }

    private enum Confidence { DEFINITE, UNCERTAIN, NONE }

    private static Confidence classify(ChunkAnalysis chunk) {
        if (chunk.hasPlayerBlocks() && chunk.inhabitedTime() > INHABITED_THRESHOLD) {
            return Confidence.DEFINITE;
        }
        if (chunk.hasPlayerBlocks() || chunk.tileEntityCount() > TILE_ENTITY_THRESHOLD) {
            return Confidence.UNCERTAIN;
        }
        return Confidence.NONE;
    }
}
