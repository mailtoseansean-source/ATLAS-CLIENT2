package dev.atlasclient.module.world;

import dev.atlasclient.AtlasClient;
import dev.atlasclient.core.debris.DataAvailability;
import dev.atlasclient.core.debris.DebrisRecord;
import dev.atlasclient.core.debris.DebrisTracker;
import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.region.LocalWorldAnalyzer;
import dev.atlasclient.core.region.RegionScanner;
import dev.atlasclient.core.render.OverlayCuller;
import dev.atlasclient.core.setting.Settings.ActionSetting;
import dev.atlasclient.core.setting.Settings.BooleanSetting;
import dev.atlasclient.core.setting.Settings.ColorSetting;
import dev.atlasclient.core.setting.Settings.EnumSetting;
import dev.atlasclient.core.setting.Settings.NumberSetting;
import dev.atlasclient.module.AtlasModule;
import dev.atlasclient.scan.BlockInfo;
import dev.atlasclient.scan.ScanService;
import dev.atlasclient.util.Env;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.WorldSavePath;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Detects ancient debris in block data the client has received. It does not use the X-Ray module.
 *
 * <p>On servers with anti-xray, debris buried in netherrack is either hidden or replaced by fake
 * ores, and no client can see through that. What anti-xray never hides is debris that touches air
 * (exposed) and debris next to blocks you mined (the server sends the real blocks around every
 * block you break). Those are always shown as real; buried debris is only shown when the data
 * looks natural, or as "may be fake" if you ask for it. Mining a tunnel at Y 13-17 (Auto Mine)
 * is how you reveal more. In single-player it can also scan the owned save's region files.</p>
 */
public final class AncientDebrisFinderModule extends AtlasModule {
    private final ScanService scan;
    final NumberSetting range = add(new NumberSetting("Range", "Only show debris within this distance", 64, 8, 512, 8));
    final BooleanSetting exposedOnly = add(new BooleanSetting("Exposed only", "Only show debris touching air or next to blocks you mined", false));
    final BooleanSetting notify = add(new BooleanSetting("Notify", "Notify when new debris is discovered", true));
    final BooleanSetting showWhenUntrusted = add(new BooleanSetting("Show possibly fake", "On servers also show buried debris nobody has uncovered yet (may be anti-xray fakes)", false));
    final ColorSetting exposedColor = add(new ColorSetting("Exposed color", "Debris with a visible face", 0xFFFF7A1A));
    final ColorSetting enclosedColor = add(new ColorSetting("Enclosed color", "Fully enclosed debris", 0xFFB24A1A));
    final NumberSetting maxBoxes = add(new NumberSetting("Max markers", "Rendering cap (nearest first)", 256, 16, 2048, 16));
    final EnumSetting<DebrisTracker.ExportFormat> format = add(new EnumSetting<>("Export format", "File format for exports", DebrisTracker.ExportFormat.CSV));
    final ActionSetting export = add(new ActionSetting("Export", "Write discoveries to config/atlasclient/exports", this::exportNow));
    final NumberSetting localRadius = add(new NumberSetting("Local scan radius", "Chunks around you for 'Scan saved world' (single-player only)", 16, 2, 128, 2));
    final ActionSetting scanSave = add(new ActionSetting("Scan saved world", "Single-player: read this world's region files (Nether)", this::scanSavedWorld));
    private final AtomicBoolean localScanRunning = new AtomicBoolean();
    /** Blocks you broke: anti-xray re-sends the real blocks within 2 of each (Paper's default update radius). */
    private final java.util.LinkedHashSet<Long> mined = new java.util.LinkedHashSet<>();
    private final java.util.ArrayDeque<long[]> rescans = new java.util.ArrayDeque<>();
    private static final int REVEAL_RADIUS = 2;
    private long ticks;

    public AncientDebrisFinderModule(ScanService scan) {
        super("Ancient Debris Finder", "Marks ancient debris present in client-received block data", Category.WORLD);
        this.scan = scan;
        scan.onNewDebris(this::onNew);
    }

    @Override protected void onEnable() { BaseFinderModule.refreshScanWants(); }

    @Override protected void onDisable() { BaseFinderModule.refreshScanWants(); }

    public DataAvailability availability() { return scan.debris().availability(Env.dimension()); }

    /** Called for every block the player breaks (by hand or by Auto Mine). */
    public void onBlockBroken(int x, int y, int z) {
        long key = net.minecraft.util.math.BlockPos.asLong(x, y, z);
        mined.remove(key);
        mined.add(key);
        if (mined.size() > 20_000) {
            var it = mined.iterator();
            it.next();
            it.remove();
        }
        // The server's reveal packets arrive a moment later: re-read this chunk after 1 and 3 seconds.
        rescans.add(new long[]{x, z, ticks + 20});
        rescans.add(new long[]{x, z, ticks + 60});
        while (rescans.size() > 512) rescans.poll();
    }

