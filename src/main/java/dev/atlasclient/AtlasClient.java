package dev.atlasclient;

import dev.atlasclient.command.AtlasCommands;
import dev.atlasclient.core.event.CoreEvents;
import dev.atlasclient.core.module.Module;
import dev.atlasclient.core.setting.Keybind;
import dev.atlasclient.core.util.SessionLifecycle;
import dev.atlasclient.gui.DiscoveriesScreen;
import dev.atlasclient.hud.HudRenderer;
import dev.atlasclient.module.building.AutoBuilderModule;
import dev.atlasclient.module.building.MaterialChecklistModule;
import dev.atlasclient.module.building.SchematicPreviewModule;
import dev.atlasclient.module.client.DiagnosticsModule;
import dev.atlasclient.module.client.GuiSettingsModule;
import dev.atlasclient.module.client.KeybindsModule;
import dev.atlasclient.module.client.PerformanceModule;
import dev.atlasclient.module.client.ThemeModule;
import dev.atlasclient.module.movement.AuthorizedFlightModule;
import dev.atlasclient.module.movement.NavigationAssistModule;
import dev.atlasclient.module.render.BlockHighlightModule;
import dev.atlasclient.module.render.ChunkOutlinesModule;
import dev.atlasclient.module.render.DetectionLabelsModule;
import dev.atlasclient.module.render.WaypointsModule;
import dev.atlasclient.module.utility.ConfigProfilesModule;
import dev.atlasclient.module.utility.NotificationsModule;
import dev.atlasclient.module.utility.SavedDiscoveriesModule;
import dev.atlasclient.module.world.AncientDebrisFinderModule;
import dev.atlasclient.module.world.BaseFinderModule;
import dev.atlasclient.module.world.SuspiciousChunkAnalyzerModule;
import dev.atlasclient.render.AtlasRenderLayers;
import dev.atlasclient.render.WorldOverlayRenderer;
import dev.atlasclient.scan.ScanService;
import dev.atlasclient.util.Env;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;

/**
 * Client entrypoint. Wires services, modules and Fabric events. Uses only public Fabric API
 * events: no mixins, no networking, no telemetry.
 */
public final class AtlasClient implements ClientModInitializer {
    private static ScanService scan;
    private static volatile boolean running;
    private final WorldOverlayRenderer overlay = new WorldOverlayRenderer();
    private final HudRenderer hud = new HudRenderer();
    private KeyBinding discoveriesKey;
    private KeyBinding pauseKey;
    private KeyBinding pickaxeKey;
    private static dev.atlasclient.scan.XrayScanner xray;
    private static dev.atlasclient.scan.XrayScanner blockEsp;
    private long ticks;
    /** World key of the session in progress ("" when not connected), used to tell dimension changes apart. */
    private String sessionKey = "";

    public static ScanService scan() { return scan; }

    public static dev.atlasclient.scan.XrayScanner xray() { return xray; }

    /** False once the client is shutting down (background jobs check this). */
    public static boolean running() { return running; }

