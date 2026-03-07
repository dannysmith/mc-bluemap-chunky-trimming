package dev.danny.chunktrimmer.scanner;

/**
 * Analysis data for a single chunk.
 */
public record ChunkAnalysis(
        int chunkX,
        int chunkZ,
        long inhabitedTime,
        int regionTimestamp
) {
    /** InhabitedTime converted to seconds. */
    public double inhabitedSeconds() {
        return inhabitedTime / 20.0;
    }

    /** InhabitedTime converted to minutes. */
    public double inhabitedMinutes() {
        return inhabitedTime / 1200.0;
    }

    /** InhabitedTime converted to hours. */
    public double inhabitedHours() {
        return inhabitedTime / 72000.0;
    }

    /** Human-readable inhabited time string. */
    public String inhabitedTimeFormatted() {
        double seconds = inhabitedSeconds();
        if (seconds < 60) return String.format("%.0fs", seconds);
        double minutes = inhabitedMinutes();
        if (minutes < 60) return String.format("%.1fm", minutes);
        return String.format("%.1fh", inhabitedHours());
    }

    /** Unique key for this chunk, suitable for map keys. */
    public String key() {
        return chunkX + "," + chunkZ;
    }
}
