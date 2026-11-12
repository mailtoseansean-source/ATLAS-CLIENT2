package dev.atlasclient.module.building;

import dev.atlasclient.Atlas;
import dev.atlasclient.build.BuildExecutor;
import dev.atlasclient.build.McBlockTraits;
import dev.atlasclient.build.McWorld;
import dev.atlasclient.build.SchematicSession;
import dev.atlasclient.build.StateResolver;
import dev.atlasclient.core.build.AreaMining;
import dev.atlasclient.core.build.Restock;
import dev.atlasclient.core.build.BuildJob;
import dev.atlasclient.core.build.BuildSession;
import dev.atlasclient.core.build.ExcavationPlanner;
import dev.atlasclient.core.build.ExcavationSession;
import dev.atlasclient.core.build.MaterialList;
import dev.atlasclient.core.build.PlacementPlanner;
import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.schematic.LitematicaPlacementImporter;
import dev.atlasclient.core.schematic.Placement;
import dev.atlasclient.core.setting.Keybind;
import dev.atlasclient.core.setting.Settings.ActionSetting;
import dev.atlasclient.core.setting.Settings.BooleanSetting;
import dev.atlasclient.core.setting.Settings.ChoiceSetting;
import dev.atlasclient.core.setting.Settings.EnumSetting;
import dev.atlasclient.core.setting.Settings.KeybindSetting;
import dev.atlasclient.core.setting.Settings.NumberSetting;
import dev.atlasclient.core.setting.Settings.StringSetting;
import dev.atlasclient.integration.LitematicaBridge;
import dev.atlasclient.module.AtlasModule;
import dev.atlasclient.util.Env;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.InputUtil;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the loaded schematic (.schem / .litematic) with normal interactions, optionally digging
 * out the space first.
 */
public final class AutoBuilderModule extends AtlasModule {
    public final SchematicSession session = new SchematicSession();
    private final dev.atlasclient.build.SurvivalAssist assist = new dev.atlasclient.build.SurvivalAssist();
    private int buildFloorY = Integer.MIN_VALUE;
    private double buildCenterX, buildCenterZ;
    private final StateResolver resolver = new StateResolver();

