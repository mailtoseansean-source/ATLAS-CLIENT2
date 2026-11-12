package dev.atlasclient.module.render;

import dev.atlasclient.AtlasClient;
import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.render.OverlayCuller;
import dev.atlasclient.core.setting.Settings.BooleanSetting;
import dev.atlasclient.core.setting.Settings.ColorSetting;
import dev.atlasclient.core.setting.Settings.NumberSetting;
import dev.atlasclient.core.setting.Settings.StringSetting;
import dev.atlasclient.core.xray.XrayIndex;
import dev.atlasclient.module.AtlasModule;
import dev.atlasclient.scan.XrayScanner;
import dev.atlasclient.util.Env;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Highlights chosen blocks (ores by default) through walls, using only chunk data the client already
 * has. It draws boxes over the normal world instead of hiding terrain, so it works the same with
 * Sodium and Iris and the world looks normal the moment it is switched off. Blocks a server hides
 * with anti-xray are not revealed; the HUD says when the data looks hidden or faked.
 */
public final class XRayModule extends AtlasModule {
    private record Group(String name, BooleanSetting on, ColorSetting color, List<String> ids) { }

    private final List<Group> groups = new ArrayList<>();
    final StringSetting custom;
    final ColorSetting customColor;
    final NumberSetting opacity;
    final BooleanSetting outlines;
    final NumberSetting lineWidth;
    final NumberSetting distance;
    final NumberSetting maxBlocks;
    final BooleanSetting exposedOnly;
    final BooleanSetting hideFakes;
    final NumberSetting radius;
    final NumberSetting speed;
    final NumberSetting rescan;
    final BooleanSetting hud;

    private final XrayScanner scanner;
    private final Map<String, Integer> colorById = new HashMap<>();
    private final Set<String> enabledIds = new HashSet<>();
    private boolean configDirty = true;
    private int ticks;
    private Map<String, Integer> lastCounts = Map.of();
    /** Nearest hits, refreshed a few times per second (blocks do not move; frames just draw this list). */
    private List<XrayIndex.Hit> visible = List.of();
    private long visibleVersion = -1;

    public static final int DEFAULT_KEY = 88; // X

    public XRayModule(XrayScanner scanner) {
        super("X-Ray", "Highlights ores and chosen blocks through walls (client-loaded chunks only)", Category.RENDER, DEFAULT_KEY);
        this.scanner = scanner;
        group("Diamond", true, 0xFF00E5FF, "diamond_ore", "deepslate_diamond_ore");
        group("Ancient debris", true, 0xFFC0703A, "ancient_debris");
        group("Emerald", true, 0xFF00E676, "emerald_ore", "deepslate_emerald_ore");
        group("Gold", true, 0xFFFFD700, "gold_ore", "deepslate_gold_ore", "nether_gold_ore");
        group("Iron", true, 0xFFE8C4A8, "iron_ore", "deepslate_iron_ore");
        group("Redstone", true, 0xFFFF1744, "redstone_ore", "deepslate_redstone_ore");
        group("Lapis", false, 0xFF2962FF, "lapis_ore", "deepslate_lapis_ore");
        group("Copper", false, 0xFFFF8A65, "copper_ore", "deepslate_copper_ore");
        group("Coal", false, 0xFFB0B0B0, "coal_ore", "deepslate_coal_ore");
        group("Nether quartz", false, 0xFFF5F5F5, "nether_quartz_ore");
        custom = add(new StringSetting("Custom blocks",
            "Extra block ids, comma separated; optional colour per entry: spawner=#FF00FF, minecraft:budding_amethyst", "", 1000));
        customColor = add(new ColorSetting("Custom colour", "Colour for custom blocks without their own", 0xFFFF40FF));
        opacity = add(new NumberSetting("Opacity", "Fill opacity of the highlight boxes (0 = outline only)", 0.35, 0, 1, 0.05));
        outlines = add(new BooleanSetting("Outlines", "Draw box outlines", true));
        lineWidth = add(new NumberSetting("Line width", "Outline width", 2, 1, 6, 0.5));
        distance = add(new NumberSetting("Max distance", "Only highlight blocks within this many blocks", 64, 8, 256, 4));
        maxBlocks = add(new NumberSetting("Max blocks", "Nearest blocks drawn per frame", 800, 10, 5000, 10));
        exposedOnly = add(new BooleanSetting("Exposed only", "Only blocks touching air or water (cave ores)", false));
        hideFakes = add(new BooleanSetting("Hide likely fakes", "When the server seems to send fake ores (anti-xray), show only ores touching air: "
                + "those are real", true));
        radius = add(new NumberSetting("Scan radius", "Chunks around you to scan", 8, 2, 16, 1));
        speed = add(new NumberSetting("Scan speed", "Chunks queued per tick", 2, 1, 8, 1));
        rescan = add(new NumberSetting("Rescan seconds", "Re-read nearby chunks this often (picks up changes)", 15, 5, 120, 5));
        hud = add(new BooleanSetting("HUD summary", "Counts and data quality in the info column", true));
        for (Group g : groups) {
            g.on().onChange(v -> configDirty = true);
            g.color().onChange(v -> configDirty = true);
        }
        custom.onChange(v -> configDirty = true);
        customColor.onChange(v -> configDirty = true);
    }

