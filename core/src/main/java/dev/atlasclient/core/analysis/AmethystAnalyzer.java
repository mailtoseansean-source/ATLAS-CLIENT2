package dev.atlasclient.core.analysis;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Interprets visible amethyst geode blocks.
 *
 * <p>Background: world generation places buds of every stage on some faces of budding amethyst,
 * so a freshly generated geode already contains clusters (roughly a quarter of buds with vanilla
 * settings). Buds only grow further while the chunk receives random ticks, which happens only when
 * a player is within simulation distance. A geode whose buds are overwhelmingly fully grown is
 * therefore consistent with the area having been loaded for a long time. It is NOT proof of a base:
 * long AFK sessions, passers-by over many visits, or unusual generation can produce the same
 * picture. This analyzer reports such patterns as inferences with explicit caveats.</p>
 */
public final class AmethystAnalyzer {

    public record Report(boolean geodeVisible, int budding, Map<Indicator, Integer> budCounts,
                         int totalBuds, int clusters, double clusterShare, double zScore,
                         int bareBudding, int amethystBlocks, int calcite, List<Evidence> evidence,
                         double contribution) {
        public String summary() {
            if (!geodeVisible) {
                return "No geode visible";
            }
            return String.format(Locale.ROOT, "Geode: %d budding, %d buds (%d clusters, %.0f%%), %d amethyst blocks",
                    budding, totalBuds, clusters, clusterShare * 100, amethystBlocks);
        }
    }

    private static final Indicator[] BUD_TYPES = {
            Indicator.SMALL_AMETHYST_BUD, Indicator.MEDIUM_AMETHYST_BUD,
            Indicator.LARGE_AMETHYST_BUD, Indicator.AMETHYST_CLUSTER};

    public Report analyze(List<BlockHit> hits, ScoringConfig cfg) {
        Map<Indicator, Integer> buds = new EnumMap<>(Indicator.class);
        for (Indicator t : BUD_TYPES) {
            buds.put(t, 0);
        }
        Set<Long> budPositions = new HashSet<>();
        List<BlockHit> buddingBlocks = new ArrayList<>();
        int amethystBlocks = 0;
        int calcite = 0;
        List<long[]> clusterSamples = new ArrayList<>();
        for (BlockHit h : hits) {
            switch (h.indicator()) {
                case BUDDING_AMETHYST -> buddingBlocks.add(h);
                case AMETHYST_BLOCK -> amethystBlocks++;
                case CALCITE -> calcite++;
                case SMALL_AMETHYST_BUD, MEDIUM_AMETHYST_BUD, LARGE_AMETHYST_BUD, AMETHYST_CLUSTER -> {
                    buds.merge(h.indicator(), 1, Integer::sum);
                    budPositions.add(h.packedPos());
                    if (h.indicator() == Indicator.AMETHYST_CLUSTER && clusterSamples.size() < 5) {
                        clusterSamples.add(new long[]{h.x(), h.y(), h.z()});
                    }
                }
                default -> { }
            }
        }
        int totalBuds = 0;
        for (int c : buds.values()) {
            totalBuds += c;
        }
        int clusters = buds.get(Indicator.AMETHYST_CLUSTER);
        int budding = buddingBlocks.size();
        boolean geodeVisible = budding > 0 || totalBuds > 0 || amethystBlocks >= 4;

        // Budding blocks with no bud on any of their six faces.
        int bare = 0;
        for (BlockHit b : buddingBlocks) {
            boolean any = false;
            int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            for (int[] d : dirs) {
                if (budPositions.contains(BlockHit.pack(b.x() + d[0], b.y() + d[1], b.z() + d[2]))) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                bare++;
            }
        }

        List<Evidence> ev = new ArrayList<>();
        double contribution = 0;
        double share = totalBuds == 0 ? 0 : clusters / (double) totalBuds;
        double z = Stats.binomialZ(clusters, totalBuds, cfg.naturalClusterShare);

        if (geodeVisible) {
            ev.add(new Evidence("amethyst.observed", Evidence.Kind.OBSERVATION, Evidence.Strength.WEAK, 0,
                    budding + totalBuds + amethystBlocks,
                    String.format(Locale.ROOT, "Observed geode blocks: %d budding amethyst, %d small / %d medium / %d large buds, %d clusters, %d amethyst blocks, %d calcite",
                            budding, buds.get(Indicator.SMALL_AMETHYST_BUD), buds.get(Indicator.MEDIUM_AMETHYST_BUD),
                            buds.get(Indicator.LARGE_AMETHYST_BUD), clusters, amethystBlocks, calcite),
                    clusterSamples));
        }

        if (totalBuds >= cfg.minGrowthSamples) {
            double strength = Stats.zToStrength(z);
            if (strength > 0) {
                double c = cfg.amethystWeight * strength;
                contribution += c;
                ev.add(new Evidence("amethyst.growth", Evidence.Kind.INFERENCE,
                        strength > 0.66 ? Evidence.Strength.MODERATE : Evidence.Strength.WEAK, c, clusters,
                        String.format(Locale.ROOT,
                                "%.0f%% of %d visible buds are fully grown clusters versus about %.0f%% expected right after generation (z=%.1f). "
                                        + "Consistent with long periods of random ticks nearby (player within simulation distance); not proof of a base.",
                                share * 100, totalBuds, cfg.naturalClusterShare * 100, z),
                        clusterSamples));
            }
        } else if (totalBuds > 0) {
            ev.add(new Evidence("amethyst.growth.insufficient", Evidence.Kind.CAVEAT, Evidence.Strength.WEAK, 0, totalBuds,
                    "Too few visible buds (" + totalBuds + " < " + cfg.minGrowthSamples + ") to judge growth; geode may extend into unscanned chunks.",
                    List.of()));
        }

        if (amethystBlocks >= 30 && budding == 0) {
            double c = cfg.amethystWeight * 0.5;
            contribution += c;
            ev.add(new Evidence("amethyst.no_budding", Evidence.Kind.INFERENCE, Evidence.Strength.WEAK, c, amethystBlocks,
                    amethystBlocks + " amethyst blocks but no budding amethyst visible in this chunk. Natural geodes normally contain budding blocks; "
                            + "they may have been mined, or the geode's interior lies in a neighbouring chunk.",
                    List.of()));
        }

        if (budding >= cfg.minGrowthSamples && totalBuds == 0) {
            double c = cfg.amethystWeight * 0.4;
            contribution += c;
            ev.add(new Evidence("amethyst.harvested", Evidence.Kind.INFERENCE, Evidence.Strength.WEAK, c, budding,
                    budding + " budding amethyst blocks with no buds at all. World generation usually leaves some buds; "
                            + "this pattern is consistent with recent harvesting.",
                    List.of()));
        }

        return new Report(geodeVisible, budding, buds, totalBuds, clusters, share, z, bare,
                amethystBlocks, calcite, ev, contribution);
    }
}
