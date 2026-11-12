package dev.atlasclient.core.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Looks for plant growth beyond what world generation produces. Growth requires random ticks, which
 * only happen in chunks within simulation distance of a player, so excess growth suggests the area
 * was loaded for an extended time. Reported as inference, never as proof.
 */
public final class PlantGrowthAnalyzer {

    public record Report(int kelpColumns, int tallKelpColumns, int maxKelpHeight,
                         int vineColumns, int longVineColumns, int maxVineLength,
                         int cocoa, int matureCocoa, int bamboo, int crops, int matureCrops, int copper, int agedCopper,
                         List<Evidence> evidence, double contribution) { }

    public Report analyze(List<BlockHit> hits, ScoringConfig cfg) {
        return analyze(hits, java.util.Set.of(), cfg);
    }

    public Report analyze(List<BlockHit> hits, java.util.Set<String> biomes, ScoringConfig cfg) {
        int crops = 0, matureCrops = 0, copper = 0, aged = 0;
        double expectedMature = 0;
        List<long[]> cropSamples = new ArrayList<>();
        List<long[]> copperSamples = new ArrayList<>();
        Map<Long, TreeSet<Integer>> kelp = new HashMap<>();
        Map<Long, TreeSet<Integer>> vines = new HashMap<>();
        int cocoa = 0;
        int matureCocoa = 0;
        int bamboo = 0;
        List<long[]> cocoaSamples = new ArrayList<>();
        for (BlockHit h : hits) {
            long col = ((long) h.x() << 32) | (h.z() & 0xFFFFFFFFL);
            switch (h.indicator()) {
                case KELP -> kelp.computeIfAbsent(col, k -> new TreeSet<>()).add(h.y());
                case VINE -> vines.computeIfAbsent(col, k -> new TreeSet<>()).add(h.y());
                case COCOA -> {
                    cocoa++;
                    if (h.isMature()) {
                        matureCocoa++;
                        if (cocoaSamples.size() < 5) cocoaSamples.add(new long[]{h.x(), h.y(), h.z()});
                    }
                }
                case BAMBOO -> bamboo++;
                case CROP -> {
                    if (h.maxStage() > 0) {
                        crops++;
                        // Village farms generate at random ages: 1 in (max+1) is fully grown.
                        expectedMature += 1.0 / (h.maxStage() + 1);
                        if (h.isMature()) {
                            matureCrops++;
                            if (cropSamples.size() < 5) cropSamples.add(new long[]{h.x(), h.y(), h.z()});
                        }
                    }
                }
                case AGED_COPPER -> {
                    aged++;
                    copper++;
                    if (copperSamples.size() < 5) copperSamples.add(new long[]{h.x(), h.y(), h.z()});
                }
                default -> { }
            }
        }
        int[] kelpStats = runs(kelp, cfg.kelpWorldgenMaxHeight);
        int[] vineStats = runs(vines, cfg.vineWorldgenMaxLength);

        List<Evidence> ev = new ArrayList<>();
        double contribution = 0;

        if (kelpStats[1] > 0) {
            double strength = Math.min(1, kelpStats[1] / 6.0);
            double c = cfg.plantGrowthWeight * strength;
            contribution += c;
            ev.add(new Evidence("plants.kelp", Evidence.Kind.INFERENCE,
                    kelpStats[1] >= 4 ? Evidence.Strength.MODERATE : Evidence.Strength.WEAK, c, kelpStats[1],
                    String.format(Locale.ROOT,
                            "%d of %d kelp columns are taller than %d blocks (max %d). World generation does not create kelp this tall; "
                                    + "it grew later through random ticks, which only occur near players.",
                            kelpStats[1], kelpStats[0], cfg.kelpWorldgenMaxHeight, kelpStats[2]),
                    List.of()));
        }
        boolean vineBiome = biomes.contains("jungle") || biomes.contains("bamboo_jungle") || biomes.contains("sparse_jungle")
                || biomes.contains("swamp") || biomes.contains("mangrove_swamp") || biomes.contains("lush_caves");
        if (vineStats[1] > 0) {
            double strength = Math.min(1, vineStats[1] / 8.0);
            double c = cfg.plantGrowthWeight * (vineBiome ? 0.3 : 0.6) * strength;
            contribution += c;
            ev.add(new Evidence("plants.vines", Evidence.Kind.INFERENCE, Evidence.Strength.WEAK, c, vineStats[1],
                    String.format(Locale.ROOT,
                            "%d vine columns longer than %d blocks (max %d). Long hanging vines usually grow after generation; "
                                    + "jungle and swamp terrain can still produce long vines naturally.",
                            vineStats[1], cfg.vineWorldgenMaxLength, vineStats[2]),
                    List.of()));
        }
        if (cocoa >= cfg.minGrowthSamples) {
            double z = Stats.binomialZ(matureCocoa, cocoa, cfg.naturalCocoaMatureShare);
            double strength = Stats.zToStrength(z);
            if (strength > 0) {
                double c = cfg.plantGrowthWeight * 0.7 * strength;
                contribution += c;
                ev.add(new Evidence("plants.cocoa", Evidence.Kind.INFERENCE, Evidence.Strength.WEAK, c, matureCocoa,
                        String.format(Locale.ROOT,
                                "%d of %d cocoa pods are fully grown (expected about %.0f%% after generation, z=%.1f).",
                                matureCocoa, cocoa, cfg.naturalCocoaMatureShare * 100, z),
                        cocoaSamples));
            }
        }
        if (crops >= cfg.minGrowthSamples) {
            double p0 = expectedMature / crops;
            double z = Stats.binomialZ(matureCrops, crops, p0);
            double strength = Stats.zToStrength(z);
            if (strength > 0) {
                double c = cfg.plantGrowthWeight * 0.6 * strength;
                contribution += c;
                ev.add(new Evidence("plants.crops", Evidence.Kind.INFERENCE, Evidence.Strength.WEAK, c, matureCrops,
                        String.format(Locale.ROOT,
                                "%d of %d crops are fully grown (generated village farms: about %.0f%%, z=%.1f). Crops only grow while a player is nearby.",
                                matureCrops, crops, p0 * 100, z),
                        cropSamples));
            }
        }
        if (aged >= 4) {
            // World generation never places unwaxed copper that has aged (trial chambers use waxed copper).
            double strength = Math.min(1, aged / 12.0);
            double c = cfg.plantGrowthWeight * 0.8 * strength;
            contribution += c;
            ev.add(new Evidence("aging.copper", Evidence.Kind.INFERENCE, Evidence.Strength.MODERATE, c, aged,
                    String.format(Locale.ROOT,
                            "%d placed copper blocks have oxidised. Unwaxed copper only ages through random ticks, i.e. with a player nearby, "
                                    + "over hours of play.", aged),
                    copperSamples));
        }
        return new Report(kelpStats[0], kelpStats[1], kelpStats[2], vineStats[0], vineStats[1], vineStats[2],
                cocoa, matureCocoa, bamboo, crops, matureCrops, copper, aged, ev, contribution);
    }

    /** Returns {columns, columnsLongerThanLimit, longestRun} for contiguous vertical runs. */
    static int[] runs(Map<Long, TreeSet<Integer>> columns, int limit) {
        int total = 0;
        int over = 0;
        int max = 0;
        for (TreeSet<Integer> ys : columns.values()) {
            int run = 0;
            int prev = Integer.MIN_VALUE;
            for (int y : ys) {
                run = (y == prev + 1) ? run + 1 : 1;
                prev = y;
                if (run == 1) {
                    total++;
                }
                if (run == limit + 1) {
                    over++;
                }
                max = Math.max(max, run);
            }
        }
        return new int[]{total, over, max};
    }
}
