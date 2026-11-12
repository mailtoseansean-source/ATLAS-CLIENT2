package dev.atlasclient.core.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Finds shapes that natural terrain almost never makes, in the underground air (and water) of one
 * chunk: box-shaped rooms, straight tunnels, big dug-out areas with a flat floor, straight vertical
 * shafts, dug staircases and straight water channels.
 *
 * <p>Natural caves are irregular: their air fills only a small part of their bounding box. Player
 * work is boxy: a room or tunnel fills almost all of it. That fill ratio (plus straightness and a
 * flat floor) is what is measured. Generated structures (dungeons, mineshafts, strongholds...) are
 * boxy too; {@link SuspicionScorer} lowers their weight when {@link StructureSignatures} recognises
 * them.</p>
 */
public final class GeometryAnalyzer {
    public enum Shape {
        ROOM("Box-shaped room"),
        TUNNEL("Straight tunnel"),
        EXCAVATION("Large dug-out area with a flat floor"),
        SHAFT("Straight vertical shaft"),
        STAIRCASE("Dug staircase"),
        CHANNEL("Straight water channel");

        private final String label;

        Shape(String label) { this.label = label; }

        public String label() { return label; }
    }

    /**
     * One shape, in absolute block coordinates. {@code borders} says which chunk edges it touches
     * (1 = west/x min, 2 = east/x max, 4 = north/z min, 8 = south/z max) so neighbouring chunks can
     * join a tunnel or room that crosses them.
     */
    public record Feature(Shape shape, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int volume, double fill,
                          int borders) {
        public int sizeX() { return maxX - minX + 1; }
        public int sizeY() { return maxY - minY + 1; }
        public int sizeZ() { return maxZ - minZ + 1; }
        public int length() { return Math.max(sizeX(), sizeZ()); }

        public String describe() {
            return switch (shape) {
                case TUNNEL, CHANNEL -> String.format(Locale.ROOT, "%s %d long (%dx%d)", shape.label(), length(),
                        Math.min(sizeX(), sizeZ()), sizeY());
                case SHAFT -> String.format(Locale.ROOT, "%s %d deep", shape.label(), sizeY());
                case STAIRCASE -> String.format(Locale.ROOT, "%s %d steps", shape.label(), sizeY());
                default -> String.format(Locale.ROOT, "%s %dx%dx%d (%.0f%% open)", shape.label(), sizeX(), sizeY(), sizeZ(), fill * 100);
            };
        }
    }

    public record Report(List<Feature> features, int components, int irregular) {
        public static final Report EMPTY = new Report(List.of(), 0, 0);

        public int count(Shape s) {
            int n = 0;
            for (Feature f : features) if (f.shape() == s) n++;
            return n;
        }
    }

    /** Components larger than this are not walked further (keeps a chunk under a few ms). */
    private static final int MAX_COMPONENT = 40_000;

