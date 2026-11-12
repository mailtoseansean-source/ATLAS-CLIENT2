package dev.atlasclient.core.path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Budgeted A* over block positions. Supports walking (with diagonals that do not cut corners),
 * stepping up one block, dropping up to {@code maxDrop} blocks, swimming, and optional free flight
 * when the player is legitimately flying. Positions are the block the player's feet occupy.
 *
 * <p>When the node budget runs out the best partial path (closest to the goal) is returned so the
 * follower can make progress and replan.</p>
 */
public final class Pathfinder {
    public record Node(int x, int y, int z) {
        long key() { return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL); }
    }

    public record Result(List<Node> path, boolean reachedGoal, int expanded) {
        public boolean found() { return !path.isEmpty(); }
    }

    /**
     * Goal test: within {@code range} blocks (Euclidean, from eye height) of the target block center.
     * With {@code notStandingOnTarget} the player may not end up standing on the target block
     * (needed when the target is going to be mined).
     */
    public record Goal(int x, int y, int z, double range, boolean notStandingOnTarget) {
        public Goal(int x, int y, int z, double range) { this(x, y, z, range, false); }

        boolean reached(Node n) {
            if (notStandingOnTarget && n.x() == x && n.z() == z && n.y() - 1 == y) {
                return false;
            }
            double dx = n.x() + 0.5 - (x + 0.5);
            double dy = n.y() + 1.62 - (y + 0.5);
            double dz = n.z() + 0.5 - (z + 0.5);
            return dx * dx + dy * dy + dz * dz <= range * range;
        }

        double heuristic(Node n) {
            double dx = n.x() - x, dy = n.y() - y, dz = n.z() - z;
            return Math.max(0, Math.sqrt(dx * dx + dy * dy + dz * dz) - range);
        }
    }

    private final PathWorld world;
    private int maxNodes = 4000;
    private int maxDrop = 3;
    private boolean canFly;
    private final Set<Long> avoid = new HashSet<>();

    public Pathfinder(PathWorld world) { this.world = world; }

    public Pathfinder maxNodes(int n) { this.maxNodes = Math.max(50, n); return this; }
    public Pathfinder maxDrop(int n) { this.maxDrop = Math.max(0, Math.min(8, n)); return this; }
    public Pathfinder canFly(boolean fly) { this.canFly = fly; return this; }

    /** Blacklists a node (e.g. after getting stuck there) for subsequent searches. */
    public void avoid(Node n) { avoid.add(n.key()); }

    public void clearAvoid() { avoid.clear(); }

    private record Open(Node node, double f, double g) { }

    public Result find(Node start, Goal goal) {
        if (goal.reached(start)) {
            return new Result(List.of(start), true, 0);
        }
        PriorityQueue<Open> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
        Map<Long, Double> best = new HashMap<>();
        Map<Long, Node> parent = new HashMap<>();
        open.add(new Open(start, goal.heuristic(start), 0));
        best.put(start.key(), 0.0);
        Node closest = start;
        double closestH = goal.heuristic(start);
        int expanded = 0;
        while (!open.isEmpty() && expanded < maxNodes) {
            Open cur = open.poll();
            Double known = best.get(cur.node().key());
            if (known != null && cur.g() > known + 1e-9) {
                continue;
            }
            expanded++;
            Node n = cur.node();
            if (goal.reached(n)) {
                return new Result(rebuild(parent, n), true, expanded);
            }
            double h = goal.heuristic(n);
            if (h < closestH) {
                closestH = h;
                closest = n;
            }
            for (Move m : neighbours(n)) {
                if (avoid.contains(m.to().key())) continue;
                double g = cur.g() + m.cost();
                Double old = best.get(m.to().key());
                if (old == null || g < old - 1e-9) {
                    best.put(m.to().key(), g);
                    parent.put(m.to().key(), n);
                    open.add(new Open(m.to(), g + goal.heuristic(m.to()) * 1.05, g));
                }
            }
        }
        if (closest == start) {
            return new Result(List.of(), false, expanded);
        }
        return new Result(rebuild(parent, closest), false, expanded);
    }

    private static List<Node> rebuild(Map<Long, Node> parent, Node end) {
        List<Node> path = new ArrayList<>();
        Node n = end;
        while (n != null) {
            path.add(n);
            n = parent.get(n.key());
        }
        Collections.reverse(path);
        return path;
    }

    record Move(Node to, double cost) { }

    private boolean bodyFits(int x, int y, int z) {
        return world.isLoaded(x, z) && world.isPassable(x, y, z) && world.isPassable(x, y + 1, z)
                && !world.isDangerous(x, y, z) && !world.isDangerous(x, y + 1, z);
    }

    /** Lava (or fire) right beside the body or in the floor next to it: one slip and the player is in it. */
    private boolean besideDanger(int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            if (world.isDangerous(x + dx, y, z + dz) || world.isDangerous(x + dx, y - 1, z + dz)
                    || world.isDangerous(x + dx, y + 1, z + dz)) return true;
        }
        return world.isDangerous(x, y - 1, z);
    }

    private static final double DANGER_COST = 8.0;

    private boolean supported(int x, int y, int z) {
        return world.isStandable(x, y - 1, z) || world.isWater(x, y, z) || world.isWater(x, y - 1, z);
    }

    List<Move> neighbours(Node n) {
        List<Move> out = new ArrayList<>(16);
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        if (canFly) {
            for (int[] d : dirs) {
                tryFly(n, d[0], 0, d[1], out);
            }
            tryFly(n, 0, 1, 0, out);
            tryFly(n, 0, -1, 0, out);
            return out;
        }
        boolean inWater = world.isWater(n.x(), n.y(), n.z());
        for (int[] d : dirs) {
            int nx = n.x() + d[0], nz = n.z() + d[1];
            boolean diagonal = d[0] != 0 && d[1] != 0;
            if (diagonal && !(bodyFits(n.x() + d[0], n.y(), n.z()) && bodyFits(n.x(), n.y(), n.z() + d[1]))) {
                continue; // don't cut corners
            }
            double base = diagonal ? 1.414 : 1.0;
            double waterCost = inWater || world.isWater(nx, n.y(), nz) ? 2.0 : 1.0;
            // flat
            if (bodyFits(nx, n.y(), nz) && supported(nx, n.y(), nz)) {
                out.add(new Move(new Node(nx, n.y(), nz), base * waterCost + (besideDanger(nx, n.y(), nz) ? DANGER_COST : 0)
                        + world.moveCost(nx, n.y(), nz)));
                continue;
            }
            // step up (jump): needs headroom above current position
            if (!diagonal && bodyFits(nx, n.y() + 1, nz) && world.isPassable(n.x(), n.y() + 2, n.z())
                    && supported(nx, n.y() + 1, nz)) {
                out.add(new Move(new Node(nx, n.y() + 1, nz), base * 2.0 * waterCost + (besideDanger(nx, n.y() + 1, nz) ? DANGER_COST : 0)
                        + world.moveCost(nx, n.y() + 1, nz) + world.moveCost(n.x(), n.y() + 1, n.z()) * 0.5));
                continue;
            }
            // drop down
            if (bodyFits(nx, n.y(), nz)) {
                for (int drop = 1; drop <= maxDrop; drop++) {
                    int ny = n.y() - drop;
                    if (!bodyFits(nx, ny, nz)) break;
                    if (supported(nx, ny, nz)) {
                        // a drop can't be steered: never land right next to lava
                        if (besideDanger(nx, ny, nz)) break;
                        out.add(new Move(new Node(nx, ny, nz), base + drop * 0.5 + (drop > 2 ? 2 : 0) + world.moveCost(nx, ny, nz)));
                        break;
                    }
                }
            }
        }
        // swim up / down in water
        if (inWater) {
            if (bodyFits(n.x(), n.y() + 1, n.z())) out.add(new Move(new Node(n.x(), n.y() + 1, n.z()), 2.5));
            if (bodyFits(n.x(), n.y() - 1, n.z()) && world.isWater(n.x(), n.y() - 1, n.z())) {
                out.add(new Move(new Node(n.x(), n.y() - 1, n.z()), 2.5));
            }
        }
        return out;
    }

    private void tryFly(Node n, int dx, int dy, int dz, List<Move> out) {
        int nx = n.x() + dx, ny = n.y() + dy, nz = n.z() + dz;
        if (!bodyFits(nx, ny, nz)) return;
        if (dx != 0 && dz != 0 && !(bodyFits(n.x() + dx, ny, n.z()) && bodyFits(n.x(), ny, n.z() + dz))) return;
        double cost = (dx != 0 && dz != 0) ? 1.414 : 1.0;
        out.add(new Move(new Node(nx, ny, nz), cost));
    }
}
