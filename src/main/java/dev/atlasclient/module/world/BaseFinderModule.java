package dev.atlasclient.module.world;

import dev.atlasclient.Atlas;
import dev.atlasclient.AtlasClient;
import dev.atlasclient.core.analysis.AlertThrottle;
import dev.atlasclient.core.analysis.ChunkAssessment;
import dev.atlasclient.core.analysis.Confidence;
import dev.atlasclient.core.analysis.DiscoveryStore;
import dev.atlasclient.core.analysis.Evidence;
import dev.atlasclient.core.analysis.GeometryAnalyzer;
import dev.atlasclient.core.analysis.RangePreset;
import dev.atlasclient.core.analysis.ScoringConfig;
import dev.atlasclient.core.analysis.SiteClusterer;
import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.render.OverlayCuller;
import dev.atlasclient.core.setting.Settings.ActionSetting;
import dev.atlasclient.core.setting.Settings.BooleanSetting;
import dev.atlasclient.core.setting.Settings.ColorSetting;
import dev.atlasclient.core.setting.Settings.EnumSetting;
import dev.atlasclient.core.setting.Settings.NumberSetting;
import dev.atlasclient.gui.DiscoveriesScreen;
import dev.atlasclient.module.AtlasModule;
import dev.atlasclient.module.render.WaypointsModule;
import dev.atlasclient.module.utility.SavedDiscoveriesModule;
import dev.atlasclient.scan.ScanService;
import dev.atlasclient.util.Env;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Base Finder v2. Loaded chunks are scored on the analysis thread (growth near players, lived-in
 * blocks, artificial shapes underground, generated-structure recognition); neighbouring chunks are
 * joined into sites; sites are drawn, listed in the dashboard, announced (grouped, with cooldowns) and
 * optionally saved. Everything is a heuristic estimate from block data the server sent, except
 * "Confirmed Structure", which needs player-only blocks observed in a built, used arrangement.
 */
public final class BaseFinderModule extends AtlasModule {
    public enum RenderMode { COLUMN, FLOOR, OUTLINE_ONLY }

    private final ScanService scan;
    private final AlertThrottle alerts = new AlertThrottle();

