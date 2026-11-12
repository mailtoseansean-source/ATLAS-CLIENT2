package dev.atlasclient.core.analysis;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Patterns in how indicator blocks are placed, beyond single straight runs
 * ({@link ArrangementAnalyzer}): flat walls and floors of one block, lights at a regular spacing,
 * mirror-symmetric layouts, containers side by side, farm plots, saplings in a grid, several kinds of
 * player blocks clustered together, and vines growing on built blocks. Pure function; background
 * thread safe.
 */
public final class LayoutAnalyzer {
    /** One pattern and which evidence group it adds to. */
    public record Pattern(String id, Indicator.Group group, double contribution, String description, List<long[]> samples) { }

    public record Report(List<Pattern> patterns) {
        public double total() {
            double t = 0;
            for (Pattern p : patterns) t += p.contribution();
            return t;
        }
    }

    /** Building materials: what walls, floors and symmetric layouts are made of. */
    private static boolean material(Indicator i) {
        return switch (i) {
            case CRAFTED_BUILDING, PLAYER_ONLY, BUILDING_PART, TERRACOTTA, PLACED_LEAVES, FARMLAND -> true;
            default -> false;
        };
    }

    private static boolean built(Indicator i) {
        return switch (i) {
            case CRAFTED_BUILDING, PLAYER_ONLY, BUILDING_PART, TERRACOTTA, PLACED_LEAVES, DECORATION, LIGHT_SOURCE, REDSTONE,
                 STORAGE, WORKSTATION, BED, FARMLAND, MINESHAFT_WOOD -> true;
            default -> false;
        };
    }

    public Report analyze(List<BlockHit> hits, Set<String> biomes, ScoringConfig cfg) {
        List<Pattern> out = new ArrayList<>();
        double w = cfg.layoutWeight;
        if (w <= 0 || hits.isEmpty()) return new Report(out);
        planes(hits, w, out);
        spacing(hits, Indicator.LIGHT_SOURCE, w, "layout.lights", Indicator.Group.BUILDING, "lights placed every %d blocks (%d in a row)", out);
        spacing(hits, Indicator.SAPLING, w, "layout.saplings", Indicator.Group.FARMING, "saplings planted every %d blocks (%d in a row): a tree farm", out);
        symmetry(hits, w, out);
        storageRows(hits, w * cfg.weight(Indicator.STORAGE) / Indicator.STORAGE.defaultWeight(), out);
        clusters(hits, w, out);
        vines(hits, biomes, w, out);
        return new Report(out);
    }

    // ------------------------------------------------------------------ flat walls / floors

    private void planes(List<BlockHit> hits, double w, List<Pattern> out) {
        // key: blockKey, plane orientation, plane coordinate -> cells in that plane
        Map<String, Set<Long>> planes = new HashMap<>();
        for (BlockHit h : hits) {
            if (!material(h.indicator())) continue;
            planes.computeIfAbsent(h.blockKey() + "|y|" + h.y(), k -> new HashSet<>()).add(pack2(h.x(), h.z()));
            planes.computeIfAbsent(h.blockKey() + "|x|" + h.x(), k -> new HashSet<>()).add(pack2(h.z(), h.y()));
            planes.computeIfAbsent(h.blockKey() + "|z|" + h.z(), k -> new HashSet<>()).add(pack2(h.x(), h.y()));
        }
        int bestWall = 0, bestFloor = 0, farm = 0;
        long[] wallAt = null, floorAt = null, farmAt = null;
        Map<Integer, Indicator> kindOf = new HashMap<>();
        for (BlockHit h : hits) kindOf.putIfAbsent(h.blockKey(), h.indicator());
        for (Map.Entry<String, Set<Long>> e : planes.entrySet()) {
            if (e.getValue().size() < 9) continue;
            int[] rect = largestRectangle(e.getValue());
            int area = rect[0] * rect[1];
            if (rect[0] < 3 || rect[1] < 3) continue;
            String[] k = e.getKey().split("\\|");
            Indicator ind = kindOf.get(Integer.parseInt(k[0]));
            long[] at = new long[]{rect[2], rect[3], Long.parseLong(k[2])};
            if (k[1].equals("y")) {
                if (ind == Indicator.FARMLAND) {
                    if (area > farm) { farm = area; farmAt = at; }
                } else if (area > bestFloor) {
                    bestFloor = area;
                    floorAt = at;
                }
            } else if (area > bestWall) {
                bestWall = area;
                wallAt = at;
            }
        }
        if (bestWall >= 9) {
            out.add(new Pattern("layout.wall", Indicator.Group.BUILDING, w * Math.min(4, 1.5 + bestWall / 12.0),
                    String.format(Locale.ROOT, "Flat wall of one crafted block (%d blocks in a solid rectangle)", bestWall), List.of()));
        }
        if (bestFloor >= 9) {
            out.add(new Pattern("layout.floor", Indicator.Group.BUILDING, w * Math.min(4, 1.5 + bestFloor / 12.0),
                    String.format(Locale.ROOT, "Flat floor / platform of one crafted block (%d blocks in a solid rectangle)", bestFloor), List.of()));
        }
        if (farm >= 9) {
            out.add(new Pattern("layout.farm", Indicator.Group.FARMING, w * Math.min(5, 2 + farm / 10.0),
                    String.format(Locale.ROOT, "Farm plot: %d farmland blocks in a solid rectangle", farm), List.of()));
        }
    }

