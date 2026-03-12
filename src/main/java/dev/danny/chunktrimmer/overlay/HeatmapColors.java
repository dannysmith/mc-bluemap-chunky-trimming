package dev.danny.chunktrimmer.overlay;

import de.bluecolored.bluemap.api.math.Color;

/**
 * Maps InhabitedTime (ticks) to a heatmap color.
 * Gradient: blue (cold, low alpha) -> teal -> yellow -> orange -> red (hot).
 * Chunks under 1 minute are not rendered.
 */
public class HeatmapColors {

    /** Minimum inhabited time (ticks) to render. Below this, chunks are transparent. */
    private static final long MIN_TICKS = 1200; // 1 minute

    // Threshold ticks and their corresponding RGBA colors (RGB 0-255, A 0-1)
    private static final long[] THRESHOLDS = {
            1200,   // 1 minute
            6000,   // 5 minutes
            12000,  // 10 minutes
            36000,  // 30 minutes
            72000,  // 1 hour
            216000, // 3 hours
            720000, // 10 hours
    };

    // R, G, B (0-255), A (0.0-1.0)
    // Blue with increasing alpha for cold range, then color shift through teal/yellow/orange/red
    private static final int[][] RGB = {
            {77, 128, 230},   // blue (just walked through)
            {77, 128, 230},   // blue (brief pass-through)
            {77, 128, 230},   // blue (lingered a bit)
            {51, 204, 204},   // teal/cyan (actually spent time here)
            {230, 204, 51},   // yellow/amber (proper activity)
            {230, 120, 26},   // orange (significant investment)
            {204, 30, 20},    // deep red (base / major build)
    };

    private static final float[] ALPHA = {
            0.12f,  // barely visible
            0.18f,  // faint
            0.25f,  // noticeable
            0.33f,  // moderate
            0.42f,  // warm
            0.52f,  // strong
            0.60f,  // hot
    };

    /**
     * Returns a heatmap color for the given InhabitedTime in ticks.
     * Returns null if inhabitedTime is below the minimum threshold (skip rendering).
     */
    public static Color colorFor(long inhabitedTime) {
        if (inhabitedTime < MIN_TICKS) return null;

        // Clamp to last threshold
        if (inhabitedTime >= THRESHOLDS[THRESHOLDS.length - 1]) {
            int[] c = RGB[RGB.length - 1];
            return new Color(c[0], c[1], c[2], ALPHA[ALPHA.length - 1]);
        }

        // Find the bracket and interpolate
        for (int i = 0; i < THRESHOLDS.length - 1; i++) {
            if (inhabitedTime < THRESHOLDS[i + 1]) {
                float t = (float) (inhabitedTime - THRESHOLDS[i]) / (THRESHOLDS[i + 1] - THRESHOLDS[i]);
                int[] a = RGB[i];
                int[] b = RGB[i + 1];
                return new Color(
                        lerp(a[0], b[0], t),
                        lerp(a[1], b[1], t),
                        lerp(a[2], b[2], t),
                        lerpf(ALPHA[i], ALPHA[i + 1], t)
                );
            }
        }

        int[] c = RGB[RGB.length - 1];
        return new Color(c[0], c[1], c[2], ALPHA[ALPHA.length - 1]);
    }

    /**
     * Returns a line (border) color — same hue but fully opaque.
     */
    public static Color lineColorFor(long inhabitedTime) {
        if (inhabitedTime < MIN_TICKS) return null;

        if (inhabitedTime >= THRESHOLDS[THRESHOLDS.length - 1]) {
            int[] c = RGB[RGB.length - 1];
            return new Color(c[0], c[1], c[2], 1f);
        }

        for (int i = 0; i < THRESHOLDS.length - 1; i++) {
            if (inhabitedTime < THRESHOLDS[i + 1]) {
                float t = (float) (inhabitedTime - THRESHOLDS[i]) / (THRESHOLDS[i + 1] - THRESHOLDS[i]);
                int[] a = RGB[i];
                int[] b = RGB[i + 1];
                return new Color(
                        lerp(a[0], b[0], t),
                        lerp(a[1], b[1], t),
                        lerp(a[2], b[2], t),
                        1f
                );
            }
        }

        int[] c = RGB[RGB.length - 1];
        return new Color(c[0], c[1], c[2], 1f);
    }

    private static int lerp(int a, int b, float t) {
        return Math.round(a + (b - a) * t);
    }

    private static float lerpf(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
