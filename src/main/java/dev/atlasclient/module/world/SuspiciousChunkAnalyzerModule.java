package dev.atlasclient.module.world;

import dev.atlasclient.core.analysis.Indicator;
import dev.atlasclient.core.analysis.ScoringConfig;
import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.setting.Settings.BooleanSetting;
import dev.atlasclient.core.setting.Settings.NumberSetting;
import dev.atlasclient.module.AtlasModule;
import dev.atlasclient.scan.ScanService;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * The analysis engine behind the Base Finder: scan budget, range, rescan interval and the weighted
 * scoring algorithm. Enabling it starts incremental scanning of loaded chunks.
 */
public final class SuspiciousChunkAnalyzerModule extends AtlasModule {
    private final ScanService scan;
    final NumberSetting budget = add(new NumberSetting("Chunks per tick", "Scan budget: chunks snapshotted per client tick", 4, 1, 16, 1));
    final NumberSetting radius = add(new NumberSetting("Scan radius (0 = range)", "Chunks around you to check; 0 = Base Finder's Range (capped by what is loaded)", 0, 0, 64, 1));
    final NumberSetting every = add(new NumberSetting("Scan every (ticks)", "How often new chunks are picked up (1 = every tick)", 1, 1, 20, 1));
    final NumberSetting cpuBudget = add(new NumberSetting("CPU budget (ms)", "Most client-thread time per tick spent copying chunks for analysis", 3, 0.5, 20, 0.5));
    final NumberSetting fpsFloor = add(new NumberSetting("Slow down below FPS", "Scan less while the game runs below this many FPS (0 = never)", 30, 0, 240, 5));
    final NumberSetting geometryWeight = add(new NumberSetting("Shape weight", "Weight of underground rooms, tunnels and dug-out areas", 1, 0, 3, 0.1));
    final NumberSetting layoutWeight = add(new NumberSetting("Layout weight", "Weight of walls, floors, light spacing, symmetry, storage rows", 1, 0, 3, 0.1));
    final NumberSetting rescan = add(new NumberSetting("Rescan seconds", "Re-analyse loaded chunks after this many seconds", 45, 5, 600, 5));
    final NumberSetting scale = add(new NumberSetting("Score scale", "Higher = harder to reach high scores", 30, 5, 100, 1));
    final NumberSetting amethystWeight = add(new NumberSetting("Amethyst weight", "Weight of geode growth inferences", 8, 0, 30, 0.5));
    final NumberSetting plantWeight = add(new NumberSetting("Plant growth weight", "Weight of kelp/vine/cocoa growth inferences", 7, 0, 30, 0.5));
    final NumberSetting arrangementWeight = add(new NumberSetting("Arrangement weight", "Weight of straight runs of crafted blocks", 6, 0, 30, 0.5));
    final NumberSetting damping = add(new NumberSetting("Structure damping", "Score multiplier when villages/dungeons are present", 0.55, 0, 1, 0.05));
    final BooleanSetting showHud = add(new BooleanSetting("Show stats", "Show scan statistics on the HUD", false));
    private final Map<Indicator, NumberSetting> weights = new EnumMap<>(Indicator.class);

    public SuspiciousChunkAnalyzerModule(ScanService scan) {
        super("Suspicious Chunk Analyzer", "Incrementally scores loaded chunks for signs of player activity (heuristic)", Category.WORLD);
        this.scan = scan;
        for (Indicator i : Indicator.values()) {
            if (i.defaultWeight() > 0) {
                weights.put(i, add(new NumberSetting("Weight: " + i.label(), "Score weight per log2 excess count", i.defaultWeight(), 0, 20, 0.1)));
            }
        }
        budget.onChange(v -> apply());
        radius.onChange(v -> apply());
        rescan.onChange(v -> apply());
        every.onChange(v -> apply());
        cpuBudget.onChange(v -> apply());
        fpsFloor.onChange(v -> apply());
        geometryWeight.onChange(v -> apply());
        layoutWeight.onChange(v -> apply());
        scale.onChange(v -> apply());
        amethystWeight.onChange(v -> apply());
        plantWeight.onChange(v -> apply());
        arrangementWeight.onChange(v -> apply());
        damping.onChange(v -> apply());
        weights.values().forEach(s -> s.onChange(v -> apply()));
    }

    /** Pushes settings into the scan service as an immutable snapshot. */
    public void apply() {
        ScoringConfig.Builder b = ScoringConfig.defaults().toBuilder()
                .scale(scale.get())
                .amethystWeight(amethystWeight.get())
                .plantGrowthWeight(plantWeight.get())
                .arrangementWeight(arrangementWeight.get())
                .confounderDamping(damping.get())
                .geometryWeight(geometryWeight.get())
                .layoutWeight(layoutWeight.get());
        weights.forEach((ind, s) -> b.weight(ind, s.get()));
        ScoringConfig base = b.build();
        BaseFinderModule bf = dev.atlasclient.Atlas.get().modules.get(BaseFinderModule.class);
        scan.setScoring(bf == null ? base : bf.adjust(base.toBuilder(), base).build());
        scan.setEveryTicks(every.intValue());
        scan.setCpuBudgetMs(cpuBudget.get());
        scan.setFpsFloor(fpsFloor.intValue());
        scan.setBudget(budget.intValue());
        scan.setRadius(radius.intValue());
        scan.setRescanSeconds(rescan.intValue());
    }

    @Override protected void onEnable() {
        apply();
        BaseFinderModule.refreshScanWants();
    }

    @Override protected void onDisable() { BaseFinderModule.refreshScanWants(); }

    @Override public void onTick() {
        setStatus(String.format(Locale.ROOT, "%d scanned, %d queued", scan.scannedCount(), scan.queue().pendingCount()));
    }

    @Override public int renderHud(DrawContext ctx, int x, int y, float tickDelta) {
        if (!showHud.get()) {
            return 0;
        }
        var tr = MinecraftClient.getInstance().textRenderer;
        ctx.drawTextWithShadow(tr, String.format(Locale.ROOT, "Analyzer: %s, %d cached, %.1f chunks/s, %.1f ms/chunk%s", status(),
                scan.discoveries().size(), scan.chunksPerSecond(), scan.analysisMs(), scan.throttled() ? " (slowed: low FPS)" : ""), x, y, 0xFFB0B8FF);
        return 10;
    }
}
