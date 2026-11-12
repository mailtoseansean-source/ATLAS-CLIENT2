package dev.atlasclient.module.building;

import com.google.gson.JsonObject;
import dev.atlasclient.build.BuildExecutor;
import dev.atlasclient.build.McBlockTraits;
import dev.atlasclient.build.McWorld;
import dev.atlasclient.build.MovementController;
import dev.atlasclient.core.path.PathFollower;
import dev.atlasclient.core.path.Pathfinder;
import dev.atlasclient.build.SurvivalAssist;
import dev.atlasclient.camera.FreecamController;
import dev.atlasclient.core.build.AreaMining;
import dev.atlasclient.core.build.BuildJob;
import dev.atlasclient.core.build.BuildSession;
import dev.atlasclient.core.build.ExcavationPlanner;
import dev.atlasclient.core.build.ExcavationSession;
import dev.atlasclient.core.build.PlacementPlanner;
import dev.atlasclient.core.build.Restock;
import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.render.OverlayCuller;
import dev.atlasclient.core.schematic.BlockStateString;
import dev.atlasclient.core.schematic.SchematicPlacer.PlacedBlock;
import dev.atlasclient.core.schematic.transform.Transforms;
import dev.atlasclient.core.setting.Settings.ActionSetting;
import dev.atlasclient.core.setting.Settings.BooleanSetting;
import dev.atlasclient.core.setting.Settings.EnumSetting;
import dev.atlasclient.core.setting.Settings.NumberSetting;
import dev.atlasclient.module.AtlasModule;
import dev.atlasclient.util.Env;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Auto Mine: switch it on and it digs where you are facing. <b>Tunnel</b> mode digs a straight
 * 1x2 or 3x3 tunnel at your feet level, walking forward as it goes. <b>Area</b> mode digs out a
 * box of Width x Length x Depth in front of you (for example 50 x 50 x 50), two layers at a
 * time from the top down. Mining uses the same code as the AutoBuilder's
 * excavation: normal block breaking at vanilla speed, best pickaxe from your inventory, Regular or
 * Amethyst (3x3) mode.
 *
 * <p>It stops by itself before lava or water, before a gap in the floor, at unbreakable blocks,
 * when your health is low, when no usable pickaxe is left, or when it gets stuck. Ores it uncovers
 * in the tunnel walls and ceiling are mined too (whole veins next to the tunnel), and junk blocks
 * are thrown behind you when the inventory is full.</p>
 */
public final class AutoMineModule extends AtlasModule {
    public enum Mode { TUNNEL, AREA }
    public enum Size { SMALL_1X2, BIG_3X3 }
    /** Ores outside the dug space: none, only those touching it, or whole veins. */
    public enum Ores { OFF, TOUCHING_TUNNEL, WHOLE_VEIN }

    public final EnumSetting<Mode> mode = add(new EnumSetting<>("Mode", "Tunnel: a straight tunnel. Area: dig out a Width x Length x Depth box", Mode.TUNNEL));
    public final EnumSetting<Size> size = add(new EnumSetting<>("Tunnel size", "1 wide x 2 high, or 3x3 (best with an Amethyst pickaxe)", Size.SMALL_1X2))
            .visibleWhen(() -> mode.get() == Mode.TUNNEL);
    public final NumberSetting length = add(new NumberSetting("Length", "Blocks to dig (0 = keep going until you stop it)", 0, 0, 1024, 1))
            .visibleWhen(() -> mode.get() == Mode.TUNNEL);
    public final NumberSetting areaWidth = add(new NumberSetting("Area width", "Blocks left to right (you stand in the middle of the back edge)", 16, 1, 128, 1))
            .visibleWhen(() -> mode.get() == Mode.AREA);
    public final NumberSetting areaLength = add(new NumberSetting("Area length", "Blocks forward, starting in front of you", 16, 1, 128, 1))
            .visibleWhen(() -> mode.get() == Mode.AREA);
    public final NumberSetting areaDepth = add(new NumberSetting("Area depth", "Blocks from head height downward (2 = a room at your level)", 16, 1, 128, 1))
            .visibleWhen(() -> mode.get() == Mode.AREA);
    public final EnumSetting<AreaMining.PickaxeMode> pickaxe = add(new EnumSetting<>("Pickaxe", "Regular, or Amethyst (3x3 swings where safe)", AreaMining.PickaxeMode.REGULAR));
    public final EnumSetting<Ores> ores = add(new EnumSetting<>("Extra ores", "Ores outside the tunnel: Off = only what is in the way (keeps moving), touching the tunnel, or whole veins", Ores.OFF))
            .visibleWhen(() -> mode.get() == Mode.TUNNEL);
    public final BooleanSetting dropJunk = add(new BooleanSetting("Drop junk when full", "Throw cobblestone, deepslate, dirt... behind you so ores still fit", true));
    public final ActionSetting pauseResume = add(new ActionSetting("Pause / resume", "Pause digging and keep your place; click again to carry on", this::togglePause));
    public final NumberSetting fastBreaks = add(new NumberSetting("Instant breaks per tick", "With Efficiency V + Haste blocks break instantly: "
            + "how many to break per game tick. 1 = like holding the button (safest on servers); more is faster but anti-cheat may flag it", 1, 1, 4, 1));
    public final NumberSetting stopHealth = add(new NumberSetting("Stop at hearts", "Stop when your health drops to this many hearts (0 = never)", 4, 0, 10, 0.5));
    public final NumberSetting fallDamage = add(new NumberSetting("Fall damage OK", "Hearts of fall damage it may take to drop down to the next layer (0 = only safe 3-block drops)", 2, 0, 5, 0.5));

