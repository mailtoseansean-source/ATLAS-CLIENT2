package dev.atlasclient.core.build;

import dev.atlasclient.core.build.PlacementPlanner.Entry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * State machine for one construction job. Owns per-entry progress; the game layer asks it for the
 * next targets, performs interactions, and reports verification results. Client thread only.
 */
public final class BuildSession {
    public enum State { READY, RUNNING, PAUSED, COMPLETED, CANCELLED }

    public enum EntryState {
        PENDING,
        /** An interaction was sent; waiting for the server's block update. */
        AWAITING_VERIFY,
        /** Placed, but a property still needs adjustment interactions. */
        NEEDS_ADJUST,
        DONE,
        ALREADY_CORRECT,
        /** A different block occupies the position (reported, not broken). */
        INCORRECT,
        /** Gave up after the maximum number of attempts. */
        FAILED,
        /** Skipped: missing material or missing support. */
        SKIPPED
    }

    public record Progress(int total, int done, int alreadyCorrect, int incorrect, int failed, int skipped,
                           int remaining, State state) {
        public double fraction() { return total == 0 ? 1 : (done + alreadyCorrect) / (double) total; }
    }

    private final List<Entry> entries;
    private final EntryState[] states;
    private final int[] attempts;
    private final long[] sentAtTick;
    private final int[] adjustmentsDone;
    private State state = State.READY;
    private int maxAttempts = 3;
    private int verifyTimeoutTicks = 20;
    private String lastFailure = "";

    public BuildSession(PlacementPlanner.Plan plan) {
        this.entries = plan.entries();
        int n = entries.size();
        this.states = new EntryState[n];
        this.attempts = new int[n];
        this.sentAtTick = new long[n];
        this.adjustmentsDone = new int[n];
        resetStates();
    }