    /**
     * Debris anti-xray cannot fake: touching air, or within the reveal radius of a block you broke.
     * In single-player (your own world) everything is real. On a server, buried debris is never
     * treated as real, even when the data looks natural: some anti-xray setups send only a few fakes.
     */
    public boolean real(DebrisRecord r, DataAvailability av) {
        if (av == DataAvailability.AUTHORITATIVE_LOCAL || r.exposure() == DebrisRecord.Exposure.EXPOSED) return true;
        return revealed(r);
    }

    private boolean revealed(DebrisRecord r) {
        if (mined.isEmpty()) return false;
        for (int dx = -REVEAL_RADIUS; dx <= REVEAL_RADIUS; dx++) {
            for (int dy = -REVEAL_RADIUS; dy <= REVEAL_RADIUS; dy++) {
                for (int dz = -REVEAL_RADIUS; dz <= REVEAL_RADIUS; dz++) {
                    if (mined.contains(net.minecraft.util.math.BlockPos.asLong(r.x() + dx, r.y() + dy, r.z() + dz))) return true;
                }
            }
        }
        return false;
    }

    /** What is drawn: real debris always; buried debris on anti-xray servers only when asked for. */
    public List<DebrisRecord> shown(double x, double y, double z) {
        DataAvailability av = availability();
        List<DebrisRecord> out = new java.util.ArrayList<>();
        for (DebrisRecord r : scan.debris().query(Env.dimension(), x, y, z, range.get(), false)) {
            boolean real = real(r, av);
            boolean openFace = r.exposure() == DebrisRecord.Exposure.EXPOSED || revealed(r);
            if (!real && !showWhenUntrusted.get()) continue;
            if (exposedOnly.get() && !openFace) continue;
            out.add(r);
        }
        return out;
    }

    private void onNew(List<DebrisRecord> added) {
        if (!isEnabled() || !notify.get() || added.isEmpty()) {
            return;
        }
        DataAvailability av = availability();
        DebrisRecord first = null;
        int real = 0;
        for (DebrisRecord r : added) {
            if (real(r, av)) {
                real++;
                if (first == null) first = r;
            }
        }
        if (first == null) {
            return; // only buried debris on an anti-xray server: may be fake, not announced
        }
        discovery("Ancient debris discovered", String.format(Locale.ROOT, "%d new - nearest at %d %d %d (%s)",
                real, first.x(), first.y(), first.z(), first.exposure() == DebrisRecord.Exposure.EXPOSED ? "exposed" : "revealed by mining"));
    }

    @Override public void onTick() {
        ticks++;
        while (!rescans.isEmpty() && rescans.peek()[2] <= ticks) {
            long[] r = rescans.poll();
            scan.invalidateAround((int) r[0], (int) r[1], 0);
        }
        DataAvailability av = availability();
        var p = MinecraftClient.getInstance().player;
        int shownCount = p == null ? 0 : shown(p.getX(), p.getY(), p.getZ()).size();
        setStatus(av == DataAvailability.AUTHORITATIVE_LOCAL || av == DataAvailability.NO_DATA || av == DataAvailability.NOT_IN_NETHER
                ? shownCount + " shown; " + av.message()
                : shownCount + " confirmed real (touching air or next to blocks you mined); buried ones hidden because they may be fake."
                        + (av.resultsTrustworthy() ? "" : " Anti-xray detected.") + " Mine at Y 13-17 to reveal more");
    }

    @Override
    public void collectOverlay(Frame f, List<OverlayCuller.Box> out) {
        DataAvailability av = availability();
        int n = 0;
        for (DebrisRecord r : shown(f.camX(), f.camY(), f.camZ())) {
            if (n++ >= maxBoxes.intValue()) {
                break;
            }
            boolean real = real(r, av);
            int color = r.exposure() == DebrisRecord.Exposure.EXPOSED ? exposedColor.argb() : enclosedColor.argb();
            String label = n <= 8 ? String.format(Locale.ROOT, "Debris %.0fm%s", r.distanceTo(f.camX(), f.camY(), f.camZ()),
                    real ? "" : " (may be fake)") : null;
            out.add(new OverlayCuller.Box(r.x(), r.y(), r.z(), r.x() + 1, r.y() + 1, r.z() + 1, color, label, 2));
        }
    }

