package dev.atlasclient.build;

import dev.atlasclient.Atlas;
import dev.atlasclient.AtlasClient;
import dev.atlasclient.core.build.AreaMining;
import dev.atlasclient.core.build.BuildJob;
import dev.atlasclient.core.build.BuildSession;
import dev.atlasclient.core.build.ExcavationPlanner;
import dev.atlasclient.core.build.ExcavationSession;
import dev.atlasclient.core.build.MaterialList;
import dev.atlasclient.core.build.PlacementPlanner;
import dev.atlasclient.core.build.Restock;
import dev.atlasclient.core.build.RestockSession;
import dev.atlasclient.core.build.WorldProbe;
import dev.atlasclient.core.path.PathFollower;
import dev.atlasclient.core.path.Pathfinder;
import dev.atlasclient.core.schematic.SchematicPlacer.PlacedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Runs a {@link BuildJob} in the game, one small step per client tick, using only normal player
 * interactions:
 * <ul>
 *   <li>Excavation: look at an exposed face, {@code attackBlock} then {@code updateBlockBreakingProgress}
 *       each tick (vanilla mining, vanilla speed), with Regular/Amethyst (3x3) tool selection.</li>
 *   <li>Placement: select the item, turn toward the solved facing, and on the following tick
 *       {@code interactBlock} (a right-click). Results are verified from the server's block updates.</li>
 *   <li>Navigation: hold movement keys along an A* path when nothing is in reach.</li>
 * </ul>
 */
public final class BuildExecutor {
    public record Config(double reach, int placementsPerTick, int placeDelayTicks, boolean autoWalk, boolean sprint,
                         int hotbarSlot, AreaMining.PickaxeMode pickaxeMode, boolean edgeFallback, int minDurability,
                         List<String> areaKeywords, int manualAreaSlot, int pathNodes,
                         boolean restockFromShulkers, Restock.DropMode dropMode, Restock.DropScope dropScope,
                         Set<String> junk, Set<String> neverDrop, int keepFree) { }

    private final BuildJob job;
    private final StateResolver resolver;
    private final McWorld world;
    private final MovementController movement = new MovementController();
    private long tick;
    private int cooldown;
    // pending rotated placement
    private int pendingEntry = -1;
    private PlacementSolver.Solution pendingSolution;
    // current mining target
    private int miningEntry = -1;
    private long miningStartTick;
    /** Hotbar slot of the tool picked for the block being mined (-1 = whatever is in hand). */
    private int miningSlot = -1;
    /** Blocks where the 3x3 pickaxe did not get the block broken in time: use the regular pickaxe there. */
    private final Set<Long> noAreaCells = new HashSet<>();
    private Direction miningFace;
    private boolean miningWithAreaTool;
    private int navigateCooldown;
    private Set<String> missingAnnounced = new HashSet<>();
    private String lastStatus = "";
    private int recheckCursor;
    // shulker restock and dropping
    private RestockSession restock;
    private int restockCooldown;
    private boolean restockLookPending;
    private int dropSlotPending = -1;
    private int dropCooldown;
    private String lastRestockNote = "";
    private double buildCenterX, buildCenterZ;

    public BuildExecutor(BuildJob job, StateResolver resolver, McWorld world) {
        this.job = job;
        this.resolver = resolver;
        this.world = world;
        double cx = 0, cz = 0;
        int n = 0;
        for (PlacementPlanner.Entry e : job.build().entries()) {
            cx += e.target().x() + 0.5;
            cz += e.target().z() + 0.5;
            n++;
        }
        this.buildCenterX = n == 0 ? 0 : cx / n;
        this.buildCenterZ = n == 0 ? 0 : cz / n;
    }

    /** True while our own restock has a shulker box screen open (the module must not pause for it). */
    public boolean ownsScreen(MinecraftClient mc) {
        return restock != null && mc.player != null && mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler;
    }

    public boolean restocking() { return restock != null; }

    public BuildJob job() { return job; }
    public String status() { return lastStatus; }

    /** Unneeded items are thrown away from this point (Auto Mine passes a point ahead of the tunnel). */
    public void setDropCenter(double x, double z) {
        buildCenterX = x;
        buildCenterZ = z;
    }
    public MovementController movement() { return movement; }