    @Override
    public void onInitializeClient() {
        running = true;
        Atlas atlas = new Atlas(FabricLoader.getInstance().getConfigDir());
        scan = new ScanService();
        xray = new dev.atlasclient.scan.XrayScanner();
        blockEsp = new dev.atlasclient.scan.XrayScanner();
        AtlasRenderLayers.init();
        Keybind.setNameResolver(code -> InputUtil.Type.KEYSYM.createFromCode(code).getLocalizedText().getString());

        // World
        atlas.modules.register(new SuspiciousChunkAnalyzerModule(scan));
        atlas.modules.register(new BaseFinderModule(scan));
        atlas.modules.register(new AncientDebrisFinderModule(scan));
        // Render
        atlas.modules.register(new BlockHighlightModule());
        atlas.modules.register(new ChunkOutlinesModule());
        atlas.modules.register(new WaypointsModule());
        atlas.modules.register(new DetectionLabelsModule());
        atlas.modules.register(new dev.atlasclient.module.render.XRayModule(xray));
        atlas.modules.register(new dev.atlasclient.module.render.PlayerEspModule());
        atlas.modules.register(new dev.atlasclient.module.render.MobEspModule());
        atlas.modules.register(new dev.atlasclient.module.render.ItemEspModule());
        atlas.modules.register(new dev.atlasclient.module.render.ContainerEspModule());
        atlas.modules.register(new dev.atlasclient.module.render.BlockEspModule(blockEsp));
        // Camera
        atlas.modules.register(new dev.atlasclient.module.camera.FreecamModule());
        atlas.modules.register(new dev.atlasclient.module.camera.CameraSpeedModule());
        atlas.modules.register(new dev.atlasclient.module.camera.CameraSettingsModule());
        // Building
        atlas.modules.register(new dev.atlasclient.module.building.AutoMineModule());
        atlas.modules.register(new AutoBuilderModule());
        atlas.modules.register(new SchematicPreviewModule());
        atlas.modules.register(new MaterialChecklistModule());
        // Movement
        atlas.modules.register(new AuthorizedFlightModule());
        atlas.modules.register(new NavigationAssistModule());
        // Utility
        atlas.modules.register(new NotificationsModule());
        atlas.modules.register(new SavedDiscoveriesModule());
        atlas.modules.register(new ConfigProfilesModule());
        // Client
        atlas.modules.register(new GuiSettingsModule());
        atlas.modules.register(new ThemeModule());
        atlas.modules.register(new KeybindsModule());
        atlas.modules.register(new PerformanceModule());
        atlas.modules.register(new DiagnosticsModule());

        // Per-world state is cleared in this order: modules first (they may save), then caches.
        atlas.lifecycle.register(new SessionLifecycle.Resettable() {
            @Override public String resetName() { return "modules"; }
            @Override public void reset(CoreEvents.SessionReset.Reason reason) { atlas.modules.sessionReset(); }
        });
        atlas.lifecycle.register(scan);
        atlas.lifecycle.register(xray);
        atlas.lifecycle.register(blockEsp);
        atlas.lifecycle.register(new SessionLifecycle.Resettable() {
            @Override public String resetName() { return "esp-glow"; }
            @Override public void reset(CoreEvents.SessionReset.Reason reason) { dev.atlasclient.render.EspGlow.clear(); }
        });
        atlas.lifecycle.register(new SessionLifecycle.Resettable() {
            @Override public String resetName() { return "keybinds"; }
            @Override public void reset(CoreEvents.SessionReset.Reason reason) { atlas.keybinds.releaseAll(); }
        });

        atlas.config.loadAll();
        atlas.modules.get(NotificationsModule.class).apply();
        atlas.modules.get(SuspiciousChunkAnalyzerModule.class).apply();
        GuiSettingsModule gs = atlas.modules.get(GuiSettingsModule.class);
        gs.syncKey();
        gs.openKey.onChange(k -> gs.syncKey());
        gs.espKey.onChange(k -> gs.syncKey());
        applyEasyStartKeys(atlas);
        for (Module m : atlas.modules.all()) {
            for (var s : m.settings()) {
                s.onChange(v -> atlas.config.markDirty());
            }
            m.keybind().onChange(v -> atlas.config.markDirty());
        }
        atlas.bus.subscribe(CoreEvents.ModuleToggled.class, e -> atlas.config.markDirty());

        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(Atlas.MOD_ID, "main"));
        discoveriesKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.atlasclient.discoveries", InputUtil.UNKNOWN_KEY.getCode(), category));
        pauseKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.atlasclient.build_pause", InputUtil.UNKNOWN_KEY.getCode(), category));
        pickaxeKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.atlasclient.pickaxe_mode", InputUtil.UNKNOWN_KEY.getCode(), category));

        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            if (dev.atlasclient.camera.FreecamController.active()) {
                var speed = atlas.modules.get(dev.atlasclient.module.camera.CameraSpeedModule.class);
                var cam = atlas.modules.get(dev.atlasclient.module.camera.CameraSettingsModule.class);
                dev.atlasclient.camera.FreecamController.preTick(mc, speed.tuning(cam));
            } else if (mc.player != null) {
                atlas.modules.get(AuthorizedFlightModule.class).preTick(mc);
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(this::endTick);
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            scan.onChunkLoad(chunk);
            xray.onChunkLoad(chunk);
            blockEsp.onChunkLoad(chunk);
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            scan.onChunkUnload(chunk);
            xray.onChunkUnload(chunk);
            blockEsp.onChunkUnload(chunk);
        });
        // Schematic placement tool (birch plank by default): move/rotate/mirror/build with clicks.
        dev.atlasclient.build.PlacementTool.register();
        ClientTickEvents.END_CLIENT_TICK.register(dev.atlasclient.build.PlacementTool::tick);
        // Blocks you break: anti-xray re-sends the real blocks around them, so the debris finder can trust those.
        net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents.AFTER.register((world, player, pos, state) -> {
            atlas.modules.get(AncientDebrisFinderModule.class).onBlockBroken(pos.getX(), pos.getY(), pos.getZ());
            xray.onBlockBroken(pos.getX(), pos.getZ());
            blockEsp.onBlockBroken(pos.getX(), pos.getZ());
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> client.execute(() -> {
            sessionKey = Env.worldKey();
            BaseFinderModule.refreshScanWants();
            showStartHint(atlas);
        }));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            reset(CoreEvents.SessionReset.Reason.DISCONNECT);
            sessionKey = "";
        }));
        ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register((client, world) -> {
            String key = Env.worldKey();
            boolean sameServer = !key.isEmpty() && key.equals(sessionKey);
            sessionKey = key;
            reset(sameServer ? CoreEvents.SessionReset.Reason.DIMENSION_CHANGE : CoreEvents.SessionReset.Reason.WORLD_CHANGE);
            BaseFinderModule.refreshScanWants();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            running = false;
            reset(CoreEvents.SessionReset.Reason.SHUTDOWN);
            atlas.config.markDirty();
            atlas.config.flushIfDirty();
            scan.shutdown();
            xray.shutdown();
            blockEsp.shutdown();
        });
        WorldRenderEvents.BEFORE_TRANSLUCENT.register(overlay::render);
        // Once per frame: the detached camera follows the mouse.
        WorldRenderEvents.END_EXTRACTION.register(context -> dev.atlasclient.camera.FreecamController.frame(MinecraftClient.getInstance()));
        HudElementRegistry.addLast(Identifier.of(Atlas.MOD_ID, "hud"), hud::render);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> AtlasCommands.register(dispatcher));

        Atlas.LOG.info("Atlas Client initialized: {} modules, config at {}", atlas.modules.size(), atlas.rootDir);
    }

    private void reset(CoreEvents.SessionReset.Reason reason) {
        Atlas atlas = Atlas.get();
        var failures = atlas.lifecycle.reset(reason, Env.worldKey());
        atlas.bus.post(new CoreEvents.SessionReset(reason, Env.worldKey()));
        if (!failures.isEmpty()) {
            atlas.notifications.error("Cleanup", failures.size() + " components failed to reset; see log");
        }
    }

    private void endTick(MinecraftClient mc) {
        Atlas atlas = Atlas.get();
        ticks++;
        long start = System.nanoTime();
        boolean blocked = mc.currentScreen != null;
        // A key counts when it is held now or was tapped since the last tick (KeyboardMixin).
        atlas.keybinds.poll(key -> key >= 0 && (dev.atlasclient.input.KeyTaps.consume(key) | InputUtil.isKeyPressed(mc.getWindow(), key)), blocked);
        dev.atlasclient.input.KeyTaps.clear();
        while (discoveriesKey.wasPressed()) {
            mc.setScreen(new DiscoveriesScreen(null));
        }
        while (pauseKey.wasPressed()) {
            atlas.modules.get(AutoBuilderModule.class).togglePause();
        }
        while (pickaxeKey.wasPressed()) {
            AutoBuilderModule ab = atlas.modules.get(AutoBuilderModule.class);
            ab.setPickaxeMode(ab.pickaxeMode.get() == dev.atlasclient.core.build.AreaMining.PickaxeMode.REGULAR
                    ? dev.atlasclient.core.build.AreaMining.PickaxeMode.AMETHYST : dev.atlasclient.core.build.AreaMining.PickaxeMode.REGULAR);
        }
        atlas.modules.tick();
        dev.atlasclient.render.EspGlow.commit();
        PerformanceModule perf = atlas.modules.get(PerformanceModule.class);
        boolean lowFps = perf.pauseScanWhenLowFps.get() && mc.getCurrentFps() < perf.minFps.intValue() && ticks > 200;
        // Low FPS slows the scan down (one chunk every other tick) instead of stopping it: on a slow
        // computer Base Finder must still work, just more gently.
        scan.setLowFps(lowFps);
        scan.tick(mc);
        if (ticks % (perf.autosaveSeconds.intValue() * 20L) == 0) {
            atlas.config.flushIfDirty();
        }
        atlas.perf.record("tick/total", System.nanoTime() - start);
    }

    /**
     * Older configs saved every module key as "none". Give the main features their default keys
     * once (only where that key is still free), so they work without opening any menu.
     */
    private static void applyEasyStartKeys(Atlas atlas) {
        com.google.gson.JsonObject ui = atlas.config.uiPrefs();
        if (ui.has("easyStartKeys")) return;
        ui.addProperty("easyStartKeys", 1);
        int[][] defaults = {{0, dev.atlasclient.module.render.XRayModule.DEFAULT_KEY}, {1, dev.atlasclient.module.camera.FreecamModule.DEFAULT_KEY},
                {2, dev.atlasclient.module.building.AutoMineModule.DEFAULT_KEY}};
        Module[] targets = {atlas.modules.get(dev.atlasclient.module.render.XRayModule.class),
                atlas.modules.get(dev.atlasclient.module.camera.FreecamModule.class),
                atlas.modules.get(dev.atlasclient.module.building.AutoMineModule.class)};
        for (int[] d : defaults) {
            Module m = targets[d[0]];
            if (m.keybind().isBound()) continue;
            boolean used = false;
            for (Module o : atlas.modules.all()) if (o.keybind().key() == d[1]) used = true;
            if (!used) m.keybind().set(d[1]);
        }
        atlas.config.saveClientFile();
        atlas.config.markDirty();
    }

    private static boolean hintShown;

    /** One short line on the first world join of each game start: how to open the menu and the main keys. */
    static void showStartHint(Atlas atlas) {
        if (hintShown) return;
        hintShown = true;
        GuiSettingsModule gs = atlas.modules.get(GuiSettingsModule.class);
        StringBuilder sb = new StringBuilder(dev.atlasclient.core.setting.Keybind.name(gs.openKey.key()) + " = menu");
        Module[] keyed = {atlas.modules.get(dev.atlasclient.module.render.XRayModule.class),
                atlas.modules.get(dev.atlasclient.module.camera.FreecamModule.class),
                atlas.modules.get(dev.atlasclient.module.building.AutoMineModule.class)};
        for (Module m : keyed) {
            if (m.keybind().isBound()) sb.append(", ").append(dev.atlasclient.core.setting.Keybind.name(m.keybind().key())).append(" = ").append(m.name());
        }
        if (gs.espKey.isBound()) sb.append(", ").append(dev.atlasclient.core.setting.Keybind.name(gs.espKey.key())).append(" = ESP");
        atlas.notifications.info("Atlas Client", sb.toString());
    }
}