    private static final int STUCK_TICKS = 600;
    private static final int ORE_STUCK_TICKS = 400; // ancient debris takes ~6 s with a diamond pickaxe
    private static final int ORE_REACH_SIDE = 4;
    /** Area mode: no block broken for 20 s (obsidian takes ~9 s) = leave those blocks for the clean-up pass. */
    private static final int AREA_STUCK_TICKS = 400;
    private static final int SLICES_PER_JOB = 2;

    private McWorld world;
    private BuildJob job;
    private BuildExecutor executor;
    private boolean oreJob;
    private int dx, dz, ox, oy, oz;
    private int slice;
    private int blocksMined, oresMined;
    private final Set<Long> oreTried = new HashSet<>();
    private final List<long[]> oreDug = new ArrayList<>();
    private List<PlacedBlock> current = List.of();
    private long ticks;
    private long lastProgressTick;
    private int lastProgress;
    private String facingName = "";
    // Area mode: one flat 3-high layer after another, top down, row by row out to every edge; then a
    // clean-up pass for anything left over.
    private int areaTop, areaBottom;
    private int layer, laneIdx, laneK, laneDir, layers, lanesPerLayer;
    private int rowTroubles;
    private boolean cleanup;
    private final Set<Long> caveFloor = new HashSet<>();
    private final Set<Long> hazardCells = new HashSet<>();
    private final Set<Long> gaveUp = new HashSet<>();
    private final java.util.Map<Long, Integer> cleanupTries = new java.util.HashMap<>();
    private int cleanupLeft;
    // Tunnel mode: turns made to get around lava, water or bedrock, and blocks dug before the last turn.
    private int turns, dugBeforeTurn;
    private int planSkipped;
    private int noPathTicks;
    /** Jobs in a row that found no path (the next ones may drop further to get down into caves). */
    private int noPathStreak;
    private int leftOverCaves;
    private final SurvivalAssist assist = new SurvivalAssist();
    /** With a long reach the face of the tunnel can get far ahead: walk up so the drops are picked up. */
    private final MovementController walker = new MovementController();
    private boolean paused;
    private boolean pauseApplied;
    private boolean areaTrouble;
    private int walkTries;

    public static final int DEFAULT_KEY = 78; // N

    public AutoMineModule() {
        super("Auto Mine", "Digs a tunnel where you look, mines the ores it finds, stops before lava, holes and danger", Category.WORLD, DEFAULT_KEY);
    }

    public static AutoMineModule get() { return atlas().modules.get(AutoMineModule.class); }

    @Override
    public String enableBlockedReason() {
        return Env.inWorld() ? null : "Join a world first";
    }

    /** Never restored as "on" from a saved profile: mining starts only when you ask for it. */
    @Override public JsonObject toJson() {
        JsonObject o = super.toJson();
        o.addProperty("enabled", false);
        return o;
    }