    @Override
    public int renderHud(DrawContext ctx, int x, int y, float tickDelta) {
        var tr = MinecraftClient.getInstance().textRenderer;
        DataAvailability av = availability();
        var p = MinecraftClient.getInstance().player;
        int shownCount = p == null ? 0 : shown(p.getX(), p.getY(), p.getZ()).size();
        int color = av == DataAvailability.AUTHORITATIVE_LOCAL ? 0xFFFFB070 : 0xFFFFD060;
        String text = av == DataAvailability.AUTHORITATIVE_LOCAL ? "Debris: " + shownCount + " (your world)"
                : av == DataAvailability.NO_DATA || av == DataAvailability.NOT_IN_NETHER ? "Debris: " + av.message()
                : "Debris: " + shownCount + " confirmed real (buried ones hidden: may be fake)";
        if (text.length() > 90) {
            text = text.substring(0, 87) + "...";
        }
        ctx.drawTextWithShadow(tr, text, x, y, color);
        return 10;
    }

    private void exportNow() {
        var p = MinecraftClient.getInstance().player;
        if (p == null) {
            return;
        }
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String ext = format.get() == DebrisTracker.ExportFormat.CSV ? "csv" : "json";
        Path file = atlas().exportsDir().resolve("debris-" + Env.worldKey().replaceAll("[^A-Za-z0-9._-]", "_") + "-" + stamp + "." + ext);
        try {
            int n = scan.debris().export(file, Env.dimension(), format.get(), p.getX(), p.getY(), p.getZ());
            success("Debris exported", n + " records -> " + file.getFileName());
        } catch (Exception e) {
            error("Export failed", e.getMessage());
        }
    }

    /** Single-player only: reads the owned save's Nether region files on a background thread. */
    private void scanSavedWorld() {
        MinecraftClient mc = MinecraftClient.getInstance();
        IntegratedServer server = mc.getServer();
        if (server == null || mc.player == null) {
            warn("Scan saved world", "Only available in single-player (your own save files).");
            return;
        }
        if (!Env.inNether()) {
            warn("Scan saved world", "Stand in the Nether: debris only generates there.");
            return;
        }
        if (!localScanRunning.compareAndSet(false, true)) {
            warn("Scan saved world", "A scan is already running.");
            return;
        }
        Path worldDir = server.getSavePath(WorldSavePath.ROOT);
        Path regionDir = RegionScanner.regionDir(worldDir, "minecraft:the_nether");
        int cx = mc.player.getBlockPos().getX() >> 4, cz = mc.player.getBlockPos().getZ() >> 4;
        int radius = localRadius.intValue();
        String dim = Env.dimension();
        info("Scan saved world", "Reading region files within " + radius + " chunks. Chunks not yet saved by the game are skipped.");
        Thread t = new Thread(() -> {
            try {
                LocalWorldAnalyzer analyzer = new LocalWorldAnalyzer(BlockInfo.registry());
                long now = System.currentTimeMillis();
                int[] total = {0};
                RegionScanner.Stats stats = analyzer.scanDebris(regionDir, cx, cz, radius, chunk -> {
                    scan.debris().observeChunk(dim, chunk.chunkX(), chunk.chunkZ(), chunk.found(), chunk.naturalOres(),
                            chunk.sections(), DebrisRecord.Source.LOCAL_SAVE_FILE, now);
                    total[0] += chunk.found().size();
                }, () -> !AtlasClient.running());
                mc.execute(() -> success("Saved world scanned", String.format(Locale.ROOT, "%d debris in %d chunks (%d skipped)%s",
                        total[0], stats.chunksDecoded(), stats.chunksSkipped(), stats.problems().isEmpty() ? "" : "; " + stats.problems().get(0))));
            } catch (RuntimeException e) {
                mc.execute(() -> error("Scan saved world", e.toString()));
            } finally {
                localScanRunning.set(false);
            }
        }, "Atlas-LocalWorldScan");
        t.setDaemon(true);
        t.start();
    }

    @Override public void onSessionReset() {
        // Keep a record of this session's finds before the tracker is cleared.
        var p = MinecraftClient.getInstance().player;
        String key = Env.worldKey();
        if (!key.isEmpty() && scan.debris().count(Env.dimension()) > 0) {
            try {
                scan.debris().export(atlas().worldDir(key).resolve("debris-last-session.json"), Env.dimension(),
                        DebrisTracker.ExportFormat.JSON, p == null ? 0 : p.getX(), p == null ? 0 : p.getY(), p == null ? 0 : p.getZ());
            } catch (Exception e) {
                dev.atlasclient.Atlas.LOG.warn("Could not save debris session file", e);
            }
        }
    }
}
