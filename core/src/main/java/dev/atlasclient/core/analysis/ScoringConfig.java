package dev.atlasclient.core.analysis;

import java.util.EnumMap;
import java.util.Map;

/**
 * Tunable weights and thresholds for {@link SuspicionScorer}. Instances are immutable snapshots so
 * a background analysis thread never sees a half-updated configuration.
 */
public final class ScoringConfig {
    /** What to do with evidence in chunks where a generated structure (village, mineshaft...) is recognised. */
    public enum StructureHandling {
        LOWER("Lower its confidence"), IGNORE("Ignore it"), COUNT("Count it like anything else");

        private final String label;

        StructureHandling(String label) { this.label = label; }

        public String label() { return label; }

        @Override public String toString() { return label; }
    }

    private final Map<Indicator, Double> weights;
    private final Map<Indicator, Integer> baselines;
    public final double amethystWeight;
    public final double plantGrowthWeight;
    public final double arrangementWeight;
    /** Natural share of fully grown amethyst clusters among buds right after world generation. */
    public final double naturalClusterShare;
    /** Natural share of mature cocoa pods right after world generation (ages 0-2 uniformly). */
    public final double naturalCocoaMatureShare;
    /** Kelp columns longer than this cannot come from world generation alone. */
    public final int kelpWorldgenMaxHeight;
    /**
     * Vine columns longer than this are unusual for world generation. Leaf decorators hang vines up
     * to 4 blocks; jungle trunk vines can occasionally form longer runs, hence the margin.
     */
    public final int vineWorldgenMaxLength;
    public final int minGrowthSamples;
    public final int minRunLength;
    public final double confounderDamping;
    /** Multiplier for evidence in recognised generated structures (blocks world generation cannot make are exempt). */
    public final double structureDamping;
    public final StructureHandling structureHandling;
    /** Multipliers for the shape (underground rooms, tunnels...) and layout (walls, spacing, symmetry...) evidence. */
    public final double geometryWeight;
    public final double layoutWeight;
    public final double veryLowThreshold;
    public final double lowThreshold;
    public final double mediumThreshold;
    public final double highThreshold;
    public final double veryHighThreshold;
    /** Raw-score scale: score = 100 * (1 - exp(-raw / scale)). */
    public final double scale;

    private ScoringConfig(Builder b) {
        this.weights = new EnumMap<>(b.weights);
        this.baselines = new EnumMap<>(b.baselines);
        this.amethystWeight = b.amethystWeight;
        this.plantGrowthWeight = b.plantGrowthWeight;
        this.arrangementWeight = b.arrangementWeight;
        this.naturalClusterShare = b.naturalClusterShare;
        this.naturalCocoaMatureShare = b.naturalCocoaMatureShare;
        this.kelpWorldgenMaxHeight = b.kelpWorldgenMaxHeight;
        this.vineWorldgenMaxLength = b.vineWorldgenMaxLength;
        this.minGrowthSamples = b.minGrowthSamples;
        this.minRunLength = b.minRunLength;
        this.confounderDamping = b.confounderDamping;
        this.structureDamping = b.structureDamping;
        this.structureHandling = b.structureHandling;
        this.geometryWeight = b.geometryWeight;
        this.layoutWeight = b.layoutWeight;
        this.veryLowThreshold = b.veryLowThreshold;
        this.lowThreshold = b.lowThreshold;
        this.mediumThreshold = b.mediumThreshold;
        this.highThreshold = b.highThreshold;
        this.veryHighThreshold = b.veryHighThreshold;
        this.scale = b.scale;
    }

    public double weight(Indicator i) { return weights.getOrDefault(i, i.defaultWeight()); }

    public int baseline(Indicator i) { return baselines.getOrDefault(i, i.defaultBaseline()); }

    public static ScoringConfig defaults() { return builder().build(); }

