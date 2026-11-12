package dev.atlasclient.gui;

import dev.atlasclient.Atlas;
import dev.atlasclient.AtlasClient;
import dev.atlasclient.core.analysis.Confidence;
import dev.atlasclient.core.analysis.DiscoveryStore;
import dev.atlasclient.core.analysis.Evidence;
import dev.atlasclient.core.analysis.GeometryAnalyzer;
import dev.atlasclient.core.analysis.Indicator;
import dev.atlasclient.core.analysis.SiteClusterer;
import dev.atlasclient.core.analysis.StructureSignatures;
import dev.atlasclient.gui.UiKit.Palette;
import dev.atlasclient.module.utility.SavedDiscoveriesModule;
import dev.atlasclient.module.world.BaseFinderModule;
import dev.atlasclient.scan.ScanService;
import dev.atlasclient.util.Env;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.input.KeyInput;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Base Finder dashboard: scan status and performance, every find (sites of neighbouring chunks)
 * with sorting and filters, a detail panel (coordinates, confidence, blocks, patterns, reasons,
 * last seen, saved state) that separates observed blocks from inferences, saved / historical
 * finds, and the alert history.
 */
public final class DiscoveriesScreen extends Screen {
    public enum Sort {
        CONFIDENCE("Confidence"), DISTANCE("Closest"), RECENT("Most recent"), EVIDENCE("Most evidence"), TYPE("Type");

        final String label;

        Sort(String label) { this.label = label; }
    }

    public enum Filter {
        ALL("All", null), OVERGROWN("Overgrown", SiteClusterer.Type.OVERGROWN), UNDERGROUND("Underground", SiteClusterer.Type.UNDERGROUND),
        STORAGE("Storage", SiteClusterer.Type.STORAGE), FARMS("Farms", SiteClusterer.Type.FARM),
        REDSTONE("Redstone", SiteClusterer.Type.REDSTONE), TERRAIN("Artificial terrain", SiteClusterer.Type.ARTIFICIAL_TERRAIN),
        SAVED("Saved / historical", null);

        final String label;
        final SiteClusterer.Type type;

        Filter(String label, SiteClusterer.Type type) {
            this.label = label;
            this.type = type;
        }
    }

    private final Screen parent;
    private Sort sort = Sort.CONFIDENCE;
    private Filter filter = Filter.ALL;
    private boolean includeHidden;
    private String selectedKey;
    private DiscoveryStore.SavedFinding selectedSaved;
    private double scroll, detailScroll;
    private final UiKit.Hits hits = new UiKit.Hits();

    public DiscoveriesScreen(Screen parent) {
        super(Text.translatable("screen.atlasclient.discoveries"));
        this.parent = parent;
    }

    @Override public boolean shouldPause() { return false; }

    @Override public void close() { MinecraftClient.getInstance().setScreen(parent); }

    private static BaseFinderModule bf() { return Atlas.get().modules.get(BaseFinderModule.class); }

    private List<SiteClusterer.Site> sites() {
        BaseFinderModule bf = bf();
        var p = MinecraftClient.getInstance().player;
        double px = p == null ? 0 : p.getX(), pz = p == null ? 0 : p.getZ();
        List<SiteClusterer.Site> out = new ArrayList<>(AtlasClient.scan().discoveries().visibleSites(Env.dimension(),
                AtlasClient.scan().scoring(), bf.undergroundOnly.get(), bf.minConfidence.get(), includeHidden, System.currentTimeMillis()));
        if (filter.type != null) out.removeIf(s -> !s.types().contains(filter.type));
        Comparator<SiteClusterer.Site> cmp = switch (sort) {
            case CONFIDENCE -> Comparator.comparing(SiteClusterer.Site::confidence).reversed()
                    .thenComparing(Comparator.comparingDouble(SiteClusterer.Site::score).reversed());
            case DISTANCE -> Comparator.comparingDouble(s -> s.horizontalDistanceTo(px, pz));
            case RECENT -> Comparator.comparingLong(SiteClusterer.Site::observedAtMillis).reversed();
            case EVIDENCE -> Comparator.comparingInt(SiteClusterer.Site::evidenceCount).reversed()
                    .thenComparing(Comparator.comparingInt(SiteClusterer.Site::blockCount).reversed());
            case TYPE -> Comparator.comparing(SiteClusterer.Site::primary).thenComparing(Comparator.comparing(SiteClusterer.Site::confidence).reversed());
        };
        out.sort(cmp);
        return out;
    }