    public final EnumSetting<RangePreset> range = add(new EnumSetting<>("Range", "How far to look and show finds. Limited by what the server "
            + "sends you (its view distance); finds stay remembered after you walk away", RangePreset.ULTRA));
    public final BooleanSetting growthFocus = add(new BooleanSetting("Focus on growth", "Weigh growth that only happens near players "
            + "(grown amethyst, long vines, tall kelp, ripe cocoa/crops, aged copper) extra high", true));
    public final EnumSetting<Confidence> minConfidence = add(new EnumSetting<>("Show from", "Lowest confidence that is shown", Confidence.MEDIUM));
    public final BooleanSetting undergroundOnly = add(new BooleanSetting("Underground only",
            "Only count blocks buried under the surface: finds hidden bases from above ground, ignores surface builds (growth always counts)", true));
    public final NumberSetting minDepth = add(new NumberSetting("Min depth", "How far below the surface (top block of the column) a block must be", 6, 1, 64, 1));
    public final EnumSetting<ScoringConfig.StructureHandling> generated = add(new EnumSetting<>("Generated structures",
            "Villages, mineshafts, strongholds, ancient cities, trial chambers, temples...: lower their confidence, ignore them, or count them",
            ScoringConfig.StructureHandling.LOWER));
    public final BooleanSetting markBlocks = add(new BooleanSetting("Mark blocks", "Also outline the blocks and shapes that were found", true));
    public final BooleanSetting beams = add(new BooleanSetting("Beams", "A tall line above each finding, easy to spot from far away", true));
    public final BooleanSetting labels = add(new BooleanSetting("Labels", "Show confidence / distance / reasons", true));
    public final BooleanSetting showSaved = add(new BooleanSetting("Show saved", "Also show saved finds that are not detected right now (marked as historical)", true));
    public final BooleanSetting autoSave = add(new BooleanSetting("Auto-save High+", "Save every High, Very High and Confirmed find, so it is still there next time", true));
    public final EnumSetting<Confidence> notifyFrom = add(new EnumSetting<>("Notify from", "Lowest confidence that triggers a notification", Confidence.HIGH));
    public final BooleanSetting alertGrowth = add(new BooleanSetting("Alert: overgrown", "Notify about unusual growth (Medium+)", true));
    public final BooleanSetting alertRooms = add(new BooleanSetting("Alert: underground rooms", "Notify about dug rooms / tunnels (Medium+)", true));
    public final BooleanSetting alertStorage = add(new BooleanSetting("Alert: storage", "Notify about storage areas (Medium+)", true));
    public final BooleanSetting alertFarms = add(new BooleanSetting("Alert: farms", "Notify about farms (Medium+)", false));
    public final BooleanSetting alertLarge = add(new BooleanSetting("Alert: large structures", "Notify about finds spanning 4+ chunks or big dug-out areas (Medium+)", true));
    public final BooleanSetting alertClusters = add(new BooleanSetting("Alert: block clusters", "Notify about several kinds of player blocks together (Medium+)", false));
    public final NumberSetting alertCooldown = add(new NumberSetting("Alert cooldown", "Seconds between alerts of one kind; finds close together are grouped", 20, 0, 300, 5));
    public final EnumSetting<RenderMode> renderMode = add(new EnumSetting<>("Render mode", "How finds are drawn", RenderMode.COLUMN));
    public final BooleanSetting amethystNotify = add(new BooleanSetting("Amethyst alerts", "Notify on new amethyst growth evidence in a chunk", false));
    public final ColorSetting lowColor = add(new ColorSetting("Low color", "Very low / low confidence", 0x5566CC66));
    public final ColorSetting mediumColor = add(new ColorSetting("Medium color", "Medium confidence", 0x66E6C84B));
    public final ColorSetting highColor = add(new ColorSetting("High color", "High confidence", 0x77F08A3C));
    public final ColorSetting veryHighColor = add(new ColorSetting("Very high color", "Very high confidence", 0x88F04848));
    public final ColorSetting confirmedColor = add(new ColorSetting("Confirmed color", "Confirmed structure", 0x99D946EF));
    public final ColorSetting savedColor = add(new ColorSetting("Saved color", "Saved finds not detected right now", 0x6690A4AE));
    final ActionSetting open = add(new ActionSetting("Open discoveries", "Dashboard: scan status, all finds, sorting, filters, evidence", () ->
            MinecraftClient.getInstance().setScreen(new DiscoveriesScreen(MinecraftClient.getInstance().currentScreen))));
    final ActionSetting waypointNearest = add(new ActionSetting("Waypoint nearest", "Create a waypoint at the nearest shown find", this::waypointNearest));

    /** Recent finds for the dashboard's history (newest first). */
    private final List<String> history = new ArrayList<>();
    private int ticks;

    public BaseFinderModule(ScanService scan) {
        super("Base Finder", "Finds hidden bases and places players stayed at (overgrowth), in loaded chunks (heuristic)", Category.WORLD);
        this.scan = scan;
        undergroundOnly.onChange(v -> applyDepth());
        minDepth.onChange(v -> applyDepth());
        range.onChange(r -> scan.setRange(r));
        growthFocus.onChange(v -> applyScoring());
        generated.onChange(v -> applyScoring());
        scan.onAmethystEvidence(a -> {
            if (isEnabled() && amethystNotify.get()) {
                discovery("New amethyst evidence", String.format(Locale.ROOT, "Chunk %d,%d: %s", a.chunkX(), a.chunkZ(), a.amethyst().summary()));
            }
        });
    }

    /** Recomputes which analysis kinds the scan service must produce. */
    public static void refreshScanWants() {
        Atlas atlas = Atlas.get();
        boolean indicators = atlas.modules.get(BaseFinderModule.class).isEnabled()
                || atlas.modules.get(SuspiciousChunkAnalyzerModule.class).isEnabled();
        boolean debris = atlas.modules.get(AncientDebrisFinderModule.class).isEnabled();
        AtlasClient.scan().setWanted(indicators, debris);
    }

    private void applyDepth() {
        scan.setUndergroundDepth(undergroundOnly.get() ? minDepth.intValue() : -1);
    }

    private void applyScoring() {
        atlas().modules.get(SuspiciousChunkAnalyzerModule.class).apply();
    }