    @Override protected void onEnable() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        AutoBuilderModule builder = AutoBuilderModule.get();
        if (builder.job() != null && builder.job().isRunning()) builder.togglePause();
        Direction facing = p.getHorizontalFacing();
        dx = facing.getOffsetX();
        dz = facing.getOffsetZ();
        BlockPos feet = p.getBlockPos();
        ox = feet.getX();
        oy = feet.getY();
        oz = feet.getZ();
        slice = 0;
        blocksMined = 0;
        oresMined = 0;
        oreTried.clear();
        oreDug.clear();
        current = List.of();
        job = null;
        executor = null;
        world = new McWorld(mc.world, builder.resolver());
        facingName = facing.name().toLowerCase(Locale.ROOT);
        layer = 0;
        laneIdx = 0;
        laneK = 0;
        laneDir = 1;
        rowTroubles = 0;
        cleanup = false;
        unfillable.clear();
        bridged = 0;
        caveFloor.clear();
        hazardCells.clear();
        gaveUp.clear();
        cleanupTries.clear();
        cleanupLeft = 0;
        turns = 0;
        dugBeforeTurn = 0;
        leftOverCaves = 0;
        assist.reset(mc);
        assist.fallMargin = mode.get() == Mode.AREA ? 1 : 0;
        paused = false;
        int depth = areaDepth.intValue();
        areaTop = oy + (depth >= 2 ? 1 : 0);
        areaBottom = areaTop - depth + 1;
        layers = (depth + 2) / 3;
        lanesPerLayer = (areaWidth.intValue() + 2) / 3;
        if (mode.get() == Mode.AREA) {
            info("Auto Mine", String.format(Locale.ROOT, "Clearing %d x %d x %d (width x length x depth) %s, one flat layer at a time from Y %d down to Y %d. Toggle it again to stop.",
                    areaWidth.intValue(), areaLength.intValue(), depth, facingName, areaTop, areaBottom));
            return;
        }
        int len = length.intValue();
        if (Env.inNether() && (oy < 9 || oy > 21)) {
            info("Auto Mine", "Tip: ancient debris is most common at Y 13-17. You are at Y " + oy + ".");
        }
        info("Auto Mine", String.format(Locale.ROOT, "Digging a %s tunnel %s%s. Toggle it again to stop.",
                size.get() == Size.BIG_3X3 ? "3x3" : "1x2", facingName, len > 0 ? " for " + len + " blocks" : ""));
    }

    @Override protected void onDisable() {
        assist.reset(MinecraftClient.getInstance());
        walker.stop();
        if (executor != null) executor.abandon();
        job = null;
        executor = null;
        current = List.of();
    }

    @Override public void onSessionReset() {
        if (isEnabled()) setEnabled(false);
    }

    /** Pause keeps the tunnel/area, the job and its progress; resume carries on from the same place. */
    public void togglePause() {
        if (!isEnabled()) {
            info("Auto Mine", "Not running. Press N (or click the card) to start");
            return;
        }
        paused = !paused;
        pauseApplied = false;
        info("Auto Mine", paused ? "Paused. Click Pause / resume again to carry on" : "Carrying on");
    }

    public boolean paused() { return paused; }

    private void stop(String title, String why) {
        warn(title, why);
        setEnabled(false);
    }

    @Override
    public void onTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.interactionManager == null) return;
        ticks++;
        if (paused) {
            if (!pauseApplied) {
                pauseApplied = true;
                if (executor != null) executor.movement().stop();
                walker.stop();
                assist.releaseKeys(mc);
                mc.interactionManager.cancelBlockBreaking();
            }
            lastProgressTick = ticks;
            setStatus("Paused - Pause / resume to carry on");
            return;
        }
        if (FreecamController.active()) {
            if (executor != null) executor.movement().stop();
            setStatus("Paused while Freecam is on");
            return;
        }
        if (p.isDead()) {
            stop("Auto Mine stopped", String.format(Locale.ROOT, "You died at %d %d %d. %d blocks were mined", p.getBlockX(), p.getBlockY(),
                    p.getBlockZ(), blocksMined));
            return;
        }
        if (mc.currentScreen != null) {
            if (executor != null) executor.movement().stop(); // never act while a menu is open
            setStatus("Paused while a menu is open");
            return;
        }
        if (stopHealth.get() > 0 && p.getHealth() <= stopHealth.get() * 2) {
            stop("Auto Mine stopped", String.format(Locale.ROOT, "Your health is low (%.1f hearts)", p.getHealth() / 2));
            return;
        }
        if (world == null || world.world() != mc.world) world = new McWorld(mc.world, AutoBuilderModule.get().resolver());
        // Eat, fill floor gaps, climb back after a fall: those come first and pause the digging.
        int workY = mode.get() == Mode.TUNNEL ? oy : (cleanup ? areaBottom : layerBottom());
        double homeX, homeZ;
        if (mode.get() == Mode.TUNNEL) {
            int k = Math.max(0, slice - 1);
            homeX = ox + dx * k + 0.5;
            homeZ = oz + dz * k + 0.5;
        } else {
            homeX = ox + dx * (areaLength.intValue() + 1) / 2.0 + 0.5;
            homeZ = oz + dz * (areaLength.intValue() + 1) / 2.0 + 0.5;
        }
        if (assist.tick(mc, workY, homeX, homeZ)) {
            if (executor != null) executor.movement().stop();
            // Eating, filling, pillaring count as doing something; just swimming up for air does not
            // (bobbing in water must not keep the "no progress" watchdog quiet forever).
            if (assist.phase() != SurvivalAssist.Phase.IDLE) lastProgressTick = ticks;
            setStatus(switch (assist.phase()) {
                case EATING -> "Eating";
                case FILLING -> "Filling a gap in the floor";
                case PILLARING -> "Fell in a hole: pillaring up";
                case RETURNING -> "Going back to the tunnel";
                default -> "";
            });
            return;
        }
        if (assist.takeFillFailed() && lastBridge != null) unfillable.add(lastBridge.asLong());
        if (assist.phase() == SurvivalAssist.Phase.FAILED) {
            stop("Auto Mine stopped", assist.failure());
            return;
        }
        if (walker.active()) {
            PathFollower.Status st = walker.tick(mc);
            int along = (int) Math.floor((p.getX() - ox) * dx + (p.getZ() - oz) * dz + 0.5);
            if (along >= slice - 2 || st == PathFollower.Status.STUCK) walker.stop();
            lastProgressTick = ticks;
            setStatus("Walking up to the tunnel face");
            return;
        }
        if (job == null || job.phase() == BuildJob.Phase.FINISHED) {
            if (!next(p)) return;
        }
        if (job == null) return;
        int progress = progressCount();
        if (progress != lastProgress) {
            if (progress > lastProgress) noPathStreak = 0;
            lastProgress = progress;
            lastProgressTick = ticks;
        } else if (ticks - lastProgressTick > (oreJob ? ORE_STUCK_TICKS : mode.get() == Mode.AREA ? AREA_STUCK_TICKS : STUCK_TICKS)) {
            if (oreJob) {
                finishJob(); // that ore could not be reached from the tunnel: leave it and carry on
            } else if (mode.get() == Mode.AREA) {
                if (cleanup) giveUpOnJob();
                areaTrouble = true; // stuck here: leave these blocks for the clean-up pass and carry on
                nextArea();
            } else {
                stop("Auto Mine stuck", "No progress for 30 seconds at " + where() + " (" + executor.status() + ")");
            }
            return;
        }
        executor.tick(mc, config(mc));
        BlockPos[] bridge = executor.takeBridgeRequest();
        if (bridge != null) {
            if (assist.requestFill(mc, bridge[0], bridge[1], true)) {
                lastBridge = bridge[0];
                bridged++;
                lastProgressTick = ticks;
            } else {
                unfillable.add(bridge[0].asLong()); // no blocks to place
            }
        }
        // Area mode: no way to walk to these blocks. Don't wait out the 20 s watchdog: leave them now
        // (for the clean-up pass, or for good if this already is the clean-up).
        if (mode.get() == Mode.AREA && executor != null && executor.status().contains("No path")) {
            if (++noPathTicks > 60) {
                noPathTicks = 0;
                noPathStreak++;
                // In the clean-up the next try may drop further (see begin); after 3 tries a block is given up.
                if (cleanup && noPathStreak > 3) giveUpOnJob();
                areaTrouble = true;
                nextArea();
                return;
            }
        } else {
            noPathTicks = 0;
        }
        setStatus(statusText());
    }

    private int progressCount() {
        ExcavationSession.Progress pr = job.excavation().progress();
        return pr.done() + pr.skipped() + pr.failed();
    }

    /** Wraps up the finished job and plans the next one. Returns true when a new job is running. */
    private boolean next(ClientPlayerEntity p) {
        if (mode.get() == Mode.AREA) return nextArea();
        if (job != null) {
            ExcavationSession.Progress pr = job.excavation().progress();
            boolean wasOre = oreJob;
            String problem = job.excavation().lastProblem();
            finishJob();
            if (wasOre) {
                oresMined += pr.done();
            } else {
                blocksMined += pr.done();
                if (pr.skipped() + pr.failed() > 0) {
                    if (problem.startsWith("No safe tool")) {
                        problem = "No usable pickaxe for the block at " + where() + " (none in your inventory, or it is about to break)";
                    }
                    stop("Auto Mine stopped", problem.isEmpty() ? "A block ahead can't be mined safely at " + where() : problem);
                    return false;
                }
            }
        }
        // A gap in the floor of the slice just dug: put a block in it from the edge (sneaking).
        if (slice >= 1) {
            int gx = ox + dx * slice, gz = oz + dz * slice;
            BlockPos gap = new BlockPos(gx, oy - 1, gz);
            if (world.isLoaded(gx, gz) && !world.isSolid(gx, oy - 1, gz) && !world.isDangerous(gx, oy - 1, gz) && !world.isWater(gx, oy - 1, gz)) {
                BlockPos support = new BlockPos(ox + dx * (slice - 1), oy - 1, oz + dz * (slice - 1));
                if (!assist.requestFill(MinecraftClient.getInstance(), gap, support)) {
                    stop("Auto Mine stopped", "Gap in the floor at " + gx + " " + (oy - 1) + " " + gz + " and no plain blocks (cobblestone, dirt...) to fill it");
                }
                return false;
            }
        }
        if (ores.get() != Ores.OFF) {
            List<PlacedBlock> ores = exposedOres();
            if (!ores.isEmpty()) {
                ExcavationPlanner.Plan plan = ExcavationPlanner.plan(ores, world, true);
                for (PlacedBlock b : ores) oreTried.add(BlockPos.asLong(b.x(), b.y(), b.z()));
                if (!plan.nothingToDo()) {
                    begin(plan, ores, true);
                    return true;
                }
            }
        }
        int len = length.intValue();
        if (len > 0 && dugBeforeTurn + slice >= len) {
            success("Auto Mine finished", String.format(Locale.ROOT, "Tunnel of %d blocks done: %d blocks and %d ores mined%s",
                    dugBeforeTurn + slice, blocksMined, oresMined, turns > 0 ? " (turned " + turns + "x around lava/water/bedrock)" : ""));
            setEnabled(false);
            return false;
        }
        int along = (int) Math.floor((p.getX() - ox) * dx + (p.getZ() - oz) * dz + 0.5);
        if (slice - along > 3 && walkTries < 3) {
            walkTries++;
            int k0 = slice - 1;
            walker.setMaxDrop(0);
            walker.navigate(world, p, new Pathfinder.Goal(ox + dx * k0, oy, oz + dz * k0, 1.2), false, 2000);
            if (walker.active()) return false;
        }
        walkTries = 0;
        int k = slice + 1;
        int fx = ox + dx * k, fz = oz + dz * k;
        if (!world.isLoaded(fx, fz)) {
            setStatus("Waiting for the chunk ahead to load");
            return false;
        }
        if (world.isDangerous(fx, oy - 1, fz) || world.isWater(fx, oy - 1, fz)) {
            return turn("Lava or water under the tunnel ahead");
        }
        List<PlacedBlock> cells = new ArrayList<>();
        if (k > 1) cells.addAll(sliceCells(k - 1)); // re-check: gravel or sand may have fallen in
        cells.addAll(sliceCells(k));
        ExcavationPlanner.Plan plan = ExcavationPlanner.plan(cells, world, true);
        if (plan.fluidCells() + plan.fluidNeighbours() > 0) {
            return turn("Lava or water ahead");
        }
        if (plan.unbreakable() > 0) {
            return turn("Unbreakable block (bedrock?) ahead");
        }
        if (plan.unavailable() > 0) {
            setStatus("Waiting for the chunk ahead to load");
            return false;
        }
        slice = k;
        if (plan.nothingToDo()) {
            current = cells;
            return false; // already open (cave air): move on next tick
        }
        begin(plan, cells, false);
        return true;
    }

    /**
     * Something ahead can't be dug safely: leave it and carry on in another direction (right, then
     * left, ...) from where the tunnel is now. After 4 turns it stops.
     */
    private boolean turn(String why) {
        if (turns >= 4) {
            stop("Auto Mine stopped", why + " at " + where() + ", and no way around it after 4 turns");
            return false;
        }
        turns++;
        int nox = ox + dx * slice, noz = oz + dz * slice;
        int ndx, ndz;
        if (turns % 2 == 1) { ndx = -dz; ndz = dx; } else { ndx = dz; ndz = -dx; } // right, then left
        dugBeforeTurn += slice;
        ox = nox;
        oz = noz;
        dx = ndx;
        dz = ndz;
        slice = 0;
        oreDug.clear();
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            if (d.getOffsetX() == dx && d.getOffsetZ() == dz) facingName = d.name().toLowerCase(Locale.ROOT);
        }
        info("Auto Mine", why + ": turning " + (turns % 2 == 1 ? "right" : "left") + " and carrying on " + facingName);
        return false;
    }

    // ------------------------------------------------------------------ area mode

    /**
     * Area mode clears the box one flat 3-high layer at a time, top down. Each layer is cleared row by
     * row (3 wide, back and forth) out to every edge, so the walls come out straight and the floor
     * level. Only the blocks that touch lava or water (and bedrock) are left in place, never a whole
     * row; blocks at the bottom of the box over a cave stay as floor so nobody falls in. Anything it
     * could not get on the way (fallen gravel, a block it got stuck on) is picked up by a clean-up
     * pass at the end. Returns true when a job is running.
     */
    private boolean nextArea() {
        boolean trouble = areaTrouble;
        areaTrouble = false;
        if (job != null) {
            ExcavationSession.Progress pr = job.excavation().progress();
            String problem = job.excavation().lastProblem();
            blocksMined += pr.done();
            if (pr.skipped() > planSkipped || pr.failed() > 0) trouble = true;
            finishJob();
            if (problem.startsWith("No safe tool")) {
                stop("Auto Mine stopped", "No usable pickaxe or shovel left (none in your inventory, or about to break). "
                        + blocksMined + " blocks mined");
                return false;
            }
        }
        if (cleanup) return nextCleanup();
        if (trouble) {
            // Two stuck spots in a row on one row: leave the rest of the row to the clean-up pass.
            if (++rowTroubles >= 2 && !nextLane()) return startCleanup();
        } else {
            rowTroubles = 0;
        }
        for (int guard = 0; guard < 4096; guard++) {
            int nk = laneK + laneDir;
            if (nk < 1 || nk > areaLength.intValue()) {
                if (!nextLane()) return startCleanup();
                continue;
            }
            // Two slices per job: both are within reach from one spot, so it walks half as often.
            int last = nk;
            for (int j = 1; j < SLICES_PER_JOB; j++) {
                int c = nk + laneDir * j;
                if (c < 1 || c > areaLength.intValue()) break;
                last = c;
            }
            List<PlacedBlock> cells = new ArrayList<>();
            if (laneK >= 1 && laneK <= areaLength.intValue()) cells.addAll(laneSlice(laneK)); // gravel may have fallen in
            for (int c = nk; ; c += laneDir) {
                cells.addAll(laneSlice(c));
                if (c == last) break;
            }
            ExcavationPlanner.Plan plan = ExcavationPlanner.plan(cells, world, true);
            if (plan.unavailable() > 0) {
                setStatus("Waiting for the chunks ahead to load");
                return false;
            }
            noteHazards(plan);
            // Big cave under this row: lay a 1-block bridge on the layer's floor first, so the bot can walk
            // on (the bridge is mined away with the next layer).
            if (!plan.nothingToDo() && bridgeToward(nk)) return false;
            laneK = last;
            slice = laneK;
            if (plan.nothingToDo()) {
                current = cells;
                continue; // cave space or blocks next to lava: nothing to dig here, move on
            }
            begin(plan, cells, false);
            return true;
        }
        return false;
    }

    private final Set<Long> unfillable = new HashSet<>();
    private BlockPos lastBridge;
    private int bridged;

    /** Middle of the current row, sideways. */
    private int laneCenter() {
        int w = areaWidth.intValue();
        int from = -(w - 1) / 2, to = from + w - 1;
        int l0 = from + 3 * laneNumber(), l1 = Math.min(to, l0 + 2);
        return (l0 + l1) / 2;
    }

    /**
     * Makes sure the floor of this layer is there from where you stand to slice {@code k} of the row
     * (sideways to the row's middle, then along it). The first hole on the way gets a block from the
     * edge (sneaking). Returns true when a block is being placed (wait for it).
     */
    private boolean bridgeToward(int k) {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p == null || p.getBlockY() != layerBottom() || !p.isOnGround()) return false;
        int floorY = layerBottom() - 1;
        int lx = -dz, lz = dx;
        int bx = p.getBlockX(), bz = p.getBlockZ();
        int pk = (bx - ox) * dx + (bz - oz) * dz, pl = (bx - ox) * lx + (bz - oz) * lz;
        int center = laneCenter();
        List<int[]> path = new ArrayList<>();
        for (int l = pl; l != center; l += Integer.signum(center - l)) path.add(new int[]{pk, l + Integer.signum(center - l)});
        for (int kk = pk; kk != k; kk += Integer.signum(k - kk)) path.add(new int[]{kk + Integer.signum(k - kk), center});
        BlockPos prev = new BlockPos(bx, floorY, bz);
        if (!world.isSolid(bx, floorY, bz)) return false;
        for (int[] c : path) {
            int x = ox + dx * c[0] + lx * c[1], z = oz + dz * c[0] + lz * c[1];
            if (!world.isLoaded(x, z)) return false;
            if (world.isSolid(x, floorY, z)) {
                prev = new BlockPos(x, floorY, z);
                continue;
            }
            BlockPos hole = new BlockPos(x, floorY, z);
            if (unfillable.contains(hole.asLong()) || world.isDangerous(x, floorY, z) || world.isWater(x, floorY, z)) return false;
            if (!assist.requestFill(MinecraftClient.getInstance(), hole, prev, true)) return false; // no blocks to place
            lastBridge = hole;
            bridged++;
            lastProgressTick = ticks;
            return true;
        }
        return false;
    }

    /** Blocks next to lava/water (or bedrock) stay where they are; remember them for the summary. */
    private void noteHazards(ExcavationPlanner.Plan plan) {
        for (ExcavationPlanner.Entry e : plan.entries()) {
            if (e.skip() == ExcavationPlanner.Skip.FLUID_CELL || e.skip() == ExcavationPlanner.Skip.FLUID_NEIGHBOUR
                    || e.skip() == ExcavationPlanner.Skip.UNBREAKABLE) {
                hazardCells.add(BlockPos.asLong(e.x(), e.y(), e.z()));
            }
        }
    }

    /** Moves to the next row (or the layer below). Returns false when every layer is done. */
    private boolean nextLane() {
        rowTroubles = 0;
        laneDir = -laneDir;
        laneIdx++;
        if (laneIdx >= lanesPerLayer) {
            laneIdx = 0;
            layer++;
            if (layer >= layers) return false;
        }
        // The next row (or the layer below) starts right next to where this one ended.
        laneK = Math.max(1, Math.min(areaLength.intValue(), laneK)) - laneDir;
        return true;
    }

    private boolean startCleanup() {
        cleanup = true;
        return nextCleanup();
    }

    /**
     * Clean-up pass: everything still standing inside the box (except blocks next to lava/water and
     * the floor over caves) is mined, highest first, a few neighbouring blocks per job. A block that
     * fails twice is left and reported.
     */
    private boolean nextCleanup() {
        List<long[]> left = leftovers();
        cleanupLeft = left.size();
        if (left.isEmpty()) return finishArea();
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        double px = p.getX(), py = p.getY(), pz = p.getZ();
        long[] best = null;
        double bestScore = Double.MAX_VALUE;
        for (long[] c : left) {
            double d = Math.sqrt((c[0] + 0.5 - px) * (c[0] + 0.5 - px) + (c[1] + 0.5 - py) * (c[1] + 0.5 - py) + (c[2] + 0.5 - pz) * (c[2] + 0.5 - pz));
            double score = -c[1] * 1000.0 + d; // highest first (nothing is mined out from under something else), then nearest
            if (score < bestScore) {
                bestScore = score;
                best = c;
            }
        }
        List<PlacedBlock> cells = new ArrayList<>();
        for (long[] c : left) {
            if (Math.abs(c[0] - best[0]) <= 2 && Math.abs(c[2] - best[2]) <= 2 && c[1] <= best[1] && c[1] >= best[1] - 2) {
                cells.add(air((int) c[0], (int) c[1], (int) c[2]));
                if (cells.size() >= 12) break;
            }
        }
        for (PlacedBlock c : cells) {
            long key = BlockPos.asLong(c.x(), c.y(), c.z());
            int tries = cleanupTries.merge(key, 1, Integer::sum);
            if (tries > 2) gaveUp.add(key);
        }
        cells.removeIf(c -> gaveUp.contains(BlockPos.asLong(c.x(), c.y(), c.z())));
        if (cells.isEmpty()) return false; // try again next tick with what is left
        ExcavationPlanner.Plan plan = ExcavationPlanner.plan(cells, world, true);
        if (plan.unavailable() > 0) {
            setStatus("Waiting for the chunks to load");
            return false;
        }
        noteHazards(plan);
        if (plan.nothingToDo()) return false;
        slice = (int) ((best[0] - ox) * dx + (best[2] - oz) * dz);
        begin(plan, cells, false);
        return true;
    }

    /** Blocks still standing inside the box that should go. */
    private List<long[]> leftovers() {
        int w = areaWidth.intValue(), len = areaLength.intValue();
        int from = -(w - 1) / 2, to = from + w - 1;
        int lx = -dz, lz = dx;
        List<long[]> out = new ArrayList<>();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int y = areaTop; y >= areaBottom; y--) {
            for (int k = 1; k <= len; k++) {
                for (int l = from; l <= to; l++) {
                    int x = ox + dx * k + lx * l, z = oz + dz * k + lz * l;
                    long key = BlockPos.asLong(x, y, z);
                    if (hazardCells.contains(key) || caveFloor.contains(key) || gaveUp.contains(key)) continue;
                    if (!world.isLoaded(x, z)) continue;
                    BlockState st = world.world().getBlockState(m.set(x, y, z));
                    if (st.isAir() || st.isReplaceable() || !st.getFluidState().isEmpty()) continue;
                    if (keepAsFloor(x, y, z)) {
                        caveFloor.add(key);
                        continue;
                    }
                    out.add(new long[]{x, y, z});
                }
            }
        }
        return out;
    }

    /** Bottom of the box over a cave: kept as floor so nobody falls through. */
    private boolean keepAsFloor(int x, int y, int z) {
        return y == areaBottom && !world.isSolid(x, y - 1, z);
    }

    private boolean finishArea() {
        int kept = 0;
        for (long c : caveFloor) if (!world.world().getBlockState(BlockPos.fromLong(c)).isAir()) kept++;
        int nearFluid = 0;
        for (long c : hazardCells) if (!world.world().getBlockState(BlockPos.fromLong(c)).isAir()) nearFluid++;
        success("Auto Mine finished", String.format(Locale.ROOT, "Area %d x %d x %d done: %d blocks mined%s%s%s",
                areaWidth.intValue(), areaLength.intValue(), areaDepth.intValue(), blocksMined,
                nearFluid > 0 ? ", " + nearFluid + " left next to lava/water/bedrock" : "",
                kept > 0 ? ", " + kept + " kept as floor over caves" : "",
                gaveUp.isEmpty() ? "" : ", " + gaveUp.size() + " out of reach"));
        setEnabled(false);
        return false;
    }

    /**
     * Which row of the layer is dug now. The top layer starts with the row right in front of you
     * (you can only get into the box there), goes out to one side, then comes back for the other
     * side. Each layer below starts under the row where the one above ended and sweeps across.
     */
    private int laneNumber() {
        int n = lanesPerLayer;
        int c = Math.min(n - 1, ((areaWidth.intValue() - 1) / 2) / 3); // the row you stand in front of
        if (layer == 0) {
            return laneIdx < n - c ? c + laneIdx : c - 1 - (laneIdx - (n - c));
        }
        boolean fromZero = (c > 0) == ((layer - 1) % 2 == 0);
        return fromZero ? laneIdx : n - 1 - laneIdx;
    }

    /** The clean-up could not get these blocks: leave them for good. */
    private void giveUpOnJob() {
        if (job == null) return;
        for (ExcavationPlanner.Entry e : job.excavation().entries()) {
            BlockPos c = new BlockPos(e.x(), e.y(), e.z());
            if (!world.world().getBlockState(c).isAir()) gaveUp.add(c.asLong());
        }
    }

    private int layerTop() { return areaTop - 3 * layer; }
    private int layerBottom() { return Math.max(areaBottom, layerTop() - 2); }

    /** One 3 wide x 3 high slice of the current row at distance k (smaller at the box's edges). */
    private List<PlacedBlock> laneSlice(int k) {
        int w = areaWidth.intValue();
        int from = -(w - 1) / 2, to = from + w - 1;
        int l0 = from + 3 * laneNumber(), l1 = Math.min(to, l0 + 2);
        int yTop = layerTop(), yBottom = layerBottom();
        int lx = -dz, lz = dx;
        List<PlacedBlock> out = new ArrayList<>(9);
        for (int y = yTop; y >= yBottom; y--) {
            for (int l = l0; l <= l1; l++) {
                int x = ox + dx * k + lx * l, z = oz + dz * k + lz * l;
                if (world.isLoaded(x, z) && keepAsFloor(x, y, z) && !world.world().getBlockState(new BlockPos(x, y, z)).isAir()) {
                    if (caveFloor.add(BlockPos.asLong(x, y, z))) leftOverCaves++;
                    continue;
                }
                out.add(air(x, y, z));
            }
        }
        return out;
    }

    private void begin(ExcavationPlanner.Plan plan, List<PlacedBlock> cells, boolean ores) {
        ExcavationSession ex = new ExcavationSession(plan);
        planSkipped = plan.entries().size() - plan.toRemove();
        BuildSession empty = new BuildSession(PlacementPlanner.plan(List.of(), new McBlockTraits(AutoBuilderModule.get().resolver()), world));
        job = new BuildJob(ex, empty, world);
        executor = new BuildExecutor(job, AutoBuilderModule.get().resolver(), world);
        // Tunnels never step into a hole. In area mode it only steps down 3 (no fall damage) to get onto
        // the layer below or for the clean-up; inside a layer at most 1, so it never walks into a cave.
        int maxDrop = 0;
        if (mode.get() == Mode.AREA) {
            ClientPlayerEntity p = MinecraftClient.getInstance().player;
            boolean goingDown = cleanup || (p != null && p.getBlockPos().getY() > layerBottom());
            maxDrop = goingDown ? 3 : 1;
            if (goingDown && p != null) {
                // Getting down to the next layer (or to leftovers in a cave) may cost a little health: a fall
                // of N blocks does about N-3 half-hearts. Up to the "Fall damage OK" setting, and never so much
                // that it would end up near the "Stop at hearts" level (plus a 2-heart margin).
                double okHp = fallDamage.get() * 2;
                double spare = p.getHealth() - (stopHealth.get() * 2 + 4);
                int extra = (int) Math.floor(Math.max(0, Math.min(okHp, spare)));
                maxDrop = Math.min(8, 3 + extra);
            }
        }
        executor.movement().setMaxDrop(maxDrop);
        if (mode.get() == Mode.AREA) {
            // Walks may dig through the job's blocks and bridge holes in the floor inside the box.
            int w = areaWidth.intValue(), len = areaLength.intValue(), from = -(w - 1) / 2, to = from + w - 1;
            int lx = -dz, lz = dx;
            int x1 = ox + dx + lx * from, x2 = ox + dx * len + lx * to, z1 = oz + dz + lz * from, z2 = oz + dz * len + lz * to;
            // Bridge blocks only go where a later layer mines them again (or under the box): never into a
            // layer that is already done, where they would be left behind.
            int bridgeTop = cleanup ? areaBottom - 1 : layerBottom() - 1;
            ClientPlayerEntity bp = MinecraftClient.getInstance().player;
            if (bp != null && bp.getBlockY() - 1 > bridgeTop && bp.getBlockY() - 1 <= areaTop) {
                // Standing above this layer's floor (at the start over an open cave, or after climbing):
                // it may bridge at its own level to get into the box at all. Those few blocks are picked
                // up by the clean-up pass.
                bridgeTop = bp.getBlockY() - 1;
            }
            executor.setBridging(Math.min(x1, x2), Math.max(x1, x2), areaBottom - 1, bridgeTop, Math.min(z1, z2), Math.max(z1, z2), unfillable);
            // ... and never leave the box (the caves around it are not part of the job).
            executor.movement().setFence(new int[]{Math.min(x1, x2), Math.max(x1, x2), areaBottom, areaTop + 2, Math.min(z1, z2), Math.max(z1, z2)});
        }
        executor.setInstantBreaksPerTick(fastBreaks.intValue());
        // Throw junk backwards, away from the tunnel face.
        executor.setDropCenter(ox + dx * (slice + 64) + 0.5, oz + dz * (slice + 64) + 0.5);
        job.start();
        oreJob = ores;
        current = cells;
        lastProgress = -1;
        lastProgressTick = ticks;
    }

    private void finishJob() {
        if (job != null && oreJob) {
            for (ExcavationPlanner.Entry e : job.excavation().entries()) {
                if (world.world().getBlockState(new BlockPos(e.x(), e.y(), e.z())).isAir()) oreDug.add(new long[]{e.x(), e.y(), e.z()});
            }
        }
        if (executor != null) executor.stop();
        job = null;
        executor = null;
    }

    private int half() { return size.get() == Size.BIG_3X3 ? 1 : 0; }
    private int height() { return size.get() == Size.BIG_3X3 ? 3 : 2; }

    private List<PlacedBlock> sliceCells(int k) {
        int lx = -dz, lz = dx; // to the right of the tunnel direction
        List<PlacedBlock> out = new ArrayList<>();
        for (int l = -half(); l <= half(); l++) {
            for (int h = height() - 1; h >= 0; h--) {
                out.add(air(ox + dx * k + lx * l, oy + h, oz + dz * k + lz * l));
            }
        }
        return out;
    }

    private static PlacedBlock air(int x, int y, int z) {
        return new PlacedBlock(x, y, z, BlockStateString.AIR, Transforms.StateTransform.IDENTITY, "automine");
    }

    /**
     * Ores touching the dug-out space: the last few tunnel slices plus ores already mined (so a vein
     * is followed). Never below the tunnel floor and never far from the tunnel, so it can't dig pits.
     */
    private List<PlacedBlock> exposedOres() {
        List<long[]> dug = new ArrayList<>();
        for (int k = Math.max(1, slice - 2); k <= slice; k++) {
            for (PlacedBlock b : sliceCells(k)) dug.add(new long[]{b.x(), b.y(), b.z()});
        }
        boolean vein = ores.get() == Ores.WHOLE_VEIN;
        oreDug.removeIf(c -> along(c) < slice - 6);
        if (vein) dug.addAll(oreDug);
        int reachSide = vein ? ORE_REACH_SIDE : 1;
        List<PlacedBlock> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (long[] c : dug) {
            for (Direction d : Direction.values()) {
                int x = (int) c[0] + d.getOffsetX(), y = (int) c[1] + d.getOffsetY(), z = (int) c[2] + d.getOffsetZ();
                long key = BlockPos.asLong(x, y, z);
                if (y < oy || y > oy + height() - 1 + reachSide || Math.abs(side(x, z)) > half() + reachSide) continue;
                if (oreTried.contains(key) || !seen.add(key)) continue;
                if (!world.isLoaded(x, z)) continue;
                BlockState s = world.world().getBlockState(m.set(x, y, z));
                if (isOre(s)) out.add(air(x, y, z));
            }
        }
        return out;
    }

    private int along(long[] c) { return (int) ((c[0] - ox) * dx + (c[2] - oz) * dz); }
    private int side(int x, int z) { return (x - ox) * -dz + (z - oz) * dx; }

    static boolean isOre(BlockState s) {
        String path = Registries.BLOCK.getId(s.getBlock()).getPath();
        return path.endsWith("_ore") || path.equals("ancient_debris");
    }

    private String where() {
        return (ox + dx * slice) + " " + oy + " " + (oz + dz * slice);
    }

    private String statusText() {
        if (mode.get() == Mode.AREA) {
            String exec = shortExec();
            if (cleanup) {
                return String.format(Locale.ROOT, "Area: cleaning up, %d blocks left, %d mined%s", cleanupLeft, blocksMined, exec);
            }
            int done = layer * lanesPerLayer + laneIdx, all = Math.max(1, layers * lanesPerLayer);
            return String.format(Locale.ROOT, "Area: %d%%, layer %d/%d (Y %d), row %d/%d, %d mined%s", done * 100 / all, layer + 1, layers,
                    layerBottom(), laneIdx + 1, lanesPerLayer, blocksMined, exec);
        }
        int len = length.intValue();
        String exec = executor == null ? "" : executor.status();
        return String.format(Locale.ROOT, "%s %d%s blocks, %d ores%s", facingName, slice, len > 0 ? "/" + len : "", oresMined,
                exec.isEmpty() ? "" : " - " + exec);
    }

    /** What the digger is doing, only when it is not plain digging (walking, bridging, no path...). */
    private String shortExec() {
        String exec = executor == null ? "" : executor.status();
        if (exec.isEmpty() || exec.startsWith("Excavating")) return "";
        int paren = exec.indexOf(" (");
        return " - " + (paren > 0 ? exec.substring(0, paren) : exec);
    }

    private BuildExecutor.Config config(MinecraftClient mc) {
        AutoBuilderModule b = AutoBuilderModule.get();
        double gameReach = mc.player.getBlockInteractionRange();
        List<String> keywords = new ArrayList<>();
        for (String k : b.amethystKeyword.get().split(",")) keywords.add(k.trim());
        // All the reach the server gives you (block_interaction_range); the server rejects anything farther.
        return new BuildExecutor.Config(gameReach, 1, 1, true, false, 8, pickaxe.get(), true,
                b.minDurability.intValue(), keywords, b.amethystSlot.intValue() - 1, 4000, false,
                dropJunk.get() ? Restock.DropMode.WHEN_FULL : Restock.DropMode.OFF, Restock.DropScope.JUNK_ONLY,
                Restock.parseIds(b.junkList.get()), Restock.parseIds(b.neverDrop.get()), 2);
    }

    /** Outlines the blocks being dug right now (area mode: the whole box). */
    @Override
    public void collectOverlay(Frame f, List<OverlayCuller.Box> out) {
        if (mode.get() == Mode.AREA && isEnabled()) {
            int w = areaWidth.intValue(), len = areaLength.intValue();
            int lx = -dz, lz = dx, from = -(w - 1) / 2, to = from + w - 1;
            int x1 = ox + dx + lx * from, z1 = oz + dz + lz * from;
            int x2 = ox + dx * len + lx * to, z2 = oz + dz * len + lz * to;
            out.add(new OverlayCuller.Box(Math.min(x1, x2), areaBottom, Math.min(z1, z2), Math.max(x1, x2) + 1, areaTop + 1,
                    Math.max(z1, z2) + 1, 0xFFFFAA00, null, 0));
            return;
        }
        for (PlacedBlock b : current) {
            out.add(new OverlayCuller.Box(b.x() + 0.05, b.y() + 0.05, b.z() + 0.05, b.x() + 0.95, b.y() + 0.95, b.z() + 0.95,
                    oreJob ? 0x6600E5FF : 0x44FFAA00, null, 0));
        }
    }

    public int slice() { return slice; }
    public int oresMined() { return oresMined; }
    public boolean busy() { return job != null; }
}
