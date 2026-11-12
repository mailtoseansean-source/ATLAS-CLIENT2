package dev.atlasclient.core.build;

import dev.atlasclient.core.build.ExcavationPlanner.Entry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Progress of the excavation phase. Mining proceeds from the highest remaining layer downward
 * (one layer of slack so the player can work around the block under their feet). The block the
 * player stands on is never selected. Client thread only.
 */
public final class ExcavationSession {
    public enum EntryState { PENDING, MINING, DONE, SKIPPED, FAILED }

    public record Progress(int total, int done, int skipped, int failed, int remaining) {
        public double fraction() { return total == 0 ? 1 : (done + skipped + failed) / (double) total; }
    }

    private final List<Entry> entries;
    private final EntryState[] states;
    private final int[] attempts;
    private final long[] startedTick;
    private int maxAttempts = 3;
    private int timeoutTicks = 600; // 30 s: obsidian by hand takes ~250 s, so slow blocks get extended
    private String lastProblem = "";

    public ExcavationSession(ExcavationPlanner.Plan plan) {
        this.entries = plan.entries();
        this.states = new EntryState[entries.size()];
        this.attempts = new int[entries.size()];
        this.startedTick = new long[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            states[i] = entries.get(i).skip() == ExcavationPlanner.Skip.NONE ? EntryState.PENDING : EntryState.SKIPPED;
        }
    }

    public void setMaxAttempts(int n) { maxAttempts = Math.max(1, n); }
    public void setTimeoutTicks(int t) { timeoutTicks = Math.max(20, t); }
    public List<Entry> entries() { return entries; }
    public EntryState state(int i) { return states[i]; }
    public String lastProblem() { return lastProblem; }

    public boolean finished() {
        for (EntryState s : states) {
            if (s == EntryState.PENDING || s == EntryState.MINING) return false;
        }
        return true;
    }

    private int highestPendingY() {
        int y = Integer.MIN_VALUE;
        for (int i = 0; i < states.length; i++) {
            if (states[i] == EntryState.PENDING || states[i] == EntryState.MINING) {
                y = Math.max(y, entries.get(i).y());
            }
        }
        return y;
    }

    /**
     * Next block to mine within reach, nearest first. Blocks below the player's feet are excluded.
     * Returns -1 when nothing is reachable right now.
     */
    public int nextTarget(double px, double py, double pz, double reach) {
        List<Integer> c = candidates(px, py, pz, reach, true);
        return c.isEmpty() ? -1 : c.get(0);
    }

    /** Every entry that {@link #nextTarget} could pick right now, best first. */
    public List<Integer> targetsInReach(double px, double py, double pz, double reach) {
        return candidates(px, py, pz, reach, true);
    }

    /**
     * Like {@link #targetsInReach} but from every layer, highest first. Used when the top layers are
     * still buried with no face to click (a room dug from the side): opening a lower block first is
     * the only way in.
     */
    public List<Integer> targetsInReachAnyLayer(double px, double py, double pz, double reach) {
        return candidates(px, py, pz, reach, true, true);
    }

    /** Nearest pending entry regardless of reach, for navigation. */
    public int nearestPending(double px, double py, double pz) {
        List<Integer> c = candidates(px, py, pz, Double.MAX_VALUE, false);
        return c.isEmpty() ? -1 : c.get(0);
    }

    private List<Integer> candidates(double px, double py, double pz, double reach, boolean excludeStanding) {
        return candidates(px, py, pz, reach, excludeStanding, false);
    }

    private List<Integer> candidates(double px, double py, double pz, double reach, boolean excludeStanding, boolean anyLayer) {
        int top = highestPendingY();
        List<Integer> out = new ArrayList<>();
        if (top == Integer.MIN_VALUE) {
            return out;
        }
        int standX = (int) Math.floor(px), standY = (int) Math.floor(py - 0.2), standZ = (int) Math.floor(pz);
        double eyeY = py + 1.62;
        double r2 = reach * reach;
        for (int i = 0; i < entries.size(); i++) {
            if (states[i] != EntryState.PENDING && states[i] != EntryState.MINING) continue;
            Entry e = entries.get(i);
            if (!anyLayer && e.y() < top - 1) continue;
            if (excludeStanding && e.x() == standX && e.y() == standY && e.z() == standZ) continue;
            double dx = e.x() + 0.5 - px, dy = e.y() + 0.5 - eyeY, dz = e.z() + 0.5 - pz;
            if (dx * dx + dy * dy + dz * dz > r2) continue;
            out.add(i);
        }
        out.sort(Comparator.comparingInt((Integer i) -> -entries.get(i).y()).thenComparingDouble(i -> {
            Entry e = entries.get(i);
            double dx = e.x() + 0.5 - px, dy = e.y() + 0.5 - eyeY, dz = e.z() + 0.5 - pz;
            return dx * dx + dy * dy + dz * dz;
        }));
        return out;
    }

    public void markStarted(int i, long tick) {
        if (states[i] == EntryState.PENDING) {
            attempts[i]++;
            startedTick[i] = tick;
        }
        states[i] = EntryState.MINING;
    }

    /**
     * Reports the world state of an entry being mined.
     *
     * @param nowEmpty  the position is now air/replaceable
     * @param fluidNow  fluid flowed into the cell or a neighbour while mining
     */
    public EntryState update(int i, boolean nowEmpty, boolean fluidNow, long tick) {
        if (states[i] == EntryState.DONE || states[i] == EntryState.SKIPPED || states[i] == EntryState.FAILED) {
            return states[i];
        }
        if (nowEmpty) {
            states[i] = EntryState.DONE;
        } else if (fluidNow) {
            states[i] = EntryState.SKIPPED;
            lastProblem = "Fluid next to " + pos(i) + "; stopped mining there";
        } else if (states[i] == EntryState.MINING && tick - startedTick[i] > timeoutTicks) {
            if (attempts[i] >= maxAttempts) {
                states[i] = EntryState.FAILED;
                lastProblem = "Could not break block at " + pos(i);
            } else {
                states[i] = EntryState.PENDING;
            }
        }
        return states[i];
    }

    /** Called when something new appears in an already-dug cell (sand fell in, someone placed a block). */
    public void reopen(int i) {
        if (states[i] == EntryState.DONE) {
            states[i] = EntryState.PENDING;
        }
    }

    public void abandon(int i, String reason) {
        states[i] = EntryState.SKIPPED;
        lastProblem = reason;
    }

    public Progress progress() {
        int done = 0, skipped = 0, failed = 0, remaining = 0;
        for (EntryState s : states) {
            switch (s) {
                case DONE -> done++;
                case SKIPPED -> skipped++;
                case FAILED -> failed++;
                default -> remaining++;
            }
        }
        return new Progress(states.length, done, skipped, failed, remaining);
    }

    private String pos(int i) {
        Entry e = entries.get(i);
        return e.x() + " " + e.y() + " " + e.z();
    }
}