    public void stop() {
        movement.stop();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.interactionManager != null && miningEntry >= 0) {
            mc.interactionManager.cancelBlockBreaking();
        }
        miningEntry = -1;
        pendingEntry = -1;
        dropSlotPending = -1;
        // A paused restock keeps its state and continues on resume; only the box screen is closed.
        if (restock != null && mc.player != null && mc.player.currentScreenHandler instanceof ShulkerBoxScreenHandler) {
            mc.player.closeHandledScreen();
        }
    }

    /** Stops for good (cancel/restart). Warns if a shulker box was left placed in the world. */
    public void abandon() {
        stop();
        if (restock != null && restock.phase() != RestockSession.Phase.PLACE && !restock.finished()) {
            int[] sp = restock.spot();
            Atlas.get().notifications.warn("Shulker box", "Restock cancelled: your shulker box may still be at "
                    + sp[0] + " " + sp[1] + " " + sp[2]);
        }
        restock = null;
    }

    public void tick(MinecraftClient mc, Config cfg) {
        PlacementTool.automating = true; // our own clicks are never placement-tool clicks
        try {
            tickInner(mc, cfg);
        } finally {
            PlacementTool.automating = false;
        }
    }

    private void tickInner(MinecraftClient mc, Config cfg) {
        tick++;
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.interactionManager == null || !job.isRunning()) {
            if (!job.isRunning()) stop();
            return;
        }
        job.advance();
        if (restock != null) {
            tickRestock(mc, p, cfg);
            return;
        }
        if (mc.currentScreen == null && tickDrops(mc, p, cfg)) {
            return;
        }
        if (job.phase() == BuildJob.Phase.EXCAVATING) {
            tickExcavation(mc, p, cfg);
        } else if (job.phase() == BuildJob.Phase.BUILDING) {
            tickBuilding(mc, p, cfg);
        } else {
            stop();
        }
        job.advance();
    }

    // ------------------------------------------------------------------ excavation

    private void tickExcavation(MinecraftClient mc, ClientPlayerEntity p, Config cfg) {
        ExcavationSession ex = job.excavation();
        if (miningEntry >= 0) {
            ExcavationPlanner.Entry e = ex.entries().get(miningEntry);
            BlockPos pos = new BlockPos(e.x(), e.y(), e.z());
            BlockState s = world.world().getBlockState(pos);
            boolean empty = s.isAir() || s.isReplaceable();
            boolean fluid = world.neighbourHasFluid(e.x(), e.y(), e.z()) && !empty;
            ExcavationSession.EntryState st = ex.update(miningEntry, empty, fluid && !miningWithAreaTool, tick);
            if (st != ExcavationSession.EntryState.MINING) {
                if (empty && miningWithAreaTool) {
                    markAreaCellsDone(ex, e);
                }
                mc.interactionManager.cancelBlockBreaking();
                AtlasClient.scan().invalidateAround(e.x(), e.z(), 0);
                miningEntry = -1;
                lastStatus = job.summary();
                // Go straight on to the next block in this same tick (no idle tick between blocks).
            }
            if (miningEntry >= 0 && miningWithAreaTool && tick - miningStartTick > AREA_TROUBLE_TICKS) {
                // The 3x3 pickaxe is having trouble here (blocks above or around in the way, or the
                // server's 3x3 not breaking it): switch to the regular pickaxe for this block.
                noAreaCells.add(pos.asLong());
                mc.interactionManager.cancelBlockBreaking();
                miningEntry = -1;
                lastStatus = job.summary() + " [3x3 trouble: regular pickaxe]";
                return;
            }
            if (miningEntry >= 0 && miningSlot >= 0 && p.getInventory().getSelectedSlot() != miningSlot) {
                // Something else changed the held item (a block placed to pillar up or fill a gap, food):
                // start this block again so the right tool is picked, never dig with a block in hand.
                mc.interactionManager.cancelBlockBreaking();
                miningEntry = -1;
                return;
            }
            if (miningEntry >= 0) {
                Vec3d hitPoint = Vec3d.ofCenter(pos).add(miningFace.getOffsetX() * 0.5, miningFace.getOffsetY() * 0.5, miningFace.getOffsetZ() * 0.5);
                look(p, hitPoint);
                mc.interactionManager.updateBlockBreakingProgress(pos, miningFace);
                p.swingHand(Hand.MAIN_HAND);
                return;
            }
        }
        // Instant breaks (Efficiency + Haste): a new block can be started as soon as one breaks; up to
        // "instant breaks per tick" of them (1 = like a player holding the button).
        for (int n = 0; n < Math.max(1, instantPerTick); n++) {
            if (!startNextBlock(mc, p, cfg, ex)) break;
        }
    }

    // ------------------------------------------------------------------ digging / bridging a way through

    private int[] bridgeBox;
    private Set<Long> unbridgeable = new HashSet<>();
    private BlockPos[] bridgeRequest;
    private int forcedEntry = -1;
    private long workRetryTick;

    /**
     * Lets the walk to a dig site go through blocks that are going to be mined anyway, and over holes
     * in the floor inside this box (a block gets placed there first, see {@link #takeBridgeRequest}).
     * {@code unbridgeable} collects holes that could not be filled.
     */
    public void setBridging(int minX, int maxX, int minY, int maxY, int minZ, int maxZ, Set<Long> unbridgeable) {
        bridgeBox = new int[]{minX, maxX, minY, maxY, minZ, maxZ};
        this.unbridgeable = unbridgeable;
    }

    /** {hole, support}: a floor block to place before walking on (taken once). */
    public BlockPos[] takeBridgeRequest() {
        BlockPos[] r = bridgeRequest;
        bridgeRequest = null;
        return r;
    }

    private boolean bridgeable(int x, int y, int z) {
        if (bridgeBox == null || x < bridgeBox[0] || x > bridgeBox[1] || y < bridgeBox[2] || y > bridgeBox[3]
                || z < bridgeBox[4] || z > bridgeBox[5]) return false;
        if (unbridgeable.contains(BlockPos.asLong(x, y, z))) return false;
        BlockState s = world.world().getBlockState(new BlockPos(x, y, z));
        if (!(s.isAir() || s.isReplaceable()) || !s.getFluidState().isEmpty()) return false;
        // No bridging anywhere near lava: the walk out onto the bridge and the sneaking to its edge
        // must not be able to slip into it.
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 2; dy++) {
            if (world.isDangerous(x + dx, y + dy, z + dz)) return false;
        }
        return true;
    }

    /** The world as seen by a walk that may dig through the blocks of this job and bridge holes. */
    private final class WorkWorld implements dev.atlasclient.core.path.PathWorld {
        final Set<Long> dig;

        WorkWorld(Set<Long> dig) { this.dig = dig; }

        boolean solidDig(int x, int y, int z) { return dig.contains(BlockPos.asLong(x, y, z)) && world.isSolid(x, y, z); }

        @Override public boolean isPassable(int x, int y, int z) { return world.isPassable(x, y, z) || solidDig(x, y, z); }
        @Override public boolean isStandable(int x, int y, int z) { return world.isStandable(x, y, z) || bridgeable(x, y, z); }
        @Override public boolean isDangerous(int x, int y, int z) { return world.isDangerous(x, y, z); }
        @Override public boolean isWater(int x, int y, int z) { return world.isWater(x, y, z); }
        @Override public boolean isLoaded(int x, int z) { return world.isLoaded(x, z); }

        @Override public double moveCost(int x, int y, int z) {
            double c = 0;
            if (solidDig(x, y, z)) c += 2.5;
            if (solidDig(x, y + 1, z)) c += 2.5;
            if (!world.isStandable(x, y - 1, z) && !world.isWater(x, y, z) && !world.isWater(x, y - 1, z)) c += 4;
            return c;
        }
    }

    private static final boolean WALK_WHILE_MINING = false;
    private long steeredTick = -1;

    /** Moves along the current path, once per game tick. */
    private void steer(MinecraftClient mc) {
        if (steeredTick == tick) return;
        steeredTick = tick;
        if (movement.tick(mc) == PathFollower.Status.STUCK) {
            movement.stop();
            workRetryTick = tick + 5; // replan shortly (a long wait here made every job slower)
        }
    }

    /** Solid floor all around (2 blocks): safe to walk while looking at the blocks being broken. */
    private boolean safeGround(ClientPlayerEntity p) {
        if (!p.isOnGround() || p.isTouchingWater() || p.isInLava()) return false;
        int fx = p.getBlockX(), fy = p.getBlockY(), fz = p.getBlockZ();
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            int x = fx + dx, z = fz + dz;
            if (!world.isLoaded(x, z)) return false;
            if (world.isDangerous(x, fy, z) || world.isDangerous(x, fy - 1, z)) return false;
            if (world.isPassable(x, fy, z) && !world.isStandable(x, fy - 1, z)) return false;
        }
        return true;
    }

    /** Starts walking toward {@code goal} when the way there is open (no digging or bridging needed). */
    private void startWalkAhead(ClientPlayerEntity p, Config cfg, ExcavationSession ex, Pathfinder.Goal goal) {
        workRetryTick = tick + 10;
        Pathfinder pf = new Pathfinder(movement.fenced(world)).maxNodes(Math.min(cfg.pathNodes(), 800)).maxDrop(0);
        Pathfinder.Result r = pf.find(new Pathfinder.Node(p.getBlockX(), p.getBlockY(), p.getBlockZ()), goal);
        if (r.path().size() < 2) return;
        // Only flat steps on the same level (anything else: stop and do it the careful way).
        List<Pathfinder.Node> path = r.path();
        int i = 1;
        while (i < path.size() && path.get(i).y() == p.getBlockY()) i++;
        if (i < 2) return;
        movement.followPath(path.subList(0, i), p, cfg.sprint());
    }

    /** Blocks that must be open for the step {@code a -> b}, in the order they should be dug. */
    private static List<BlockPos> stepCells(Pathfinder.Node a, Pathfinder.Node b) {
        List<BlockPos> out = new ArrayList<>(8);
        if (b.y() > a.y()) out.add(new BlockPos(a.x(), a.y() + 2, a.z())); // head room for the jump
        int dx = b.x() - a.x(), dz = b.z() - a.z();
        if (dx != 0 && dz != 0) {
            for (int y = a.y() + 1; y >= a.y(); y--) {
                out.add(new BlockPos(a.x() + dx, y, a.z()));
                out.add(new BlockPos(a.x(), y, a.z() + dz));
            }
        }
        for (int y = Math.max(a.y(), b.y()) + 1; y >= b.y(); y--) out.add(new BlockPos(b.x(), y, b.z()));
        return out;
    }

    /**
     * Walks toward {@code goal}; when the only way there goes through blocks of this job or over a
     * hole in the floor, walks the free part, then digs the block in the way (or asks for a bridge).
     */
    private void workToward(MinecraftClient mc, ClientPlayerEntity p, Config cfg, ExcavationSession ex, Pathfinder.Goal goal, String why) {
        if (!cfg.autoWalk()) {
            walkTo(p, cfg, goal, why);
            return;
        }
        if (movement.active()) {
            steer(mc);
            lastStatus = why + " (" + job.summary() + ")";
            return;
        }
        if (tick < workRetryTick) return;
        Set<Long> digSet = pendingDigCells(ex);
        WorkWorld ww = new WorkWorld(digSet);
        Pathfinder pf = new Pathfinder(movement.fenced(ww)).maxNodes(cfg.pathNodes()).maxDrop(movement.maxDrop());
        Pathfinder.Node startNode = new Pathfinder.Node(p.getBlockX(), p.getBlockY(), p.getBlockZ());
        Pathfinder.Result r = pf.find(startNode, goal);
        List<Pathfinder.Node> path = r.path();
        int savedTop = bridgeBox == null ? 0 : bridgeBox[3];
        if (path.size() < 2 && bridgeBox != null && p.getBlockY() - 1 > bridgeBox[3]) {
            // No way at all (e.g. standing on a ledge over a big cave): also allow bridging at its own
            // level. Only as a last resort - otherwise it fills back holes it has just dug.
            bridgeBox[3] = p.getBlockY() - 1;
            path = pf.find(startNode, goal).path();
        }
        try {
            workStep(p, cfg, ex, goal, why, path);
        } finally {
            if (bridgeBox != null) bridgeBox[3] = savedTop;
        }
    }

    private void workStep(ClientPlayerEntity p, Config cfg, ExcavationSession ex, Pathfinder.Goal goal, String why, List<Pathfinder.Node> path) {
        if (path.size() < 2) {
            workRetryTick = tick + 5; // planning is not free: don't redo a failed search every tick
            lastStatus = "No path to " + goal.x() + " " + goal.y() + " " + goal.z() + " - move closer manually";
            return;
        }
        int i = 1;
        for (; i < path.size(); i++) {
            Pathfinder.Node a = path.get(i - 1), b = path.get(i);
            boolean blocked = !world.isStandable(b.x(), b.y() - 1, b.z()) && !world.isWater(b.x(), b.y(), b.z()) && !world.isWater(b.x(), b.y() - 1, b.z());
            for (BlockPos c : stepCells(a, b)) if (world.isSolid(c.getX(), c.getY(), c.getZ())) blocked = true;
            if (blocked) break;
        }
        if (i > 1) {
            movement.followPath(path.subList(0, i), p, cfg.sprint());
            lastStatus = why + " (" + job.summary() + ")";
            return;
        }
        Pathfinder.Node a = path.get(0), b = path.get(1);
        for (BlockPos c : stepCells(a, b)) {
            if (!world.isSolid(c.getX(), c.getY(), c.getZ())) continue;
            long key = c.asLong();
            for (int k = 0; k < ex.entries().size(); k++) {
                ExcavationPlanner.Entry e = ex.entries().get(k);
                if (BlockPos.asLong(e.x(), e.y(), e.z()) == key && ex.state(k) == ExcavationSession.EntryState.PENDING) {
                    forcedEntry = k;
                    workRetryTick = tick;
                    lastStatus = "Digging a way through (" + job.summary() + ")";
                    return;
                }
            }
            lastStatus = "No path to " + goal.x() + " " + goal.y() + " " + goal.z() + " - move closer manually";
            return;
        }
        // The floor is missing: bridge it (only flat steps; a diagonal step bridges one side first).
        BlockPos support = new BlockPos(a.x(), a.y() - 1, a.z());
        BlockPos hole = new BlockPos(b.x(), b.y() - 1, b.z());
        int dx = b.x() - a.x(), dz = b.z() - a.z();
        if (dx != 0 && dz != 0) {
            hole = world.isStandable(a.x() + dx, a.y() - 1, a.z()) ? new BlockPos(a.x(), a.y() - 1, a.z() + dz) : new BlockPos(a.x() + dx, a.y() - 1, a.z());
        }
        if (b.y() != a.y() || p.isTouchingWater() || !world.isStandable(support.getX(), support.getY(), support.getZ())
                || !bridgeable(hole.getX(), hole.getY(), hole.getZ())) {
            unbridgeable.add(hole.asLong());
            workRetryTick = tick;
            return;
        }
        bridgeRequest = new BlockPos[]{hole, support};
        lastStatus = "Bridging a hole (" + job.summary() + ")";
    }

    /** Blocks broken instantly at most per tick (1 = vanilla-like). */
    private int instantPerTick = 1;

    public void setInstantBreaksPerTick(int n) { instantPerTick = Math.max(1, Math.min(4, n)); }

    /**
     * Picks the next block and starts breaking it. Returns true when it broke instantly (so another
     * one may be started this tick).
     */
    private boolean startNextBlock(MinecraftClient mc, ClientPlayerEntity p, Config cfg, ExcavationSession ex) {
        List<Integer> inReach = new ArrayList<>(ex.targetsInReach(p.getX(), p.getY(), p.getZ(), cfg.reach()));
        // Lower layers last: only used when nothing in the top layers has a face to click.
        Set<Integer> seen = new HashSet<>(inReach);
        for (int i : ex.targetsInReachAnyLayer(p.getX(), p.getY(), p.getZ(), cfg.reach())) {
            if (seen.add(i)) inReach.add(i);
        }
        if (inReach.isEmpty()) {
            int nearest = ex.nearestPending(p.getX(), p.getY(), p.getZ());
            if (nearest < 0) {
                lastStatus = job.summary();
                return false;
            }
            ExcavationPlanner.Entry e = ex.entries().get(nearest);
            workToward(mc, p, cfg, ex, new Pathfinder.Goal(e.x(), e.y(), e.z(), cfg.reach() - 0.7, true), "Walking to dig site");
            return false;
        }
        Set<Long> digSet = pendingDigCells(ex);
        // Breaking while walking was tried and made the flat 20x20x20 test much slower (see TESTING.md):
        // off until it is reworked.
        boolean safe = WALK_WHILE_MINING && cfg.autoWalk() && safeGround(p);
        // Few blocks left in reach and more further on: start walking there and keep breaking on the way.
        if (safe && !movement.active() && inReach.size() <= 6 && digSet.size() > inReach.size() && tick >= workRetryTick) {
            int far = -1;
            double best = Double.MAX_VALUE;
            Set<Integer> near = new HashSet<>(inReach);
            for (int i = 0; i < ex.entries().size(); i++) {
                if (ex.state(i) != ExcavationSession.EntryState.PENDING || near.contains(i)) continue;
                ExcavationPlanner.Entry ce = ex.entries().get(i);
                double d = Math.pow(ce.x() + 0.5 - p.getX(), 2) + Math.pow(ce.y() + 0.5 - p.getY(), 2) + Math.pow(ce.z() + 0.5 - p.getZ(), 2);
                if (d < best) { best = d; far = i; }
            }
            if (far >= 0) {
                ExcavationPlanner.Entry ce = ex.entries().get(far);
                startWalkAhead(p, cfg, ex, new Pathfinder.Goal(ce.x(), ce.y(), ce.z(), cfg.reach() - 0.7, true));
            }
        }
        boolean walking = movement.active();
        if (walking) steer(mc);
        float pathYaw = p.getYaw();
        if (walking && movement.follower().current() != null) {
            Pathfinder.Node wp = movement.follower().current();
            pathYaw = MovementController.lookAt(p, wp.x() + 0.5, p.getEyeY(), wp.z() + 0.5)[0];
        }
        Vec3d eye = p.getEyePos();
        // A block in the way of the path to the dig site goes first.
        if (forcedEntry >= 0) {
            int fe = forcedEntry;
            forcedEntry = -1;
            if (fe < ex.entries().size() && ex.state(fe) == ExcavationSession.EntryState.PENDING && inReach.contains(fe)) {
                inReach.remove(Integer.valueOf(fe));
                inReach.add(0, fe);
            }
        }
        java.util.function.Predicate<int[]> allowed = c -> {
            BlockState cs = world.world().getBlockState(new BlockPos(c[0], c[1], c[2]));
            return cs.isAir() || cs.isReplaceable() || digSet.contains(BlockPos.asLong(c[0], c[1], c[2]));
        };
        java.util.function.Predicate<int[]> fluidNear = c -> world.neighbourHasFluid(c[0], c[1], c[2]);
        // The best candidate may still be buried (e.g. the top row of a 3x3 tunnel before the
        // middle is open): take the first one that has a face we can see and reach.
        int idx = -1, areaFace = -1, plainFace = -1;
        BlockPos pos = null;
        BlockState state = null;
        ExcavationPlanner.Entry e = null;
        // Amethyst mode: first look for a block whose 3x3 swing stays inside the dig area (the middle of a
        // 3x3 face clears all 9 at once); otherwise the first block that can be clicked at all.
        if (cfg.pickaxeMode() == AreaMining.PickaxeMode.AMETHYST) {
            for (int cand : inReach) {
                ExcavationPlanner.Entry ce = ex.entries().get(cand);
                BlockPos cpos = new BlockPos(ce.x(), ce.y(), ce.z());
                if (noAreaCells.contains(cpos.asLong())) continue;
                BlockState cstate = world.world().getBlockState(cpos);
                if (cstate.isAir() || cstate.isReplaceable()) continue;
                java.util.function.Predicate<Integer> exposed = f -> {
                    BlockState n = world.world().getBlockState(cpos.offset(Direction.byIndex(f)));
                    return n.isAir() || n.isReplaceable() || !n.isOpaqueFullCube();
                };
                int af = AreaMining.chooseFace(ce.x(), ce.y(), ce.z(), eye.x, eye.y, eye.z, true, 1, exposed, allowed, fluidNear);
                if (af < 0) continue;
                int pf = AreaMining.chooseFace(ce.x(), ce.y(), ce.z(), eye.x, eye.y, eye.z, false, 1, exposed, allowed, fluidNear);
                idx = cand;
                e = ce;
                pos = cpos;
                state = cstate;
                areaFace = af;
                plainFace = pf >= 0 ? pf : af;
                break;
            }
        }
        int aside = -1, asideFace = -1;
        for (int cand : (idx >= 0 ? List.<Integer>of() : inReach)) {
            ExcavationPlanner.Entry ce = ex.entries().get(cand);
            BlockPos cpos = new BlockPos(ce.x(), ce.y(), ce.z());
            BlockState cstate = world.world().getBlockState(cpos);
            if (cstate.isAir() || cstate.isReplaceable()) {
                // Already open (cave, fallen away): done, look at the next one right away.
                ex.markStarted(cand, tick);
                ex.update(cand, true, false, tick);
                continue;
            }
            java.util.function.Predicate<Integer> exposed = f -> {
                Direction d = Direction.byIndex(f);
                BlockState n = world.world().getBlockState(cpos.offset(d));
                return n.isAir() || n.isReplaceable() || !n.isOpaqueFullCube();
            };
            int pf = AreaMining.chooseFace(ce.x(), ce.y(), ce.z(), eye.x, eye.y, eye.z, false, 1, exposed, allowed, fluidNear);
            if (pf < 0) continue;
            if (walking && Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(
                    MovementController.lookAt(p, ce.x() + 0.5, ce.y() + 0.5, ce.z() + 0.5)[0] - pathYaw)) > 40) {
                if (aside < 0) { aside = cand; asideFace = pf; }
                continue; // walking: only blocks ahead (looking at them steers the walk)
            }
            idx = cand;
            e = ce;
            pos = cpos;
            state = cstate;
            plainFace = pf;
            areaFace = noAreaCells.contains(cpos.asLong()) ? -1
                    : AreaMining.chooseFace(ce.x(), ce.y(), ce.z(), eye.x, eye.y, eye.z, true, 1, exposed, allowed, fluidNear);
            break;
        }
        if (idx < 0 && walking && aside >= 0) {
            // Nothing ahead, but blocks beside or behind still to do: stop and do those first.
            movement.stop();
            workRetryTick = tick + 40;
            idx = aside;
            e = ex.entries().get(aside);
            pos = new BlockPos(e.x(), e.y(), e.z());
            state = world.world().getBlockState(pos);
            plainFace = asideFace;
            areaFace = -1;
        }
        if (idx < 0 && walking) {
            return false; // nothing ahead to break yet: keep walking
        }
        if (idx < 0) {
            // No visible exposed face from here; try again later or after moving.
            ExcavationPlanner.Entry first = ex.entries().get(inReach.get(0));
            if (navigateCooldown-- <= 0) {
                // In water nothing can be mined (faces next to water are skipped): get much closer, on dry ground.
                double range = p.isTouchingWater() ? 2.0 : cfg.reach() - 1.0;
                walkTo(p, cfg, new Pathfinder.Goal(first.x(), first.y(), first.z(), range, true), "Repositioning");
                navigateCooldown = 20;
            }
            return false;
        }
        List<AreaMining.Tool> tools = InventoryHelper.tools(p, state, cfg.areaKeywords(), cfg.manualAreaSlot());
        AreaMining.Tool tool = AreaMining.chooseTool(cfg.pickaxeMode(), tools, areaFace >= 0, cfg.edgeFallback(), cfg.minDurability());
        if (tool == null) {
            ex.abandon(idx, "No safe tool for " + e.x() + " " + e.y() + " " + e.z()
                    + (cfg.pickaxeMode() == AreaMining.PickaxeMode.AMETHYST ? " (3x3 unsafe here and regular fallback is off)" : ""));
            return false;
        }
        if (!InventoryHelper.selectSlot(mc, tool.slot(), cfg.hotbarSlot())) {
            return false; // swapped into hotbar; mine next tick
        }
        boolean area = tool.areaTool();
        // Keep walking only for blocks that break at once, on solid ground; otherwise stand and dig.
        if (movement.active() && (area || !safe || state.calcBlockBreakingDelta(p, mc.world, pos) < 1.0f)) movement.stop();
        miningSlot = tool.slot() >= 0 ? p.getInventory().getSelectedSlot() : -1;
        miningFace = Direction.byIndex(area ? areaFace : plainFace);
        miningWithAreaTool = area;
        Vec3d hitPoint = Vec3d.ofCenter(pos).add(miningFace.getOffsetX() * 0.5, miningFace.getOffsetY() * 0.5, miningFace.getOffsetZ() * 0.5);
        look(p, hitPoint);
        mc.interactionManager.attackBlock(pos, miningFace);
        p.swingHand(Hand.MAIN_HAND);
        ex.markStarted(idx, tick);
        miningEntry = idx;
        miningStartTick = tick;
        lastStatus = String.format(Locale.ROOT, "%s [%s]", job.summary(), area ? "Amethyst 3x3" : "Regular");
        BlockState after = world.world().getBlockState(pos);
        if (!area && (after.isAir() || after.isReplaceable())) {
            // Broke instantly: count it now instead of waiting a tick to notice.
            ex.update(idx, true, false, tick);
            AtlasClient.scan().invalidateAround(e.x(), e.z(), 0);
            miningEntry = -1;
            return true;
        }
        return false;
    }

    private static final int AREA_TROUBLE_TICKS = 100;

    private Set<Long> pendingDigCells(ExcavationSession ex) {
        Set<Long> s = new HashSet<>();
        for (int i = 0; i < ex.entries().size(); i++) {
            ExcavationSession.EntryState st = ex.state(i);
            if (st == ExcavationSession.EntryState.PENDING || st == ExcavationSession.EntryState.MINING) {
                ExcavationPlanner.Entry e = ex.entries().get(i);
                s.add(BlockPos.asLong(e.x(), e.y(), e.z()));
            }
        }
        return s;
    }

    /** After a 3x3 swing, cells broken by the extra 8 are confirmed from the world. */
    private void markAreaCellsDone(ExcavationSession ex, ExcavationPlanner.Entry center) {
        for (int i = 0; i < ex.entries().size(); i++) {
            if (ex.state(i) != ExcavationSession.EntryState.PENDING) continue;
            ExcavationPlanner.Entry e = ex.entries().get(i);
            if (Math.abs(e.x() - center.x()) > 1 || Math.abs(e.y() - center.y()) > 1 || Math.abs(e.z() - center.z()) > 1) continue;
            BlockState s = world.world().getBlockState(new BlockPos(e.x(), e.y(), e.z()));
            if (s.isAir() || s.isReplaceable()) {
                ex.markStarted(i, tick);
                ex.update(i, true, false, tick);
            }
        }
    }

    // ------------------------------------------------------------------ building

    private void tickBuilding(MinecraftClient mc, ClientPlayerEntity p, Config cfg) {
        BuildSession bs = job.build();
        for (int i : bs.awaitingVerification()) {
            bs.verify(i, world.status(bs.entries().get(i).target()), tick);
        }
        recheckSome(bs);
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        // Finish a rotated placement prepared last tick.
        if (pendingEntry >= 0) {
            PlacementSolver.Solution sol = pendingSolution;
            int idx = pendingEntry;
            pendingEntry = -1;
            if (bs.entryState(idx) == BuildSession.EntryState.PENDING && sol != null
                    && Math.abs(p.getYaw() - sol.yaw()) < 0.5 && Math.abs(p.getPitch() - sol.pitch()) < 0.5) {
                place(mc, p, bs, idx, sol);
                cooldown = cfg.placeDelayTicks();
            }
            return;
        }
        boolean creative = InventoryHelper.creative(p);
        Map<String, Integer> inv = InventoryHelper.counts(p);
        List<Integer> targets = bs.nextTargets(p.getX(), p.getEyeY() - 1.62, p.getZ(), cfg.reach(), 12,
                e -> e.item() == null || creative || inv.getOrDefault(e.item(), 0) > 0,
                e -> supportPresent(e));
        int placed = 0;
        for (int idx : targets) {
            if (placed >= cfg.placementsPerTick()) break;
            PlacementPlanner.Entry e = bs.entries().get(idx);
            BlockPos pos = new BlockPos(e.target().x(), e.target().y(), e.target().z());
            if (overlapsPlayer(p, pos)) continue;
            BlockState wanted = resolver.resolve(e.target().state(), e.target().transform());
            if (wanted == null) {
                bs.skip(idx, "Unknown block " + e.target().state().id());
                continue;
            }
            if (bs.entryState(idx) == BuildSession.EntryState.NEEDS_ADJUST) {
                adjust(mc, p, bs, idx, pos);
                placed++;
                continue;
            }
            Item item = e.item() == null ? null : InventoryHelper.item(e.item());
            if (item == null) {
                bs.skip(idx, "No item for " + e.target().state().id());
                continue;
            }
            // Solve with a stand-in stack first, so the hotbar only changes for a block that can actually be
            // placed from here (otherwise two unplaceable targets could swap items back and forth forever).
            PlacementSolver.Solution sol = PlacementSolver.solve(world.world(), p, pos, wanted, new ItemStack(item), cfg.reach());
            if (sol == null) {
                continue; // no valid click from here (support missing or out of reach): try another block
            }
            if (!InventoryHelper.select(mc, item, cfg.hotbarSlot())) {
                return; // inventory swap / creative pick issued; place on the next tick
            }
            movement.stop();
            if (sol.needsRotation()) {
                p.setYaw(sol.yaw());
                p.setPitch(sol.pitch());
                pendingEntry = idx;
                pendingSolution = sol;
                lastStatus = job.summary();
                return; // click next tick, after the server received the new rotation
            }
            place(mc, p, bs, idx, sol);
            placed++;
        }
        if (placed > 0) {
            cooldown = cfg.placeDelayTicks();
            lastStatus = job.summary();
            return;
        }
        // Nothing placeable in reach: report missing materials or walk to the next target.
        MaterialList materials = MaterialList.compute(remaining(bs), inv, creative);
        java.util.function.Predicate<PlacementPlanner.Entry> hasItem = e -> e.item() == null || creative || inv.getOrDefault(e.item(), 0) > 0;
        java.util.function.Predicate<PlacementPlanner.Entry> notResting = e -> {
            Long until = resting.get(e);
            return until == null || until <= tick;
        };
        // Walk to a block that can be placed once there (its support is built), skipping for a while any
        // block it just failed to reach; only then to any block.
        int next = bs.nearestUnfinished(p.getX(), p.getY(), p.getZ(), e -> hasItem.test(e) && notResting.test(e) && supportPresent(e));
        if (next < 0) next = bs.nearestUnfinished(p.getX(), p.getY(), p.getZ(), e -> hasItem.test(e) && notResting.test(e));
        if (next < 0 && bs.nearestUnfinished(p.getX(), p.getY(), p.getZ(), hasItem) >= 0) {
            movement.stop();
            lastStatus = "Can't reach the remaining blocks from here yet - retrying (" + job.summary() + ")";
            return;
        }
        if (next < 0) {
            if (!materials.complete() && !creative && cfg.restockFromShulkers() && tryStartRestock(p, cfg, materials)) {
                return;
            }
            if (!materials.complete()) {
                Set<String> missing = new HashSet<>();
                for (MaterialList.Line l : materials.missingLines()) missing.add(l.item());
                if (!missing.equals(missingAnnounced)) {
                    missingAnnounced = missing;
                    MaterialList.Line first = materials.missingLines().get(0);
                    Atlas.get().notifications.warn("Missing building materials", materials.missingLines().size()
                            + " item types missing, e.g. " + first.missing() + "x " + first.item());
                }
                lastStatus = "Waiting for materials (" + materials.totalMissing() + " items)";
            }
            movement.stop();
            return;
        }
        PlacedBlock t = bs.entries().get(next).target();
        double dx = t.x() + 0.5 - p.getX(), dy = t.y() + 0.5 - p.getEyeY(), dz = t.z() + 0.5 - p.getZ();
        boolean inReach = dx * dx + dy * dy + dz * dz <= cfg.reach() * cfg.reach();
        // In reach but no valid click from here (support face hidden, wrong side of a wall): move closer
        // instead of standing still, a little closer each time it stays stuck.
        stuckInReach = inReach ? stuckInReach + 1 : 0;
        double range = inReach ? Math.max(1.5, cfg.reach() - 1.5 - (stuckInReach / 40) * 0.5) : cfg.reach() - 0.7;
        walkTo(p, cfg, new Pathfinder.Goal(t.x(), t.y(), t.z(), range, inReach), "Walking to next placement");
        // No way there (or in reach for 10 s without a valid click): leave this block for a while and go on
        // with the others instead of giving up.
        if (lastStatus.startsWith("No path") || lastStatus.startsWith("Stuck") || stuckInReach > 200) {
            resting.put(bs.entries().get(next), tick + 300);
            stuckInReach = 0;
            movement.stop();
        }
    }

    private int stuckInReach;
    /** Placement entries it could not get to, and until which tick they are left alone. */
    private final Map<PlacementPlanner.Entry, Long> resting = new java.util.IdentityHashMap<>();

    // ------------------------------------------------------------------ shulker restock

    private boolean tryStartRestock(ClientPlayerEntity p, Config cfg, MaterialList materials) {
        if (restockCooldown > 0) {
            restockCooldown--;
            return false;
        }
        Map<String, Integer> missing = new HashMap<>();
        for (MaterialList.Line l : materials.missingLines()) missing.put(l.item(), l.missing());
        Restock.Shulker box = Restock.chooseShulker(InventoryHelper.shulkers(p), missing);
        if (box == null) {
            return false;
        }
        int[] spot = Restock.chooseSpot(p.getBlockX(), p.getBlockY(), p.getBlockZ(), p.getX(), p.getEyeY(), p.getZ(),
                cfg.reach(), new Restock.SpotProbe() {
                    @Override public boolean empty(int x, int y, int z) {
                        BlockState s = world.world().getBlockState(new BlockPos(x, y, z));
                        return s.isAir();
                    }
                    @Override public boolean solid(int x, int y, int z) { return world.isSolid(x, y, z); }
                    @Override public boolean inBuild(int x, int y, int z) { return inBuildVolume(x, y, z); }
                    @Override public boolean fluidNear(int x, int y, int z) { return world.neighbourHasFluid(x, y, z); }
                });
        if (spot == null) {
            note("No free spot next to you for a shulker box (needs solid ground, outside the build)");
            restockCooldown = 100;
            return false;
        }
        movement.stop();
        restock = new RestockSession(box.slot(), spot, InventoryHelper.shulkerCount(p));
        restockLookPending = false;
        lastStatus = "Restocking from shulker box";
        return true;
    }

    private boolean inBuildVolume(int x, int y, int z) {
        for (PlacementPlanner.Entry e : job.build().entries()) {
            PlacedBlock t = e.target();
            if (t.x() == x && t.y() == y && t.z() == z) return true;
        }
        if (job.excavation() != null) {
            for (ExcavationPlanner.Entry e : job.excavation().entries()) {
                if (e.x() == x && e.y() == y && e.z() == z) return true;
            }
        }
        return false;
    }

    private void tickRestock(MinecraftClient mc, ClientPlayerEntity p, Config cfg) {
        int[] sp = restock.spot();
        BlockPos spot = new BlockPos(sp[0], sp[1], sp[2]);
        boolean boxAtSpot = world.world().getBlockState(spot).getBlock() instanceof ShulkerBoxBlock;
        boolean open = p.currentScreenHandler instanceof ShulkerBoxScreenHandler;
        List<Restock.Stack> container = new ArrayList<>();
        if (open) {
            var handler = p.currentScreenHandler;
            for (int i = 0; i < 27 && i < handler.slots.size(); i++) {
                ItemStack st = handler.getSlot(i).getStack();
                container.add(new Restock.Stack(i, st.isEmpty() ? "" : net.minecraft.registry.Registries.ITEM.getId(st.getItem()).toString(),
                        st.getCount(), true, false));
            }
        }
        List<Restock.Stack> inv = InventoryHelper.stacks(p);
        Map<String, Integer> needed = neededForBuild();
        boolean canMakeRoom = !Restock.drops(inv, needed, cfg.dropMode() == Restock.DropMode.OFF ? Restock.DropMode.OFF : Restock.DropMode.ALWAYS,
                cfg.dropScope(), cfg.junk(), cfg.neverDrop(), 1).isEmpty();
        Map<String, Integer> missing = new HashMap<>();
        MaterialList ml = MaterialList.compute(remaining(job.build()), InventoryHelper.counts(p), false);
        for (MaterialList.Line l : ml.missingLines()) missing.put(l.item(), l.missing());
        RestockSession.Action a = restock.tick(new RestockSession.Observation(tick, boxAtSpot, open, container,
                Restock.freeSlots(inv), InventoryHelper.shulkerCount(p), canMakeRoom), missing);
        Vec3d top = new Vec3d(sp[0] + 0.5, sp[1], sp[2] + 0.5);
        switch (a.kind()) {
            case PLACE -> {
                movement.stop();
                if (!restockLookPending) {
                    look(p, top);
                    restockLookPending = true; // click next tick, after the new rotation was sent
                    return;
                }
                restockLookPending = false;
                if (!InventoryHelper.selectSlot(mc, a.slot(), cfg.hotbarSlot())) return;
                BlockHitResult hit = new BlockHitResult(top, Direction.UP, spot.down(), false);
                mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, hit);
                p.swingHand(Hand.MAIN_HAND);
            }
            case OPEN -> {
                Vec3d c = Vec3d.ofCenter(spot);
                look(p, c);
                mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(c.add(0, 0.5, 0), Direction.UP, spot, false));
                p.swingHand(Hand.MAIN_HAND);
            }
            case TAKE -> mc.interactionManager.clickSlot(p.currentScreenHandler.syncId, a.slot(), 0, SlotActionType.QUICK_MOVE, p);
            case CLOSE -> p.closeHandledScreen();
            case BREAK -> breakBox(mc, p, cfg, spot);
            case WALK -> {
                if (p.getBlockPos().equals(spot) || p.squaredDistanceTo(Vec3d.ofBottomCenter(spot)) < 0.3) {
                    movement.stop();
                } else {
                    walkTo(p, cfg, new Pathfinder.Goal(sp[0], sp[1], sp[2], 0.4), "Picking up the shulker box");
                }
            }
            case MAKE_ROOM -> {
                List<Integer> d = Restock.drops(inv, needed, Restock.DropMode.ALWAYS, cfg.dropScope(), cfg.junk(), cfg.neverDrop(), 1);
                if (!d.isEmpty()) InventoryHelper.throwStack(mc, d.get(0));
            }
            case WAIT -> {
                if (!a.message().isEmpty()) note(a.message());
            }
            case DONE -> {
                movement.stop();
                int taken = restock.stacksTaken();
                Atlas.get().notifications.success("Restocked from shulker box", taken + " stack" + (taken == 1 ? "" : "s")
                        + " taken; the box is back in your inventory" + (restock.problem().isEmpty() ? "" : " (" + restock.problem() + ")"));
                if (taken == 0) restockCooldown = 200; // nothing usable came out: do not retry immediately
                restock = null;
            }
            case FAIL -> {
                movement.stop();
                Atlas.get().notifications.warn("Shulker box", a.message());
                job.pause();
                restock = null;
                restockCooldown = 200;
            }
        }
        if (restock != null) lastStatus = "Restocking: " + restock.phase().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /** Mines the placed box with the best normal tool. Never a 3x3 tool: it would break the blocks around it. */
    private void breakBox(MinecraftClient mc, ClientPlayerEntity p, Config cfg, BlockPos spot) {
        movement.stop();
        BlockState state = world.world().getBlockState(spot);
        AreaMining.Tool best = null;
        for (AreaMining.Tool t : InventoryHelper.tools(p, state, cfg.areaKeywords(), cfg.manualAreaSlot())) {
            if (t.areaTool()) continue;
            if (best == null || t.speed() > best.speed()) best = t;
        }
        if (best != null && !InventoryHelper.selectSlot(mc, best.slot(), cfg.hotbarSlot())) return;
        if (best == null && InventoryHelper.isAreaTool(p.getMainHandStack(), cfg.areaKeywords())) {
            // Only 3x3 tools available: use an empty hand (or any other slot) instead.
            p.getInventory().setSelectedSlot((p.getInventory().getSelectedSlot() + 1) % 9);
            return;
        }
        Vec3d c = Vec3d.ofCenter(spot).add(0, 0.5, 0);
        look(p, c);
        if (!spot.equals(boxMining)) {
            mc.interactionManager.attackBlock(spot, Direction.UP);
            boxMining = spot;
        } else {
            mc.interactionManager.updateBlockBreakingProgress(spot, Direction.UP);
        }
        p.swingHand(Hand.MAIN_HAND);
    }

    private BlockPos boxMining;

    private void note(String msg) {
        lastStatus = msg;
        if (!msg.equals(lastRestockNote)) {
            lastRestockNote = msg;
            Atlas.get().notifications.warn("AutoBuilder", msg);
        }
    }

    // ------------------------------------------------------------------ dropping unneeded blocks

    private Map<String, Integer> neededCache = Map.of();
    private long neededAt = -100;

    /** Items the unfinished part of the build still needs (cached for a second). */
    private Map<String, Integer> neededForBuild() {
        if (tick - neededAt >= 20) {
            Map<String, Integer> m = new HashMap<>();
            for (PlacementPlanner.Entry e : remaining(job.build())) {
                if (e.item() != null) m.merge(e.item(), Math.max(1, e.itemCount()), Integer::sum);
            }
            neededCache = m;
            neededAt = tick;
        }
        return neededCache;
    }

    /**
     * Throws out one unneeded stack when the drop settings say so. Two ticks per throw: turn away from
     * the build first (so items land outside it), then throw. Returns true while busy.
     */
    private boolean tickDrops(MinecraftClient mc, ClientPlayerEntity p, Config cfg) {
        if (cfg.dropMode() == Restock.DropMode.OFF || miningEntry >= 0 || pendingEntry >= 0 || movement.active()) {
            dropSlotPending = -1;
            return false;
        }
        if (dropSlotPending >= 0) {
            int slot = dropSlotPending;
            dropSlotPending = -1;
            InventoryHelper.throwStack(mc, slot);
            dropCooldown = 4;
            return true;
        }
        if (dropCooldown > 0) {
            dropCooldown--;
            return false;
        }
        List<Integer> d = Restock.drops(InventoryHelper.stacks(p), neededForBuild(), cfg.dropMode(), cfg.dropScope(),
                cfg.junk(), cfg.neverDrop(), cfg.keepFree());
        if (d.isEmpty()) {
            dropCooldown = 20;
            return false;
        }
        double ax = p.getX() - buildCenterX, az = p.getZ() - buildCenterZ;
        double len = Math.max(0.001, Math.sqrt(ax * ax + az * az));
        look(p, new Vec3d(p.getX() + ax / len * 4, p.getEyeY() - 0.5, p.getZ() + az / len * 4));
        dropSlotPending = d.get(0);
        lastStatus = "Dropping unneeded blocks";
        return true;
    }

    private Iterable<PlacementPlanner.Entry> remaining(BuildSession bs) {
        java.util.List<PlacementPlanner.Entry> out = new java.util.ArrayList<>();
        for (int i = 0; i < bs.entries().size(); i++) {
            BuildSession.EntryState s = bs.entryState(i);
            if (s == BuildSession.EntryState.PENDING || s == BuildSession.EntryState.SKIPPED) out.add(bs.entries().get(i));
        }
        return out;
    }

    private boolean supportPresent(PlacementPlanner.Entry e) {
        PlacedBlock t = e.target();
        if (e.placementClass() == dev.atlasclient.core.build.BlockTraits.PlacementClass.GRAVITY) {
            return world.isSolid(t.x(), t.y() - 1, t.z());
        }
        for (Direction d : Direction.values()) {
            if (world.isSolid(t.x() + d.getOffsetX(), t.y() + d.getOffsetY(), t.z() + d.getOffsetZ())) return true;
        }
        return false;
    }

    private static boolean overlapsPlayer(ClientPlayerEntity p, BlockPos pos) {
        BlockPos feet = p.getBlockPos();
        return pos.equals(feet) || pos.equals(feet.up());
    }

    private void place(MinecraftClient mc, ClientPlayerEntity p, BuildSession bs, int idx, PlacementSolver.Solution sol) {
        ActionResult r = mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, sol.hit());
        p.swingHand(Hand.MAIN_HAND);
        bs.markSent(idx, tick);
        if (!r.isAccepted()) {
            Atlas.LOG.debug("Placement at {} not accepted locally: {}", sol.hit().getBlockPos(), r);
        }
    }

    /** Right-clicks a placed block to cycle an adjustable property (repeater delay, comparator mode...). */
    private void adjust(MinecraftClient mc, ClientPlayerEntity p, BuildSession bs, int idx, BlockPos pos) {
        Vec3d hit = Vec3d.ofCenter(pos).add(0, 0.5, 0);
        if (p.getEyePos().squaredDistanceTo(hit) > 30) return;
        look(p, hit);
        mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, Direction.UP, pos, false));
        p.swingHand(Hand.MAIN_HAND);
        bs.markAdjustmentSent(idx, tick);
    }

    /** Periodically re-checks finished entries so later changes (incorrect blocks) are detected. */
    private void recheckSome(BuildSession bs) {
        int n = bs.entries().size();
        if (n == 0) return;
        for (int k = 0; k < 8; k++) {
            recheckCursor = (recheckCursor + 1) % n;
            bs.recheck(recheckCursor, world.status(bs.entries().get(recheckCursor).target()));
        }
    }

    private void walkTo(ClientPlayerEntity p, Config cfg, Pathfinder.Goal goal, String why) {
        if (!cfg.autoWalk()) {
            lastStatus = "Move closer: " + why.toLowerCase(Locale.ROOT) + " at " + goal.x() + " " + goal.y() + " " + goal.z();
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        Pathfinder.Goal current = movement.goal();
        if (!movement.active() || current == null || current.x() != goal.x() || current.y() != goal.y() || current.z() != goal.z()) {
            movement.navigate(world, p, goal, cfg.sprint(), cfg.pathNodes());
            if (!movement.active()) {
                lastStatus = "No path to " + goal.x() + " " + goal.y() + " " + goal.z() + " - move closer manually";
                return;
            }
        }
        PathFollower.Status st = movement.tick(mc);
        if (st == PathFollower.Status.STUCK && !movement.replan(world, p, cfg.sprint(), cfg.pathNodes())) {
            lastStatus = "Stuck - trying another block";
            return;
        }
        lastStatus = why + " (" + job.summary() + ")";
    }

    private static void look(ClientPlayerEntity p, Vec3d point) {
        float[] r = MovementController.lookAt(p, point.x, point.y, point.z);
        p.setYaw(r[0]);
        p.setPitch(r[1]);
    }

    /** Status for the HUD: probe each entry state quickly. */
    public WorldProbe probe() { return world; }
}
