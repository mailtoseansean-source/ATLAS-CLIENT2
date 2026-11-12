package dev.atlasclient.gametest;

import dev.atlasclient.Atlas;
import dev.atlasclient.build.StateResolver;
import dev.atlasclient.core.build.AreaMining;
import dev.atlasclient.core.build.BuildJob;
import dev.atlasclient.core.build.BuildSession;
import dev.atlasclient.core.notify.Notification;
import dev.atlasclient.core.samples.SampleSchematics;
import dev.atlasclient.core.schematic.SchematicPlacer;
import dev.atlasclient.core.schematic.transform.Mirror;
import dev.atlasclient.core.schematic.transform.Rotation;
import dev.atlasclient.AtlasClient;
import dev.atlasclient.camera.FreecamController;
import dev.atlasclient.core.analysis.ChunkAssessment;
import dev.atlasclient.core.analysis.Confidence;
import dev.atlasclient.core.analysis.Indicator;
import dev.atlasclient.core.analysis.SiteClusterer;
import dev.atlasclient.gui.ClickGuiScreen;
import dev.atlasclient.module.building.AutoBuilderModule;
import dev.atlasclient.module.building.AutoMineModule;
import dev.atlasclient.module.world.AncientDebrisFinderModule;
import dev.atlasclient.module.world.BaseFinderModule;
import dev.atlasclient.util.Env;
import dev.atlasclient.module.camera.FreecamModule;
import dev.atlasclient.module.render.ContainerEspModule;
import dev.atlasclient.module.render.ItemEspModule;
import dev.atlasclient.module.render.MobEspModule;
import dev.atlasclient.module.render.XRayModule;
import net.minecraft.component.type.ContainerComponent;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * In-game tests in a real Minecraft client. Part 1 runs on a real dedicated server (multiplayer,
 * like playing on a public server): ClickGUI, Auto Mine, Base Finder underground, X-Ray, ESP and
 * Freecam. Part 2 is the single-player AutoBuilder suite.
 *
 * <p>AutoBuilder tests. Run with {@code ./gradlew runClientGameTest}. Each scenario builds a
 * sample schematic in a fresh flat singleplayer world, then checks every block on the
 * <b>server</b> world (not the client's view), so a scenario only passes if the blocks were really
 * placed. A report is written to {@code <run dir>/atlas-gametest-report.txt}.
 */
@SuppressWarnings("UnstableApiUsage")
public class AtlasClientGameTest implements FabricClientGameTest {
    private final List<String> report = new ArrayList<>();
    private int failures;
    /** AutoBuilder scenarios are reported but do not fail the run (the focus is Auto Mine and the finders). */
    private boolean blocking = true;

    private record Verify(int expected, int matching, int wrongBlock, int wrongState, String firstProblem) {
        boolean ok() { return expected > 0 && matching == expected; }
        @Override public String toString() {
            return matching + "/" + expected + " correct, " + wrongBlock + " wrong block, " + wrongState + " wrong state"
                    + (firstProblem.isEmpty() ? "" : " (first: " + firstProblem + ")");
        }
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        testCtx = context;
        Path schemDir = FabricLoader.getInstance().getGameDir().resolve("schematics");
        try {
            SampleSchematics.writeAll(schemDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String suite = System.getenv().getOrDefault("ATLAS_SUITE", "all");
        System.out.println("[Atlas gametest] NOTE  suite " + suite + (System.getenv("ATLAS_LEVEL") != null ? " level " + System.getenv("ATLAS_LEVEL") : ""));
        this.suite = suite;
        if (suite.equals("all") || suite.equals("mine") || suite.equals("finder")) safe("dedicated server session", () -> {
            java.util.Properties props = new java.util.Properties();
            props.setProperty("view-distance", "6");
            props.setProperty("simulation-distance", "6");
            try (TestDedicatedServerContext server = context.worldBuilder().createServer(props);
                 TestServerConnection conn = server.connect()) {
                conn.getClientWorld().waitForChunksRender();
                multiplayer(context, server);
            }
        });
        if (suite.equals("all") || suite.equals("builder")) try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            sp.getClientWorld().waitForChunksRender();
            TestServerContext server = sp.getServer();
            server.runCommand("gamemode creative @a");
            BlockPos spawn = context.computeOnClient(mc -> mc.player.getBlockPos());
            int baseX = spawn.getX() + 4, y = spawn.getY(), baseZ = spawn.getZ() + 4;

            blocking = false;
            // 1-7: creative builds, one site every 16 blocks along +X.
            safe("scenario", () -> scenario(context, server, "stone platform", "stone_platform.schem", new BlockPos(baseX, y, baseZ), Rotation.NONE, Mirror.NONE, false, 1200));
            safe("scenario", () -> scenario(context, server, "small house", "small_house.litematic", new BlockPos(baseX + 16, y, baseZ), Rotation.NONE, Mirror.NONE, false, 2400));
            safe("scenario", () -> scenario(context, server, "stairs and slabs", "stairs_and_slabs.schem", new BlockPos(baseX + 32, y, baseZ), Rotation.NONE, Mirror.NONE, false, 1200));
            safe("scenario", () -> scenario(context, server, "redstone mechanism", "redstone_mechanism.litematic", new BlockPos(baseX + 48, y, baseZ), Rotation.NONE, Mirror.NONE, false, 1200));
            safe("scenario", () -> scenario(context, server, "multi-region", "multi_region.litematic", new BlockPos(baseX + 64, y, baseZ), Rotation.NONE, Mirror.NONE, false, 1200));
            safe("scenario", () -> scenario(context, server, "rotated house (90 cw)", "small_house.litematic", new BlockPos(baseX + 80, y, baseZ), Rotation.CLOCKWISE_90, Mirror.NONE, false, 2400));
            safe("scenario", () -> scenario(context, server, "mirrored stairs (front-back)", "stairs_and_slabs.schem", new BlockPos(baseX + 96, y, baseZ), Rotation.NONE, Mirror.FRONT_BACK, false, 1200));

            // 8: missing materials (survival, empty inventory) then materials supplied.
            safe("missingMaterials", () -> missingMaterials(context, server, new BlockPos(baseX, y, baseZ + 20)));
            // 9: pause / resume.
            safe("pauseResume", () -> pauseResume(context, server, new BlockPos(baseX + 16, y, baseZ + 20)));
            // 10: excavation in creative, then 11: excavation in survival with Amethyst mode on a
            // vanilla server (no 3x3 plugin): the builder must notice single breaks and finish anyway.
            safe("excavation", () -> excavation(context, server, "excavation (creative, regular)", new BlockPos(baseX + 32, y, baseZ + 20), false));
            safe("excavation", () -> excavation(context, server, "excavation (survival, amethyst mode, vanilla server)", new BlockPos(baseX + 48, y, baseZ + 20), true));
            // 12: shulker restock with a full inventory (junk must be dropped first).
            safe("shulkerRestock", () -> shulkerRestock(context, server, new BlockPos(baseX + 64, y, baseZ + 20)));
            blocking = true;
            // 13-16: X-Ray, ESP, Freecam, settings persistence.
            safe("xray", () -> xray(context, server, new BlockPos(baseX, y, baseZ + 40)));
            safe("esp", () -> esp(context, server, new BlockPos(baseX + 16, y, baseZ + 40)));
            safe("freecam", () -> freecam(context, server));
            safe("persistence", () -> persistence(context));
            // Leave Freecam and X-Ray on, then switch worlds: everything per-world must be released.
            context.runOnClient(mc -> {
                Atlas.get().modules.get(XRayModule.class).setEnabled(true);
                Atlas.get().modules.get(FreecamModule.class).setEnabled(true);
            });
            context.waitTicks(20);
        }
        if (suite.equals("all") || suite.equals("builder")) try (TestSingleplayerContext second = context.worldBuilder().create()) {
            second.getClientWorld().waitForChunksRender();
            context.waitTicks(20);
            boolean freecamOff = context.computeOnClient(mc -> !FreecamController.active() && mc.getCameraEntity() == mc.player
                    && !Atlas.get().modules.get(FreecamModule.class).isEnabled());
            boolean noJob = context.computeOnClient(mc -> AutoBuilderModule.get().job() == null && !AutoMineModule.get().busy());
            record("world switch cleanup", freecamOff && noJob, "freecam released=" + freecamOff + ", builder state cleared=" + noJob);
            context.runOnClient(mc -> Atlas.get().modules.get(XRayModule.class).setEnabled(false));
        }
        // Auto Mine in real terrain: a normal (not flat) world, an area through real caves.
        if (suite.equals("all") || suite.equals("caves")) {
            String lv = System.getenv().getOrDefault("ATLAS_LEVEL", "1");
            for (String one : lv.split(",")) {
                CaveLevel level = CaveLevel.of(Integer.parseInt(one.trim()));
                try (TestSingleplayerContext real = context.worldBuilder()
                        .adjustSettings(c -> {
                            c.setWorldType((net.minecraft.client.gui.screen.world.WorldCreator.WorldType) c.getNormalWorldTypes().get(0));
                            c.setSeed(level.seed());
                            c.setGenerateStructures(true);
                        })
                        .create()) {
                    real.getClientWorld().waitForChunksRender();
                    blocking = true;
                    safe("Auto Mine in real caves, level " + level.number(), () -> autoMineRealCaves(context, real.getServer(), level));
                }
            }
        }
        writeReport();
        if (failures > 0) {
            throw new AssertionError(failures + " scenario(s) failed:\n" + String.join("\n", report));
        }
    }

    // ------------------------------------------------------------------ multiplayer (dedicated server)

    private void multiplayer(ClientGameTestContext ctx, TestDedicatedServerContext server) {
        boolean mp = ctx.computeOnClient(mc -> !Env.singleplayer() && mc.getServer() == null && mc.player != null);
        record("joined a real dedicated server (multiplayer)", mp, "singleplayer=" + ctx.computeOnClient(mc -> Env.singleplayer())
                + ", address=" + ctx.computeOnClient(mc -> Env.serverAddress()));
        server.runCommand("gamemode creative @a");
        server.runCommand("time set day");
        server.runCommand("gamerule doDaylightCycle false");
        server.runCommand("gamerule doMobSpawning false");
        ctx.waitTicks(20);
        BlockPos spawn = ctx.computeOnClient(mc -> mc.player.getBlockPos());
        boolean all = suite.equals("all");
        if (all || suite.equals("finder")) {
            safe("ClickGUI opens with Right Shift", () -> gui(ctx));
            safe("placement tool (birch plank)", () -> placementTool(ctx, server, spawn.add(0, 0, -48)));
        }
        if (all || suite.equals("mine")) minerTests(ctx, server, spawn);
        if (all || suite.equals("finder")) finderTests(ctx, server, spawn);
    }

    private void minerTests(ClientGameTestContext ctx, TestServerContext server, BlockPos spawn) {
        safe("Auto Mine tunnel (survival, server)", () -> autoMine(ctx, server, spawn.add(24, 0, -24)));
        safe("Auto Mine 3x3 (server)", () -> autoMine3x3(ctx, server, spawn.add(24, 0, 0)));
        safe("Auto Mine stops before lava (server)", () -> autoMineLava(ctx, server, spawn.add(24, 0, 24)));
        safe("Auto Mine shovel on gravel (server)", () -> autoMineShovel(ctx, server, spawn.add(-48, 10, 48)));
        safe("Auto Mine pause / resume (server)", () -> autoMinePause(ctx, server, spawn.add(-48, 10, -48)));
        safe("Auto Mine eats when hungry (server)", () -> autoMineEats(ctx, server, spawn.add(-48, 10, -24)));
        safe("Auto Mine bridges a floor gap (server)", () -> autoMineGap(ctx, server, spawn.add(-48, 10, 0)));
        safe("Auto Mine climbs out after a fall (server)", () -> autoMineFall(ctx, server, spawn.add(-48, 10, 24)));
        safe("Auto Mine area 20x20x20 (server)", () -> autoMineArea(ctx, server, spawn.add(96, 24, 0)));
    }

    private void finderTests(ClientGameTestContext ctx, TestServerContext server, BlockPos spawn) {
        safe("Base Finder 70 blocks deep (server)", () -> baseFinderDeep(ctx, server, spawn));
        safe("Base Finder underground (server)", () -> baseFinder(ctx, server, spawn));
        safe("Base Finder overgrowth (server)", () -> baseFinderGrowth(ctx, server, spawn));
        safe("Base Finder random blocks (server)", () -> baseFinderSpam(ctx, server, spawn));
        safe("Base Finder two-chunk base (server)", () -> baseFinderMultiChunk(ctx, server, spawn));
        safe("Base Finder village (server)", () -> baseFinderVillage(ctx, server, spawn));
        safe("X-Ray on a server", () -> xray(ctx, server, spawn.add(-24, 0, 0)));
        safe("ESP on a server", () -> esp(ctx, server, spawn.add(-24, 0, 24)));
        safe("Freecam on a server", () -> freecam(ctx, server));
        AntiXraySim.register();
        safe("X-Ray vs anti-xray (hidden ores)", () -> xrayAntiXray(ctx, server, spawn.add(-64, 0, -96), AntiXraySim.Mode.HIDE));
        safe("X-Ray vs anti-xray (fake ores)", () -> xrayAntiXray(ctx, server, spawn.add(-64, 0, 96), AntiXraySim.Mode.FAKE));
        safe("Debris Finder vs anti-xray (fake debris, Nether)", () -> debrisAntiXray(ctx, server));
        safe("Base Finder vs anti-xray (fake ores)", () -> baseFinderAntiXray(ctx, server, spawn.add(160, 0, -96)));
    }

    private void gui(ClientGameTestContext ctx) {
        ctx.getInput().pressKey(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT);
        int waited = 0;
        boolean open = false;
        while (waited < 40 && !open) {
            ctx.waitTick();
            waited++;
            open = ctx.computeOnClient(mc -> mc.currentScreen instanceof ClickGuiScreen);
        }
        if (open) ctx.takeScreenshot("atlas-gui");
        // Schematic search: type part of a name, Enter picks the first match.
        boolean searched = false;
        String picked = "";
        if (open) {
            ctx.runOnClient(mc -> ((ClickGuiScreen) mc.currentScreen).openChoice(
                    (dev.atlasclient.core.setting.Settings.ChoiceSetting) AutoBuilderModule.get().setting("Schematic")));
            ctx.waitTicks(2);
            ctx.getInput().typeChars("redst");
            ctx.waitTicks(3);
            ctx.takeScreenshot("atlas-schematic-search");
            ctx.getInput().pressKey(257); // Enter
            ctx.waitTicks(2);
            picked = ctx.computeOnClient(mc -> String.valueOf(AutoBuilderModule.get().setting("Schematic").get()));
            searched = picked.equals("redstone_mechanism.litematic");
        }
        record("schematic search: type 'redst', Enter picks redstone_mechanism.litematic", searched, "picked '" + picked + "'");
        ctx.runOnClient(mc -> mc.setScreen(null));
        ctx.waitTicks(2);
        record("ClickGUI opens with Right Shift", open, open ? "opened after " + waited + " ticks (screenshot atlas-gui)" : "no screen after 40 ticks");
        // Hotkeys: X toggles X-Ray, H toggles all ESP, with a quick tap (down and up within one tick).
        ctx.getInput().pressKey(XRayModule.DEFAULT_KEY);
        ctx.waitTicks(2);
        boolean xOn = ctx.computeOnClient(mc -> Atlas.get().modules.get(XRayModule.class).isEnabled());
        ctx.getInput().pressKey(XRayModule.DEFAULT_KEY);
        ctx.waitTicks(2);
        boolean xOff = !ctx.computeOnClient(mc -> Atlas.get().modules.get(XRayModule.class).isEnabled());
        ctx.getInput().pressKey(72);
        ctx.waitTicks(2);
        boolean espOn = ctx.computeOnClient(mc -> dev.atlasclient.module.client.GuiSettingsModule.anyEspOn());
        ctx.getInput().pressKey(72);
        ctx.waitTicks(2);
        boolean espOff = !ctx.computeOnClient(mc -> dev.atlasclient.module.client.GuiSettingsModule.anyEspOn());
        record("hotkeys X (X-Ray) and H (ESP) toggle on a quick tap", xOn && xOff && espOn && espOff,
                "X on=" + xOn + " off=" + xOff + ", H on=" + espOn + " off=" + espOff);
    }

    /**
     * The birch-plank placement tool, with real mouse input: right-click a block moves the
     * schematic there (and places no plank), left-click rotates (and breaks nothing, even in
     * creative), Ctrl + scroll nudges it (and keeps the hotbar slot), sneak + right-click builds.
     */
    private void placementTool(ClientGameTestContext ctx, TestServerContext server, BlockPos start) {
        String name = "placement tool (birch plank): right-click moves, left-click rotates, Ctrl+scroll nudges, sneak+right-click builds";
        int sx = start.getX(), y = start.getY(), sz = start.getZ();
        server.runCommand("gamemode creative @a");
        server.runCommand("clear @a");
        server.runCommand("give @a minecraft:birch_planks 64");
        server.runCommand("tp @a " + (sx + 0.5) + " " + y + " " + (sz + 0.5) + " -90 50"); // facing east, looking at the ground ahead
        ctx.waitTicks(20);
        ctx.getInput().pressKey(49); // hotbar slot 1
        ctx.waitTicks(2);
        ctx.runOnClient(mc -> {
            AutoBuilderModule b = AutoBuilderModule.get();
            b.cancel();
            b.session.unload();
            ((dev.atlasclient.core.setting.Settings.ChoiceSetting) b.setting("Schematic")).set("stone_platform.schem");
        });
        ctx.waitTicks(2);
        BlockPos aimed = ctx.computeOnClient(mc -> mc.crosshairTarget instanceof net.minecraft.util.hit.BlockHitResult bh ? bh.getBlockPos() : null);
        BlockPos expected = ctx.computeOnClient(mc -> mc.crosshairTarget instanceof net.minecraft.util.hit.BlockHitResult bh
                ? bh.getBlockPos().offset(bh.getSide()) : null);
        if (aimed == null || expected == null) {
            record(name, false, "not looking at a block");
            return;
        }
        ctx.getInput().pressMouse(1); // right-click
        ctx.waitTicks(4);
        int[] origin = ctx.computeOnClient(mc -> {
            var p = AutoBuilderModule.get().session.placement();
            return p == null ? new int[]{Integer.MIN_VALUE, 0, 0} : new int[]{p.originX(), p.originY(), p.originZ()};
        });
        boolean moved = origin[0] == expected.getX() && origin[1] == expected.getY() && origin[2] == expected.getZ();
        boolean noPlank = server.computeOnServer(s -> s.getOverworld().getBlockState(expected).isAir());
        ctx.getInput().pressMouse(0); // left-click
        ctx.waitTicks(4);
        boolean rotated = ctx.computeOnClient(mc -> AutoBuilderModule.get().session.placement().rotation() == Rotation.CLOCKWISE_90);
        boolean notBroken = server.computeOnServer(s -> !s.getOverworld().getBlockState(aimed).isAir());
        int slotBefore = ctx.computeOnClient(mc -> mc.player.getInventory().getSelectedSlot());
        ctx.getInput().holdControl();
        ctx.getInput().scroll(1);
        ctx.waitTicks(2);
        ctx.getInput().releaseControl();
        int[] after = ctx.computeOnClient(mc -> {
            var p = AutoBuilderModule.get().session.placement();
            return new int[]{p.originX(), p.originY(), p.originZ(), mc.player.getInventory().getSelectedSlot()};
        });
        boolean nudged = after[0] == expected.getX() + 1 && after[1] == expected.getY() && after[2] == expected.getZ();
        boolean slotKept = after[3] == slotBefore;
        ctx.takeScreenshot("atlas-placement-tool");
        ctx.getInput().holdShift();
        ctx.waitTicks(3);
        ctx.getInput().pressMouse(1); // sneak + right-click: build
        ctx.waitTicks(4);
        ctx.getInput().releaseShift();
        boolean building = ctx.computeOnClient(mc -> AutoBuilderModule.get().job() != null);
        ctx.runOnClient(mc -> {
            AutoBuilderModule.get().cancel();
            AutoBuilderModule.get().session.unload();
            AutoBuilderModule.get().setEnabled(false);
        });
        server.runCommand("clear @a");
        record(name, moved && noPlank && rotated && notBroken && nudged && slotKept && building, String.format(Locale.ROOT,
                "moved to clicked spot=%s (origin %d %d %d, expected %s), no plank placed=%s, rotated=%s, aimed block not broken=%s, "
                        + "Ctrl+scroll nudged=%s, hotbar slot kept=%s, sneak+right-click started build=%s",
                moved, origin[0], origin[1], origin[2], expected.toShortString(), noPlank, rotated, notBroken, nudged, slotKept, building));
    }

    /** Solid stone mass in front of the player, an ore vein in the right wall and one in the ceiling. */
    private void autoMine(ClientGameTestContext ctx, TestServerContext server, BlockPos start) {
        String name = "Auto Mine digs a 16-block tunnel, mines ores touching it, does not chase the vein";
        int sx = start.getX(), y = start.getY(), sz = start.getZ();
        server.runCommand(fill(new BlockPos(sx - 1, y - 1, sz - 3), new BlockPos(sx + 22, y + 4, sz + 3), "minecraft:stone"));
        server.runCommand(fill(new BlockPos(sx, y, sz), new BlockPos(sx, y + 1, sz), "minecraft:air"));
        BlockPos diamond = new BlockPos(sx + 5, y + 1, sz + 1), iron = new BlockPos(sx + 5, y + 1, sz + 2), coal = new BlockPos(sx + 9, y + 2, sz);
        server.runCommand(setblock(diamond, "minecraft:diamond_ore"));
        server.runCommand(setblock(iron, "minecraft:iron_ore"));
        server.runCommand(setblock(coal, "minecraft:coal_ore"));
        server.runCommand("gamemode survival @a");
        server.runCommand("clear @a");
        server.runCommand("give @a minecraft:diamond_pickaxe");
        server.runCommand("tp @a " + (sx + 0.5) + " " + y + " " + (sz + 0.5) + " -90 0"); // facing east (+X)
        ctx.waitTicks(30);
        ctx.runOnClient(mc -> {
            AutoMineModule m = AutoMineModule.get();
            m.mode.set(AutoMineModule.Mode.TUNNEL);
            m.size.set(AutoMineModule.Size.SMALL_1X2);
            m.length.set(16.0);
            m.pickaxe.set(AreaMining.PickaxeMode.REGULAR);
            m.ores.set(AutoMineModule.Ores.TOUCHING_TUNNEL);
            m.stopHealth.set(0.0);
            m.setEnabled(true);
        });
        int t = 0;
        while (t < 3000 && ctx.computeOnClient(mc -> AutoMineModule.get().isEnabled())) {
            ctx.waitTicks(10);
            t += 10;
        }
        boolean stopped = !ctx.computeOnClient(mc -> AutoMineModule.get().isEnabled());
        ctx.takeScreenshot("atlas-automine");
        String last = ctx.computeOnClient(mc -> lastNotification());
        ctx.runOnClient(mc -> AutoMineModule.get().setEnabled(false));
        int[] r = server.computeOnServer(s -> {
            ServerWorld w = s.getOverworld();
            int open = 0, floor = 0;
            for (int k = 1; k <= 16; k++) {
                if (w.getBlockState(new BlockPos(sx + k, y, sz)).isAir() && w.getBlockState(new BlockPos(sx + k, y + 1, sz)).isAir()) open++;
                if (!w.getBlockState(new BlockPos(sx + k, y - 1, sz)).isAir()) floor++;
            }
            int ores = (w.getBlockState(diamond).isAir() ? 1 : 0) + (w.getBlockState(coal).isAir() ? 1 : 0);
            boolean beyond = !w.getBlockState(new BlockPos(sx + 17, y, sz)).isAir();
            boolean ironLeft = !w.getBlockState(iron).isAir(); // 2 blocks into the wall: not touching the tunnel
            return new int[]{open, floor, ores, beyond ? 1 : 0, ironLeft ? 1 : 0};
        });
        String items = server.computeOnServer(s -> {
            ServerPlayerEntity p = s.getPlayerManager().getPlayerList().get(0);
            int d = 0, ir = 0, c = 0;
            for (int i = 0; i < 36; i++) {
                ItemStack st = p.getInventory().getStack(i);
                String id = net.minecraft.registry.Registries.ITEM.getId(st.getItem()).getPath();
                if (id.equals("diamond")) d += st.getCount();
                if (id.equals("raw_iron")) ir += st.getCount();
                if (id.equals("coal")) c += st.getCount();
            }
            return "picked up diamond=" + d + " raw_iron=" + ir + " coal=" + c;
        });
        record(name, stopped && r[0] == 16 && r[1] == 16 && r[2] == 2 && r[3] == 1 && r[4] == 1, String.format(Locale.ROOT,
                "finished=%s after %d ticks, tunnel open %d/16, floor intact %d/16, touching ores mined %d/2, deeper vein ore left=%s, stopped at length=%s; %s; last message: %s",
                stopped, t, r[0], r[1], r[2], r[4] == 1, r[3] == 1, items, last));
        server.runCommand("gamemode creative @a");
        server.runCommand("kill @e[type=item]");
    }

    private void autoMine3x3(ClientGameTestContext ctx, TestServerContext server, BlockPos start) {
        String name = "Auto Mine digs a 3x3 tunnel";
        int sx = start.getX(), y = start.getY(), sz = start.getZ();
        server.runCommand(fill(new BlockPos(sx - 1, y - 1, sz - 3), new BlockPos(sx + 10, y + 4, sz + 3), "minecraft:stone"));
        server.runCommand(fill(new BlockPos(sx, y, sz), new BlockPos(sx, y + 1, sz), "minecraft:air"));
        server.runCommand("gamemode survival @a");
        server.runCommand("clear @a");
        server.runCommand("give @a minecraft:diamond_pickaxe");
        server.runCommand("tp @a " + (sx + 0.5) + " " + y + " " + (sz + 0.5) + " -90 0");
        ctx.waitTicks(30);
        ctx.runOnClient(mc -> {
            AutoMineModule m = AutoMineModule.get();
            m.size.set(AutoMineModule.Size.BIG_3X3);
            m.length.set(6.0);
            m.stopHealth.set(0.0);
            m.setEnabled(true);
        });
        int t = 0;
        while (t < 2400 && ctx.computeOnClient(mc -> AutoMineModule.get().isEnabled())) {
            ctx.waitTicks(10);
            t += 10;
        }
        boolean stopped = !ctx.computeOnClient(mc -> AutoMineModule.get().isEnabled());
        String last = ctx.computeOnClient(mc -> lastNotification());
        ctx.runOnClient(mc -> {
            AutoMineModule.get().setEnabled(false);
            AutoMineModule.get().size.set(AutoMineModule.Size.SMALL_1X2);
        });
        int open = server.computeOnServer(s -> {
            ServerWorld w = s.getOverworld();
            int n = 0;
            for (int k = 1; k <= 6; k++) for (int l = -1; l <= 1; l++) for (int h = 0; h < 3; h++) {
                if (w.getBlockState(new BlockPos(sx + k, y + h, sz + l)).isAir()) n++;
            }
            return n;
        });
        record(name, stopped && open == 54, String.format(Locale.ROOT, "finished=%s after %d ticks, open %d/54; message: %s", stopped, t, open, last));
        server.runCommand("gamemode creative @a");
        server.runCommand("kill @e[type=item]");
    }

    /** Area mode: a 5 wide, 6 long, 4 deep box dug out of solid stone, floor below left intact. */
    /**
     * A 20 x 20 x 20 area in a real survival setup (Efficiency V netherite pickaxe + Haste II, so it
     * finishes in a few minutes), with a cave inside the box, a cave under the floor, a lava pocket
     * and gravel in the roof. It must come out clean: every block gone except the ones touching the
     * lava and the floor over the cave, walls and floor untouched, no lava let out, no deaths.
     */
    private void autoMineArea(ClientGameTestContext ctx, TestServerContext server, BlockPos start) {
        String name = "Auto Mine area 20 x 20 x 20: clean, level, walls straight, keeps out of caves and lava";
        int sx = start.getX(), y = start.getY(), sz = start.getZ();
        final int W = 20, L = 20, D = 20, from = -9, to = 10, top = 1, bottom = top - D + 1; // h = y offset
        server.runCommand("gamemode creative @a");
        server.runCommand("tp @a " + (sx + 10.5) + " " + (y + 8) + " " + (sz + 0.5));
        ctx.waitTicks(40);
        server.runCommand(fill(new BlockPos(sx - 1, y + bottom - 6, sz + from - 2), new BlockPos(sx + L + 2, y + top + 3, sz + to + 2), "minecraft:stone"));
        server.runCommand(fill(new BlockPos(sx, y, sz), new BlockPos(sx, y + 1, sz), "minecraft:air"));
        server.runCommand(fill(new BlockPos(sx + 10, y + top + 1, sz - 1), new BlockPos(sx + 12, y + top + 1, sz + 1), "minecraft:gravel"));
        server.runCommand(fill(new BlockPos(sx + 14, y - 8, sz + 7), new BlockPos(sx + 15, y - 7, sz + 8), "minecraft:lava"));
        server.runCommand(fill(new BlockPos(sx + 6, y - 14, sz - 4), new BlockPos(sx + 10, y - 11, sz), "minecraft:air"));
        server.runCommand(fill(new BlockPos(sx + 3, y + bottom - 3, sz + 3), new BlockPos(sx + 6, y + bottom - 1, sz + 6), "minecraft:air"));
        server.runCommand("tp @a " + (sx + 0.5) + " " + y + " " + (sz + 0.5) + " -90 0");
        ctx.waitTicks(10);
        server.runOnServer(s -> s.getPlayerManager().getPlayerList().get(0).onLanding());
        ctx.runOnClient(mc -> {
            mc.player.onLanding();
            mc.player.getInventory().setSelectedSlot(0);
        });
        server.runCommand("gamemode survival @a");
        server.runCommand("clear @a");
        server.runCommand("give @a minecraft:netherite_pickaxe[minecraft:enchantments={\"minecraft:efficiency\":5,\"minecraft:unbreaking\":3}]");
        server.runCommand("give @a minecraft:cooked_beef 16");
        server.runCommand("give @a minecraft:cobblestone 64");
        server.runCommand("effect give @a minecraft:haste infinite 1 true");
        server.runCommand("effect clear @a minecraft:mining_fatigue");
        ctx.waitTicks(10);
        String pick = server.computeOnServer(s -> {
            ItemStack st = s.getPlayerManager().getPlayerList().get(0).getInventory().getStack(0);
            return net.minecraft.registry.Registries.ITEM.getId(st.getItem()).getPath() + (st.hasEnchantments() ? " enchanted" : " NOT enchanted");
        });
        if (pick.contains("NOT")) {
            server.runCommand("enchant @a minecraft:efficiency 5");
            server.runCommand("enchant @a minecraft:unbreaking 3");
        }
        server.runCommand("tp @a " + (sx + 0.5) + " " + y + " " + (sz + 0.5) + " -90 0");
        ctx.waitTicks(30);
        ctx.runOnClient(mc -> {
            AutoMineModule m = AutoMineModule.get();
            m.mode.set(AutoMineModule.Mode.AREA);
            m.areaWidth.set((double) W);
            m.areaLength.set((double) L);
            m.areaDepth.set((double) D);
            m.pickaxe.set(dev.atlasclient.core.build.AreaMining.PickaxeMode.REGULAR);
            m.stopHealth.set(0.0);
            m.setEnabled(true);
        });
        int t = 0, lowest = Integer.MAX_VALUE, deaths = 0;
        float minHealth = 20f;
        boolean wasDead = false;
        while (t < 30000 && ctx.computeOnClient(mc -> AutoMineModule.get().isEnabled())) {
            ctx.waitTicks(5);
            t += 5;
            if (t % 200 == 0) server.runCommand("kill @e[type=item]");
            if (t % 2000 == 0) {
                String st = ctx.computeOnClient(mc -> AutoMineModule.get().debugStatus());
                System.out.println("[Atlas gametest] ...   area after " + t + " ticks: " + st);
                if (st.contains("No path") || st.contains("Repositioning")) System.out.println("[Atlas gametest] ...   " + ctx.computeOnClient(AtlasClientGameTest::around));
            }
            boolean dead = ctx.computeOnClient(mc -> mc.player.isDead());
            if (dead && !wasDead) deaths++;
            wasDead = dead;
            if (!dead) {
                lowest = Math.min(lowest, ctx.computeOnClient(mc -> mc.player.getBlockPos().getY()));
                minHealth = Math.min(minHealth, ctx.computeOnClient(mc -> mc.player.getHealth()));
            }
        }
        boolean stopped = !ctx.computeOnClient(mc -> AutoMineModule.get().isEnabled());
        ctx.takeScreenshot("atlas-automine-area");
        String last = ctx.computeOnClient(mc -> lastNotification());
        String status = ctx.computeOnClient(mc -> AutoMineModule.get().status());
        ctx.runOnClient(mc -> {
            AutoMineModule.get().setEnabled(false);
            AutoMineModule.get().mode.set(AutoMineModule.Mode.TUNNEL);
        });
        StringBuilder left = new StringBuilder();
        int[] r = server.computeOnServer(s -> {
            ServerWorld wd = s.getOverworld();
            java.util.Set<BlockPos> lava = new java.util.HashSet<>();
            for (int x = sx + 14; x <= sx + 15; x++) for (int yy = y - 8; yy <= y - 7; yy++) for (int z = sz + 7; z <= sz + 8; z++) lava.add(new BlockPos(x, yy, z));
            int unexpected = 0, open = 0, lavaInPlace = 0, lavaOutside = 0, floor = 0, floorWant = 0, wallsMined = 0, keptNearLava = 0, keptOverCave = 0;
            for (int k = 0; k <= L + 1; k++) for (int l = from - 1; l <= to + 1; l++) for (int h = bottom - 1; h <= top; h++) {
                BlockPos c = new BlockPos(sx + k, y + h, sz + l);
                net.minecraft.block.BlockState st = wd.getBlockState(c);
                boolean isLava = st.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.LAVA);
                if (lava.contains(c)) { if (isLava) lavaInPlace++; continue; }
                if (isLava) lavaOutside++;
                boolean inBox = k >= 1 && k <= L && l >= from && l <= to && h >= bottom;
                boolean overCave = k >= 3 && k <= 6 && l >= 3 && l <= 6;
                if (inBox) {
                    if (st.isAir()) { open++; continue; }
                    boolean nearLava = false;
                    for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.values()) if (lava.contains(c.offset(d))) nearLava = true;
                    if (nearLava) { keptNearLava++; continue; }
                    if (h == bottom && overCave) { keptOverCave++; continue; }
                    unexpected++;
                    if (left.length() < 400) left.append(" ").append(k).append("/").append(l).append("/").append(h).append("=")
                            .append(net.minecraft.registry.Registries.BLOCK.getId(st.getBlock()).getPath());
                } else if (h == bottom - 1 && k >= 1 && k <= L && l >= from && l <= to) {
                    if (overCave) continue;
                    floorWant++;
                    if (!st.isAir()) floor++;
                } else if (h >= bottom) {
                    boolean startCell = k == 0 && l == 0 && (h == 0 || h == 1);
                    if (!startCell && st.isAir()) wallsMined++;
                }
            }
            return new int[]{unexpected, open, lavaInPlace, lavaOutside, floor, floorWant, wallsMined, keptNearLava, keptOverCave};
        });
        boolean ok = stopped && r[0] == 0 && r[2] == 8 && r[3] == 0 && r[4] == r[5] && r[6] == 0 && deaths == 0 && lowest >= y + bottom;
        record(name, ok, String.format(Locale.ROOT,
                "finished=%s after %d ticks (%.1f min), left that should be gone %d, open %d, kept next to lava %d, kept as floor over the cave %d, "
                        + "lava in place %d/8 and outside %d, floor intact %d/%d, wall blocks mined %d, deaths %d, lowest health %.1f, lowest feet %d (box bottom %d); "
                        + "pickaxe: %s; status: %s; left (forward/side/height):%s; message: %s",
                stopped, t, t / 1200.0, r[0], r[1], r[7], r[8], r[2], r[3], r[4], r[5], r[6], deaths, minHealth, lowest, y + bottom, pick, status, left, last));
        server.runCommand("effect clear @a");
        server.runCommand("gamemode creative @a");
        server.runCommand("kill @e[type=item]");
    }

    /**
     * Auto Mine in real world generation: looks near spawn for the underground 20x20x20 area that
     * crosses the most cave space (not open to the sky, little water/lava), then clears it in survival.
     * Nothing is built: only the 2 blocks you stand in are opened. Checks: finishes, no deaths, no
     * lava or water let out, nothing mined outside the area, and everything mined except blocks
     * touching lava/water and the floor over caves (a few unreachable blocks are tolerated and
     * reported).
     */
    /**
     * One difficulty level of the real-terrain test.
     *
     * @param lavaWanted   pick an area with lava in it (and at least some)
     * @param waterWanted  pick an area with water (aquifers) in it
     * @param tool         "netherite" (Efficiency V, Unbreaking III, Haste II) or "diamond" (plain, no Haste)
     */
    record CaveLevel(int number, String title, String seed, int size, int yMin, int yMax, boolean lavaWanted, boolean waterWanted,
                     String tool, int maxTicks) {
        static CaveLevel of(int n) {
            return switch (n) {
                case 2 -> new CaveLevel(2, "15x15x15 deep in deepslate with lava", "5729301847", 15, -46, -22, true, false, "netherite", 45000);
                case 3 -> new CaveLevel(3, "15x15x15 through water-filled caves", "991827364501", 15, 10, 44, false, true, "netherite", 30000);
                case 4 -> new CaveLevel(4, "12x12x12 with a plain diamond pickaxe, no Haste", "8645109230411", 12, 12, 44, false, false, "diamond", 40000);
                case 5 -> new CaveLevel(5, "20x20x20 in deepslate with lava", "31337420", 20, -42, -18, true, false, "netherite", 70000);
                default -> new CaveLevel(1, "20x20x20 through stone caves", "8645109230411", 20, 12, 48, false, false, "netherite", 40000);
            };
        }
    }

    /** The blocks around the player (feet level -1..+2, 5x5), for logs: # solid, . air, ~ water, L lava. */
    static String around(net.minecraft.client.MinecraftClient mc) {
        BlockPos f = mc.player.getBlockPos();
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "player %.2f %.2f %.2f onGround=%s yaw %.0f; around (rows z-2..z+2, x-2..x+2) by height:",
                mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.isOnGround(), mc.player.getYaw()));
        for (int dy = -1; dy <= 2; dy++) {
            b.append(" y").append(dy >= 0 ? "+" : "").append(dy).append(":");
            for (int dz = -2; dz <= 2; dz++) {
                b.append(dz == -2 ? "" : "/");
                for (int dx = -2; dx <= 2; dx++) {
                    net.minecraft.block.BlockState s = mc.world.getBlockState(f.add(dx, dy, dz));
                    char c = s.isAir() ? '.' : s.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.LAVA) ? 'L'
                            : !s.getFluidState().isEmpty() ? '~' : s.isReplaceable() ? ',' : '#';
                    if (dx == 0 && dz == 0 && (dy == 0 || dy == 1)) c = c == '.' ? 'P' : '!';
                    b.append(c);
                }
            }
        }
        return b.toString();
    }

    private void autoMineRealCaves(ClientGameTestContext ctx, TestServerContext server, CaveLevel level) {
        String name = "Auto Mine real caves, level " + level.number() + ": " + level.title();
        // The world spawn (not where the player happened to appear: that is randomised a little), and
        // every chunk of the search area loaded, so each run picks the same spot.
        BlockPos spawn = server.computeOnServer(s -> s.getOverworld().getSpawnPoint().getPos());
        server.runCommand("gamemode creative @a");
        server.runCommand("difficulty easy");
        server.runCommand("time set day");
        final int W = level.size(), L = level.size(), D = level.size(), from = -(W - 1) / 2, to = from + W - 1, top = 1, bottom = top - D + 1;
        int fx1 = spawn.getX() - 48, fx2 = spawn.getX() + 24 + L + 1, fz1 = spawn.getZ() - 40 + from, fz2 = spawn.getZ() + 40 + to;
        server.runCommand("forceload add " + fx1 + " " + fz1 + " " + fx2 + " " + fz2);
        for (int wait = 0; wait < 1200; wait += 20) {
            boolean all = server.computeOnServer(s -> {
                ServerWorld w = s.getOverworld();
                for (int cx = fx1 >> 4; cx <= fx2 >> 4; cx++) for (int cz = fz1 >> 4; cz <= fz2 >> 4; cz++) if (!w.isChunkLoaded(cx, cz)) return false;
                return true;
            });
            if (all) break;
            ctx.waitTicks(20);
        }
        int[] best = server.computeOnServer(s -> {
            ServerWorld w = s.getOverworld();
            int[] b = null;
            double bestAir = 0;
            for (int ox = spawn.getX() - 48; ox <= spawn.getX() + 24; ox += 6) {
                for (int oz = spawn.getZ() - 40; oz <= spawn.getZ() + 40; oz += 6) {
                    if (!w.isChunkLoaded(ox >> 4, (oz + from) >> 4) || !w.isChunkLoaded((ox + L + 1) >> 4, (oz + to) >> 4)
                            || !w.isChunkLoaded((ox + L + 1) >> 4, (oz + from) >> 4) || !w.isChunkLoaded(ox >> 4, (oz + to) >> 4)) continue;
                    int surface = Integer.MAX_VALUE;
                    for (int[] c : new int[][]{{0, from}, {L, from}, {0, to}, {L, to}, {L / 2, 0}}) {
                        surface = Math.min(surface, w.getTopY(net.minecraft.world.Heightmap.Type.WORLD_SURFACE, ox + c[0], oz + c[1]));
                    }
                    for (int oy = level.yMin(); oy <= level.yMax() && oy + top <= surface - 10; oy += 3) {
                        if (oy + bottom - 1 <= -63) continue;
                        // Where you stand to start must not touch water or lava (you would drown or burn there).
                        boolean wetStart = false;
                        for (int sh = -1; sh <= 2 && !wetStart; sh++) {
                            BlockPos sp = new BlockPos(ox, oy + sh, oz);
                            if (!w.getFluidState(sp).isEmpty()) wetStart = true;
                            for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.values()) {
                                if (!w.getFluidState(sp.offset(d)).isEmpty()) wetStart = true;
                            }
                        }
                        if (wetStart) continue;
                        int air = 0, fluid = 0, lava = 0, water = 0, n = 0;
                        BlockPos.Mutable m = new BlockPos.Mutable();
                        for (int k = 1; k <= L; k += 2) for (int l = from; l <= to; l += 2) for (int h = bottom; h <= top; h += 2) {
                            net.minecraft.block.BlockState st = w.getBlockState(m.set(ox + k, oy + h, oz + l));
                            n++;
                            if (st.isAir()) air++;
                            else if (!st.getFluidState().isEmpty()) {
                                fluid++;
                                if (st.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.LAVA)) lava++; else water++;
                            }
                        }
                        double a = air / (double) n, fl = fluid / (double) n;
                        if (level.lavaWanted() && lava == 0) continue;
                        if (level.waterWanted() && water < 2) continue;
                        double maxFluid = level.lavaWanted() || level.waterWanted() ? 0.12 : 0.03;
                        // Most cave air wins; with lava/water wanted, more of it is a tie-breaker.
                        double score = a + (level.lavaWanted() ? lava * 0.01 : 0) + (level.waterWanted() ? water * 0.005 : 0);
                        if (a >= 0.06 && a <= 0.55 && fl <= maxFluid && score > bestAir) {
                            bestAir = score;
                            b = new int[]{ox, oy, oz, (int) Math.round(a * 100), fluid, lava, water};
                        }
                    }
                }
            }
            return b;
        });
        if (best == null) {
            record(name, false, "no suitable underground cave area found near spawn (seed " + level.seed() + "); the test needs another seed");
            return;
        }
        int sx = best[0], y = best[1], sz = best[2];
        System.out.println("[Atlas gametest] ...   real caves L" + level.number() + ": area at " + sx + " " + y + " " + sz + ", about " + best[3]
                + "% cave air, samples: " + best[5] + " lava, " + best[6] + " water");
        // Before: where the fluids are, and which blocks around the area are solid (not sand/gravel).
        java.util.Set<Long> fluidBefore = new java.util.HashSet<>();
        java.util.Set<Long> shell = new java.util.HashSet<>();
        int solidBefore = server.computeOnServer(s -> {
            ServerWorld w = s.getOverworld();
            int solid = 0;
            for (int k = 0; k <= L + 1; k++) for (int l = from - 1; l <= to + 1; l++) for (int h = bottom - 1; h <= top + 1; h++) {
                BlockPos c = new BlockPos(sx + k, y + h, sz + l);
                net.minecraft.block.BlockState st = w.getBlockState(c);
                if (!st.getFluidState().isEmpty()) fluidBefore.add(c.asLong());
                boolean inBox = k >= 1 && k <= L && l >= from && l <= to && h >= bottom && h <= top;
                boolean falls = st.getBlock() instanceof net.minecraft.block.FallingBlock;
                if (inBox) {
                    if (!st.isAir() && st.getFluidState().isEmpty()) solid++;
                } else if (!st.isAir() && st.getFluidState().isEmpty() && !falls && !(k == 0 && l == 0 && (h == 0 || h == 1))) {
                    shell.add(c.asLong());
                }
            }
            return solid;
        });
        server.runCommand(fill(new BlockPos(sx, y, sz), new BlockPos(sx, y + 1, sz), "minecraft:air"));
        server.runCommand(setblock(new BlockPos(sx, y - 1, sz), "minecraft:stone"));
        server.runCommand("tp @a " + (sx + 0.5) + " " + y + " " + (sz + 0.5) + " -90 0");
        ctx.waitTicks(10);
        server.runOnServer(s -> s.getPlayerManager().getPlayerList().get(0).onLanding());
        ctx.runOnClient(mc -> {
            mc.player.onLanding();
            mc.player.getInventory().setSelectedSlot(0);
        });
        server.runCommand("gamemode survival @a");
        server.runCommand("clear @a");
        if (level.tool().equals("diamond")) {
            for (int i = 0; i < 3; i++) server.runCommand("give @a minecraft:diamond_pickaxe");
            server.runCommand("give @a minecraft:diamond_shovel");
            server.runCommand("give @a minecraft:cooked_beef 16");
        } else {
            for (int i = 0; i < 2; i++) {
                server.runCommand("give @a minecraft:netherite_pickaxe[minecraft:enchantments={\"minecraft:efficiency\":5,\"minecraft:unbreaking\":3}]");
            }
            server.runCommand("give @a minecraft:netherite_shovel[minecraft:enchantments={\"minecraft:efficiency\":5}]");
            server.runCommand("give @a minecraft:cooked_beef 32");
            server.runCommand("effect give @a minecraft:haste infinite 1 true");
        }
        server.runCommand("give @a minecraft:cobblestone 64");
        server.runCommand("tp @a " + (sx + 0.5) + " " + y + " " + (sz + 0.5) + " -90 0");
        ctx.waitTicks(30);
        ctx.runOnClient(mc -> {
            AutoMineModule m = AutoMineModule.get();
            m.mode.set(AutoMineModule.Mode.AREA);
            m.areaWidth.set((double) W);
            m.areaLength.set((double) L);
            m.areaDepth.set((double) D);
            m.pickaxe.set(dev.atlasclient.core.build.AreaMining.PickaxeMode.REGULAR);
            m.stopHealth.set(0.0);
            m.setEnabled(true);
        });
        int t = 0, lowest = Integer.MAX_VALUE, deaths = 0, lastDangerLog = -1000, lavaTouches = 0, lastFallLog = -1000;
        double lastY = Double.NaN;
        float minHealth = 20f;
        boolean wasDead = false;
        while (t < level.maxTicks() && ctx.computeOnClient(mc -> AutoMineModule.get().isEnabled())) {
            ctx.waitTicks(5);
            t += 5;
            if (t % 200 == 0) server.runCommand("kill @e[type=!player]");
            if (t % 2000 == 0) {
                String st = ctx.computeOnClient(mc -> AutoMineModule.get().debugStatus() + String.format(java.util.Locale.ROOT,
                        " | at %.1f %.1f %.1f, health %.1f", mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getHealth()));
                System.out.println("[Atlas gametest] ...   real caves L" + level.number() + " after " + t + " ticks: " + st);
                if (st.contains("No path") || st.contains("Repositioning")) System.out.println("[Atlas gametest] ...   " + ctx.computeOnClient(AtlasClientGameTest::around));
            }
            double yNow = ctx.computeOnClient(mc -> mc.player.getY());
            if (lastY - yNow >= 2.5 && t - lastFallLog >= 40) {
                lastFallLog = t;
                final double fromY = lastY;
                System.out.println("[Atlas gametest] ...   real caves L" + level.number() + " tick " + t + ": dropped from " + fromY + " - "
                        + ctx.computeOnClient(mc -> AutoMineModule.get().debugStatus() + " | " + around(mc)));
            }
            if (t % 20 == 0) lastY = yNow;
            String danger = ctx.computeOnClient(mc -> mc.player.isDead() ? null
                    : mc.player.isInLava() ? "in lava" : mc.player.isOnFire() ? "on fire"
                    : mc.player.getHealth() < 10 ? "health low" : null);
            if (danger != null && t - lastDangerLog >= 100) {
                lastDangerLog = t;
                if (danger.equals("in lava")) lavaTouches++;
                String where = ctx.computeOnClient(mc -> String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f health %.1f, status %s",
                        mc.player.getX(), mc.player.getY(), mc.player.getZ(), mc.player.getHealth(), AutoMineModule.get().status()));
                System.out.println("[Atlas gametest] ...   real caves L" + level.number() + " tick " + t + ": " + danger + " at " + where);
            }
            boolean dead = ctx.computeOnClient(mc -> mc.player.isDead());
            if (dead && !wasDead) deaths++;
            wasDead = dead;
            if (dead) {
                ctx.runOnClient(mc -> mc.player.requestRespawn());
                ctx.waitTicks(20);
                continue;
            }
            lowest = Math.min(lowest, ctx.computeOnClient(mc -> mc.player.getBlockPos().getY()));
            minHealth = Math.min(minHealth, ctx.computeOnClient(mc -> mc.player.getHealth()));
        }
        boolean stopped = !ctx.computeOnClient(mc -> AutoMineModule.get().isEnabled());
        ctx.takeScreenshot("atlas-automine-real-caves");
        String last = ctx.computeOnClient(mc -> lastNotification());
        String status = ctx.computeOnClient(mc -> AutoMineModule.get().status());
        ctx.runOnClient(mc -> {
            AutoMineModule.get().setEnabled(false);
            AutoMineModule.get().mode.set(AutoMineModule.Mode.TUNNEL);
        });
        StringBuilder left = new StringBuilder();
        int[] r = server.computeOnServer(s -> {
            ServerWorld w = s.getOverworld();
            int unexpected = 0, keptFluid = 0, keptFloor = 0, newLava = 0, newWater = 0, shellMined = 0;
            for (int k = 0; k <= L + 1; k++) for (int l = from - 1; l <= to + 1; l++) for (int h = bottom - 1; h <= top + 1; h++) {
                BlockPos c = new BlockPos(sx + k, y + h, sz + l);
                net.minecraft.block.BlockState st = w.getBlockState(c);
                if (!st.getFluidState().isEmpty() && !fluidBefore.contains(c.asLong())) {
                    if (st.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.LAVA)) newLava++;
                    else newWater++;
                }
                boolean inBox = k >= 1 && k <= L && l >= from && l <= to && h >= bottom && h <= top;
                if (!inBox) {
                    if (shell.contains(c.asLong()) && st.isAir()) shellMined++;
                    continue;
                }
                if (st.isAir() || !st.getFluidState().isEmpty()) continue;
                boolean nearFluid = false;
                for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.values()) {
                    if (!w.getFluidState(c.offset(d)).isEmpty()) nearFluid = true;
                }
                if (nearFluid) { keptFluid++; continue; }
                if (h == bottom && !w.getBlockState(c.down()).isSolidBlock(w, c.down())) { keptFloor++; continue; }
                if (net.minecraft.registry.Registries.BLOCK.getId(st.getBlock()).getPath().equals("bedrock")) continue;
                unexpected++;
                if (left.length() < 300) left.append(" ").append(k).append("/").append(l).append("/").append(h).append("=")
                        .append(net.minecraft.registry.Registries.BLOCK.getId(st.getBlock()).getPath());
            }
            return new int[]{unexpected, keptFluid, keptFloor, newLava, newWater, shellMined};
        });
        int allowed = Math.max(10, solidBefore * 3 / 100);
        boolean ok = stopped && deaths == 0 && r[3] == 0 && r[4] == 0 && r[5] == 0 && r[0] <= allowed;
        record(name, ok, String.format(Locale.ROOT,
                "area at %d %d %d with ~%d%% cave air; finished=%s after %d ticks (%.1f min, %.2f ticks per solid block), %d solid blocks before, left that should be gone %d "
                        + "(allowed %d), kept next to water/lava %d, kept as floor over caves %d, new lava %d, new water %d, blocks mined outside the area %d, "
                        + "deaths %d, times in lava %d, lowest health %.1f, lowest feet %d (area bottom %d); status: %s; left (fwd/side/height):%s; message: %s",
                sx, y, sz, best[3], stopped, t, t / 1200.0, t / (double) Math.max(1, solidBefore), solidBefore, r[0], allowed, r[1], r[2], r[3], r[4], r[5], deaths, lavaTouches, minHealth,
                lowest, y + bottom, status, left, last));
        server.runCommand("effect clear @a");
        server.runCommand("gamemode creative @a");
    }

    /** Gravel in the tunnel: dug with the shovel (faster), not the pickaxe. */
    private void autoMineShovel(ClientGameTestContext ctx, TestServerContext server, BlockPos start) {
        String name = "Auto Mine uses a shovel on gravel";
        int sx = start.getX(), y = start.getY(), sz = start.getZ();
        tunnelSite(ctx, server, sx, y, sz, 5);
        server.runCommand(fill(new BlockPos(sx + 1, y, sz), new BlockPos(sx + 3, y + 1, sz), "minecraft:gravel"));
        server.runCommand("give @a minecraft:iron_shovel");
        ctx.waitTicks(10); // the shovel reaches the client's inventory before Auto Mine looks for tools
        startTunnel(ctx, 5);
        int[] w = waitMine(ctx, 2400);
        String last = ctx.computeOnClient(mc -> lastNotification());
        ctx.runOnClient(mc -> AutoMineModule.get().setEnabled(false));
        int[] r = server.computeOnServer(s -> {
            ServerPlayerEntity p = s.getPlayerManager().getPlayerList().get(0);
            int shovelDamage = -1, pickDamage = -1;
            for (int i = 0; i < 36; i++) {
                ItemStack st = p.getInventory().getStack(i);
                String id = net.minecraft.registry.Registries.ITEM.getId(st.getItem()).getPath();
                if (id.equals("iron_shovel")) shovelDamage = st.getDamage();
                if (id.equals("diamond_pickaxe")) pickDamage = st.getDamage();
            }
            return new int[]{shovelDamage, pickDamage, openCells(s.getOverworld(), sx, y, sz, 5)};
        });
        record(name, r[0] >= 6 && r[2] == 10, String.format(Locale.ROOT,
                "shovel used %d times, pickaxe used %d times, tunnel open %d/10, after %d ticks; message: %s", r[0], r[1], r[2], w[0], last));
        server.runCommand("gamemode creative @a");
        server.runCommand("kill @e[type=item]");
    }

    /** Stone mass for a tunnel at level y along +X from sx, with the player standing at the start. */
    private void tunnelSite(ClientGameTestContext ctx, TestServerContext server, int sx, int y, int sz, int length) {
        server.runCommand("tp @a " + (sx + 0.5) + " " + (y + 6) + " " + (sz + 0.5)); // load the chunks before filling
        ctx.waitTicks(20);
        server.runCommand(fill(new BlockPos(sx - 1, y - 6, sz - 3), new BlockPos(sx + length + 2, y + 4, sz + 3), "minecraft:stone"));
        server.runCommand(fill(new BlockPos(sx, y, sz), new BlockPos(sx, y + 1, sz), "minecraft:air"));
        // Land in the start cell while still in creative: the fill can close around a player who was
        // still falling, and that fall must not count once survival is on.
        server.runCommand("tp @a " + (sx + 0.5) + " " + y + " " + (sz + 0.5) + " -90 0");
        ctx.waitTicks(10);
        server.runOnServer(s -> s.getPlayerManager().getPlayerList().get(0).onLanding());
        ctx.runOnClient(mc -> mc.player.onLanding());
        server.runCommand("gamemode survival @a");
        server.runCommand("clear @a");
        server.runCommand("give @a minecraft:diamond_pickaxe");
        server.runCommand("tp @a " + (sx + 0.5) + " " + y + " " + (sz + 0.5) + " -90 0");
        ctx.waitTicks(30);
    }

    private void startTunnel(ClientGameTestContext ctx, int len) {
        ctx.runOnClient(mc -> {
            AutoMineModule m = AutoMineModule.get();
            m.mode.set(AutoMineModule.Mode.TUNNEL);
            m.size.set(AutoMineModule.Size.SMALL_1X2);
            m.length.set((double) len);
            m.ores.set(AutoMineModule.Ores.TOUCHING_TUNNEL);
            m.stopHealth.set(0.0);
            m.setEnabled(true);
        });
    }

    /** Waits until Auto Mine switches itself off; returns {ticks, lowest feet Y seen}. */
    private int[] waitMine(ClientGameTestContext ctx, int maxTicks) {
        int t = 0;
        int lowest = Integer.MAX_VALUE;
        while (t < maxTicks && ctx.computeOnClient(mc -> AutoMineModule.get().isEnabled())) {
            ctx.waitTicks(5);
            t += 5;
            lowest = Math.min(lowest, ctx.computeOnClient(mc -> mc.player.getBlockPos().getY()));
        }
        return new int[]{t, lowest};
    }

    /** Pause keeps the place: nothing is dug while paused, then it carries on and finishes. */
    private void autoMinePause(ClientGameTestContext ctx, TestServerContext server, BlockPos start) {
        String name = "Auto Mine pause / resume keeps its place";
        int sx = start.getX(), y = start.getY(), sz = start.getZ();
        tunnelSite(ctx, server, sx, y, sz, 8);
        startTunnel(ctx, 8);
        int t = 0;
        while (t < 600 && ctx.computeOnClient(mc -> AutoMineModule.get().slice()) < 3) {
            ctx.waitTicks(5);
            t += 5;
        }
        ctx.runOnClient(mc -> AutoMineModule.get().togglePause());
        ctx.waitTicks(10);
        int[] before = server.computeOnServer(s -> new int[]{openCells(s.getOverworld(), sx, y, sz, 8)});
        ctx.waitTicks(100);
        int[] during = server.computeOnServer(s -> new int[]{openCells(s.getOverworld(), sx, y, sz, 8)});
        boolean stillOn = ctx.computeOnClient(mc -> AutoMineModule.get().isEnabled() && AutoMineModule.get().paused());
        ctx.runOnClient(mc -> AutoMineModule.get().togglePause());
        int[] w = waitMine(ctx, 2400);
        int open = server.computeOnServer(s -> openCells(s.getOverworld(), sx, y, sz, 8));
        String last = ctx.computeOnClient(mc -> lastNotification());
        ctx.runOnClient(mc -> AutoMineModule.get().setEnabled(false));
        record(name, before[0] == during[0] && stillOn && open == 16, String.format(Locale.ROOT,
                "blocks open when paused %d, after 100 paused ticks %d, still on and paused=%s, after resume %d/16 open in %d ticks; message: %s",
                before[0], during[0], stillOn, open, w[0], last));
        server.runCommand("gamemode creative @a");
        server.runCommand("kill @e[type=item]");
    }

    private static int openCells(ServerWorld w, int sx, int y, int sz, int len) {
        int n = 0;
        for (int k = 1; k <= len; k++) for (int h = 0; h < 2; h++) if (w.getBlockState(new BlockPos(sx + k, y + h, sz)).isAir()) n++;
        return n;
    }

    private void autoMineEats(ClientGameTestContext ctx, TestServerContext server, BlockPos start) {
        String name = "Auto Mine eats steak when hungry (and not rotten flesh)";
        int sx = start.getX(), y = start.getY(), sz = start.getZ();
        tunnelSite(ctx, server, sx, y, sz, 6);
        server.runCommand("give @a minecraft:rotten_flesh 8");
        server.runCommand("give @a minecraft:cooked_beef 4");
        server.runOnServer(s -> s.getPlayerManager().getPlayerList().get(0).getHungerManager().setFoodLevel(6));
        ctx.waitTicks(10);
        startTunnel(ctx, 6);
        int[] w = waitMine(ctx, 2400);
        String last = ctx.computeOnClient(mc -> lastNotification());
        ctx.runOnClient(mc -> AutoMineModule.get().setEnabled(false));
        int[] r = server.computeOnServer(s -> {
            ServerPlayerEntity p = s.getPlayerManager().getPlayerList().get(0);
            int beef = 0, flesh = 0;
            for (int i = 0; i < 36; i++) {
                ItemStack st = p.getInventory().getStack(i);
                String id = net.minecraft.registry.Registries.ITEM.getId(st.getItem()).getPath();
                if (id.equals("cooked_beef")) beef += st.getCount();
                if (id.equals("rotten_flesh")) flesh += st.getCount();
            }
            return new int[]{p.getHungerManager().getFoodLevel(), beef, flesh};
        });
        record(name, r[0] > 6 && r[1] < 4 && r[2] == 8, String.format(Locale.ROOT,
                "food level 6 -> %d, steak left %d/4, rotten flesh left %d/8, after %d ticks; message: %s", r[0], r[1], r[2], w[0], last));
        server.runCommand("gamemode creative @a");
        server.runCommand("kill @e[type=item]");
    }

    /** A 3-block cave under the tunnel line: the floor is filled with cobblestone, nobody falls. */
    private void autoMineGap(ClientGameTestContext ctx, TestServerContext server, BlockPos start) {
        String name = "Auto Mine fills a 3-block gap in the floor instead of falling or stopping";
        int sx = start.getX(), y = start.getY(), sz = start.getZ();
        tunnelSite(ctx, server, sx, y, sz, 10);
        server.runCommand(fill(new BlockPos(sx + 4, y - 5, sz), new BlockPos(sx + 6, y - 1, sz), "minecraft:air"));
        server.runCommand("give @a minecraft:cobblestone 8");
        startTunnel(ctx, 10);
        int[] w = waitMine(ctx, 3000);
        ctx.takeScreenshot("atlas-automine-gap");
        String last = ctx.computeOnClient(mc -> lastNotification());
        ctx.runOnClient(mc -> AutoMineModule.get().setEnabled(false));
        int[] r = server.computeOnServer(s -> {
            ServerWorld wd = s.getOverworld();
            int filled = 0, open = 0;
            for (int k = 4; k <= 6; k++) if (!wd.getBlockState(new BlockPos(sx + k, y - 1, sz)).isAir()) filled++;
            for (int k = 1; k <= 10; k++) if (wd.getBlockState(new BlockPos(sx + k, y, sz)).isAir() && wd.getBlockState(new BlockPos(sx + k, y + 1, sz)).isAir()) open++;
            return new int[]{filled, open};
        });
        record(name, r[0] == 3 && r[1] == 10 && w[1] >= y, String.format(Locale.ROOT,
                "gap floor filled %d/3, tunnel open %d/10, lowest feet Y %d (tunnel at %d), after %d ticks; message: %s",
                r[0], r[1], w[1], y, w[0], last));
        server.runCommand("gamemode creative @a");
        server.runCommand("kill @e[type=item]");
    }

    /** Auto Mine starts, then the player drops 4 blocks into a pit: it pillars up, gets back and finishes. */
    private void autoMineFall(ClientGameTestContext ctx, TestServerContext server, BlockPos start) {
        String name = "Auto Mine pillars up after falling into a hole, goes back and finishes";
        int sx = start.getX(), y = start.getY(), sz = start.getZ();
        tunnelSite(ctx, server, sx, y, sz, 6);
        server.runCommand(fill(new BlockPos(sx + 1, y - 4, sz + 2), new BlockPos(sx + 1, y - 1, sz + 2), "minecraft:air"));
        server.runCommand("give @a minecraft:cobblestone 16");
        startTunnel(ctx, 6);
        ctx.waitTicks(5);
        server.runCommand("tp @a " + (sx + 1.5) + " " + (y - 4) + " " + (sz + 2.5)); // "falls" into the pit
        int[] w = waitMine(ctx, 3600);
        ctx.takeScreenshot("atlas-automine-fall");
        String last = ctx.computeOnClient(mc -> lastNotification());
        double feetY = ctx.computeOnClient(mc -> mc.player.getY());
        ctx.runOnClient(mc -> AutoMineModule.get().setEnabled(false));
        int[] r = server.computeOnServer(s -> {
            ServerWorld wd = s.getOverworld();
            int pillar = 0, open = 0;
            for (int h = 1; h <= 4; h++) if (!wd.getBlockState(new BlockPos(sx + 1, y - h, sz + 2)).isAir()) pillar++;
            for (int k = 1; k <= 6; k++) if (wd.getBlockState(new BlockPos(sx + k, y, sz)).isAir() && wd.getBlockState(new BlockPos(sx + k, y + 1, sz)).isAir()) open++;
            int pickDamage = -1;
            ServerPlayerEntity sp = s.getPlayerManager().getPlayerList().get(0);
            for (int i = 0; i < 36; i++) {
                ItemStack st = sp.getInventory().getStack(i);
                if (net.minecraft.registry.Registries.ITEM.getId(st.getItem()).getPath().equals("diamond_pickaxe")) pickDamage = st.getDamage();
            }
            return new int[]{pillar, open, pickDamage};
        });
        String held = ctx.computeOnClient(mc -> net.minecraft.registry.Registries.ITEM.getId(mc.player.getMainHandStack().getItem()).getPath());
        // After pillaring with cobblestone the pickaxe must be back in hand: the tunnel is dug with it.
        record(name, r[0] >= 3 && r[1] == 6 && feetY >= y - 0.01 && r[2] >= 10 && held.equals("diamond_pickaxe"), String.format(Locale.ROOT,
                "pillar blocks %d/4, tunnel open %d/6, feet Y at the end %.1f (tunnel at %d), pickaxe used %d times, held at the end %s, after %d ticks; message: %s",
                r[0], r[1], feetY, y, r[2], held, w[0], last));
        server.runCommand("gamemode creative @a");
        server.runCommand("kill @e[type=item]");
    }

    /** A base under 70 blocks of stone; the player stands on top of the stone, 70 blocks above it. */
    private void baseFinderDeep(ClientGameTestContext ctx, TestServerContext server, BlockPos spawn) {
        String name = "Base Finder finds a base 70 blocks below you";
        int cx = (spawn.getX() >> 4) - 3, cz = (spawn.getZ() >> 4) + 3;
        int y = spawn.getY();
        int ax = cx * 16, az = cz * 16;
        loadSite(ctx, server, ax + 8, y + 80, az + 8);
        server.runCommand(fill(new BlockPos(ax, y, az), new BlockPos(ax + 15, y + 70, az + 15), "minecraft:stone"));
        server.runCommand(fill(new BlockPos(ax + 3, y, az + 3), new BlockPos(ax + 12, y + 3, az + 12), "minecraft:air"));
        for (int i = 0; i < 6; i++) server.runCommand(setblock(new BlockPos(ax + 4 + i, y, az + 12), "minecraft:chest"));
        server.runCommand(setblock(new BlockPos(ax + 4, y, az + 4), "minecraft:furnace"));
        server.runCommand(setblock(new BlockPos(ax + 5, y, az + 4), "minecraft:crafting_table"));
        server.runCommand(setblock(new BlockPos(ax + 6, y, az + 4), "minecraft:enchanting_table"));
        server.runCommand(setblock(new BlockPos(ax + 7, y, az + 4), "minecraft:anvil"));
        server.runCommand(setblock(new BlockPos(ax + 8, y, az + 4), "minecraft:red_bed[part=foot,facing=south]"));
        server.runCommand(setblock(new BlockPos(ax + 8, y, az + 5), "minecraft:red_bed[part=head,facing=south]"));
        server.runCommand("tp @a " + (ax + 8) + " " + (y + 71) + " " + (az + 8));
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> {
            BaseFinderModule bf = Atlas.get().modules.get(BaseFinderModule.class);
            bf.undergroundOnly.set(true);
            bf.setEnabled(true);
        });
        Confidence c = Confidence.NONE;
        int t = 0;
        for (; t < 600; t += 10) {
            ctx.waitTicks(10);
            c = confidenceAt(ctx, cx, cz);
            if (c.atLeast(Confidence.MEDIUM)) break;
        }
        double above = ctx.computeOnClient(mc -> mc.player.getY()) - y;
        ctx.takeScreenshot("atlas-basefinder-deep");
        ctx.runOnClient(mc -> Atlas.get().modules.get(BaseFinderModule.class).setEnabled(false));
        record(name, c.atLeast(Confidence.MEDIUM) && above >= 60, String.format(Locale.ROOT,
                "confidence=%s, player %.0f blocks above the base, waited %d ticks (screenshot atlas-basefinder-deep)", c, above, t));
        server.runCommand("tp @a " + spawn.getX() + " " + spawn.getY() + " " + spawn.getZ());
    }

    private void autoMineLava(ClientGameTestContext ctx, TestServerContext server, BlockPos start) {
        String name = "Auto Mine turns away from lava (never opens it) and carries on";
        int sx = start.getX(), y = start.getY(), sz = start.getZ();
        server.runCommand(fill(new BlockPos(sx - 1, y - 1, sz - 3), new BlockPos(sx + 12, y + 4, sz + 3), "minecraft:stone"));
        server.runCommand(fill(new BlockPos(sx, y, sz), new BlockPos(sx, y + 1, sz), "minecraft:air"));
        BlockPos lava = new BlockPos(sx + 5, y, sz);
        server.runCommand(setblock(lava, "minecraft:lava"));
        server.runCommand("gamemode survival @a");
        server.runCommand("clear @a");
        server.runCommand("give @a minecraft:diamond_pickaxe");
        server.runCommand("tp @a " + (sx + 0.5) + " " + y + " " + (sz + 0.5) + " -90 0");
        ctx.waitTicks(30);
        ctx.runOnClient(mc -> {
            Atlas.get().notifications.clearHistory();
            AutoMineModule m = AutoMineModule.get();
            m.length.set(10.0);
            m.setEnabled(true);
        });
        int t = 0;
        while (t < 1200 && ctx.computeOnClient(mc -> AutoMineModule.get().isEnabled())) {
            ctx.waitTicks(10);
            t += 10;
        }
        boolean stopped = !ctx.computeOnClient(mc -> AutoMineModule.get().isEnabled());
        String last = ctx.computeOnClient(mc -> lastNotification());
        ctx.runOnClient(mc -> AutoMineModule.get().setEnabled(false));
        int[] r = server.computeOnServer(s -> {
            ServerWorld w = s.getOverworld();
            int open = 0, lavaInTunnel = 0;
            for (int k = 1; k <= 4; k++) {
                if (w.getBlockState(new BlockPos(sx + k, y, sz)).isAir()) open++;
                for (int h = 0; h < 2; h++) {
                    if (!w.getFluidState(new BlockPos(sx + k, y + h, sz)).isEmpty()) lavaInTunnel++;
                }
            }
            return new int[]{open, lavaInTunnel, w.getBlockState(new BlockPos(sx + 4, y, sz)).isAir() ? 1 : 0};
        });
        boolean turned = last.contains("turned");
        record(name, stopped && turned && r[1] == 0 && r[2] == 0 && r[0] >= 2, String.format(Locale.ROOT,
                "finished=%s, turned=%s, tunnel blocks open before the lava %d, block next to lava mined=%s, lava in tunnel=%d; message: %s",
                stopped, turned, r[0], r[2] == 1, r[1], last));
        server.runCommand("gamemode creative @a");
    }

    /**
     * Three chunks next to each other: a base buried 10 blocks under stone, a mineshaft corridor
     * (oak planks, fences, rails, cobwebs) under the same stone, and chests standing on the surface.
     * The player stands on the ground outside. Only the buried base should be found.
     */
    private void baseFinder(ClientGameTestContext ctx, TestServerContext server, BlockPos spawn) {
        String name = "Base Finder finds a buried base from the surface, ignores a mineshaft and surface chests";
        int cx = (spawn.getX() >> 4) + 2, cz = (spawn.getZ() >> 4) - 3;
        int y = spawn.getY();
        int ax = cx * 16, az = cz * 16;          // buried base
        int bx = (cx + 2) * 16, bz = cz * 16;    // mineshaft
        int sx2 = (cx + 4) * 16, sz2 = cz * 16;  // surface chests
        loadSite(ctx, server, bx + 8, y + 20, bz + 8);
        for (int x0 : new int[]{ax, bx}) {
            server.runCommand(fill(new BlockPos(x0, y, az), new BlockPos(x0 + 15, y + 14, az + 15), "minecraft:stone"));
        }
        // Buried base: room at the bottom, 11 blocks of stone above it.
        server.runCommand(fill(new BlockPos(ax + 3, y, az + 3), new BlockPos(ax + 12, y + 3, az + 12), "minecraft:air"));
        server.runCommand(fill(new BlockPos(ax + 3, y - 1, az + 3), new BlockPos(ax + 12, y - 1, az + 3), "minecraft:white_concrete"));
        for (int i = 0; i < 8; i++) server.runCommand(setblock(new BlockPos(ax + 4 + i, y, az + 12), "minecraft:chest"));
        for (int i = 0; i < 3; i++) server.runCommand(setblock(new BlockPos(ax + 4 + i, y, az + 4), "minecraft:barrel"));
        server.runCommand(setblock(new BlockPos(ax + 8, y, az + 4), "minecraft:furnace"));
        server.runCommand(setblock(new BlockPos(ax + 9, y, az + 4), "minecraft:crafting_table"));
        server.runCommand(setblock(new BlockPos(ax + 10, y, az + 4), "minecraft:blast_furnace"));
        server.runCommand(setblock(new BlockPos(ax + 11, y, az + 4), "minecraft:smithing_table"));
        server.runCommand(setblock(new BlockPos(ax + 8, y + 3, az + 8), "minecraft:lantern[hanging=true]"));
        // Mineshaft corridor along Z.
        server.runCommand(fill(new BlockPos(bx + 6, y, bz), new BlockPos(bx + 8, y + 2, bz + 15), "minecraft:air"));
        server.runCommand(fill(new BlockPos(bx + 7, y, bz), new BlockPos(bx + 7, y, bz + 15), "minecraft:rail"));
        server.runCommand(fill(new BlockPos(bx + 6, y + 2, bz), new BlockPos(bx + 8, y + 2, bz + 15), "minecraft:oak_planks"));
        for (int z = 1; z < 16; z += 4) {
            server.runCommand(fill(new BlockPos(bx + 6, y, bz + z), new BlockPos(bx + 6, y + 1, bz + z), "minecraft:oak_fence"));
            server.runCommand(fill(new BlockPos(bx + 8, y, bz + z), new BlockPos(bx + 8, y + 1, bz + z), "minecraft:oak_fence"));
        }
        server.runCommand(setblock(new BlockPos(bx + 6, y + 1, bz + 3), "minecraft:cobweb"));
        server.runCommand(setblock(new BlockPos(bx + 8, y + 1, bz + 10), "minecraft:cobweb"));
        // Surface chests: in the open, nothing above.
        for (int i = 0; i < 8; i++) server.runCommand(setblock(new BlockPos(sx2 + 4 + i, y, sz2 + 8), "minecraft:chest"));
        for (int i = 0; i < 4; i++) server.runCommand(setblock(new BlockPos(sx2 + 4 + i, y, sz2 + 4), "minecraft:furnace"));
        server.runCommand(fill(new BlockPos(sx2 + 3, y - 1, sz2 + 3), new BlockPos(sx2 + 12, y - 1, sz2 + 3), "minecraft:white_concrete"));
        // Stand on the ground outside, south of the three chunks, looking toward the buried base.
        server.runCommand("tp @a " + (bx + 8) + " " + y + " " + (bz + 24) + " 116.6 12");
        ctx.waitTicks(40);
        ctx.runOnClient(mc -> {
            BaseFinderModule bf = Atlas.get().modules.get(BaseFinderModule.class);
            bf.undergroundOnly.set(true);
            bf.minDepth.set(6.0);
            bf.setEnabled(true);
        });
        Confidence[] c = new Confidence[3];
        int t = 0;
        for (; t < 600; t += 10) {
            ctx.waitTicks(10);
            c[0] = confidenceAt(ctx, cx, cz);
            if (c[0].atLeast(Confidence.MEDIUM) && t >= 100) break;
        }
        c[1] = confidenceAt(ctx, cx + 2, cz);
        c[2] = confidenceAt(ctx, cx + 4, cz);
        ctx.takeScreenshot("atlas-basefinder");
        ctx.runOnClient(mc -> Atlas.get().modules.get(dev.atlasclient.module.render.ChunkOutlinesModule.class).setEnabled(true));
        ctx.waitTicks(3);
        ctx.takeScreenshot("atlas-chunk-outlines");
        ctx.runOnClient(mc -> Atlas.get().modules.get(dev.atlasclient.module.render.ChunkOutlinesModule.class).setEnabled(false));
        String explain = ctx.computeOnClient(mc -> {
            ChunkAssessment a = AtlasClient.scan().discoveries().get(Env.dimension(), cx, cz);
            return a == null ? "not analysed" : a.score() + " " + a.explanation();
        });
        boolean playerAbove = ctx.computeOnClient(mc -> mc.player.getY() >= y);
        ctx.runOnClient(mc -> Atlas.get().modules.get(BaseFinderModule.class).setEnabled(false));
        record(name, c[0].atLeast(Confidence.MEDIUM) && !c[1].atLeast(Confidence.MEDIUM) && !c[2].atLeast(Confidence.MEDIUM) && playerAbove,
                "buried base=" + c[0] + " (" + explain + "), mineshaft=" + c[1] + ", surface chests=" + c[2]
                        + ", player above ground=" + playerAbove + ", waited " + t + " ticks (screenshot atlas-basefinder)");
    }

    /** Fill commands only work in loaded chunks: go there first (creative, so nothing falls). */
    private static void loadSite(ClientGameTestContext ctx, TestServerContext server, int x, int y, int z) {
        server.runCommand("gamemode creative @a");
        server.runCommand("tp @a " + x + " " + y + " " + z);
        ctx.waitTicks(40);
    }

    /** A chunk filled with stone from y to y+24 (so everything inside is ~20 blocks underground). */
    private static void buriedChunk(TestServerContext server, int cx, int cz, int y) {
        server.runCommand(fill(new BlockPos(cx * 16, y, cz * 16), new BlockPos(cx * 16 + 15, y + 24, cz * 16 + 15), "minecraft:stone"));
    }

    private static List<SiteClusterer.Site> sitesNow(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> Atlas.get().modules.get(BaseFinderModule.class).allSites());
    }

    private static void startBaseFinder(ClientGameTestContext ctx, boolean underground) {
        ctx.runOnClient(mc -> {
            BaseFinderModule bf = Atlas.get().modules.get(BaseFinderModule.class);
            bf.undergroundOnly.set(underground);
            bf.minDepth.set(6.0);
            bf.setEnabled(true);
        });
    }

    private static void stopBaseFinder(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> {
            BaseFinderModule bf = Atlas.get().modules.get(BaseFinderModule.class);
            bf.setEnabled(false);
            bf.undergroundOnly.set(true);
        });
    }

    /**
     * A buried amethyst pocket whose buds are all fully grown clusters (world generation leaves about a
     * quarter): growth that only happens with a player nearby. Must be found as overgrown, from the
     * surface, with nothing else there.
     */
    private void baseFinderGrowth(ClientGameTestContext ctx, TestServerContext server, BlockPos spawn) {
        String name = "Base Finder finds overgrown (fully grown) amethyst buried 20 blocks down";
        int cx = (spawn.getX() >> 4), cz = ((spawn.getZ() - 160) >> 4);
        int y = spawn.getY(), ax = cx * 16, az = cz * 16;
        loadSite(ctx, server, ax + 8, y + 40, az + 8);
        buriedChunk(server, cx, cz, y);
        server.runCommand(fill(new BlockPos(ax + 3, y + 2, az + 3), new BlockPos(ax + 12, y + 5, az + 12), "minecraft:air"));
        server.runCommand(fill(new BlockPos(ax + 4, y + 1, az + 4), new BlockPos(ax + 11, y + 1, az + 11), "minecraft:budding_amethyst"));
        server.runCommand(fill(new BlockPos(ax + 4, y + 2, az + 4), new BlockPos(ax + 11, y + 2, az + 11), "minecraft:amethyst_cluster[facing=up]"));
        server.runCommand("tp @a " + (ax + 8) + " " + (y + 25) + " " + (az + 30));
        ctx.waitTicks(20);
        startBaseFinder(ctx, true);
        String[] found = {"not analysed"};
        boolean ok = false;
        int t = 0;
        for (; t < 600 && !ok; t += 10) {
            ctx.waitTicks(10);
            found[0] = ctx.computeOnClient(mc -> {
                ChunkAssessment a = AtlasClient.scan().discoveries().get(Env.dimension(), cx, cz);
                if (a == null) return "not analysed";
                boolean growth = a.evidence().stream().anyMatch(e -> e.id().equals("amethyst.growth"));
                return a.confidence() + " growth=" + growth + " " + a.amethyst().summary();
            });
            ok = found[0].contains("growth=true") && !found[0].startsWith("NONE") && !found[0].startsWith("VERY_LOW");
        }
        boolean overgrown = sitesNow(ctx).stream().anyMatch(s -> s.contains(cx, cz) && s.types().contains(SiteClusterer.Type.OVERGROWN));
        ctx.takeScreenshot("atlas-basefinder-growth");
        stopBaseFinder(ctx);
        record(name, ok && overgrown, "chunk: " + found[0] + ", site marked overgrown=" + overgrown + ", waited " + t + " ticks");
    }

    /** Concrete, glass and planks placed randomly inside stone (someone spamming blocks): not a base. */
    private void baseFinderSpam(ClientGameTestContext ctx, TestServerContext server, BlockPos spawn) {
        String name = "Base Finder does not call randomly placed blocks a base";
        int cx = (spawn.getX() >> 4) + 2, cz = ((spawn.getZ() - 160) >> 4);
        int y = spawn.getY(), ax = cx * 16, az = cz * 16;
        loadSite(ctx, server, ax + 8, y + 40, az + 8);
        buriedChunk(server, cx, cz, y);
        java.util.Random rnd = new java.util.Random(11);
        String[] blocks = {"minecraft:white_concrete", "minecraft:red_concrete", "minecraft:glass", "minecraft:spruce_planks", "minecraft:bricks"};
        for (int i = 0; i < 70; i++) {
            server.runCommand(setblock(new BlockPos(ax + rnd.nextInt(16), y + 1 + rnd.nextInt(17), az + rnd.nextInt(16)), blocks[i % blocks.length]));
        }
        server.runCommand("tp @a " + (ax + 8) + " " + (y + 25) + " " + (az + 8));
        ctx.waitTicks(20);
        startBaseFinder(ctx, true);
        String result = "not analysed";
        int t = 0;
        for (; t < 400; t += 10) {
            ctx.waitTicks(10);
            result = ctx.computeOnClient(mc -> {
                ChunkAssessment a = AtlasClient.scan().discoveries().get(Env.dimension(), cx, cz);
                return a == null ? "not analysed" : a.confidence().name();
            });
            if (!result.equals("not analysed") && t >= 100) break;
        }
        stopBaseFinder(ctx);
        Confidence c = result.equals("not analysed") ? null : Confidence.valueOf(result);
        record(name, c != null && !c.atLeast(Confidence.MEDIUM), "random blocks chunk scored " + result + " after " + t + " ticks");
    }

    /**
     * A base split over two chunks: storage room in one, farm + bed + furnaces in the next. Judged as
     * one site with both kinds of evidence.
     */
    private void baseFinderMultiChunk(ClientGameTestContext ctx, TestServerContext server, BlockPos spawn) {
        String name = "Base Finder joins a base spread over two chunks (storage + farm) into one High+ site";
        int cx = (spawn.getX() >> 4) + 4, cz = ((spawn.getZ() - 160) >> 4);
        int y = spawn.getY(), ax = cx * 16, az = cz * 16, bx = ax + 16;
        loadSite(ctx, server, bx, y + 40, az + 8);
        buriedChunk(server, cx, cz, y);
        buriedChunk(server, cx + 1, cz, y);
        server.runCommand(fill(new BlockPos(ax + 3, y + 2, az + 3), new BlockPos(ax + 12, y + 5, az + 12), "minecraft:air"));
        for (int i = 0; i < 10; i++) server.runCommand(setblock(new BlockPos(ax + 3 + i, y + 2, az + 12), "minecraft:chest"));
        server.runCommand(setblock(new BlockPos(ax + 4, y + 2, az + 4), "minecraft:barrel"));
        server.runCommand(setblock(new BlockPos(ax + 5, y + 2, az + 4), "minecraft:barrel"));
        server.runCommand(fill(new BlockPos(bx + 3, y + 2, az + 3), new BlockPos(bx + 12, y + 5, az + 12), "minecraft:air"));
        server.runCommand(fill(new BlockPos(bx + 4, y + 1, az + 4), new BlockPos(bx + 10, y + 1, az + 10), "minecraft:farmland[moisture=7]"));
        server.runCommand(setblock(new BlockPos(bx + 7, y + 1, az + 7), "minecraft:water"));
        for (int[] l : new int[][]{{5, 5}, {5, 9}, {9, 5}, {9, 9}}) {
            server.runCommand(setblock(new BlockPos(bx + l[0], y + 6, az + l[1]), "minecraft:glowstone")); // crops need light
        }
        server.runCommand(fill(new BlockPos(bx + 4, y + 2, az + 4), new BlockPos(bx + 10, y + 2, az + 10), "minecraft:wheat[age=7]"));
        server.runCommand(setblock(new BlockPos(bx + 7, y + 2, az + 7), "minecraft:air"));
        server.runCommand(setblock(new BlockPos(bx + 12, y + 2, az + 4), "minecraft:red_bed[part=foot,facing=south]"));
        server.runCommand(setblock(new BlockPos(bx + 12, y + 2, az + 5), "minecraft:red_bed[part=head,facing=south]"));
        for (int i = 0; i < 3; i++) server.runCommand(setblock(new BlockPos(bx + 4 + i, y + 2, az + 12), "minecraft:furnace"));
        server.runCommand(setblock(new BlockPos(bx + 8, y + 2, az + 12), "minecraft:crafting_table"));
        server.runCommand("tp @a " + bx + " " + (y + 25) + " " + (az + 30));
        ctx.waitTicks(20);
        startBaseFinder(ctx, true);
        String detail = "no site";
        boolean ok = false;
        int t = 0;
        for (; t < 600 && !ok; t += 10) {
            ctx.waitTicks(10);
            for (SiteClusterer.Site s : sitesNow(ctx)) {
                if (!s.contains(cx, cz)) continue;
                detail = s.confidence() + " over " + s.chunks().size() + " chunks, groups " + s.groups() + ": " + s.reasons(3);
                ok = s.chunks().size() >= 2 && s.confidence().atLeast(Confidence.HIGH)
                        && s.groups().contains(Indicator.Group.STORAGE) && s.groups().contains(Indicator.Group.FARMING);
            }
        }
        ctx.takeScreenshot("atlas-basefinder-multichunk");
        stopBaseFinder(ctx);
        record(name, ok, detail + ", waited " + t + " ticks");
    }

    /** A real generated village (placed with /place structure): must not come out High or above. */
    private void baseFinderVillage(ClientGameTestContext ctx, TestServerContext server, BlockPos spawn) {
        String name = "Base Finder does not mistake a generated village for a base";
        int vx = spawn.getX(), vz = spawn.getZ() + 224;
        loadSite(ctx, server, vx, spawn.getY() + 30, vz);
        // Villages span several chunks: keep them loaded on the server while it is placed.
        server.runCommand("forceload add " + (vx - 80) + " " + (vz - 80) + " " + (vx + 80) + " " + (vz + 80));
        ctx.waitTicks(100);
        int bells = 0;
        for (int attempt = 0; attempt < 3 && bells == 0; attempt++) {
            server.runCommand("place structure minecraft:village_plains " + vx + " " + spawn.getY() + " " + vz);
            ctx.waitTicks(60);
            bells = countBells(server, vx, vz, spawn.getY());
        }
        server.runCommand("forceload remove all");
        if (bells == 0) {
            System.out.println("[Atlas gametest] NOTE  village could not be placed here (/place structure); village check skipped");
            return;
        }
        startBaseFinder(ctx, false); // surface blocks count too: the hardest case for villages
        villageCheck(ctx, name, vx, vz, bells);
    }

    private static int countBells(TestServerContext server, int vx, int vz, int y0) {
        return server.computeOnServer(s -> {
            ServerWorld w = s.getOverworld();
            int n = 0;
            for (int x = vx - 96; x <= vx + 96; x++) for (int z = vz - 96; z <= vz + 96; z++) for (int yy = y0; yy < y0 + 12; yy++) {
                if (net.minecraft.registry.Registries.BLOCK.getId(w.getBlockState(new BlockPos(x, yy, z)).getBlock()).getPath().equals("bell")) n++;
            }
            return n;
        });
    }

    private void villageCheck(ClientGameTestContext ctx, String name, int vx, int vz, int bells) {
        ctx.waitTicks(500);
        String[] worst = {"none"};
        boolean[] recognised = {false};
        Confidence top = ctx.computeOnClient(mc -> {
            Confidence best = Confidence.NONE;
            for (SiteClusterer.Site s : Atlas.get().modules.get(BaseFinderModule.class).allSites()) {
                if (Math.hypot(s.centerX() - vx, s.centerZ() - vz) > 112) continue;
                if (s.structures().stream().anyMatch(m -> m.kind() == dev.atlasclient.core.analysis.StructureSignatures.Kind.VILLAGE)) recognised[0] = true;
                if (s.confidence().ordinal() > best.ordinal()) {
                    best = s.confidence();
                    worst[0] = s.confidence() + " (" + s.chunks().size() + " chunks): " + s.reasons(3);
                }
            }
            return best;
        });
        ctx.takeScreenshot("atlas-basefinder-village");
        stopBaseFinder(ctx);
        record(name, recognised[0] && !top.atLeast(Confidence.HIGH), bells + " bell(s) placed, village recognised=" + recognised[0]
                + ", highest site near it: " + worst[0]);
    }

    private static boolean exposedOnClient(net.minecraft.client.MinecraftClient mc, BlockPos p) {
        for (net.minecraft.util.math.Direction d : net.minecraft.util.math.Direction.values()) {
            if (!mc.world.getBlockState(p.offset(d)).isOpaqueFullCube()) return true;
        }
        return false;
    }

    /**
     * X-Ray against a simulated anti-xray server. A stone block with a short cave: 3 diamonds in the
     * cave walls (exposed, real) and 8 buried in stone. HIDE: only the 3 show, X-Ray says the server
     * hides ores, and digging next to a buried one reveals it. FAKE: the ground is full of fake ores;
     * X-Ray notices and shows only ores touching air, i.e. the 3 real ones.
     */
    private void xrayAntiXray(ClientGameTestContext ctx, TestServerContext server, BlockPos at, AntiXraySim.Mode mode) {
        String name = "X-Ray on an anti-xray server (" + (mode == AntiXraySim.Mode.HIDE ? "ores hidden" : "fake ores") + ")";
        int ax = (at.getX() >> 4) << 4, az = (at.getZ() >> 4) << 4, y = at.getY();
        loadSite(ctx, server, ax + 8, y + 20, az + 8);
        // 3x3 chunks of stone ground (a real server fakes ores in every chunk, not just one).
        for (int ix = -1; ix <= 1; ix++) for (int iz = -1; iz <= 1; iz++) {
            server.runCommand(fill(new BlockPos(ax + ix * 16, y, az + iz * 16), new BlockPos(ax + ix * 16 + 15, y + 12, az + iz * 16 + 15), "minecraft:stone"));
        }
        server.runCommand(fill(new BlockPos(ax + 3, y + 4, az + 6), new BlockPos(ax + 12, y + 6, az + 8), "minecraft:air"));
        List<BlockPos> real = List.of(new BlockPos(ax + 5, y + 5, az + 5), new BlockPos(ax + 8, y + 5, az + 9), new BlockPos(ax + 11, y + 3, az + 7));
        List<BlockPos> buried = new ArrayList<>();
        for (int i = 0; i < 8; i++) buried.add(new BlockPos(ax + 3 + i, y + 10, az + 12));
        for (BlockPos p : real) server.runCommand(setblock(p, "minecraft:diamond_ore"));
        for (BlockPos p : buried) server.runCommand(setblock(p, "minecraft:diamond_ore"));
        server.runCommand("tp @a " + (ax + 8) + " " + (y + 13) + " " + (az + 3));
        ctx.waitTicks(40);
        net.minecraft.server.MinecraftServer ms = server.computeOnServer(srv -> srv);
        ctx.runOnClient(mc -> AntiXraySim.start(mode, ms, ax >> 4, az >> 4, 2));
        ctx.waitTicks(5);
        ctx.runOnClient(mc -> Atlas.get().modules.get(XRayModule.class).setEnabled(true));
        int t = 0;
        for (; t < 400; t += 20) {
            ctx.waitTicks(20);
            boolean seen = ctx.computeOnClient(mc -> {
                var hits = Atlas.get().modules.get(XRayModule.class).visibleHits();
                return real.stream().allMatch(p -> hits.stream().anyMatch(h -> h.x() == p.getX() && h.y() == p.getY() && h.z() == p.getZ()));
            });
            if (seen && t >= 100) break;
        }
        String[] summary = new String[1];
        boolean ok = ctx.computeOnClient(mc -> {
            XRayModule x = Atlas.get().modules.get(XRayModule.class);
            var hits = x.visibleHits();
            int realShown = 0, buriedShown = 0, enclosedShown = 0;
            for (var h : hits) {
                BlockPos p = new BlockPos(h.x(), h.y(), h.z());
                if (real.contains(p)) realShown++;
                if (buried.contains(p)) buriedShown++;
                if (!exposedOnClient(mc, p)) enclosedShown++;
            }
            int indexed = AtlasClient.xray().index().size();
            summary[0] = String.format(Locale.ROOT, "availability=%s, real exposed shown %d/3, buried shown %d, shown-but-enclosed %d, "
                    + "blocks the client knows about %d, exposed-only=%s", x.availability(), realShown, buriedShown, enclosedShown, indexed, x.onlyExposed());
            if (mode == AntiXraySim.Mode.HIDE) {
                return realShown == 3 && buriedShown == 0 && x.availability() != dev.atlasclient.core.xray.XrayIndex.Availability.SUSPECT_FAKE;
            }
            return realShown == 3 && enclosedShown == 0 && x.onlyExposed()
                    && x.availability() == dev.atlasclient.core.xray.XrayIndex.Availability.SUSPECT_FAKE;
        });
        boolean revealed = true;
        if (mode == AntiXraySim.Mode.HIDE) {
            // Dig the stone right above a buried ore: the server sends the real blocks around it.
            BlockPos target = buried.get(0);
            ctx.runOnClient(mc -> mc.interactionManager.attackBlock(target.up(), net.minecraft.util.math.Direction.UP));
            revealed = false;
            for (int i = 0; i < 400 && !revealed; i += 20) {
                ctx.waitTicks(20);
                revealed = ctx.computeOnClient(mc -> Atlas.get().modules.get(XRayModule.class).visibleHits().stream()
                        .anyMatch(h -> h.x() == target.getX() && h.y() == target.getY() && h.z() == target.getZ()));
            }
            summary[0] += ", buried ore shown after digging next to it=" + revealed;
        }
        ctx.takeScreenshot(mode == AntiXraySim.Mode.HIDE ? "atlas-xray-antixray-hide" : "atlas-xray-antixray-fake");
        ctx.runOnClient(mc -> {
            Atlas.get().modules.get(XRayModule.class).setEnabled(false);
            AntiXraySim.stop();
        });
        record(name, ok && revealed, summary[0] + " (after " + t + " ticks)");
    }

    /**
     * Debris Finder in the Nether against fake debris: 2 real debris in a cave wall, 3 buried. The
     * ground is full of fake debris. Only the 2 exposed ones may show; digging next to a buried one
     * makes it real (the server sent the true blocks around the dug block).
     */
    private void debrisAntiXray(ClientGameTestContext ctx, TestServerContext server) {
        String name = "Debris Finder on an anti-xray server (fake debris in the Nether)";
        int ax = 16, az = 16, y = 60;
        server.runCommand("gamemode creative @a");
        server.runCommand("execute in minecraft:the_nether run tp @a " + (ax + 8) + " " + (y + 14) + " " + (az + 8));
        ctx.waitTicks(100);
        server.runCommand("execute in minecraft:the_nether run " + fill(new BlockPos(ax, y, az), new BlockPos(ax + 15, y + 12, az + 15), "minecraft:netherrack"));
        server.runCommand("execute in minecraft:the_nether run " + fill(new BlockPos(ax, y + 13, az), new BlockPos(ax + 15, y + 17, az + 15), "minecraft:air"));
        server.runCommand("execute in minecraft:the_nether run " + fill(new BlockPos(ax + 3, y + 4, az + 6), new BlockPos(ax + 12, y + 6, az + 8), "minecraft:air"));
        List<BlockPos> real = List.of(new BlockPos(ax + 5, y + 5, az + 5), new BlockPos(ax + 9, y + 5, az + 9));
        List<BlockPos> buried = List.of(new BlockPos(ax + 4, y + 10, az + 12), new BlockPos(ax + 8, y + 10, az + 12), new BlockPos(ax + 12, y + 10, az + 12));
        for (BlockPos p : real) server.runCommand("execute in minecraft:the_nether run " + setblock(p, "minecraft:ancient_debris"));
        for (BlockPos p : buried) server.runCommand("execute in minecraft:the_nether run " + setblock(p, "minecraft:ancient_debris"));
        ctx.waitTicks(40);
        net.minecraft.server.MinecraftServer ms = server.computeOnServer(srv -> srv);
        ctx.runOnClient(mc -> AntiXraySim.start(AntiXraySim.Mode.FAKE, ms, ax >> 4, az >> 4, 2));
        ctx.waitTicks(5);
        ctx.runOnClient(mc -> Atlas.get().modules.get(AncientDebrisFinderModule.class).setEnabled(true));
        // Shown debris, classified with the server's truth (natural debris in the Nether is real too).
        java.util.function.Function<net.minecraft.client.MinecraftClient, int[]> count0 = mc -> new int[0];
        java.util.function.Supplier<int[]> countNow = () -> {
            List<Long> shownPos = ctx.computeOnClient(mc -> {
                var d = Atlas.get().modules.get(AncientDebrisFinderModule.class);
                List<Long> l = new ArrayList<>();
                for (var r : d.shown(mc.player.getX(), mc.player.getY(), mc.player.getZ())) l.add(BlockPos.asLong(r.x(), r.y(), r.z()));
                return l;
            });
            int sent = ctx.computeOnClient(mc -> AtlasClient.scan().debris().count(Env.dimension()));
            return server.computeOnServer(srv -> {
                ServerWorld nether = srv.getWorld(net.minecraft.world.World.NETHER);
                int realShown = 0, buriedShown = 0, fakeShown = 0, naturalShown = 0;
                for (long k : shownPos) {
                    BlockPos p = BlockPos.fromLong(k);
                    boolean isDebris = net.minecraft.registry.Registries.BLOCK.getId(nether.getBlockState(p).getBlock()).getPath().equals("ancient_debris");
                    if (real.contains(p)) realShown++;
                    else if (buried.contains(p)) buriedShown++;
                    else if (isDebris) naturalShown++;
                    else fakeShown++;
                }
                return new int[]{realShown, buriedShown, fakeShown, sent, naturalShown};
            });
        };
        int[] c = {0, 0, 0, 0, 0};
        int t = 0;
        for (; t < 400; t += 20) {
            ctx.waitTicks(20);
            c = countNow.get();
            if (c[0] == 2 && t >= 100) break;
        }
        int[] before = c;
        BlockPos target = buried.get(1);
        ctx.runOnClient(mc -> mc.interactionManager.attackBlock(target.up(), net.minecraft.util.math.Direction.UP));
        int[] after = before;
        for (int i = 0; i < 400; i += 20) {
            ctx.waitTicks(20);
            after = countNow.get();
            if (after[1] >= 1) break;
        }
        ctx.takeScreenshot("atlas-debris-antixray");
        ctx.runOnClient(mc -> {
            Atlas.get().modules.get(AncientDebrisFinderModule.class).setEnabled(false);
            AntiXraySim.stop();
        });
        server.runCommand("execute in minecraft:overworld run tp @a 0 -59 0");
        ctx.waitTicks(60);
        record(name, before[0] == 2 && before[1] == 0 && before[2] == 0 && after[1] == 1 && after[2] == 0, String.format(Locale.ROOT,
                "before digging: real exposed shown %d/2, buried shown %d, fake shown %d, natural real debris shown %d (debris the client was sent: %d); "
                        + "after digging next to one buried: buried shown %d, fake shown %d", before[0], before[1], before[2], before[4], before[3], after[1], after[2]));
    }

    /** Base Finder still finds a buried base when the server floods the ground with fake ores. */
    private void baseFinderAntiXray(ClientGameTestContext ctx, TestServerContext server, BlockPos at) {
        String name = "Base Finder on an anti-xray server (fake ores everywhere)";
        int cx = at.getX() >> 4, cz = at.getZ() >> 4, y = at.getY(), ax = cx * 16, az = cz * 16;
        loadSite(ctx, server, ax + 8, y + 40, az + 8);
        buriedChunk(server, cx, cz, y);
        server.runCommand(fill(new BlockPos(ax + 3, y + 2, az + 3), new BlockPos(ax + 12, y + 5, az + 12), "minecraft:air"));
        for (int i = 0; i < 6; i++) server.runCommand(setblock(new BlockPos(ax + 4 + i, y + 2, az + 12), "minecraft:chest"));
        server.runCommand(setblock(new BlockPos(ax + 4, y + 2, az + 4), "minecraft:furnace"));
        server.runCommand(setblock(new BlockPos(ax + 5, y + 2, az + 4), "minecraft:crafting_table"));
        server.runCommand(setblock(new BlockPos(ax + 6, y + 2, az + 4), "minecraft:blast_furnace"));
        server.runCommand(setblock(new BlockPos(ax + 9, y + 2, az + 4), "minecraft:red_bed[part=foot,facing=south]"));
        server.runCommand(setblock(new BlockPos(ax + 9, y + 2, az + 5), "minecraft:red_bed[part=head,facing=south]"));
        server.runCommand("tp @a " + (ax + 8) + " " + (y + 25) + " " + (az + 30));
        ctx.waitTicks(20);
        net.minecraft.server.MinecraftServer ms = server.computeOnServer(srv -> srv);
        ctx.runOnClient(mc -> AntiXraySim.start(AntiXraySim.Mode.FAKE, ms, cx, cz, 1));
        startBaseFinder(ctx, true);
        Confidence c = Confidence.NONE;
        int t = 0;
        for (; t < 600; t += 10) {
            ctx.waitTicks(10);
            c = confidenceAt(ctx, cx, cz);
            if (c.atLeast(Confidence.MEDIUM)) break;
        }
        stopBaseFinder(ctx);
        ctx.runOnClient(mc -> AntiXraySim.stop());
        record(name, c.atLeast(Confidence.MEDIUM), "buried base with fake ores around it scored " + c + " after " + t + " ticks");
    }

    private static Confidence confidenceAt(ClientGameTestContext ctx, int cx, int cz) {
        return ctx.computeOnClient(mc -> {
            ChunkAssessment a = AtlasClient.scan().discoveries().get(Env.dimension(), cx, cz);
            return a == null ? Confidence.NONE : a.confidence();
        });
    }

    private static String lastNotification() {
        List<Notification> h = Atlas.get().notifications.history();
        return h.isEmpty() ? "(none)" : h.get(0).title() + ": " + h.get(0).message();
    }

    private ClientGameTestContext testCtx;
    private String suite = "all";

    private void safe(String name, Runnable r) {
        revive();
        try {
            r.run();
        } catch (Throwable t) {
            record(name, false, "CRASHED: " + t);
            t.printStackTrace(System.out);
        }
    }

    /** A scenario that ended with the player dead must not take the next ones down with it. */
    private void revive() {
        if (testCtx == null) return;
        try {
            boolean dead = testCtx.computeOnClient(mc -> mc.player != null && mc.player.isDead());
            if (!dead) return;
            System.out.println("[Atlas gametest] NOTE  player was dead before the next scenario; respawning");
            testCtx.runOnClient(mc -> {
                mc.player.requestRespawn();
                mc.setScreen(null);
            });
            testCtx.waitTicks(40);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
        }
    }

    private static String setblock(BlockPos p, String block) {
        return "setblock " + p.getX() + " " + p.getY() + " " + p.getZ() + " " + block;
    }

    // ------------------------------------------------------------------ scenarios

    private void scenario(ClientGameTestContext ctx, TestServerContext server, String name, String file, BlockPos origin,
                          Rotation rot, Mirror mirror, boolean excavate, int timeoutTicks) {
        server.runCommand("gamemode creative @a");
        prepareSite(server, origin, 12, 8);
        teleportNear(server, origin);
        ctx.waitTicks(10);
        start(ctx, file, origin, rot, mirror, excavate, AreaMining.PickaxeMode.REGULAR);
        boolean finished = waitFinished(ctx, timeoutTicks);
        Verify v = verify(ctx, server);
        record(name, finished && v.ok(), (finished ? "finished" : "TIMED OUT") + "; server check " + v + "; " + summary(ctx));
        cancel(ctx);
    }

    private void missingMaterials(ClientGameTestContext ctx, TestServerContext server, BlockPos origin) {
        String name = "missing materials (survival)";
        prepareSite(server, origin, 10, 4);
        teleportNear(server, origin);
        server.runCommand("gamemode survival @a");
        server.runCommand("clear @a");
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> Atlas.get().notifications.clearHistory());
        start(ctx, "stone_platform.schem", origin, Rotation.NONE, Mirror.NONE, false, AreaMining.PickaxeMode.REGULAR);
        ctx.waitTicks(60);
        boolean warned = ctx.computeOnClient(mc -> {
            for (Notification n : Atlas.get().notifications.history()) {
                if (n.title().equals("Missing building materials")) return true;
            }
            return false;
        });
        int placedWithoutItems = countBlock(server, origin, 7, 1, 7, "minecraft:stone");
        server.runCommand("give @a minecraft:stone 64");
        boolean finished = waitFinished(ctx, 2400);
        Verify v = verify(ctx, server);
        record(name, warned && placedWithoutItems == 0 && finished && v.ok(), "warning shown=" + warned + ", placed before items=" + placedWithoutItems
                + ", finished after /give=" + finished + "; server check " + v);
        cancel(ctx);
        server.runCommand("gamemode creative @a");
    }

    private void pauseResume(ClientGameTestContext ctx, TestServerContext server, BlockPos origin) {
        String name = "pause / resume";
        server.runCommand("gamemode creative @a");
        prepareSite(server, origin, 12, 8);
        teleportNear(server, origin);
        ctx.waitTicks(10);
        start(ctx, "storage_room.schem", origin, Rotation.NONE, Mirror.NONE, false, AreaMining.PickaxeMode.REGULAR);
        int waited = 0;
        while (waited < 600 && progressDone(ctx) < 8) {
            ctx.waitTicks(2);
            waited += 2;
        }
        ctx.runOnClient(mc -> AutoBuilderModule.get().togglePause());
        ctx.waitTicks(10); // let packets already in flight land
        int before = countNonAir(server, origin, 5, 4, 5);
        ctx.waitTicks(80);
        int after = countNonAir(server, origin, 5, 4, 5);
        boolean paused = ctx.computeOnClient(mc -> AutoBuilderModule.get().job() != null && AutoBuilderModule.get().job().isPaused());
        ctx.runOnClient(mc -> AutoBuilderModule.get().togglePause());
        boolean finished = waitFinished(ctx, 2400);
        Verify v = verify(ctx, server);
        record(name, paused && before == after && finished && v.ok(), "paused=" + paused + ", blocks while paused " + before + " -> " + after
                + ", finished after resume=" + finished + "; server check " + v);
        cancel(ctx);
    }

    private void excavation(ClientGameTestContext ctx, TestServerContext server, String name, BlockPos origin, boolean survivalAmethyst) {
        // A solid stone mass (standing in for underground rock; the flat test world has only 3 blocks of
        // dirt above bedrock) with an open trench on the west side to stand in.
        server.runCommand(fill(origin.add(-3, -1, -3), origin.add(7, 5, 7), "minecraft:stone"));
        server.runCommand(fill(origin.add(-3, 0, 0), origin.add(-1, 3, 4), "minecraft:air"));
        server.runCommand("tp @a " + (origin.getX() - 2) + " " + origin.getY() + " " + (origin.getZ() + 2));
        if (survivalAmethyst) {
            server.runCommand("gamemode survival @a");
            server.runCommand("clear @a");
            server.runOnServer(s -> {
                ItemStack pick = new ItemStack(Items.DIAMOND_PICKAXE);
                pick.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Amethyst Pickaxe"));
                for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
                    p.getInventory().insertStack(pick.copy());
                    p.getInventory().insertStack(new ItemStack(Items.STONE_BRICKS, 32));
                    p.getInventory().insertStack(new ItemStack(Items.CRAFTING_TABLE, 1));
                }
            });
        } else {
            server.runCommand("gamemode creative @a");
        }
        ctx.waitTicks(10);
        start(ctx, "underground_room.litematic", origin, Rotation.NONE, Mirror.NONE, true,
                survivalAmethyst ? AreaMining.PickaxeMode.AMETHYST : AreaMining.PickaxeMode.REGULAR);
        boolean finished = waitFinished(ctx, survivalAmethyst ? 6000 : 2400);
        Verify v = verify(ctx, server);
        record(name, finished && v.ok(), (finished ? "finished" : "TIMED OUT") + "; server check incl. air cells " + v + "; " + summary(ctx));
        cancel(ctx);
        server.runCommand("gamemode creative @a");
    }

    private void shulkerRestock(ClientGameTestContext ctx, TestServerContext server, BlockPos origin) {
        String name = "shulker restock + junk dropping (survival, full inventory)";
        prepareSite(server, origin, 10, 4);
        teleportNear(server, origin);
        server.runCommand("gamemode survival @a");
        server.runCommand("clear @a");
        server.runOnServer(s -> {
            ItemStack box = new ItemStack(Items.SHULKER_BOX);
            box.set(DataComponentTypes.CONTAINER, ContainerComponent.fromStacks(List.of(new ItemStack(Items.STONE, 64))));
            for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
                p.getInventory().insertStack(new ItemStack(Items.DIAMOND_PICKAXE));
                p.getInventory().insertStack(box);
                for (int i = 0; i < 34; i++) p.getInventory().insertStack(new ItemStack(Items.COBBLESTONE, 64));
            }
        });
        ctx.waitTicks(10);
        start(ctx, "stone_platform.schem", origin, Rotation.NONE, Mirror.NONE, false, AreaMining.PickaxeMode.REGULAR);
        boolean finished = waitFinished(ctx, 3600);
        ctx.waitTicks(40);
        Verify v = verify(ctx, server);
        int boxesHeld = server.computeOnServer(s -> {
            int n = 0;
            for (ServerPlayerEntity p : s.getPlayerManager().getPlayerList()) {
                for (int i = 0; i < 36; i++) if (p.getInventory().getStack(i).isOf(Items.SHULKER_BOX)) n++;
            }
            return n;
        });
        int boxBlocksLeft = countBlock(server, origin.add(-6, -1, -6), 20, 4, 20, "minecraft:shulker_box");
        record(name, finished && v.ok() && boxesHeld == 1 && boxBlocksLeft == 0, (finished ? "finished" : "TIMED OUT")
                + "; server check " + v + "; shulker boxes held=" + boxesHeld + ", left in world=" + boxBlocksLeft + "; " + summary(ctx));
        cancel(ctx);
        server.runCommand("gamemode creative @a");
        server.runCommand("clear @a");
        server.runCommand("kill @e[type=item]");
    }

    private void xray(ClientGameTestContext ctx, TestServerContext server, BlockPos at) {
        String name = "X-Ray finds, forgets mined and releases on disable";
        BlockPos ore = at.add(6, -2, 0); // inside the flat world's dirt layer: enclosed
        server.runCommand("tp @a " + at.getX() + " " + at.getY() + " " + at.getZ());
        ctx.waitTicks(40); // the chunk must be loaded before the ore can be set
        server.runCommand("setblock " + ore.getX() + " " + ore.getY() + " " + ore.getZ() + " minecraft:diamond_ore");
        ctx.waitTicks(10);
        ctx.runOnClient(mc -> Atlas.get().modules.get(XRayModule.class).setEnabled(true));
        boolean found = false;
        for (int t = 0; t < 600 && !found; t += 10) {
            ctx.waitTicks(10);
            found = ctx.computeOnClient(mc -> hasXrayHit(ore));
        }
        ctx.waitTicks(5);
        ctx.takeScreenshot("atlas-xray");
        server.runCommand("setblock " + ore.getX() + " " + ore.getY() + " " + ore.getZ() + " minecraft:air");
        boolean forgotten = false;
        for (int t = 0; t < 200 && !forgotten; t += 5) {
            ctx.waitTicks(5);
            forgotten = ctx.computeOnClient(mc -> !hasXrayHit(ore));
        }
        ctx.runOnClient(mc -> Atlas.get().modules.get(XRayModule.class).setEnabled(false));
        ctx.waitTicks(2);
        boolean released = ctx.computeOnClient(mc -> AtlasClient.xray().index().size() == 0);
        record(name, found && forgotten && released, "found=" + found + ", removed after mining=" + forgotten + ", memory released=" + released);
    }

    private static boolean hasXrayHit(BlockPos p) {
        return AtlasClient.xray().index().nearest(p.getX(), p.getY(), p.getZ(), 4, 10, false, b -> true).stream()
                .anyMatch(h -> h.x() == p.getX() && h.y() == p.getY() && h.z() == p.getZ());
    }

    private void esp(ClientGameTestContext ctx, TestServerContext server, BlockPos at) {
        String name = "ESP sees mobs, items and containers";
        server.runCommand("tp @a " + at.getX() + " " + at.getY() + " " + at.getZ());
        ctx.waitTicks(40);
        server.runCommand("summon minecraft:zombie " + (at.getX() + 5) + " " + at.getY() + " " + at.getZ() + " {NoAI:1b,PersistenceRequired:1b}");
        server.runCommand("summon minecraft:item " + (at.getX() - 4) + " " + at.getY() + " " + at.getZ() + " {Item:{id:\"minecraft:diamond\",count:3}}");
        server.runCommand("setblock " + at.getX() + " " + at.getY() + " " + (at.getZ() + 5) + " minecraft:chest");
        ctx.waitTicks(20);
        ctx.runOnClient(mc -> {
            Atlas.get().modules.get(MobEspModule.class).setEnabled(true);
            Atlas.get().modules.get(ItemEspModule.class).setEnabled(true);
            Atlas.get().modules.get(ContainerEspModule.class).setEnabled(true);
        });
        ctx.waitTicks(30);
        int mobs = ctx.computeOnClient(mc -> Atlas.get().modules.get(MobEspModule.class).targetCount());
        int items = ctx.computeOnClient(mc -> Atlas.get().modules.get(ItemEspModule.class).targetCount());
        int chests = ctx.computeOnClient(mc -> Atlas.get().modules.get(ContainerEspModule.class).targetCount());
        ctx.takeScreenshot("atlas-esp");
        ctx.runOnClient(mc -> {
            Atlas.get().modules.get(MobEspModule.class).setEnabled(false);
            Atlas.get().modules.get(ItemEspModule.class).setEnabled(false);
            Atlas.get().modules.get(ContainerEspModule.class).setEnabled(false);
        });
        ctx.waitTicks(2);
        int after = ctx.computeOnClient(mc -> Atlas.get().modules.get(MobEspModule.class).targetCount());
        record(name, mobs >= 1 && items >= 1 && chests >= 1 && after == 0, "mobs=" + mobs + ", items=" + items + ", containers=" + chests
                + ", targets after disable=" + after + " (screenshot atlas-esp)");
        server.runCommand("kill @e[type=minecraft:zombie]");
        server.runCommand("kill @e[type=item]");
    }

    private void freecam(ClientGameTestContext ctx, TestServerContext server) {
        String name = "Freecam moves only the camera and restores the view";
        server.runCommand("gamemode creative @a");
        ctx.waitTicks(10);
        double[] before = server.computeOnServer(s -> {
            ServerPlayerEntity p = s.getPlayerManager().getPlayerList().get(0);
            return new double[]{p.getX(), p.getY(), p.getZ(), p.getYaw()};
        });
        boolean on = ctx.computeOnClient(mc -> Atlas.get().modules.get(FreecamModule.class).setEnabled(true)
                && FreecamController.active() && mc.getCameraEntity() != mc.player);
        ctx.getInput().holdKeyFor(o -> o.forwardKey, 40);
        ctx.getInput().holdKeyFor(o -> o.jumpKey, 20);
        ctx.waitTicks(10);
        double moved = ctx.computeOnClient(mc -> {
            var c = FreecamController.position();
            return c == null ? 0 : Math.sqrt(c.squaredDistanceTo(mc.player.getEyePos()));
        });
        ctx.getInput().pressMouse(0); // attack from the camera: must not break anything
        ctx.waitTicks(5);
        double[] during = server.computeOnServer(s -> {
            ServerPlayerEntity p = s.getPlayerManager().getPlayerList().get(0);
            return new double[]{p.getX(), p.getY(), p.getZ(), p.getYaw()};
        });
        ctx.takeScreenshot("atlas-freecam");
        ctx.runOnClient(mc -> Atlas.get().modules.get(FreecamModule.class).setEnabled(false));
        ctx.waitTicks(5);
        boolean restored = ctx.computeOnClient(mc -> !FreecamController.active() && mc.getCameraEntity() == mc.player);
        double drift = Math.abs(during[0] - before[0]) + Math.abs(during[1] - before[1]) + Math.abs(during[2] - before[2]);
        record(name, on && moved > 3 && drift < 0.01 && restored, String.format(Locale.ROOT,
                "enabled=%s, camera moved %.1f blocks, player drift on server %.3f, view restored=%s", on, moved, drift, restored));
    }

    private void persistence(ClientGameTestContext ctx) {
        String name = "settings persist through save and reload";
        boolean ok = ctx.computeOnClient(mc -> {
            XRayModule x = Atlas.get().modules.get(XRayModule.class);
            var opacity = (dev.atlasclient.core.setting.Settings.NumberSetting) x.setting("Opacity");
            opacity.set(0.8);
            Atlas.get().config.markDirty();
            Atlas.get().config.flushIfDirty();
            opacity.set(0.1);
            Atlas.get().config.loadAll();
            return Math.abs(opacity.get() - 0.8) < 1e-9;
        });
        record(name, ok, ok ? "X-Ray opacity reloaded as saved" : "value not restored");
    }

    // ------------------------------------------------------------------ helpers

    private static void start(ClientGameTestContext ctx, String file, BlockPos origin, Rotation rot, Mirror mirror, boolean excavate,
                              AreaMining.PickaxeMode mode) {
        ctx.runOnClient(mc -> {
            AutoBuilderModule ab = AutoBuilderModule.get();
            Path f = FabricLoader.getInstance().getGameDir().resolve("schematics").resolve(file);
            try {
                ab.session.load(f, origin.getX(), origin.getY(), origin.getZ());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            ab.session.setRotation(rot);
            ab.session.setMirror(mirror);
            ab.excavate.set(excavate);
            ab.pickaxeMode.set(mode);
            ab.start();
            if (ab.job() == null) {
                throw new AssertionError("AutoBuilder did not start for " + file);
            }
        });
    }

    private static boolean waitFinished(ClientGameTestContext ctx, int timeoutTicks) {
        for (int t = 0; t < timeoutTicks; t += 5) {
            boolean done = ctx.computeOnClient(mc -> {
                BuildJob j = AutoBuilderModule.get().job();
                return j != null && j.phase() == BuildJob.Phase.FINISHED;
            });
            if (done) return true;
            ctx.waitTicks(5);
        }
        return false;
    }

    private static int progressDone(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            BuildJob j = AutoBuilderModule.get().job();
            return j == null ? 0 : j.build().progress().done();
        });
    }

    private static String summary(ClientGameTestContext ctx) {
        return ctx.computeOnClient(mc -> {
            BuildJob j = AutoBuilderModule.get().job();
            if (j == null) return "no job";
            BuildSession.Progress p = j.build().progress();
            String ex = j.excavation() == null ? "" : ", dug " + j.excavation().progress().done() + "/" + j.excavation().progress().total();
            return String.format(Locale.ROOT, "client: %d placed, %d already correct, %d failed, %d skipped%s",
                    p.done(), p.alreadyCorrect(), p.failed(), p.skipped(), ex);
        });
    }

    private static void cancel(ClientGameTestContext ctx) {
        ctx.runOnClient(mc -> AutoBuilderModule.get().cancel());
        ctx.waitTicks(5);
    }

    /** Compares every cell of the loaded placement (air included when excavating) against the server world. */
    private static Verify verify(ClientGameTestContext ctx, TestServerContext server) {
        record Want(BlockPos pos, dev.atlasclient.core.schematic.BlockStateString state,
                    dev.atlasclient.core.schematic.transform.Transforms.StateTransform transform) { }
        List<Want> wants = ctx.computeOnClient(mc -> {
            AutoBuilderModule ab = AutoBuilderModule.get();
            List<SchematicPlacer.PlacedBlock> src = ab.excavate.get() ? ab.session.blocksIncludingAir() : ab.session.blocks();
            List<Want> out = new ArrayList<>(src.size());
            for (SchematicPlacer.PlacedBlock b : src) out.add(new Want(new BlockPos(b.x(), b.y(), b.z()), b.state(), b.transform()));
            return out;
        });
        return server.computeOnServer(s -> {
            ServerWorld w = s.getOverworld();
            StateResolver resolver = new StateResolver();
            int ok = 0, wrongBlock = 0, wrongState = 0;
            String first = "";
            for (Want want : wants) {
                BlockState expected = resolver.resolve(want.state(), want.transform());
                BlockState actual = w.getBlockState(want.pos());
                if (expected == null) {
                    wrongBlock++;
                    if (first.isEmpty()) first = "unknown state " + want.state();
                    continue;
                }
                StateResolver.Match m = StateResolver.compare(actual, expected);
                if (m == StateResolver.Match.SAME) {
                    ok++;
                } else {
                    if (m == StateResolver.Match.DIFFERENT_BLOCK) wrongBlock++; else wrongState++;
                    if (first.isEmpty()) first = want.pos().toShortString() + " wants " + expected + " has " + actual;
                }
            }
            return new Verify(wants.size(), ok, wrongBlock, wrongState, first);
        });
    }

    private static void prepareSite(TestServerContext server, BlockPos origin, int size, int height) {
        server.runCommand(fill(origin.add(-2, 0, -2), origin.add(size, height, size), "minecraft:air"));
    }

    private static void teleportNear(TestServerContext server, BlockPos origin) {
        server.runCommand("tp @a " + (origin.getX() - 2) + " " + origin.getY() + " " + (origin.getZ() - 2));
    }

    private static String fill(BlockPos a, BlockPos b, String block) {
        return "fill " + a.getX() + " " + a.getY() + " " + a.getZ() + " " + b.getX() + " " + b.getY() + " " + b.getZ() + " " + block;
    }

    private static int countBlock(TestServerContext server, BlockPos origin, int sx, int sy, int sz, String id) {
        return server.computeOnServer(s -> {
            ServerWorld w = s.getOverworld();
            int n = 0;
            for (BlockPos p : BlockPos.iterate(origin, origin.add(sx - 1, sy - 1, sz - 1))) {
                if (net.minecraft.registry.Registries.BLOCK.getId(w.getBlockState(p).getBlock()).toString().equals(id)) n++;
            }
            return n;
        });
    }

    private static int countNonAir(TestServerContext server, BlockPos origin, int sx, int sy, int sz) {
        return server.computeOnServer(s -> {
            ServerWorld w = s.getOverworld();
            int n = 0;
            for (BlockPos p : BlockPos.iterate(origin, origin.add(sx - 1, sy - 1, sz - 1))) {
                if (!w.getBlockState(p).isAir()) n++;
            }
            return n;
        });
    }

    private void record(String name, boolean pass, String detail) {
        if (!pass && blocking) failures++;
        String line = (pass ? "PASS  " : blocking ? "FAIL  " : "FAIL (AutoBuilder, not blocking)  ") + name + " - " + detail;
        report.add(line);
        System.out.println("[Atlas gametest] " + line);
    }

    private void writeReport() {
        try {
            Files.write(FabricLoader.getInstance().getGameDir().resolve("atlas-gametest-report.txt"), report);
        } catch (IOException e) {
            System.out.println("[Atlas gametest] could not write report: " + e);
        }
    }
}
