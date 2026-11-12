package dev.atlasclient.core.analysis;

/**
 * Distance presets for Base Finder and ESP. They are upper limits, not promises: the client only has
 * the chunks and entities the server sends (its view distance and entity tracking range), and
 * nothing beyond that is ever requested or reconstructed.
 */
public enum RangePreset {
    SMALL("Small (64)", 64),
    MEDIUM("Medium (128)", 128),
    LARGE("Large (256)", 256),
    EXTREME("Extreme (512)", 512),
    ULTRA("Ultra (1024)", 1024),
    MAXIMUM("Maximum (all loaded)", -1);

    private final String label;
    private final int blocks;

    RangePreset(String label, int blocks) {
        this.label = label;
        this.blocks = blocks;
    }

    public String label() { return label; }

    /** Distance in blocks, or -1 for "everything available". */
    public int blocks() { return blocks; }

    /** Scan radius in chunks, never more than what is loaded. */
    public int chunks(int loadedRadiusChunks) {
        if (blocks < 0) return loadedRadiusChunks;
        return Math.max(1, Math.min(loadedRadiusChunks, (blocks + 15) / 16));
    }

    /** Draw / list distance in blocks (remembered finds can be farther than loaded chunks). */
    public double distance() { return blocks < 0 ? Double.MAX_VALUE : blocks; }

    /** Effective distance given what is loaded right now. */
    public int effectiveBlocks(int loadedRadiusChunks) {
        int loaded = loadedRadiusChunks * 16;
        return blocks < 0 ? loaded : Math.min(blocks, loaded);
    }

    @Override public String toString() { return label; }
}
