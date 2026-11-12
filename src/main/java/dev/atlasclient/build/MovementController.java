package dev.atlasclient.build;

import dev.atlasclient.core.path.PathFollower;
import java.util.List;
import dev.atlasclient.core.path.Pathfinder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

/**
 * Walks the player along a path by holding the normal movement keys and turning the camera, the
 * same inputs a person would use. Nothing about the player's position is faked. Releases every
 * key it pressed when it stops.
 */
public final class MovementController {
    private final PathFollower follower = new PathFollower();
    private boolean controlling;
    private Pathfinder.Goal goal;
    private int replans;
    private int maxDrop = 3;

    /** Highest drop the path may take (Auto Mine keeps this low so it never walks into holes). */
    public void setMaxDrop(int blocks) { this.maxDrop = blocks; }

    public PathFollower follower() { return follower; }
    public boolean active() { return controlling; }
    public Pathfinder.Goal goal() { return goal; }

    public int maxDrop() { return maxDrop; }

    /** Walks a path planned elsewhere (e.g. the free part of a path that digs through blocks). */
    public void followPath(List<Pathfinder.Node> path, ClientPlayerEntity p, boolean sprint) {
        Pathfinder.Node end = path.get(path.size() - 1);
        this.goal = new Pathfinder.Goal(end.x(), end.y(), end.z(), 0.5);
        follower.setSprint(sprint);
        follower.setFlying(p.getAbilities().flying);
        follower.follow(new java.util.ArrayList<>(path));
        controlling = true;
        replans = 0;
    }

    private int[] fence;

    /** Keep walks inside this box (body blocks); null = anywhere. */
    public void setFence(int[] box) { this.fence = box; }

    /** {@code world}, limited to the fence if there is one. */
    public dev.atlasclient.core.path.PathWorld fenced(dev.atlasclient.core.path.PathWorld world) {
        if (fence == null) return world;
        net.minecraft.client.network.ClientPlayerEntity p = net.minecraft.client.MinecraftClient.getInstance().player;
        return new dev.atlasclient.core.path.FencedWorld(world, fence[0], fence[1], fence[2], fence[3], fence[4], fence[5], true)
                .deepWaterOk(p != null && p.isTouchingWater());
    }

    public void navigate(McWorld world, ClientPlayerEntity p, Pathfinder.Goal g, boolean sprint, int maxNodes) {
        this.goal = g;
        Pathfinder pf = new Pathfinder(fenced(world)).maxNodes(maxNodes).canFly(p.getAbilities().flying).maxDrop(maxDrop);
        Pathfinder.Node start = new Pathfinder.Node(p.getBlockPos().getX(), p.getBlockPos().getY(), p.getBlockPos().getZ());
        Pathfinder.Result r = pf.find(start, g);
        follower.setSprint(sprint);
        follower.setFlying(p.getAbilities().flying);
        follower.follow(r.path());
        controlling = r.found();
        replans = 0;
    }

    /** Re-plans around the node where the player got stuck. Returns false after too many tries. */
    public boolean replan(McWorld world, ClientPlayerEntity p, boolean sprint, int maxNodes) {
        if (goal == null || ++replans > 6) {
            stop();
            return false;
        }
        Pathfinder pf = new Pathfinder(fenced(world)).maxNodes(maxNodes).canFly(p.getAbilities().flying).maxDrop(maxDrop);
        Pathfinder.Node stuck = follower.stuckNode();
        if (stuck != null) pf.avoid(stuck);
        Pathfinder.Result r = pf.find(new Pathfinder.Node(p.getBlockPos().getX(), p.getBlockPos().getY(), p.getBlockPos().getZ()), goal);
        follower.follow(r.path());
        controlling = r.found();
        return controlling;
    }

    /** Applies this tick's movement. Call every client tick while navigating. */
    public PathFollower.Status tick(MinecraftClient mc) {
        if (!controlling || mc.player == null) {
            return PathFollower.Status.IDLE;
        }
        ClientPlayerEntity p = mc.player;
        PathFollower.Intent in = follower.tick(p.getX(), p.getY(), p.getZ());
        if (follower.status() != PathFollower.Status.FOLLOWING) {
            release(mc);
            if (follower.status() == PathFollower.Status.ARRIVED) controlling = false;
            return follower.status();
        }
        // Turn smoothly toward the waypoint (max 30 degrees per tick).
        float diff = MathHelper.wrapDegrees(in.yaw() - p.getYaw());
        p.setYaw(p.getYaw() + MathHelper.clamp(diff, -30f, 30f));
        boolean facing = Math.abs(diff) < 50;
        mc.options.forwardKey.setPressed(in.forward() && facing);
        mc.options.jumpKey.setPressed(in.jump() || in.ascend());
        mc.options.sneakKey.setPressed(in.descend());
        mc.options.sprintKey.setPressed(in.sprint());
        return follower.status();
    }

    public void stop() {
        if (controlling) {
            release(MinecraftClient.getInstance());
        }
        controlling = false;
        follower.stop();
        goal = null;
    }

    private static void release(MinecraftClient mc) {
        mc.options.forwardKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
    }

    /** Camera angles that look from the eye at a point. */
    public static float[] lookAt(ClientPlayerEntity p, double x, double y, double z) {
        double dx = x - p.getX(), dy = y - p.getEyeY(), dz = z - p.getZ();
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, h));
        return new float[]{yaw, MathHelper.clamp(pitch, -90f, 90f)};
    }
}
