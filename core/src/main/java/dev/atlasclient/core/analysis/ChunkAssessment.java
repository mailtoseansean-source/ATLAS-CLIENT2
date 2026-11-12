package dev.atlasclient.core.analysis;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Result of scoring one chunk. Immutable. {@code score} is 0..100 and is a heuristic estimate; the
 * {@link Evidence.Kind#OBSERVATION} entries are the verified block observations behind it.
 */
public record ChunkAssessment(String dimension, int chunkX, int chunkZ, double score, Confidence confidence,
                              Map<Indicator, Integer> counts, List<Evidence> evidence, String explanation,
                              BaseStatus.Estimate status, AmethystAnalyzer.Report amethyst,
                              long observedAtMillis, boolean authoritative, Details details) {
    /**
     * What neighbouring chunks need to combine their evidence into one site.
     *
     * @param raw         raw (pre-curve) evidence total of this chunk, after structure damping
     * @param protectedRaw the part of {@code raw} from blocks world generation cannot make
     * @param groups      evidence groups that contributed
     * @param strong      at least one strong signal
     * @param structures  generated structures recognised in the chunk
     * @param features    artificial shapes found in the underground air
     * @param minY        lowest evidence Y (Integer.MAX_VALUE when none)
     * @param maxY        highest evidence Y
     * @param confirmed   blocks world generation cannot make were seen in an artificial arrangement
     * @param byGroup     contribution per evidence group (before structure damping)
     */
    public record Details(double raw, double protectedRaw, Set<Indicator.Group> groups, boolean strong,
                          List<StructureSignatures.Match> structures, List<GeometryAnalyzer.Feature> features,
                          int minY, int maxY, boolean confirmed, Map<Indicator.Group, Double> byGroup) {
        public static final Details EMPTY = new Details(0, 0, Set.of(), false, List.of(), List.of(), Integer.MAX_VALUE, Integer.MIN_VALUE, false,
                Map.of());

        public Details {
            groups = Set.copyOf(groups);
            byGroup = Map.copyOf(byGroup);
            structures = List.copyOf(structures);
            features = List.copyOf(features);
        }
    }

    public ChunkAssessment {
        counts = Map.copyOf(counts);
        evidence = List.copyOf(evidence);
        details = details == null ? Details.EMPTY : details;
    }

    public ChunkAssessment(String dimension, int chunkX, int chunkZ, double score, Confidence confidence,
                           Map<Indicator, Integer> counts, List<Evidence> evidence, String explanation,
                           BaseStatus.Estimate status, AmethystAnalyzer.Report amethyst,
                           long observedAtMillis, boolean authoritative) {
        this(dimension, chunkX, chunkZ, score, confidence, counts, evidence, explanation, status, amethyst, observedAtMillis,
                authoritative, Details.EMPTY);
    }

    public long chunkKey() { return ChunkObservation.chunkKey(chunkX, chunkZ); }

    public int indicatorTypes() {
        int n = 0;
        for (int c : counts.values()) {
            if (c > 0) n++;
        }
        return n;
    }

    public int indicatorTotal() {
        int n = 0;
        for (int c : counts.values()) n += c;
        return n;
    }

    /** Center of the chunk in block coordinates. */
    public double centerX() { return chunkX * 16 + 8; }

    public double centerZ() { return chunkZ * 16 + 8; }

    public double horizontalDistanceTo(double x, double z) {
        double dx = centerX() - x;
        double dz = centerZ() - z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