    final ChoiceSetting file = add(new ChoiceSetting("Schematic", "File from .minecraft/schematics or config/atlasclient/schematics", "",
            SchematicSession::available));
    final ActionSetting load = add(new ActionSetting("Load here", "Load the selected schematic with its origin at your feet", this::loadHere));
    final ActionSetting buildHere = add(new ActionSetting("Build here", "Load the selected schematic at your feet and start building", this::buildHere));
    final StringSetting toolItem = add(new StringSetting("Placement tool", "Hold this item to move (right-click), rotate (left-click), mirror (sneak+left), build (sneak+right) and nudge (Ctrl+scroll) the schematic, like Litematica's stick. Empty = off",
            "minecraft:birch_planks", 64));
    final ActionSetting originHere = add(new ActionSetting("Set origin here", "Move the placement origin to your feet", this::originHere));
    final ActionSetting rotate = add(new ActionSetting("Rotate 90", "Rotate the placement clockwise", () -> {
        if (session.loaded()) { session.setRotation(session.placement().rotation().next()); session.save(); }
    }));
    final ActionSetting mirror = add(new ActionSetting("Cycle mirror", "None -> Left/Right (Z) -> Front/Back (X)", () -> {
        if (session.loaded()) { session.setMirror(session.placement().mirror().next()); session.save(); }
    }));
    final ChoiceSetting litematica = add(new ChoiceSetting("Litematica placement", "Placements saved by Litematica (config/litematica)", "",
            AutoBuilderModule::litematicaNames));
    final ActionSetting importLitematica = add(new ActionSetting("Import Litematica placement", "Use the selected Litematica placement (same origin/rotation/mirror)", this::importLitematica));
    final ActionSetting importLive = add(new ActionSetting("Import live (experimental)", "Read the first placement from a running Litematica (reflection; untested here)", this::importLive));
    final ActionSetting start = add(new ActionSetting("Start", "Start construction", this::start));
    final ActionSetting pauseResume = add(new ActionSetting("Pause / resume", "Pause or resume construction", this::togglePause));
    final ActionSetting cancel = add(new ActionSetting("Cancel", "Stop construction", this::cancel));
    final ActionSetting restart = add(new ActionSetting("Restart", "Re-plan from the current world state and start again", this::restart));
    final ActionSetting retry = add(new ActionSetting("Retry skipped", "Retry skipped/failed blocks (e.g. after collecting materials)", this::retrySkipped));
    public final BooleanSetting excavate = add(new BooleanSetting("Excavate first", "Dig out every block inside the schematic volume that does not belong there", true));
    final BooleanSetting avoidFluids = add(new BooleanSetting("Avoid fluid breaches", "Never mine blocks next to lava or water", true));
    public final EnumSetting<AreaMining.PickaxeMode> pickaxeMode = add(new EnumSetting<>("Pickaxe mode", "Regular, or Amethyst (3x3) where the whole plane is safe", AreaMining.PickaxeMode.REGULAR));
    final KeybindSetting pickaxeToggleKey = add(new KeybindSetting("Pickaxe toggle key", "Switch Regular / Amethyst", Keybind.NONE));
    final BooleanSetting edgeFallback = add(new BooleanSetting("Edge fallback", "Use a regular pickaxe where a 3x3 swing is unsafe", true));
    final StringSetting amethystKeyword = add(new StringSetting("Amethyst keyword", "Name/lore/enchantment text that identifies the 3x3 pickaxe", "amethyst", 64));
    final NumberSetting amethystSlot = add(new NumberSetting("Amethyst slot", "Inventory slot of the 3x3 pickaxe (0 = auto detect, 1-9 hotbar, 10-36 main)", 0, 0, 36, 1));
    final NumberSetting minDurability = add(new NumberSetting("Min durability", "Do not use tools with this much durability left or less", 10, 0, 200, 1));
    final NumberSetting reach = add(new NumberSetting("Max reach", "Interaction distance; capped by the reach the server gives you (block_interaction_range)", 64, 2, 64, 0.5));
    final NumberSetting perTick = add(new NumberSetting("Placements per tick", "Placement speed", 1, 1, 4, 1));
    final NumberSetting delay = add(new NumberSetting("Delay ticks", "Pause between placements", 1, 0, 20, 1));
    final BooleanSetting autoWalk = add(new BooleanSetting("Auto walk", "Walk to the next placement (otherwise you are guided)", true));
    final BooleanSetting sprint = add(new BooleanSetting("Sprint", "Sprint while walking", false));
    final NumberSetting hotbarSlot = add(new NumberSetting("Hotbar slot", "Hotbar slot used for swapped-in items (1-9)", 9, 1, 9, 1));
    final BooleanSetting restockFromShulkers = add(new BooleanSetting("Restock from shulkers",
            "When a block runs out, place a shulker box from your inventory, take what the build needs, then mine the box and pick it back up (survival)", true));
    final EnumSetting<Restock.DropMode> dropMode = add(new EnumSetting<>("Drop unneeded blocks",
            "OFF never throws anything; WHEN_FULL throws unneeded blocks only to keep free slots; ALWAYS throws them as soon as they are picked up", Restock.DropMode.WHEN_FULL));
    final EnumSetting<Restock.DropScope> dropScope = add(new EnumSetting<>("Drop which",
            "JUNK_ONLY: only blocks on the junk list (digging drops). ANY_UNNEEDED_BLOCK: any block the build does not use", Restock.DropScope.JUNK_ONLY));
    final StringSetting junkList = add(new StringSetting("Junk list", "Comma separated block ids treated as junk",
            Restock.joinIds(Restock.DEFAULT_JUNK), 2000));
    final StringSetting neverDrop = add(new StringSetting("Never drop", "Comma separated ids that are never thrown (shulker boxes, renamed and enchanted items are always kept)",
            Restock.joinIds(Restock.DEFAULT_NEVER_DROP), 2000));
    final NumberSetting keepFree = add(new NumberSetting("Keep free slots", "WHEN_FULL mode throws junk until this many slots are empty", 3, 1, 9, 1));