    private void resetStates() {
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            states[i] = e.initialStatus() == WorldProbe.Status.MATCHES ? EntryState.ALREADY_CORRECT
                    : e.initialStatus() == WorldProbe.Status.WRONG_BLOCK ? EntryState.INCORRECT
                    : EntryState.PENDING;
            attempts[i] = 0;
            adjustmentsDone[i] = 0;
        }
    }

    public void setMaxAttempts(int n) { this.maxAttempts = Math.max(1, n); }
    public void setVerifyTimeoutTicks(int ticks) { this.verifyTimeoutTicks = Math.max(2, ticks); }

    public State state() { return state; }
    public List<Entry> entries() { return entries; }
    public EntryState entryState(int i) { return states[i]; }
    public int attempts(int i) { return attempts[i]; }
    public String lastFailure() { return lastFailure; }

    public boolean start() {
        if (state != State.READY) return false;
        state = State.RUNNING;
        checkComplete();
        return true;
    }

    public boolean pause() {
        if (state != State.RUNNING) return false;
        state = State.PAUSED;
        return true;
    }

    public boolean resume() {
        if (state != State.PAUSED) return false;
        state = State.RUNNING;
        // Unverified placements may have been lost while paused: re-check them.
        for (int i = 0; i < states.length; i++) {
            if (states[i] == EntryState.AWAITING_VERIFY) {
                sentAtTick[i] = Long.MIN_VALUE;
            }
        }
        return true;
    }

    public boolean cancel() {
        if (state == State.COMPLETED || state == State.CANCELLED) return false;
        state = State.CANCELLED;
        return true;
    }

    /** Back to READY with fresh per-entry state (world is re-checked by the caller via verify). */
    public void restart() {
        resetStates();
        state = State.READY;
        lastFailure = "";
    }

    public boolean isActive() { return state == State.RUNNING; }

    /**
     * Re-evaluates unfinished entries against the current world, e.g. after the excavation phase
     * removed blocks that were in the way when the plan was made.
     */
    public void refresh(WorldProbe probe) {
        for (int i = 0; i < entries.size(); i++) {
            if (states[i] == EntryState.DONE) {
                continue;
            }
            WorldProbe.Status st = probe.status(entries.get(i).target());
            states[i] = switch (st) {
                case MATCHES -> states[i] == EntryState.PENDING || states[i] == EntryState.INCORRECT
                        || states[i] == EntryState.ALREADY_CORRECT ? EntryState.ALREADY_CORRECT : EntryState.DONE;
                case WRONG_BLOCK -> EntryState.INCORRECT;
                case NEEDS_ADJUSTMENT -> EntryState.NEEDS_ADJUST;
                default -> states[i] == EntryState.FAILED ? EntryState.FAILED : EntryState.PENDING;
            };
        }
        checkComplete();
    }

    /**
     * Next entries to work on, nearest first, filtered by reach and material availability.
     * Entries whose support is still missing are deferred until their support appears.
     */
    public List<Integer> nextTargets(double px, double py, double pz, double reach, int limit,
                                     Predicate<Entry> hasMaterial, Predicate<Entry> supportPresent) {
        List<Integer> candidates = new ArrayList<>();
        if (state != State.RUNNING) {
            return candidates;
        }
        double r2 = reach * reach;
        int lowestPendingY = Integer.MAX_VALUE;
        for (int i = 0; i < entries.size(); i++) {
            if (states[i] == EntryState.PENDING || states[i] == EntryState.NEEDS_ADJUST) {
                lowestPendingY = Math.min(lowestPendingY, entries.get(i).target().y());
            }
        }
        for (int i = 0; i < entries.size(); i++) {
            EntryState s = states[i];
            if (s != EntryState.PENDING && s != EntryState.NEEDS_ADJUST) continue;
            Entry e = entries.get(i);
            // Stay within two layers of the lowest unfinished layer so supports come first.
            if (e.target().y() > lowestPendingY + 1) continue;
            if (dist2(e, px, py, pz) > r2) continue;
            if (s == EntryState.PENDING && !hasMaterial.test(e)) continue;
            if (s == EntryState.PENDING && e.support() != PlacementPlanner.Support.NONE_REQUIRED && !supportPresent.test(e)) continue;
            candidates.add(i);
        }
        candidates.sort(Comparator.comparingDouble(i -> dist2(entries.get(i), px, py, pz)));
        return candidates.size() > limit ? new ArrayList<>(candidates.subList(0, limit)) : candidates;
    }

    /** Nearest unfinished entry regardless of reach (navigation goal). Returns -1 when none. */
    public int nearestUnfinished(double px, double py, double pz, Predicate<Entry> hasMaterial) {
        int best = -1;
        double bestD = Double.MAX_VALUE;
        int lowestY = Integer.MAX_VALUE;
        for (int i = 0; i < entries.size(); i++) {
            if (states[i] == EntryState.PENDING || states[i] == EntryState.NEEDS_ADJUST) {
                lowestY = Math.min(lowestY, entries.get(i).target().y());
            }
        }
        for (int i = 0; i < entries.size(); i++) {
            if ((states[i] == EntryState.PENDING && hasMaterial.test(entries.get(i))) || states[i] == EntryState.NEEDS_ADJUST) {
                if (entries.get(i).target().y() > lowestY + 1) continue;
                double d = dist2(entries.get(i), px, py, pz);
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
        }
        return best;
    }

    public void markSent(int i, long tick) {
        if (states[i] == EntryState.PENDING) {
            attempts[i]++;
        }
        states[i] = EntryState.AWAITING_VERIFY;
        sentAtTick[i] = tick;
    }

    public void markAdjustmentSent(int i, long tick) {
        adjustmentsDone[i]++;
        states[i] = EntryState.AWAITING_VERIFY;
        sentAtTick[i] = tick;
    }

    public int adjustmentsDone(int i) { return adjustmentsDone[i]; }

    /** Indices awaiting verification whose result can be checked now. */
    public List<Integer> awaitingVerification() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < states.length; i++) {
            if (states[i] == EntryState.AWAITING_VERIFY) out.add(i);
        }
        return out;
    }

    /**
     * Applies an observed world status for an entry awaiting verification.
     *
     * @return the new entry state
     */
    public EntryState verify(int i, WorldProbe.Status observed, long tick) {
        if (states[i] != EntryState.AWAITING_VERIFY && states[i] != EntryState.PENDING) {
            return states[i];
        }
        switch (observed) {
            case MATCHES -> states[i] = EntryState.DONE;
            case NEEDS_ADJUSTMENT -> {
                if (adjustmentsDone[i] > entries.get(i).extraInteractions() + 4) {
                    states[i] = EntryState.FAILED;
                    lastFailure = "Could not adjust " + entries.get(i).target().state();
                } else {
                    states[i] = EntryState.NEEDS_ADJUST;
                }
            }
            case WRONG_BLOCK -> {
                states[i] = EntryState.INCORRECT;
                lastFailure = "Unexpected block at " + pos(i);
            }
            case EMPTY, UNAVAILABLE -> {
                if (states[i] == EntryState.AWAITING_VERIFY && tick - sentAtTick[i] < verifyTimeoutTicks) {
                    break; // still waiting for the server
                }
                if (attempts[i] >= maxAttempts) {
                    states[i] = EntryState.FAILED;
                    lastFailure = "Placement not confirmed after " + attempts[i] + " attempts at " + pos(i);
                } else {
                    states[i] = EntryState.PENDING;
                }
            }
        }
        checkComplete();
        return states[i];
    }

    /** Marks an entry skipped (e.g. no material, unreachable). */
    public void skip(int i, String reason) {
        states[i] = EntryState.SKIPPED;
        lastFailure = reason;
        checkComplete();
    }

    /** Re-opens skipped entries (e.g. after the player picked up more material). */
    public int retrySkipped() {
        int n = 0;
        for (int i = 0; i < states.length; i++) {
            if (states[i] == EntryState.SKIPPED || states[i] == EntryState.FAILED) {
                states[i] = EntryState.PENDING;
                attempts[i] = 0;
                n++;
            }
        }
        if (n > 0 && state == State.COMPLETED) {
            state = State.RUNNING;
        }
        return n;
    }

    /** Re-checks a finished entry against the world (detects later tampering / incorrect blocks). */
    public void recheck(int i, WorldProbe.Status observed) {
        if (states[i] == EntryState.DONE || states[i] == EntryState.ALREADY_CORRECT || states[i] == EntryState.INCORRECT) {
            switch (observed) {
                case MATCHES -> states[i] = states[i] == EntryState.INCORRECT ? EntryState.DONE : states[i];
                case WRONG_BLOCK -> states[i] = EntryState.INCORRECT;
                case EMPTY -> {
                    states[i] = EntryState.PENDING;
                    if (state == State.COMPLETED) state = State.RUNNING;
                }
                case NEEDS_ADJUSTMENT -> {
                    states[i] = EntryState.NEEDS_ADJUST;
                    if (state == State.COMPLETED) state = State.RUNNING;
                }
                default -> { }
            }
        }
    }

    public Progress progress() {
        int done = 0, already = 0, incorrect = 0, failed = 0, skipped = 0, remaining = 0;
        for (EntryState s : states) {
            switch (s) {
                case DONE -> done++;
                case ALREADY_CORRECT -> already++;
                case INCORRECT -> incorrect++;
                case FAILED -> failed++;
                case SKIPPED -> skipped++;
                default -> remaining++;
            }
        }
        return new Progress(states.length, done, already, incorrect, failed, skipped, remaining, state);
    }

    private void checkComplete() {
        if (state != State.RUNNING) return;
        for (EntryState s : states) {
            if (s == EntryState.PENDING || s == EntryState.AWAITING_VERIFY || s == EntryState.NEEDS_ADJUST) {
                return;
            }
        }
        state = State.COMPLETED;
    }

    private String pos(int i) {
        var t = entries.get(i).target();
        return t.x() + " " + t.y() + " " + t.z();
    }

    private static double dist2(Entry e, double px, double py, double pz) {
        double dx = e.target().x() + 0.5 - px, dy = e.target().y() + 0.5 - py, dz = e.target().z() + 0.5 - pz;
        return dx * dx + dy * dy + dz * dz;
    }
}