    private void group(String name, boolean on, int color, String... ids) {
        List<String> full = new ArrayList<>();
        for (String id : ids) full.add("minecraft:" + id);
        groups.add(new Group(name, add(new BooleanSetting(name, "Highlight " + name.toLowerCase(Locale.ROOT), on)),
                add(new ColorSetting(name + " colour", "Highlight colour for " + name.toLowerCase(Locale.ROOT), color)), full));
    }

    /** Parses "id[=#AARRGGBB|#RRGGBB], ..." into id -> colour. Unknown ids are reported in the status. */
    static Map<String, Integer> parseCustom(String text, int fallback) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (text == null) return out;
        for (String part : text.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int color = fallback;
            int eq = p.indexOf('=');
            if (eq > 0) {
                String c = p.substring(eq + 1).trim().replace("#", "");
                p = p.substring(0, eq).trim();
                try {
                    long v = Long.parseLong(c, 16);
                    color = c.length() <= 6 ? (int) (0xFF000000L | v) : (int) v;
                } catch (NumberFormatException ignored) {
                    color = fallback;
                }
            }
            String id = p.toLowerCase(Locale.ROOT);
            out.put(id.contains(":") ? id : "minecraft:" + id, color);
        }
        return out;
    }

    private void rebuild() {
        configDirty = false;
        colorById.clear();
        enabledIds.clear();
        Set<Block> blocks = new HashSet<>();
        List<String> unknown = new ArrayList<>();
        Map<String, Integer> wanted = new LinkedHashMap<>();
        for (Group g : groups) {
            if (!g.on().get()) continue;
            for (String id : g.ids()) wanted.put(id, g.color().argb());
        }
        wanted.putAll(parseCustom(custom.get(), customColor.argb()));
        for (Map.Entry<String, Integer> e : wanted.entrySet()) {
            Identifier ident = Identifier.tryParse(e.getKey());
            Optional<Block> b = ident == null ? Optional.empty() : Registries.BLOCK.getOptionalValue(ident);
            if (b.isEmpty()) {
                unknown.add(e.getKey());
                continue;
            }
            blocks.add(b.get());
            colorById.put(e.getKey(), e.getValue());
            enabledIds.add(e.getKey());
        }
        scanner.setTargets(blocks);
        if (!unknown.isEmpty()) {
            warn("X-Ray", "Unknown block ids ignored: " + String.join(", ", unknown));
        }
    }

    @Override protected void onEnable() {
        configDirty = true;
    }

    @Override protected void onDisable() {
        scanner.release();
        lastCounts = Map.of();
        visible = List.of();
    }

    @Override public void onSessionReset() {
        lastCounts = Map.of();
        visible = List.of();
    }

    @Override
    public void onTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!Env.inWorld()) {
            setStatus("Not in a world");
            return;
        }
        if (configDirty) rebuild();
        scanner.tick(mc, radius.intValue(), speed.intValue(), rescan.intValue());
        var cam = mc.gameRenderer.getCamera().getCameraPos();
        if (ticks % 5 == 0 || scanner.index().version() != visibleVersion) {
            visibleVersion = scanner.index().version();
            visible = scanner.index().nearest(cam.x, cam.y, cam.z, distance.get(), maxBlocks.intValue(), onlyExposed(), enabledIds::contains);
        }
        if (++ticks % 20 == 0) {
            var p = mc.player;
            lastCounts = scanner.index().counts(p.getX(), p.getY(), p.getZ(), distance.get(), enabledIds::contains);
            int total = 0;
            for (int n : lastCounts.values()) total += n;
            // Short, plain status for the menu card; the full description is in the HUD and tooltips.
            int shown = visible.size();
            XrayIndex.Availability a = availability();
            String what = shown == 1 ? " ore" : " ores";
            setStatus(switch (a) {
                case LIKELY_HIDDEN -> shown + what + " (the server hides buried ores)";
                case SUSPECT_FAKE -> onlyExposed() ? shown + " real" + what + " (server sends fakes: showing exposed only)"
                        : shown + what + ", many probably fake";
                default -> shown + what + " within " + distance.intValue() + " m";
            });
        }
    }

    /** Exposed only: the setting, or automatically when the data looks like anti-xray fakes. */
    public boolean onlyExposed() {
        return exposedOnly.get() || (hideFakes.get() && availability() == XrayIndex.Availability.SUSPECT_FAKE);
    }

    /** What is drawn right now (nearest first). */
    public List<XrayIndex.Hit> visibleHits() { return visible; }

    public XrayIndex.Availability availability() {
        return scanner.index().availability(Env.singleplayer());
    }

    @Override
    public void collectBatches(Frame frame, List<Batch> out) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;
        List<XrayIndex.Hit> hits = visible;
        List<OverlayCuller.Box> boxes = new ArrayList<>(hits.size());
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (XrayIndex.Hit h : hits) {
            pos.set(h.x(), h.y(), h.z());
            // Drop blocks that are gone (mined or changed) without waiting for the next rescan.
            if (!scanner.targets().contains(mc.world.getBlockState(pos).getBlock())) {
                scanner.index().removeBlock(h.x(), h.y(), h.z());
                continue;
            }
            int c = colorById.getOrDefault(h.block(), customColor.argb());
            boxes.add(new OverlayCuller.Box(h.x() + 0.02, h.y() + 0.02, h.z() + 0.02, h.x() + 0.98, h.y() + 0.98, h.z() + 0.98,
                    c, null, 1));
        }
        out.add(new Batch(new Style(opacity.get(), outlines.get(), lineWidth.floatValue(), true, distance.get(),
                maxBlocks.intValue(), false, 0, 1f, false), boxes));
    }

    @Override
    public int renderHud(DrawContext ctx, int x, int y, float tickDelta) {
        if (!hud.get()) return 0;
        var tr = MinecraftClient.getInstance().textRenderer;
        int start = y;
        XrayIndex.Availability a = availability();
        ctx.drawTextWithShadow(tr, "X-Ray", x, y, 0xFF00E5FF);
        y += 10;
        int n = 0;
        for (Map.Entry<String, Integer> e : lastCounts.entrySet()) {
            if (n++ >= 6) break;
            String id = e.getKey().startsWith("minecraft:") ? e.getKey().substring(10) : e.getKey();
            ctx.drawTextWithShadow(tr, id.replace('_', ' ') + ": " + e.getValue(), x + 4, y,
                    colorById.getOrDefault(e.getKey(), 0xFFFFFFFF) | 0xFF000000);
            y += 9;
        }
        if (a == XrayIndex.Availability.LIKELY_HIDDEN || a == XrayIndex.Availability.SUSPECT_FAKE) {
            ctx.drawTextWithShadow(tr, a == XrayIndex.Availability.SUSPECT_FAKE ? (onlyExposed() ? "Fake ores hidden: showing exposed (real) only"
                            : "Likely fake ores (anti-xray)") : "Enclosed ores hidden (anti-xray)",
                    x + 4, y, 0xFFFFB74D);
            y += 9;
        }
        return y - start + 2;
    }
}