    private List<DiscoveryStore.SavedFinding> saved() {
        List<DiscoveryStore.SavedFinding> all = new ArrayList<>();
        for (DiscoveryStore.SavedFinding f : AtlasClient.scan().discoveries().savedFindings()) {
            if (Env.dimension().equals(f.dimension())) all.add(f);
        }
        all.sort(Comparator.comparingLong(DiscoveryStore.SavedFinding::lastSeen).reversed());
        return all;
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        Palette pal = Palette.current();
        hits.clear();
        int w = Math.min(width - 16, 760), h = Math.min(height - 16, 440);
        int x = (width - w) / 2, y = (height - h) / 2;
        UiKit.roundRect(ctx, x, y, w, h, 8, pal.panel());
        BaseFinderModule bf = bf();
        ScanService scan = AtlasClient.scan();
        UiKit.textShadow(ctx, "Base Finder dashboard (estimates, not proof)", x + 10, y + 7, pal.accent());
        String onOff = bf.isEnabled() ? "Scanning" : "Off (turn Base Finder on to scan)";
        int eff = bf.range.get().effectiveBlocks(scan.loadedRadius());
        UiKit.text(ctx, String.format(Locale.ROOT, "%s | Range %s, effective ~%d blocks (what the server sends) | %d chunks analysed, %.1f/s, %.1f ms each, %d queued%s",
                onOff, bf.range.get().label(), eff, scan.scannedCount(), scan.chunksPerSecond(), scan.analysisMs(), scan.queue().pendingCount(),
                scan.throttled() ? " | slowed (low FPS)" : ""), x + 10, y + 19, pal.textDim());
        List<SiteClusterer.Site> all = bf.allSites();
        int suspicious = 0, overgrown = 0;
        Confidence best = Confidence.NONE;
        for (SiteClusterer.Site s : all) {
            if (s.confidence().atLeast(Confidence.MEDIUM)) suspicious++;
            if (s.types().contains(SiteClusterer.Type.OVERGROWN) && s.confidence().atLeast(Confidence.LOW)) overgrown++;
            if (s.confidence().ordinal() > best.ordinal()) best = s.confidence();
        }
        int savedCount = scan.discoveries().savedFindings().size();
        int hist = scan.discoveries().historical(Env.dimension()).size();
        UiKit.text(ctx, String.format(Locale.ROOT, "Finds: %d places, %d suspicious (Medium+), %d overgrown, highest %s | Saved %d (%d historical)",
                all.size(), suspicious, overgrown, best.label(), savedCount, hist), x + 10, y + 30, pal.textDim());

        int bx = x + 10, by = y + 43;
        for (Sort k : Sort.values()) {
            bx = chip(ctx, pal, bx, by, "Sort: " + k.label, k == sort, () -> sort = k);
        }
        bx = chip(ctx, pal, bx, by, includeHidden ? "Showing hidden" : "Hidden excluded", false, () -> includeHidden = !includeHidden);
        bx = x + 10;
        by += 17;
        for (Filter f : Filter.values()) {
            bx = chip(ctx, pal, bx, by, f.label, f == filter, () -> {
                filter = f;
                scroll = 0;
            });
        }

        int listX = x + 10, listY = by + 19, listW = w / 2 - 15, listH = y + h - 10 - listY;
        var p = MinecraftClient.getInstance().player;
        ctx.enableScissor(listX, listY, listX + listW, listY + listH);
        int rowY = listY - (int) scroll;
        int rows = 0;
        long now = System.currentTimeMillis();
        SiteClusterer.Site selected = null;
        if (filter == Filter.SAVED) {
            for (DiscoveryStore.SavedFinding f : saved()) {
                boolean historical = scan.discoveries().historical(Env.dimension()).contains(f);
                boolean sel = selectedSaved != null && selectedSaved.chunkX() == f.chunkX() && selectedSaved.chunkZ() == f.chunkZ();
                UiKit.roundRect(ctx, listX, rowY, listW, 26, 4, sel ? pal.cardHover() : pal.card());
                ctx.fill(listX + 2, rowY + 4, listX + 5, rowY + 22, bf.savedColor.argb() | 0xFF000000);
                double dist = p == null ? 0 : Math.hypot(f.blockX() - p.getX(), f.blockZ() - p.getZ());
                UiKit.text(ctx, String.format(Locale.ROOT, "%s  %.0f m  Y %d  %s", f.confidence(), dist, f.blockY(),
                        historical ? "[historical]" : "[detected now]"), listX + 9, rowY + 4, pal.text());
                UiKit.text(ctx, trim("last seen " + BaseFinderModule.ago(f.lastSeen(), now) + "  " + (f.lastState() == null ? "" : f.lastState()), listW - 14),
                        listX + 9, rowY + 15, pal.textDim());
                if (rowY + 26 > listY && rowY < listY + listH) {
                    hits.add(listX, rowY, listW, 26, null, () -> {
                        selectedSaved = f;
                        selectedKey = null;
                        detailScroll = 0;
                    }, null);
                }
                rowY += 29;
                rows++;
            }
        } else {
            for (SiteClusterer.Site s : sites()) {
                boolean sel = s.key().equals(selectedKey);
                if (sel) selected = s;
                UiKit.roundRect(ctx, listX, rowY, listW, 26, 4, sel ? pal.cardHover() : pal.card());
                ctx.fill(listX + 2, rowY + 4, listX + 5, rowY + 22, bf.colorFor(s.confidence()) | 0xFF000000);
                double dist = p == null ? 0 : s.horizontalDistanceTo(p.getX(), p.getZ());
                String dir = p == null ? "" : BaseFinderModule.direction(p.getX(), p.getZ(), s.centerX(), s.centerZ());
                DiscoveryStore.Mark mark = scan.discoveries().siteMark(s);
                UiKit.text(ctx, String.format(Locale.ROOT, "%s  %.0f m %s  Y %s  %d chunk%s%s", s.confidence().label(), dist, dir,
                        s.minY() == Integer.MAX_VALUE ? "?" : String.valueOf(s.minY()), s.chunks().size(), s.chunks().size() == 1 ? "" : "s",
                        mark == DiscoveryStore.Mark.NONE ? "" : "  [" + mark.name().toLowerCase(Locale.ROOT) + "]"), listX + 9, rowY + 4, pal.text());
                UiKit.text(ctx, trim(s.primary().label() + ": " + s.reasons(2), listW - 14), listX + 9, rowY + 15, pal.textDim());
                if (rowY + 26 > listY && rowY < listY + listH) {
                    hits.add(listX, rowY, listW, 26, null, () -> {
                        selectedKey = s.key();
                        selectedSaved = null;
                        detailScroll = 0;
                    }, null);
                }
                rowY += 29;
                rows++;
            }
        }
        ctx.disableScissor();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows * 29 - listH)));
        if (rows == 0) {
            UiKit.text(ctx, filter == Filter.SAVED ? "Nothing saved in this dimension yet." : bf.isEnabled()
                    ? "Nothing found yet with these filters. Explore, or lower \"Show from\"." : "Turn Base Finder on and explore.",
                    listX + 4, listY + 6, pal.textDim());
        }

        int dx = x + w / 2 + 5, dy = listY, dw = w / 2 - 15, dh = listH;
        UiKit.roundRect(ctx, dx, dy, dw, dh, 6, pal.card());
        List<String[]> lines = new ArrayList<>();
        int ay = dy + dh - 20;
        if (selected != null) {
            final SiteClusterer.Site site = selected;
            int abx = dx + 6;
            abx = action(ctx, pal, abx, ay, "Save", () -> mark(site, DiscoveryStore.Mark.SAVED));
            abx = action(ctx, pal, abx, ay, "Hide", () -> mark(site, DiscoveryStore.Mark.HIDDEN));
            abx = action(ctx, pal, abx, ay, "Dismiss", () -> mark(site, DiscoveryStore.Mark.DISMISSED));
            abx = action(ctx, pal, abx, ay, "Clear mark", () -> mark(site, DiscoveryStore.Mark.NONE));
            action(ctx, pal, abx, ay, "Waypoint", () -> bf.waypoint(site));
            siteLines(site, lines, p, now);
        } else if (selectedSaved != null) {
            final DiscoveryStore.SavedFinding f = selectedSaved;
            int abx = dx + 6;
            abx = action(ctx, pal, abx, ay, "Forget", () -> {
                forget(f);
                selectedSaved = null;
            });
            action(ctx, pal, abx, ay, "Waypoint", () -> Atlas.get().modules.get(dev.atlasclient.module.render.WaypointsModule.class)
                    .add("Saved find " + f.confidence(), f.blockX(), f.blockY(), f.blockZ(), bf.savedColor.argb() | 0xFF000000, "basefinder"));
            savedLines(f, lines, p, now);
        } else {
            ay = dy + dh;
            lines.add(new String[]{"T", "Select a find to see its evidence."});
            lines.add(new String[]{"D", "Recent alerts:"});
            if (bf.history().isEmpty()) lines.add(new String[]{"CAVEAT", "None yet."});
            for (String hLine : bf.history()) lines.add(new String[]{"D", hLine});
        }
        ctx.enableScissor(dx, dy, dx + dw, ay - 2);
        int ly = dy + 6 - (int) detailScroll;
        for (String[] l : lines) {
            int color = switch (l[0]) {
                case "T" -> pal.accent();
                case "OBSERVATION" -> 0xFF8FE3A0;
                case "INFERENCE" -> 0xFFF2C46B;
                case "CAVEAT" -> 0xFFB0B0C0;
                default -> pal.text();
            };
            for (String wrapped : UiKit.wrap(l[1], dw - 16)) {
                UiKit.text(ctx, wrapped, dx + 8, ly, color);
                ly += 10;
            }
            ly += 3;
        }
        ctx.disableScissor();
        int contentH = ly + (int) detailScroll - dy;
        detailScroll = Math.max(0, Math.min(detailScroll, Math.max(0, contentH - (ay - dy))));
    }

    private void siteLines(SiteClusterer.Site s, List<String[]> lines, net.minecraft.client.network.ClientPlayerEntity p, long now) {
        double dist = p == null ? 0 : s.horizontalDistanceTo(p.getX(), p.getZ());
        lines.add(new String[]{"T", String.format(Locale.ROOT, "%s (%.0f/100) - %s", s.confidence().label(), s.score(), s.primary().label())});
        lines.add(new String[]{"D", String.format(Locale.ROOT, "At X %.0f, Y %s, Z %.0f in %s, %.0f m away%s", s.centerX(),
                s.minY() == Integer.MAX_VALUE ? "?" : s.minY() + (s.maxY() > s.minY() ? " to " + s.maxY() : ""), s.centerZ(), s.dimension(), dist,
                p == null ? "" : " (" + BaseFinderModule.direction(p.getX(), p.getZ(), s.centerX(), s.centerZ()) + ")")});
        lines.add(new String[]{"D", "Covers " + s.chunks().size() + " chunk(s); last observed " + BaseFinderModule.ago(s.observedAtMillis(), now)
                + "; " + markText(AtlasClient.scan().discoveries().siteMark(s))});
        List<String> types = new ArrayList<>();
        for (SiteClusterer.Type t : s.types()) types.add(t.label());
        lines.add(new String[]{"D", "Type: " + String.join(", ", types)});
        StringBuilder blocks = new StringBuilder();
        s.counts().entrySet().stream().filter(e -> e.getKey().group() != Indicator.Group.CONFOUNDER)
                .sorted(Map.Entry.<Indicator, Integer>comparingByValue().reversed()).limit(10).forEach(e -> {
                    if (blocks.length() > 0) blocks.append(", ");
                    blocks.append(e.getValue()).append(" ").append(e.getKey().label().toLowerCase(Locale.ROOT));
                });
        if (blocks.length() > 0) lines.add(new String[]{"OBSERVATION", "Blocks seen: " + blocks});
        if (!s.features().isEmpty()) {
            StringBuilder f = new StringBuilder();
            int n = 0;
            for (GeometryAnalyzer.Feature g : s.features()) {
                if (n++ >= 6) break;
                if (f.length() > 0) f.append("; ");
                f.append(g.describe()).append(" at ").append(g.minX()).append(" ").append(g.minY()).append(" ").append(g.minZ());
            }
            lines.add(new String[]{"INFERENCE", "Shapes: " + f});
        }
        if (!s.structures().isEmpty()) {
            lines.add(new String[]{"CAVEAT", "Generated structures recognised: " + StructureSignatures.describe(s.structures())});
        }
        lines.add(new String[]{"D", "Why:"});
        for (Evidence e : s.evidence()) {
            String tag = switch (e.kind()) {
                case OBSERVATION -> "[OBSERVED]";
                case INFERENCE -> "[INFERENCE]";
                case CAVEAT -> "[CAVEAT]";
            };
            String contrib = e.contribution() > 0 ? String.format(Locale.ROOT, " +%.1f", e.contribution()) : "";
            String sample = e.samplePositions().isEmpty() ? "" : " e.g. " + e.samplePositions().get(0)[0] + " " + e.samplePositions().get(0)[1] + " "
                    + e.samplePositions().get(0)[2];
            lines.add(new String[]{e.kind().name(), tag + contrib + " " + e.description() + sample});
        }
        lines.add(new String[]{"CAVEAT", "Only block data the server sent is used. Anti-xray or a short view distance can hide things; "
                + "container contents are never visible."});
    }

    private void savedLines(DiscoveryStore.SavedFinding f, List<String[]> lines, net.minecraft.client.network.ClientPlayerEntity p, long now) {
        boolean historical = AtlasClient.scan().discoveries().historical(Env.dimension()).contains(f);
        double dist = p == null ? 0 : Math.hypot(f.blockX() - p.getX(), f.blockZ() - p.getZ());
        lines.add(new String[]{"T", "Saved find: " + f.confidence() + (historical ? " (historical: not detected right now)" : " (detected now)")});
        lines.add(new String[]{"D", String.format(Locale.ROOT, "At X %d, Y %d, Z %d in %s, %.0f m away", f.blockX(), f.blockY(), f.blockZ(), f.dimension(), dist)});
        lines.add(new String[]{"D", "Saved " + BaseFinderModule.ago(f.savedAtMillis(), now) + ", last seen " + BaseFinderModule.ago(f.lastSeen(), now)
                + (f.worldId() == null || f.worldId().isEmpty() ? "" : " on " + f.worldId())});
        if (f.lastState() != null && !f.lastState().isEmpty()) lines.add(new String[]{"D", "Last state: " + f.lastState()});
        if (f.type() != null && !f.type().isEmpty()) lines.add(new String[]{"D", "Type: " + f.type() + ", " + f.chunks() + " chunk(s)"});
        if (f.note() != null && !f.note().isEmpty()) lines.add(new String[]{"D", "Note: " + f.note()});
        for (String e : f.evidence() == null ? List.<String>of() : f.evidence()) lines.add(new String[]{"INFERENCE", e});
    }

    private static String markText(DiscoveryStore.Mark m) {
        return switch (m) {
            case SAVED -> "saved";
            case HIDDEN -> "hidden";
            case DISMISSED -> "dismissed";
            default -> "not saved";
        };
    }

    private static String trim(String s, int width) {
        if (UiKit.font().getWidth(s) <= width) return s;
        while (s.length() > 3 && UiKit.font().getWidth(s + "...") > width) s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    private int chip(DrawContext ctx, Palette pal, int x, int y, String label, boolean on, Runnable r) {
        int w = UiKit.font().getWidth(label) + 10;
        UiKit.roundRect(ctx, x, y, w, 14, 4, on ? pal.accent() : pal.card());
        UiKit.text(ctx, label, x + 5, y + 3, on ? pal.text() : pal.textDim());
        hits.add(x, y, w, 14, null, r, null);
        return x + w + 4;
    }

    private int action(DrawContext ctx, Palette pal, int x, int y, String label, Runnable r) {
        int w = UiKit.font().getWidth(label) + 10;
        UiKit.roundRect(ctx, x, y, w, 14, 4, pal.accent());
        UiKit.text(ctx, label, x + 5, y + 3, pal.text());
        hits.add(x, y, w, 14, null, r, null);
        return x + w + 4;
    }

    private void mark(SiteClusterer.Site s, DiscoveryStore.Mark m) {
        if (m == DiscoveryStore.Mark.SAVED) {
            bf().save(s);
        } else {
            AtlasClient.scan().discoveries().markSite(s, m, System.currentTimeMillis());
            Atlas.get().modules.get(SavedDiscoveriesModule.class).markDirty();
        }
        Atlas.get().notifications.info("Discovery", s.confidence().label() + " find marked " + markText(m));
        if (m == DiscoveryStore.Mark.DISMISSED || m == DiscoveryStore.Mark.HIDDEN) selectedKey = null;
    }

    private void forget(DiscoveryStore.SavedFinding f) {
        var a = AtlasClient.scan().discoveries().get(f.dimension(), f.chunkX(), f.chunkZ());
        if (a != null) {
            AtlasClient.scan().discoveries().setMark(a, DiscoveryStore.Mark.NONE, System.currentTimeMillis());
        } else {
            AtlasClient.scan().discoveries().forgetSaved(f.dimension(), f.chunkX(), f.chunkZ());
        }
        Atlas.get().modules.get(SavedDiscoveriesModule.class).markDirty();
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        UiKit.Hit hit = hits.at(click.x(), click.y());
        if (hit != null && hit.onLeft() != null && click.button() == 0) {
            hit.onLeft().run();
            return true;
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (mouseX > width / 2.0) {
            detailScroll = Math.max(0, detailScroll - verticalAmount * 20);
        } else {
            scroll = Math.max(0, scroll - verticalAmount * 20);
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyInput input) {
        return super.keyPressed(input);
    }
}
