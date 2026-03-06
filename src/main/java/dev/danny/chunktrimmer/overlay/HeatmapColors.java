package dev.danny.chunktrimmer.overlay;

import de.bluecolored.bluemap.api.math.Color;

/**
 * Maps InhabitedTime (ticks) to a heatmap color.
 * Gradient: cool blue (low) -> teal -> yellow/orange -> red (high).
 */
public class HeatmapColors {

    // Threshold ticks and their corresponding RGBA colors (RGB 0-255, A 0-1)
    private static final long[] THRESHOLDS = {
            0,      // no activity
            1200,   // 1 minute
            12000,  // 10 minutes
            72000,  // 1 hour
    };

    // R, G, B (0-255), A (0.0-1.0)
    private static final int[][] RGB = {
            {77, 128, 230},   // faint cool blue
            {51, 204, 204},   // teal/cyan
            {230, 204, 51},   // yellow/orange
            {230, 51, 26},    // red
    };

    private static final float[] ALPHA = {
            0.25f,  // faint
            0.40f,  // moderate
            0.55f,  // warm
            0.70f,  // hot
    };

    /**
     * Returns a heatmap color for the given InhabitedTime in ticks.
     * Returns null if inhabitedTime is 0 (skip rendering).
     */
    public static Color colorFor(long inhabitedTime) {
        if (inhabitedTime <= 0) return null;

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
        if (inhabitedTime <= 0) return null;

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