    public Report analyze(AirGrid g) {
        List<Feature> out = new ArrayList<>();
        int cells = g.height * 256;
        boolean[] seen = new boolean[cells];
        int[] queue = new int[cells];
        int components = 0, irregular = 0;
        for (int water = 0; water <= 1; water++) {
            java.util.Arrays.fill(seen, false);
            for (int y = g.minY; y < g.minY + g.height; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int i = (y - g.minY) * 256 + z * 16 + x;
                        if (seen[i] || !open(g, x, y, z, water == 1)) continue;
                        Feature f = component(g, x, y, z, water == 1, seen, queue);
                        if (f == null) continue;
                        components++;
                        if (f.shape() == null) irregular++;
                        else out.add(f);
                    }
                }
            }
        }
        corridors(g, out);
        staircases(g, out);
        return new Report(out, components, irregular);
    }

    private static boolean open(AirGrid g, int x, int y, int z, boolean water) {
        if (water) return g.water(x, y, z);
        return g.air(x, y, z) && g.underground(x, y, z);
    }

    /** Flood fill (6 neighbours) from one cell; classifies the component. Null when too small. */
    private Feature component(AirGrid g, int sx, int sy, int sz, boolean water, boolean[] seen, int[] queue) {
        int head = 0, tail = 0;
        int start = (sy - g.minY) * 256 + sz * 16 + sx;
        seen[start] = true;
        queue[tail++] = start;
        int minX = 16, minY = Integer.MAX_VALUE, minZ = 16, maxX = -1, maxY = Integer.MIN_VALUE, maxZ = -1;
        int volume = 0;
        boolean capped = false;
        while (head < tail) {
            int i = queue[head++];
            int y = i / 256 + g.minY, z = (i % 256) / 16, x = i % 16;
            volume++;
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            if (volume >= MAX_COMPONENT) {
                capped = true;
                break;
            }
            for (int d = 0; d < 6; d++) {
                int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
                int ny = y + (d == 2 ? 1 : d == 3 ? -1 : 0);
                int nz = z + (d == 4 ? 1 : d == 5 ? -1 : 0);
                if (!g.inside(nx, ny, nz)) continue;
                int j = (ny - g.minY) * 256 + nz * 16 + nx;
                if (seen[j] || !open(g, nx, ny, nz, water)) continue;
                seen[j] = true;
                queue[tail++] = j;
            }
        }
        if (capped) {
            // Keep marking the rest as seen so it is not re-walked piece by piece.
            while (head < tail) {
                int i = queue[head++];
                int y = i / 256 + g.minY, z = (i % 256) / 16, x = i % 16;
                for (int d = 0; d < 6; d++) {
                    int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
                    int ny = y + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    int nz = z + (d == 4 ? 1 : d == 5 ? -1 : 0);
                    if (!g.inside(nx, ny, nz)) continue;
                    int j = (ny - g.minY) * 256 + nz * 16 + nx;
                    if (seen[j] || !open(g, nx, ny, nz, water)) continue;
                    seen[j] = true;
                    if (tail < queue.length) queue[tail++] = j;
                }
            }
            return new Feature(null, 0, 0, 0, 0, 0, 0, volume, 0, 0);
        }
        if (volume < 12) return null;
        int dx = maxX - minX + 1, dy = maxY - minY + 1, dz = maxZ - minZ + 1;
        double fill = volume / (double) (dx * dy * dz);
        int lon = Math.max(dx, dz), shortSide = Math.min(dx, dz);
        int floor = 0;
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                if (open(g, x, minY, z, water) && !open(g, x, minY - 1, z, water)) floor++;
            }
        }
        double floorFill = floor / (double) (dx * dz);
        int borders = (minX == 0 ? 1 : 0) | (maxX == 15 ? 2 : 0) | (minZ == 0 ? 4 : 0) | (maxZ == 15 ? 8 : 0);
        int ax = (g.chunkX << 4), az = (g.chunkZ << 4);
        Shape shape = null;
        if (water) {
            if (dy == 1 && lon >= 10 && shortSide <= 2 && fill >= 0.9) shape = Shape.CHANNEL;
        } else if (dy >= 10 && lon <= 2 && fill >= 0.85) {
            shape = Shape.SHAFT;
        } else if (lon >= 8 && shortSide <= 3 && dy >= 2 && dy <= 4 && fill >= 0.8) {
            shape = Shape.TUNNEL;
        } else if (volume >= 400 && fill >= 0.75 && floorFill >= 0.85 && dy <= 24 && shortSide >= 6) {
            shape = Shape.EXCAVATION;
        } else if (shortSide >= 3 && dy >= 2 && dy <= 8 && fill >= 0.82 && volume >= 24) {
            shape = Shape.ROOM;
        }
        return new Feature(shape, ax + minX, minY, az + minZ, ax + maxX, maxY, az + maxZ, volume, fill, borders);
    }

    /**
     * 1-wide, 2-high straight corridors with walls on both sides and a floor (branch mines, strip
     * mines). These often join big caves, so the component test alone misses them.
     */
    private void corridors(AirGrid g, List<Feature> out) {
        int ax = (g.chunkX << 4), az = (g.chunkZ << 4);
        for (int axis = 0; axis < 2; axis++) {
            for (int y = g.minY + 1; y < g.minY + g.height - 1; y++) {
                for (int a = 0; a < 16; a++) {
                    int run = 0;
                    for (int b = 0; b <= 16; b++) {
                        boolean ok = b < 16 && corridorCell(g, axis == 0 ? b : a, y, axis == 0 ? a : b, axis);
                        if (ok) {
                            run++;
                            continue;
                        }
                        if (run >= 8 && !overlaps(out, axis, a, y, b - run, b - 1, ax, az)) {
                            int x0 = axis == 0 ? b - run : a, x1 = axis == 0 ? b - 1 : a;
                            int z0 = axis == 0 ? a : b - run, z1 = axis == 0 ? a : b - 1;
                            int borders = (x0 == 0 ? 1 : 0) | (x1 == 15 ? 2 : 0) | (z0 == 0 ? 4 : 0) | (z1 == 15 ? 8 : 0);
                            out.add(new Feature(Shape.TUNNEL, ax + x0, y, az + z0, ax + x1, y + 1, az + z1, run * 2, 1.0, borders));
                        }
                        run = 0;
                    }
                }
            }
        }
    }

    private static boolean corridorCell(AirGrid g, int x, int y, int z, int axis) {
        if (!g.air(x, y, z) || !g.air(x, y + 1, z) || !g.underground(x, y + 1, z)) return false;
        // 2 or 3 high (people dig 3 high to walk down comfortably), but closed above.
        if (!g.solid(x, y - 1, z) || !(g.solid(x, y + 2, z) || g.solid(x, y + 3, z))) return false;
        if (axis == 0) return g.solid(x, y, z - 1) && g.solid(x, y + 1, z - 1) && g.solid(x, y, z + 1) && g.solid(x, y + 1, z + 1);
        return g.solid(x - 1, y, z) && g.solid(x - 1, y + 1, z) && g.solid(x + 1, y, z) && g.solid(x + 1, y + 1, z);
    }

    private static boolean overlaps(List<Feature> out, int axis, int a, int y, int b0, int b1, int ax, int az) {
        for (Feature f : out) {
            if (f.shape() != Shape.TUNNEL) continue;
            int x0 = ax + (axis == 0 ? b0 : a), z0 = az + (axis == 0 ? a : b0);
            int x1 = ax + (axis == 0 ? b1 : a), z1 = az + (axis == 0 ? a : b1);
            if (x0 <= f.maxX() && x1 >= f.minX() && z0 <= f.maxZ() && z1 >= f.minZ() && y <= f.maxY() && y + 1 >= f.minY()) {
                return true;
            }
        }
        return false;
    }

    /** Steps going down one block per block forward, 2 high, 6+ steps (a dug way down). */
    private void staircases(AirGrid g, List<Feature> out) {
        int ax = (g.chunkX << 4), az = (g.chunkZ << 4);
        int found = 0;
        for (int dir = 0; dir < 4 && found < 4; dir++) {
            int sx = dir == 0 ? 1 : dir == 1 ? -1 : 0, sz = dir == 2 ? 1 : dir == 3 ? -1 : 0;
            for (int y = g.minY + g.height - 3; y > g.minY + 6 && found < 4; y--) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (!step(g, x, y, z, sx)) continue;
                        if (step(g, x - sx, y + 1, z - sz, sx)) continue; // not the top step
                        int n = 1;
                        while (step(g, x + sx * n, y - n, z + sz * n, sx)) n++;
                        if (n >= 6) {
                            int x1 = x + sx * (n - 1), z1 = z + sz * (n - 1);
                            out.add(new Feature(Shape.STAIRCASE, ax + Math.min(x, x1), y - n + 1, az + Math.min(z, z1),
                                    ax + Math.max(x, x1), y + 1, az + Math.max(z, z1), n * 2, 1.0, 0));
                            if (++found >= 4) return;
                        }
                    }
                }
            }
        }
    }

    /** One step: 2-3 high, block under it and over it, walls on both sides (natural slopes are wide). */
    private static boolean step(AirGrid g, int x, int y, int z, int sx) {
        if (!g.inside(x, y, z) || !g.air(x, y, z) || !g.air(x, y + 1, z) || !g.underground(x, y + 1, z)) return false;
        // 2 or 3 high (people dig 3 high to walk down comfortably), but closed above.
        if (!g.solid(x, y - 1, z) || !(g.solid(x, y + 2, z) || g.solid(x, y + 3, z))) return false;
        int wx = sx == 0 ? 1 : 0, wz = sx == 0 ? 0 : 1;
        return g.solid(x - wx, y, z - wz) && g.solid(x + wx, y, z + wz) && g.solid(x - wx, y + 1, z - wz) && g.solid(x + wx, y + 1, z + wz);
    }
}