    public static Builder builder() { return new Builder(); }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.weights.putAll(weights);
        b.baselines.putAll(baselines);
        b.amethystWeight = amethystWeight;
        b.plantGrowthWeight = plantGrowthWeight;
        b.arrangementWeight = arrangementWeight;
        b.naturalClusterShare = naturalClusterShare;
        b.naturalCocoaMatureShare = naturalCocoaMatureShare;
        b.kelpWorldgenMaxHeight = kelpWorldgenMaxHeight;
        b.vineWorldgenMaxLength = vineWorldgenMaxLength;
        b.minGrowthSamples = minGrowthSamples;
        b.minRunLength = minRunLength;
        b.confounderDamping = confounderDamping;
        b.structureDamping = structureDamping;
        b.structureHandling = structureHandling;
        b.geometryWeight = geometryWeight;
        b.layoutWeight = layoutWeight;
        b.veryLowThreshold = veryLowThreshold;
        b.lowThreshold = lowThreshold;
        b.mediumThreshold = mediumThreshold;
        b.highThreshold = highThreshold;
        b.veryHighThreshold = veryHighThreshold;
        b.scale = scale;
        return b;
    }

    public static final class Builder {
        private final Map<Indicator, Double> weights = new EnumMap<>(Indicator.class);
        private final Map<Indicator, Integer> baselines = new EnumMap<>(Indicator.class);
        private double amethystWeight = 10.0;
        private double plantGrowthWeight = 10.0;
        private double arrangementWeight = 6.0;
        private double naturalClusterShare = 0.25;
        private double naturalCocoaMatureShare = 1.0 / 3.0;
        private int kelpWorldgenMaxHeight = 11;
        private int vineWorldgenMaxLength = 8;
        private int minGrowthSamples = 8;
        private int minRunLength = 5;
        private double confounderDamping = 0.55;
        private double structureDamping = 0.35;
        private StructureHandling structureHandling = StructureHandling.LOWER;
        private double geometryWeight = 1.0;
        private double layoutWeight = 1.0;
        private double veryLowThreshold = 4;
        private double lowThreshold = 8;
        private double mediumThreshold = 25;
        private double highThreshold = 50;
        private double veryHighThreshold = 75;
        private double scale = 30;

        public Builder weight(Indicator i, double w) { weights.put(i, Math.max(0, w)); return this; }
        public Builder baseline(Indicator i, int b) { baselines.put(i, Math.max(0, b)); return this; }
        public Builder amethystWeight(double w) { amethystWeight = Math.max(0, w); return this; }
        public Builder plantGrowthWeight(double w) { plantGrowthWeight = Math.max(0, w); return this; }
        public Builder arrangementWeight(double w) { arrangementWeight = Math.max(0, w); return this; }
        public Builder naturalClusterShare(double v) { naturalClusterShare = clamp01(v); return this; }
        public Builder minGrowthSamples(int v) { minGrowthSamples = Math.max(3, v); return this; }
        public Builder minRunLength(int v) { minRunLength = Math.max(3, v); return this; }
        public Builder confounderDamping(double v) { confounderDamping = clamp01(v); return this; }
        public Builder structureDamping(double v) { structureDamping = clamp01(v); return this; }
        public Builder structureHandling(StructureHandling h) { structureHandling = h == null ? StructureHandling.LOWER : h; return this; }
        public Builder geometryWeight(double v) { geometryWeight = Math.max(0, v); return this; }
        public Builder layoutWeight(double v) { layoutWeight = Math.max(0, v); return this; }
        public Builder veryLowThreshold(double v) { veryLowThreshold = Math.max(0, Math.min(lowThreshold, v)); return this; }
        public Builder thresholds(double low, double medium, double high, double veryHigh) {
            if (!(low <= medium && medium <= high && high <= veryHigh)) {
                throw new IllegalArgumentException("thresholds must be ascending");
            }
            lowThreshold = low;
            mediumThreshold = medium;
            highThreshold = high;
            veryHighThreshold = veryHigh;
            return this;
        }
        public Builder scale(double v) { scale = Math.max(1, v); return this; }
        public ScoringConfig build() { return new ScoringConfig(this); }

        private static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }
    }
}