    /** Applied on top of the analyzer's weights. */
    public ScoringConfig.Builder adjust(ScoringConfig.Builder b, ScoringConfig base) {
        b.structureHandling(generated.get());
        if (growthFocus.get()) {
            b.amethystWeight(base.amethystWeight * 1.5).plantGrowthWeight(base.plantGrowthWeight * 1.5);
        }
        return b;
    }

    @Override protected void onEnable() {
        scan.setNotifyAt(Confidence.CONFIRMED); // per-chunk announcements are replaced by site alerts
        scan.setRange(range.get());
        applyDepth();
        SuspiciousChunkAnalyzerModule analyzer = atlas().modules.get(SuspiciousChunkAnalyzerModule.class);
        analyzer.apply();
        refreshScanWants();
        alerts.reset();
        int eff = range.get().effectiveBlocks(scan.loadedRadius());
        info("Base Finder", (undergroundOnly.get() ? "Looking for buried bases (" + minDepth.intValue() + "+ blocks underground) and overgrowth "
                : "Looking for bases and overgrowth ") + "in loaded chunks, about " + eff + " blocks around you"
                + (range.get().blocks() > eff || range.get() == RangePreset.MAXIMUM ? " (all the server sends)" : "")
                + ". Results are estimates, not proof.");
    }

    @Override protected void onDisable() { refreshScanWants(); }

    @Override public void onSessionReset() {
        alerts.reset();
        history.clear();
    }

    public int colorFor(Confidence c) {
        return switch (c) {
            case VERY_LOW, LOW -> lowColor.argb();
            case MEDIUM -> mediumColor.argb();
            case HIGH -> highColor.argb();
            case VERY_HIGH -> veryHighColor.argb();
            case CONFIRMED -> confirmedColor.argb();
            default -> 0x00000000;
        };
    }

    /** All current sites in this dimension (any confidence). */
    public List<SiteClusterer.Site> allSites() {
        return scan.sites(undergroundOnly.get());
    }

    /** Sites shown with the current settings, nearest first. */
    public List<SiteClusterer.Site> visibleSites() {
        var p = MinecraftClient.getInstance().player;
        double px = p == null ? 0 : p.getX(), pz = p == null ? 0 : p.getZ();
        List<SiteClusterer.Site> out = new ArrayList<>(scan.discoveries().visibleSites(Env.dimension(), scan.scoring(), undergroundOnly.get(),
                minConfidence.get(), false, System.currentTimeMillis()));
        double max = range.get().distance();
        out.removeIf(s -> s.horizontalDistanceTo(px, pz) > max);
        out.sort(Comparator.comparingDouble(s -> s.horizontalDistanceTo(px, pz)));
        return out;
    }

    /** Chunk-level findings (kept for the chunk outline overlay and older callers). */
    public List<ChunkAssessment> visibleFindings(DiscoveryStore.SortKey sort) {
        var p = MinecraftClient.getInstance().player;
        double px = p == null ? 0 : p.getX(), pz = p == null ? 0 : p.getZ();
        return scan.discoveries().list(Env.dimension(), minConfidence.get(), sort, px, pz, false);
    }

    public List<String> history() { return history; }

    /** Y range of a site: its evidence, falling back to the camera height. */
    static int[] yRange(SiteClusterer.Site s, double fallbackY) {
        if (s.minY() == Integer.MAX_VALUE) return new int[]{(int) fallbackY - 4, (int) fallbackY + 4};
        return new int[]{s.minY(), s.maxY() + 1};
    }

    /** Compass direction from the player toward a point ("north-east"). */
    public static String direction(double fromX, double fromZ, double toX, double toZ) {
        double ang = Math.toDegrees(Math.atan2(toX - fromX, -(toZ - fromZ)));
        String[] names = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        return names[(int) Math.floorMod(Math.round(ang / 45.0), 8)];
    }

    public static String ago(long millis, long now) {
        long s = Math.max(0, (now - millis) / 1000);
        if (s < 90) return s + " s ago";
        if (s < 5400) return (s / 60) + " min ago";
        if (s < 172800) return (s / 3600) + " h ago";
        return (s / 86400) + " days ago";
    }

