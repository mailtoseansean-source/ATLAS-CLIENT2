package dev.atlasclient.core.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Detects player-placed-looking arrangements: straight runs of identical crafted blocks (walls,
 * floors, rails of glass, lines of torches...). Natural terrain almost never forms long axis-aligned
 * runs of the same crafted block, but generated structures (villages, strongholds, mineshafts) do,
 * so the scorer damps this evidence when structure markers are present.
 */
public final class ArrangementAnalyzer {

    public record Report(int longestRun, int runsFound, List<Evidence> evidence, double contribution) { }

    private static boolean candidate(Indicator i) {
        return switch (i) {
            case CRAFTED_BUILDING, MINESHAFT_WOOD, PLAYER_ONLY, LIGHT_SOURCE, REDSTONE, FARMLAND -> true;
            default -> false;
        };
    }

    public Report analyze(List<BlockHit> hits, ScoringConfig cfg) {
        Map<Integer, Set<Long>> byBlock = new HashMap<>();
        for (BlockHit h : hits) {
            if (candidate(h.indicator())) {
                byBlock.computeIfAbsent(h.blockKey(), k -> new HashSet<>()).add(h.packedPos());
            }
        }
        int longest = 0;
        int runs = 0;
        List<long[]> samples = new ArrayList<>();
        int[][] axes = {{1, 0, 0}, {0, 0, 1}, {0, 1, 0}};
        for (Set<Long> positions : byBlock.values()) {
            if (positions.size() < cfg.minRunLength) {
                continue;
            }
            for (long p : positions) {
                int x = BlockHit.unpackX(p), y = BlockHit.unpackY(p), z = BlockHit.unpackZ(p);
                for (int[] a : axes) {
                    // Only start counting at the beginning of a run.
                    if (positions.contains(BlockHit.pack(x - a[0], y - a[1], z - a[2]))) {
                        continue;
                    }
                    int len = 1;
                    while (positions.contains(BlockHit.pack(x + a[0] * len, y + a[1] * len, z + a[2] * len))) {
                        len++;
                    }
                    if (len >= cfg.minRunLength) {
                        runs++;
                        if (samples.size() < 5) {
                            samples.add(new long[]{x, y, z});
                        }
                    }
                    longest = Math.max(longest, len);
                }
            }
        }
        List<Evidence> ev = new ArrayList<>();
        double contribution = 0;
        if (runs > 0) {
            double strength = Math.min(1, (runs / 4.0) * 0.6 + (longest / 16.0) * 0.4);
            contribution = cfg.arrangementWeight * strength;
            ev.add(new Evidence("arrangement.runs", Evidence.Kind.INFERENCE,
                    runs >= 4 ? Evidence.Strength.MODERATE : Evidence.Strength.WEAK, contribution, runs,
                    String.format(Locale.ROOT,
                            "%d straight runs of %d+ identical crafted blocks (longest %d). Regular lines like this are typical of built structures.",
                            runs, cfg.minRunLength, longest),
                    samples));
        }
        return new Report(longest, runs, ev, contribution);
    }
}