    private BuildJob job;
    private BuildExecutor executor;
    private boolean toggleKeyDown;
    private boolean completedAnnounced;
    private boolean restored;

    public AutoBuilderModule() {
        super("AutoBuilder", "Builds .schem/.litematic schematics with normal interactions; can excavate first", Category.BUILDING);
    }

    private static List<String> litematicaNames() {
        List<String> out = new ArrayList<>();
        for (LitematicaPlacementImporter.Imported i : LitematicaBridge.fromFiles()) {
            out.add(i.placement().name() + " @ " + i.placement().originX() + "," + i.placement().originY() + "," + i.placement().originZ());
        }
        return out;
    }

    // No enableBlockedReason: switching it on outside a world is harmless (it waits), and a saved
    // profile must not produce a "Join a world first" warning at every game start.

    private void loadHere() {
        var p = MinecraftClient.getInstance().player;
        if (p == null) return;
        Path f = SchematicSession.locate(file.get());
        if (f == null) {
            warn("AutoBuilder", "Select a schematic first (put files into .minecraft/schematics)");
            return;
        }
        try {
            session.load(f, p.getBlockPos().getX(), p.getBlockPos().getY(), p.getBlockPos().getZ());
            session.save();
            resolver.clear();
            int unknown = countUnknown();
            info("Schematic loaded", String.format(Locale.ROOT, "%s: %d blocks in %d region(s)%s", session.schematic().name(),
                    session.schematic().totalNonAir(), session.schematic().regions().size(),
                    unknown > 0 ? ", " + unknown + " unknown block types" : ""));
        } catch (Exception e) {
            error("Schematic load failed", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ placement tool (birch plank)

    /** A schematic is loaded, or one is selected that the tool can load. */
    public boolean hasSchematic() {
        return session.loaded() || (!file.get().isEmpty() && SchematicSession.locate(file.get()) != null);
    }

    public String toolItemId() {
        String id = toolItem.get().trim().toLowerCase(Locale.ROOT);
        return id.isEmpty() || id.contains(":") ? id : "minecraft:" + id;
    }

    private void showPreview() {
        SchematicPreviewModule preview = atlas().modules.get(SchematicPreviewModule.class);
        if (preview != null && !preview.isEnabled()) preview.setEnabled(true);
    }

    private boolean busy() {
        return job != null && job.isRunning();
    }

    /** Right-click with the tool: put the schematic's origin on this block (loads the selected file if needed). */
    public String toolMoveTo(int x, int y, int z) {
        if (busy()) return "Pause or stop the build before moving it (sneak + right-click pauses)";
        Path f = SchematicSession.locate(file.get());
        boolean differentFile = f != null && (session.file() == null || !session.file().equals(f));
        if (!session.loaded() || differentFile) {
            if (f == null) return "Pick a schematic in the menu first";
            try {
                session.load(f, x, y, z);
                resolver.clear();
            } catch (Exception e) {
                error("Schematic load failed", e.getMessage());
                return "";
            }
        } else {
            session.setOrigin(x, y, z);
        }
        session.save();
        if (job != null && !job.isRunning()) cancelQuietly();
        showPreview();
        return session.schematic().name() + " placed at " + x + " " + y + " " + z;
    }

    public String toolRotate() {
        if (busy()) return "Pause or stop the build before rotating it";
        session.setRotation(session.placement().rotation().next());
        session.save();
        showPreview();
        return "Rotation: " + session.placement().rotation().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    public String toolMirror() {
        if (busy()) return "Pause or stop the build before mirroring it";
        session.setMirror(session.placement().mirror().next());
        session.save();
        showPreview();
        return "Mirror: " + session.placement().mirror().name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    public String toolNudge(int dx, int dy, int dz) {
        if (busy()) return "Pause or stop the build before moving it";
        session.nudge(dx, dy, dz);
        session.save();
        showPreview();
        return "Moved to " + session.placement().originX() + " " + session.placement().originY() + " " + session.placement().originZ();
    }

    /** Sneak + right-click: start, or pause / resume a running build. */
    public String toolBuildOrPause() {
        if (job != null && (job.isRunning() || job.isPaused())) {
            togglePause();
            return job.isPaused() ? "Build paused (sneak + right-click to resume)" : "Build resumed";
        }
        if (!session.loaded()) return "Right-click a block first to place the schematic";
        start();
        return job != null && job.isRunning() ? "Building " + session.schematic().name() : "";
    }

    private void cancelQuietly() {
        if (executor != null) executor.abandon();
        job = null;
        executor = null;
    }

    /** One click: load the selected schematic at your feet and start. */
    private void buildHere() {
        loadHere();
        if (session.loaded() && session.file() != null && session.file().equals(SchematicSession.locate(file.get()))) {
            start();
        }
    }

    private int countUnknown() {
        for (var b : session.blocks()) resolver.resolve(b.state(), null);
        return resolver.unknownBlocks().size();
    }

    private void originHere() {
        var p = MinecraftClient.getInstance().player;
        if (p == null || !session.loaded()) return;
        session.setOrigin(p.getBlockPos().getX(), p.getBlockPos().getY(), p.getBlockPos().getZ());
        session.save();
    }

    private void importLitematica() {
        List<LitematicaPlacementImporter.Imported> all = LitematicaBridge.fromFiles();
        if (all.isEmpty()) {
            warn("Litematica", "No saved Litematica placements found in config/litematica (they are written when you leave a world)");
            return;
        }
        LitematicaPlacementImporter.Imported pick = all.get(0);
        for (LitematicaPlacementImporter.Imported i : all) {
            String n = i.placement().name() + " @ " + i.placement().originX() + "," + i.placement().originY() + "," + i.placement().originZ();
            if (n.equals(litematica.get())) pick = i;
        }
        adopt(pick.placement(), "file");
    }

    private void importLive() {
        List<Placement> live = LitematicaBridge.live();
        if (live.isEmpty()) {
            warn("Litematica", LitematicaBridge.liveFailure().isEmpty() ? "No placements in the running Litematica" : LitematicaBridge.liveFailure());
            return;
        }
        adopt(live.get(0), "live (experimental)");
    }

    private void adopt(Placement p, String source) {
        Path f = Path.of(p.schematicFile());
        try {
            session.load(f, p);
            session.save();
            resolver.clear();
            info("Schematic loaded", "Litematica placement '" + p.name() + "' (" + source + "): origin " + p.originX() + " "
                    + p.originY() + " " + p.originZ() + ", " + p.rotation() + ", " + p.mirror());
        } catch (Exception e) {
            error("Litematica import failed", f.getFileName() + ": " + e.getMessage());
        }
    }

    public void start() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return;
        if (!session.loaded()) {
            warn("AutoBuilder", "Load a schematic first");
            return;
        }
        if (!session.placement().dimension().isEmpty() && !session.placement().dimension().equals(Env.dimension())) {
            warn("AutoBuilder", "The placement is in " + session.placement().dimension() + "; you are in " + Env.dimension());
            return;
        }
        if (!isEnabled()) setEnabled(true);
        if (executor != null) executor.abandon();
        McWorld world = new McWorld(mc.world, resolver);
        PlacementPlanner.Plan plan = PlacementPlanner.plan(session.blocks(), new McBlockTraits(resolver), world);
        BuildSession bs = new BuildSession(plan);
        ExcavationSession ex = null;
        if (excavate.get()) {
            ExcavationPlanner.Plan dig = ExcavationPlanner.plan(session.blocksIncludingAir(), world, avoidFluids.get());
            if (!dig.nothingToDo()) {
                ex = new ExcavationSession(dig);
                if (dig.fluidCells() + dig.fluidNeighbours() + dig.unbreakable() > 0) {
                    warn("Excavation", String.format(Locale.ROOT, "%d blocks left in place (%d fluid, %d next to fluid, %d unbreakable)",
                            dig.fluidCells() + dig.fluidNeighbours() + dig.unbreakable(), dig.fluidCells(), dig.fluidNeighbours(), dig.unbreakable()));
                }
            }
        }
        job = new BuildJob(ex, bs, world);
        executor = new BuildExecutor(job, resolver, world);
        // Work area for eating / climbing out of holes: the build's lowest level and its middle.
        int minY = Integer.MAX_VALUE, minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (var b : session.blocks()) {
            minY = Math.min(minY, b.y());
            minX = Math.min(minX, b.x());
            maxX = Math.max(maxX, b.x());
            minZ = Math.min(minZ, b.z());
            maxZ = Math.max(maxZ, b.z());
        }
        buildFloorY = minY == Integer.MAX_VALUE ? Integer.MIN_VALUE : minY;
        buildCenterX = (minX + maxX) / 2.0 + 0.5;
        buildCenterZ = (minZ + maxZ) / 2.0 + 0.5;
        assist.reset(mc);
        assist.fallMargin = 3; // only a real hole, not ordinary terrain around the build
        job.start();
        completedAnnounced = false;
        boolean creative = dev.atlasclient.build.InventoryHelper.creative(mc.player);
        java.util.Map<String, Integer> held = dev.atlasclient.build.InventoryHelper.counts(mc.player);
        java.util.Map<String, Integer> withBoxes = new java.util.HashMap<>(held);
        if (restockFromShulkers.get()) {
            dev.atlasclient.build.InventoryHelper.shulkerTotals(mc.player).forEach((k, v) -> withBoxes.merge(k, v, Integer::sum));
        }
        MaterialList inInventory = MaterialList.compute(plan.entries(), held, creative);
        MaterialList m = MaterialList.compute(plan.entries(), withBoxes, creative);
        int fromBoxes = inInventory.totalMissing() - m.totalMissing();
        success("Construction started", String.format(Locale.ROOT, "%d to place, %d already correct, %d incorrect%s%s%s",
                plan.actionable(), plan.alreadyCorrect(), plan.wrongBlocks(), ex != null ? ", " + ex.progress().total() + " to dig" : "",
                fromBoxes > 0 ? ", " + fromBoxes + " items will come from shulker boxes" : "",
                m.complete() ? "" : ", missing " + m.totalMissing() + " items"));
        if (!m.complete()) {
            MaterialList.Line l = m.missingLines().get(0);
            warn("Missing building materials", m.missingLines().size() + " types, e.g. " + l.missing() + "x " + l.item());
        }
    }

    public void togglePause() {
        if (job == null) return;
        if (job.isPaused()) {
            job.resume();
            info("AutoBuilder", "Construction resumed");
        } else if (job.isRunning()) {
            job.pause();
            executor.stop();
            info("Construction paused", job.summary());
        }
    }

    public void cancel() {
        if (job == null) return;
        job.cancel();
        if (executor != null) executor.abandon();
        info("AutoBuilder", "Construction cancelled");
        job = null;
        executor = null;
    }

    public void restart() {
        cancel();
        start();
    }

    public void retrySkipped() {
        if (job == null) return;
        int n = job.build().retrySkipped();
        info("AutoBuilder", n + " blocks queued again");
    }

    public void setPickaxeMode(AreaMining.PickaxeMode mode) {
        pickaxeMode.set(mode);
        info("Pickaxe mode", mode == AreaMining.PickaxeMode.AMETHYST ? "Amethyst (3x3 where safe)" : "Regular");
        atlas().config.markDirty();
    }

    public BuildJob job() { return job; }

    @Override
    public void onTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!restored && Env.inWorld()) {
            restored = true;
            session.restore();
        }
        // Pickaxe toggle key (edge-triggered, ignored while a screen is open).
        if (pickaxeToggleKey.isBound()) {
            boolean down = InputUtil.isKeyPressed(mc.getWindow(), pickaxeToggleKey.key());
            if (down && !toggleKeyDown && mc.currentScreen == null) {
                setPickaxeMode(pickaxeMode.get() == AreaMining.PickaxeMode.REGULAR ? AreaMining.PickaxeMode.AMETHYST : AreaMining.PickaxeMode.REGULAR);
            }
            toggleKeyDown = down;
        }
        if (job == null || executor == null) {
            setStatus(session.loaded() ? "Loaded: " + session.schematic().name() : "No schematic loaded");
            return;
        }
        if (dev.atlasclient.camera.FreecamController.active()) {
            executor.movement().stop();
            setStatus("Paused while Freecam is on");
            return;
        }
        if (mc.currentScreen != null && !executor.ownsScreen(mc)) {
            executor.movement().stop(); // never act while a menu is open (except our own shulker restock)
            return;
        }
        // Eat when hungry; climb out if it fell into a hole well below the build.
        if (job.isRunning() && assist.tick(mc, buildFloorY, buildCenterX, buildCenterZ)) {
            executor.movement().stop();
            setStatus(switch (assist.phase()) {
                case EATING -> "Eating";
                case PILLARING -> "Fell in a hole: pillaring up";
                case RETURNING -> "Going back to the build";
                case FILLING -> "Bridging back";
                default -> executor.status();
            });
            return;
        }
        if (assist.phase() == dev.atlasclient.build.SurvivalAssist.Phase.FAILED) {
            warn("AutoBuilder paused", assist.failure());
            assist.reset(mc);
            if (job.isRunning()) togglePause();
            return;
        }
        double gameReach = mc.player == null ? 4.5 : mc.player.getBlockInteractionRange();
        List<String> keywords = new ArrayList<>();
        for (String k : amethystKeyword.get().split(",")) keywords.add(k.trim());
        int manualSlot = amethystSlot.intValue() - 1; // setting 1-36 -> inventory index 0-35, 0 -> -1 (auto)
        BuildExecutor.Config cfg = new BuildExecutor.Config(Math.min(reach.get(), gameReach), perTick.intValue(), delay.intValue(),
                autoWalk.get(), sprint.get(), hotbarSlot.intValue() - 1, pickaxeMode.get(), edgeFallback.get(), minDurability.intValue(),
                keywords, manualSlot, 4000, restockFromShulkers.get(), dropMode.get(), dropScope.get(),
                Restock.parseIds(junkList.get()), Restock.parseIds(neverDrop.get()), keepFree.intValue());
        executor.tick(mc, cfg);
        setStatus(executor.status());
        if (job.phase() == BuildJob.Phase.FINISHED && !completedAnnounced) {
            completedAnnounced = true;
            BuildSession.Progress p = job.build().progress();
            success("Construction completed", String.format(Locale.ROOT, "Verified: %d placed + %d already correct of %d; %d incorrect, %d failed, %d skipped",
                    p.done(), p.alreadyCorrect(), p.total(), p.incorrect(), p.failed(), p.skipped()));
            executor.stop();
        }
    }

    @Override protected void onDisable() {
        if (job != null && job.isRunning()) {
            job.pause();
            executor.stop();
            info("Construction paused", "AutoBuilder disabled");
        }
    }

    @Override public void onSessionReset() {
        if (executor != null) executor.abandon();
        session.save();
        job = null;
        executor = null;
        session.unload();
        resolver.clear();
        restored = false;
    }

    @Override
    public int renderHud(DrawContext ctx, int x, int y, float tickDelta) {
        if (job == null) return 0;
        var tr = MinecraftClient.getInstance().textRenderer;
        double frac = job.phase() == BuildJob.Phase.EXCAVATING ? job.excavation().progress().fraction() : job.build().progress().fraction();
        ctx.fill(x, y + 1, x + 122, y + 6, 0x90000000);
        ctx.fill(x + 1, y + 2, x + 1 + (int) (120 * frac), y + 5, job.phase() == BuildJob.Phase.EXCAVATING ? 0xFFFFB74D : 0xFF81C784);
        String mode = pickaxeMode.get() == AreaMining.PickaxeMode.AMETHYST ? "Amethyst 3x3" : "Regular";
        ctx.drawTextWithShadow(tr, "Build: " + job.summary() + " | Pickaxe: " + mode, x, y + 8, 0xFFE0E0E0);
        String st = executor == null ? "" : executor.status();
        if (!st.isEmpty()) {
            ctx.drawTextWithShadow(tr, st.length() > 80 ? st.substring(0, 77) + "..." : st, x, y + 18, 0xFFA0A0B0);
            return 30;
        }
        return 20;
    }

    public StateResolver resolver() { return resolver; }

    public static AutoBuilderModule get() { return Atlas.get().modules.get(AutoBuilderModule.class); }
}