    /** Own render style: drawn much farther than the shared overlay distance, with readable labels. */
    @Override
    public void collectBatches(Frame f, List<Batch> out) {
        List<OverlayCuller.Box> boxes = new ArrayList<>();
        List<OverlayCuller.Box> lines = new ArrayList<>();
        double max = range.get().distance();
        long now = System.currentTimeMillis();
        for (SiteClusterer.Site s : visibleSites()) {
            double d = s.horizontalDistanceTo(f.camX(), f.camZ());
            int color = colorFor(s.confidence());
            double x0 = s.minChunkX() * 16, z0 = s.minChunkZ() * 16, x1 = (s.maxChunkX() + 1) * 16, z1 = (s.maxChunkZ() + 1) * 16;
            int[] yr = yRange(s, f.camY());
            String label = labels.get() ? String.format(Locale.ROOT, "%s %s | %.0f m %s | Y %d%s | %s",
                    s.confidence() == Confidence.CONFIRMED ? "Base:" : s.primary() == SiteClusterer.Type.OVERGROWN ? "Overgrown?" : "Base?",
                    s.confidence().label(), d, direction(f.camX(), f.camZ(), s.centerX(), s.centerZ()), yr[0],
                    f.camY() - yr[0] >= 3 ? String.format(Locale.ROOT, " (%.0f below)", f.camY() - yr[0]) : "", s.reasons(2)) : null;
            if (beams.get()) {
                double top = Math.max(yr[1] + 48, f.camY() + 32);
                double cx = s.centerX(), cz = s.centerZ();
                lines.add(new OverlayCuller.Box(cx - 0.15, yr[1], cz - 0.15, cx + 0.15, top, cz + 0.15, color | 0xFF000000, null, 0));
            }
            if (markBlocks.get() && d < 192) {
                int marked = 0;
                for (Evidence e : s.evidence()) {
                    if (e.kind() == Evidence.Kind.CAVEAT || e.contribution() <= 0) continue;
                    for (long[] sp : e.samplePositions()) {
                        if (marked++ >= 24) break;
                        lines.add(new OverlayCuller.Box(sp[0], sp[1], sp[2], sp[0] + 1, sp[1] + 1, sp[2] + 1, color | 0xFF000000, null, 0));
                    }
                }
                for (GeometryAnalyzer.Feature g : s.features()) {
                    if (marked++ >= 32) break;
                    lines.add(new OverlayCuller.Box(g.minX(), g.minY(), g.minZ(), g.maxX() + 1, g.maxY() + 1, g.maxZ() + 1, color | 0xFF000000, null, 0));
                }
            }
            switch (renderMode.get()) {
                case COLUMN -> boxes.add(new OverlayCuller.Box(x0, yr[0], z0, x1, yr[1], z1, color, label, 1));
                case FLOOR -> boxes.add(new OverlayCuller.Box(x0, yr[0], z0, x1, yr[0] + 0.05, z1, color, label, 1));
                case OUTLINE_ONLY -> boxes.add(new OverlayCuller.Box(x0, yr[0], z0, x1, yr[1], z1, color | 0xFF000000, label, 0));
            }
        }
        if (showSaved.get()) {
            for (DiscoveryStore.SavedFinding h : scan.discoveries().historical(Env.dimension())) {
                double dx = h.blockX() - f.camX(), dz = h.blockZ() - f.camZ();
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > max) continue;
                int c = savedColor.argb();
                double x0 = h.chunkX() * 16, z0 = h.chunkZ() * 16;
                String label = labels.get() ? String.format(Locale.ROOT, "Saved (historical): %s | %.0f m %s | last seen %s",
                        h.lastState() == null || h.lastState().isEmpty() ? h.confidence() : h.lastState(), d,
                        direction(f.camX(), f.camZ(), h.blockX(), h.blockZ()), ago(h.lastSeen(), now)) : null;
                boxes.add(new OverlayCuller.Box(x0, h.blockY(), z0, x0 + 16, h.blockY() + 4, z0 + 16, c | 0xFF000000, label, 0));
            }
        }
        boolean fill = renderMode.get() != RenderMode.OUTLINE_ONLY;
        double draw = Math.min(max, 1_000_000);
        out.add(new Batch(new Style(fill ? 0.35 : 0, true, 2.5f, true, draw, 512, labels.get(), 0xFFFFFFFF, 1.3f, false), boxes));
        out.add(new Batch(new Style(0, true, 2f, true, draw, 2048, false, 0, 1f, false), lines));
    }

    private void waypointNearest() {
        List<SiteClusterer.Site> list = visibleSites();
        if (list.isEmpty()) {
            warn("Base Finder", "No findings to mark");
            return;
        }
        waypoint(list.get(0));
    }

    public void waypoint(SiteClusterer.Site s) {
        int[] yr = yRange(s, 64);
        atlas().modules.get(WaypointsModule.class).add(String.format(Locale.ROOT, "Base? %s", s.confidence().label()),
                (int) s.centerX(), yr[0], (int) s.centerZ(), colorFor(s.confidence()) | 0xFF000000, "basefinder");
        save(s);
    }

    public void save(SiteClusterer.Site s) {
        scan.discoveries().saveSite(s, Env.worldKey(), System.currentTimeMillis());
        atlas().modules.get(SavedDiscoveriesModule.class).markDirty();
    }

    @Override public void onTick() {
        if (++ticks % 20 != 0) return;
        long now = System.currentTimeMillis();
        List<SiteClusterer.Site> shown = visibleSites();
        int overgrown = 0;
        for (SiteClusterer.Site s : shown) if (s.types().contains(SiteClusterer.Type.OVERGROWN)) overgrown++;
        int n = shown.size();
        setStatus(n == 0 ? scan.discoveries().size() + " chunks checked, nothing found yet"
                : n + (n == 1 ? " possible base" : " possible bases") + (overgrown > 0 ? " (" + overgrown + " overgrown)" : "")
                + ", " + scan.discoveries().size() + " chunks checked");
        alerts.configure(alertCooldown.intValue() * 1000L, 96);
        var p = MinecraftClient.getInstance().player;
        for (SiteClusterer.Site s : allSites()) {
            if (autoSave.get() && s.confidence().atLeast(Confidence.HIGH) && scan.discoveries().siteMark(s) == DiscoveryStore.Mark.NONE) {
                save(s);
            }
            String kind = alertKind(s);
            if (kind == null) continue;
            AlertThrottle.Decision d = alerts.offer(kind, s.key(), s.centerX(), s.centerZ(), s.confidence(), now);
            if (d != AlertThrottle.Decision.NOTIFY || p == null) continue;
            double dist = s.horizontalDistanceTo(p.getX(), p.getZ());
            int more = alerts.takeGrouped();
            int[] yr = yRange(s, p.getY());
            String msg = String.format(Locale.ROOT, "%s, %.0f m %s, at Y %d: %s%s", s.confidence().label(), dist,
                    direction(p.getX(), p.getZ(), s.centerX(), s.centerZ()), yr[0], s.reasons(2),
                    more > 0 ? " (+" + more + " more nearby)" : "");
            discovery(kind, msg);
            history.add(0, java.time.LocalTime.now().withNano(0) + "  " + kind + ": " + msg);
            if (history.size() > 50) history.remove(history.size() - 1);
        }
    }

    /** Which alert a site triggers with the current settings, or null. */
    private String alertKind(SiteClusterer.Site s) {
        if (!isEnabled() || scan.discoveries().siteMark(s) == DiscoveryStore.Mark.DISMISSED) return null;
        if (s.confidence().atLeast(notifyFrom.get())) {
            return s.confidence() == Confidence.CONFIRMED ? "Base found" : s.primary() == SiteClusterer.Type.OVERGROWN ? "Overgrown area found" : "Possible base found";
        }
        if (!s.confidence().atLeast(Confidence.MEDIUM)) return null;
        if (alertGrowth.get() && s.types().contains(SiteClusterer.Type.OVERGROWN)) return "Unusual growth";
        if (alertRooms.get() && !s.features().isEmpty()) return "Underground room / tunnel";
        if (alertStorage.get() && s.types().contains(SiteClusterer.Type.STORAGE)) return "Storage area";
        if (alertLarge.get() && (s.chunks().size() >= 4 || s.features().stream().anyMatch(g -> g.shape() == GeometryAnalyzer.Shape.EXCAVATION))) {
            return "Large structure";
        }
        if (alertFarms.get() && s.types().contains(SiteClusterer.Type.FARM)) return "Farm";
        if (alertClusters.get() && s.evidence().stream().anyMatch(e -> e.id().equals("layout.cluster"))) return "Block cluster";
        return null;
    }
}
