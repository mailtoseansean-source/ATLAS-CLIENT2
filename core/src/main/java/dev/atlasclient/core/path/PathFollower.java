package dev.atlasclient.core.path;

import java.util.List;

/**
 * Turns a path into per-tick movement intents (yaw + forward/jump/sneak/sprint) and detects when
 * the player is stuck so the caller can replan. Movement is executed by pressing the normal
 * movement keys; no positions or packets are faked.
 */
public final class PathFollower {
    public record Intent(float yaw, boolean forward, boolean jump, boolean sneak, boolean sprint, boolean ascend, boolean descend) {
        public static final Intent IDLE = new Intent(0, false, false, false, false, false, false);
    }

    public enum Status { IDLE, FOLLOWING, ARRIVED, STUCK }

    private List<Pathfinder.Node> path = List.of();
    private int index;
    private int ticksWithoutProgress;
    private double bestDistance = Double.MAX_VALUE;
    private int stuckTicksLimit = 40;
    private boolean sprint;
    private boolean flying;
    private Status status = Status.IDLE;

    public void setStuckTicksLimit(int ticks) { this.stuckTicksLimit = Math.max(10, ticks); }
    public void setSprint(boolean sprint) { this.sprint = sprint; }
    public void setFlying(boolean flying) { this.flying = flying; }

    public void follow(List<Pathfinder.Node> newPath) {
        this.path = newPath == null ? List.of() : List.copyOf(newPath);
        this.index = path.size() > 1 ? 1 : 0;
        this.ticksWithoutProgress = 0;
        this.bestDistance = Double.MAX_VALUE;
        this.status = path.isEmpty() ? Status.IDLE : Status.FOLLOWING;
    }

    public void stop() {
        path = List.of();
        status = Status.IDLE;
    }

    public Status status() { return status; }
    public List<Pathfinder.Node> path() { return path; }
    public int index() { return index; }

    public Pathfinder.Node current() { return index < path.size() ? path.get(index) : null; }

    /** Computes the intent for this tick given the player's position. */
    public Intent tick(double px, double py, double pz) {
        if (status != Status.FOLLOWING || path.isEmpty()) {
            return Intent.IDLE;
        }
        Pathfinder.Node target = path.get(index);
        double tx = target.x() + 0.5, tz = target.z() + 0.5;
        double dx = tx - px, dz = tz - pz;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double dy = target.y() - py;
        if (horiz < 0.35 && Math.abs(dy) < (flying ? 0.6 : 1.2)) {
            index++;
            ticksWithoutProgress = 0;
            bestDistance = Double.MAX_VALUE;
            if (index >= path.size()) {
                status = Status.ARRIVED;
                return Intent.IDLE;
            }
            target = path.get(index);
            tx = target.x() + 0.5;
            tz = target.z() + 0.5;
            dx = tx - px;
            dz = tz - pz;
            horiz = Math.sqrt(dx * dx + dz * dz);
            dy = target.y() - py;
        }
        double dist = Math.sqrt(horiz * horiz + dy * dy);
        if (dist < bestDistance - 0.05) {
            bestDistance = dist;
            ticksWithoutProgress = 0;
        } else if (++ticksWithoutProgress > stuckTicksLimit) {
            status = Status.STUCK;
            return Intent.IDLE;
        }
        // Minecraft yaw: 0 = +Z (south), 90 = -X (west)
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        boolean jump = !flying && dy > 0.5 && horiz < 1.6;
        boolean slowDown = horiz < 0.6;
        // Never sprint off a drop: the run-up carries the player past the landing spot.
        boolean dropAhead = dy < -1.2 || (index + 1 < path.size() && path.get(index + 1).y() < target.y() - 1);
        return new Intent(yaw, horiz > 0.15, jump, false, sprint && !slowDown && !dropAhead && dy <= 0.5,
                flying && dy > 0.3, flying && dy < -0.3);
    }

    /** The node the player was trying to reach when stuck (to blacklist before replanning). */
    public Pathfinder.Node stuckNode() { return current(); }
}