    private static long pack2(int a, int b) { return ((long) a << 32) | (b & 0xFFFFFFFFL); }

    /** {width, height, minA, minB} of the largest solid rectangle in a set of 2D cells (histogram method). */
    static int[] largestRectangle(Set<Long> cells) {
        int minA = Integer.MAX_VALUE, maxA = Integer.MIN_VALUE, minB = Integer.MAX_VALUE, maxB = Integer.MIN_VALUE;
        for (long c : cells) {
            int a = (int) (c >> 32), b = (int) c;
            minA = Math.min(minA, a); maxA = Math.max(maxA, a);
            minB = Math.min(minB, b); maxB = Math.max(maxB, b);
        }
        int wa = maxA - minA + 1, wb = maxB - minB + 1;
        if ((long) wa * wb > 1 << 16) return new int[]{0, 0, 0, 0};
        int[] heights = new int[wa];
        int bestArea = 0;
        int[] best = {0, 0, 0, 0};
        for (int b = minB; b <= maxB; b++) {
            for (int a = 0; a < wa; a++) {
                heights[a] = cells.contains(pack2(minA + a, b)) ? heights[a] + 1 : 0;
            }
            for (int a = 0; a < wa; a++) {
                if (heights[a] == 0) continue;
                int h = heights[a];
                for (int a2 = a; a2 < wa && heights[a2] > 0; a2++) {
                    h = Math.min(h, heights[a2]);
                    int area = h * (a2 - a + 1);
                    if (area > bestArea) {
                        bestArea = area;
                        best = new int[]{a2 - a + 1, h, minA + a, b - h + 1};
                    }
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ regular spacing

    private void spacing(List<BlockHit> hits, Indicator ind, double w, String id, Indicator.Group group, String fmt, List<Pattern> out) {
        Map<String, TreeSet<Integer>> lines = new HashMap<>();
        for (BlockHit h : hits) {
            if (h.indicator() != ind) continue;
            lines.computeIfAbsent("x|" + h.y() + "|" + h.z(), k -> new TreeSet<>()).add(h.x());
            lines.computeIfAbsent("z|" + h.y() + "|" + h.x(), k -> new TreeSet<>()).add(h.z());
        }
        int bestCount = 0, bestGap = 0;
        for (TreeSet<Integer> line : lines.values()) {
            if (line.size() < 4) continue;
            Integer[] v = line.toArray(new Integer[0]);
            for (int i = 0; i < v.length; i++) {
                for (int j = i + 1; j < v.length; j++) {
                    int gap = v[j] - v[i];
                    if (gap < 2 || gap > 12) continue;
                    int n = 2;
                    int next = v[j] + gap;
                    while (line.contains(next)) {
                        n++;
                        next += gap;
                    }
                    if (n > bestCount) {
                        bestCount = n;
                        bestGap = gap;
                    }
                }
            }
        }
        if (bestCount >= 4) {
            out.add(new Pattern(id, group, w * Math.min(4, 1 + bestCount * 0.5), String.format(Locale.ROOT, fmt, bestGap, bestCount), List.of()));
        }
    }

    // ------------------------------------------------------------------ symmetry

    private void symmetry(List<BlockHit> hits, double w, List<Pattern> out) {
        Map<Long, Integer> byPos = new HashMap<>();
        Set<Integer> kinds = new HashSet<>();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (BlockHit h : hits) {
            if (!material(h.indicator()) && h.indicator() != Indicator.LIGHT_SOURCE) continue;
            kinds.add(h.blockKey());
            minY = Math.min(minY, h.y());
            maxY = Math.max(maxY, h.y());
            byPos.put(h.packedPos(), h.blockKey());
            minX = Math.min(minX, h.x()); maxX = Math.max(maxX, h.x());
            minZ = Math.min(minZ, h.z()); maxZ = Math.max(maxZ, h.z());
        }
        if (byPos.size() < 12 || maxX - minX < 4 || maxZ - minZ < 4 || kinds.size() < 2) return;
        // A solid block of one material is trivially symmetric; only count designed layouts.
        if (byPos.size() > 0.5 * (maxX - minX + 1) * (maxZ - minZ + 1) * (maxY - minY + 1)) return;
        int matchX = 0, matchZ = 0;
        for (Map.Entry<Long, Integer> e : byPos.entrySet()) {
            long p = e.getKey();
            int x = BlockHit.unpackX(p), y = BlockHit.unpackY(p), z = BlockHit.unpackZ(p);
            Integer mx = byPos.get(BlockHit.pack(minX + maxX - x, y, z));
            Integer mz = byPos.get(BlockHit.pack(x, y, minZ + maxZ - z));
            if (mx != null && mx.equals(e.getValue())) matchX++;
            if (mz != null && mz.equals(e.getValue())) matchZ++;
        }
        double share = Math.max(matchX, matchZ) / (double) byPos.size();
        if (share >= 0.8) {
            out.add(new Pattern("layout.symmetry", Indicator.Group.BUILDING, w * 2.5,
                    String.format(Locale.ROOT, "Mirror-symmetric layout: %.0f%% of %d built blocks have a twin on the other side", share * 100,
                            byPos.size()), List.of()));
        }
    }

    // ------------------------------------------------------------------ storage side by side

    private void storageRows(List<BlockHit> hits, double w, List<Pattern> out) {
        Set<Long> storage = new HashSet<>();
        for (BlockHit h : hits) if (h.indicator() == Indicator.STORAGE) storage.add(h.packedPos());
        if (storage.size() < 3) return;
        int best = 0;
        long[] at = null;
        Set<Long> seen = new HashSet<>();
        for (long s : storage) {
            if (!seen.add(s)) continue;
            // connected group (6 neighbours)
            List<Long> stack = new ArrayList<>(List.of(s));
            int n = 0;
            while (!stack.isEmpty()) {
                long p = stack.remove(stack.size() - 1);
                n++;
                int x = BlockHit.unpackX(p), y = BlockHit.unpackY(p), z = BlockHit.unpackZ(p);
                long[] nb = {BlockHit.pack(x + 1, y, z), BlockHit.pack(x - 1, y, z), BlockHit.pack(x, y + 1, z),
                        BlockHit.pack(x, y - 1, z), BlockHit.pack(x, y, z + 1), BlockHit.pack(x, y, z - 1)};
                for (long q : nb) if (storage.contains(q) && seen.add(q)) stack.add(q);
            }
            if (n > best) {
                best = n;
                at = new long[]{BlockHit.unpackX(s), BlockHit.unpackY(s), BlockHit.unpackZ(s)};
            }
        }
        if (best >= 3) {
            out.add(new Pattern("layout.storage", Indicator.Group.STORAGE, w * Math.min(5, 1.5 + best * 0.4),
                    String.format(Locale.ROOT, "Organised storage: %d containers side by side", best), at == null ? List.of() : List.<long[]>of(at)));
        }
    }

    // ------------------------------------------------------------------ mixed clusters

    private void clusters(List<BlockHit> hits, double w, List<Pattern> out) {
        // Bucket by 8-block cells; a cell with 3+ kinds of player blocks is a mixed cluster.
        Map<Long, Set<Indicator.Group>> cells = new HashMap<>();
        Map<Long, Integer> counts = new HashMap<>();
        for (BlockHit h : hits) {
            Indicator.Group g = h.indicator().group();
            if (g == Indicator.Group.CONFOUNDER || g == Indicator.Group.AMETHYST || g == Indicator.Group.PLANT_GROWTH
                    || h.indicator() == Indicator.MINESHAFT_WOOD) continue;
            long cell = BlockHit.pack(Math.floorDiv(h.x(), 8), Math.floorDiv(h.y(), 8), Math.floorDiv(h.z(), 8));
            cells.computeIfAbsent(cell, k -> EnumSet.noneOf(Indicator.Group.class)).add(g);
            counts.merge(cell, 1, Integer::sum);
        }
        int bestKinds = 0;
        long bestCell = 0;
        for (Map.Entry<Long, Set<Indicator.Group>> e : cells.entrySet()) {
            if (e.getValue().size() > bestKinds) {
                bestKinds = e.getValue().size();
                bestCell = e.getKey();
            }
        }
        if (bestKinds >= 3) {
            Set<Indicator.Group> g = cells.get(bestCell);
            StringBuilder names = new StringBuilder();
            for (Indicator.Group x : g) {
                if (names.length() > 0) names.append(", ");
                names.append(x.label().toLowerCase(Locale.ROOT));
            }
            out.add(new Pattern("layout.cluster", Indicator.Group.GEOMETRY, w * Math.min(4, bestKinds),
                    String.format(Locale.ROOT, "%d kinds of player blocks within a few blocks of each other (%s; %d blocks)", bestKinds, names,
                            counts.get(bestCell)), List.<long[]>of(new long[]{BlockHit.unpackX(bestCell) * 8L + 4, BlockHit.unpackY(bestCell) * 8L + 4,
                            BlockHit.unpackZ(bestCell) * 8L + 4})));
        }
    }

    // ------------------------------------------------------------------ vines on built blocks

    private void vines(List<BlockHit> hits, Set<String> biomes, double w, List<Pattern> out) {
        List<BlockHit> vines = new ArrayList<>();
        Set<Long> builtCells = new HashSet<>();
        for (BlockHit h : hits) {
            if (h.indicator() == Indicator.VINE) vines.add(h);
            else if (built(h.indicator()) && h.indicator() != Indicator.MINESHAFT_WOOD) {
                builtCells.add(BlockHit.pack(Math.floorDiv(h.x(), 4), Math.floorDiv(h.y(), 4), Math.floorDiv(h.z(), 4)));
            }
        }
        if (vines.size() < 3 || builtCells.isEmpty()) return;
        int near = 0;
        for (BlockHit v : vines) {
            int cx = Math.floorDiv(v.x(), 4), cy = Math.floorDiv(v.y(), 4), cz = Math.floorDiv(v.z(), 4);
            boolean found = false;
            for (int dx = -1; dx <= 1 && !found; dx++)
                for (int dy = -1; dy <= 1 && !found; dy++)
                    for (int dz = -1; dz <= 1 && !found; dz++)
                        if (builtCells.contains(BlockHit.pack(cx + dx, cy + dy, cz + dz))) found = true;
            if (found) near++;
        }
        if (near < 3) return;
        // Jungles, swamps and lush caves cover their own terrain in vines; there it means little.
        boolean vineBiome = biomes != null && (biomes.contains("jungle") || biomes.contains("bamboo_jungle") || biomes.contains("sparse_jungle")
                || biomes.contains("swamp") || biomes.contains("mangrove_swamp") || biomes.contains("lush_caves"));
        double c = w * Math.min(3, near / 4.0) * (vineBiome ? 0.3 : 1.0);
        out.add(new Pattern("layout.vines", Indicator.Group.PLANT_GROWTH, c,
                String.format(Locale.ROOT, "%d vines growing on or next to built blocks%s", near,
                        vineBiome ? " (in a biome where vines are common, so this counts little)" : ""), List.of()));
    }
}
