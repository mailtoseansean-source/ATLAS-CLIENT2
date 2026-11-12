package dev.atlasclient.module.render;

import dev.atlasclient.core.analysis.RangePreset;
import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.render.OverlayCuller;
import dev.atlasclient.core.setting.Settings.BooleanSetting;
import dev.atlasclient.core.setting.Settings.ColorSetting;
import dev.atlasclient.core.setting.Settings.EnumSetting;
import dev.atlasclient.core.setting.Settings.NumberSetting;
import dev.atlasclient.core.setting.Settings.StringSetting;
import dev.atlasclient.core.xray.XrayIndex;
import dev.atlasclient.module.AtlasModule;
import dev.atlasclient.scan.XrayScanner;
import dev.atlasclient.util.Env;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Block ESP: boxes, labels and tracers for any blocks you list (spawners, beehives, budding
 * amethyst, beds...), in loaded chunks. Uses its own copy of the X-Ray chunk scanner (background
 * thread, nearest first) so X-Ray and Block ESP can watch different blocks.
 */
public final class BlockEspModule extends AtlasModule {
    private final XrayScanner scanner;
    final StringSetting blocks = add(new StringSetting("Blocks", "Block ids, comma separated; optional colour per entry: spawner=#FF00FF, beehive",
            "spawner, budding_amethyst, beehive, end_portal_frame, trial_spawner, vault", 1000));
    final ColorSetting color = add(new ColorSetting("Colour", "Colour for blocks without their own", 0xFF40C4FF));
    final EnumSetting<RangePreset> range = add(new EnumSetting<>("Range", "How far (loaded chunks only)", RangePreset.MEDIUM));
    final NumberSetting fillOpacity = add(new NumberSetting("Fill opacity", "0 = outline only", 0.25, 0, 1, 0.05));
    final BooleanSetting tracers = add(new BooleanSetting("Tracers", "Lines from your view to each block", true));
    final BooleanSetting labels = add(new BooleanSetting("Labels", "Block name and distance", true));
    final NumberSetting maxShown = add(new NumberSetting("Max shown", "Nearest blocks drawn", 200, 1, 2000, 10));
    private final Map<String, Integer> colorById = new HashMap<>();
    private final Set<String> enabled = new HashSet<>();
    private List<XrayIndex.Hit> visible = List.of();
    private boolean dirty = true;
    private int ticks;

    public BlockEspModule(XrayScanner scanner) {
        super("Block ESP", "Highlights blocks you choose (spawners, beehives, budding amethyst...) with boxes, labels and tracers", Category.RENDER);
        this.scanner = scanner;
        blocks.onChange(v -> dirty = true);
        color.onChange(v -> dirty = true);
    }

    private void rebuild() {
        dirty = false;
        colorById.clear();
        enabled.clear();
        Set<Block> set = new HashSet<>();
        List<String> unknown = new ArrayList<>();
        for (Map.Entry<String, Integer> e : XRayModule.parseCustom(blocks.get(), color.argb()).entrySet()) {
            Identifier id = Identifier.tryParse(e.getKey());
            Optional<Block> b = id == null ? Optional.empty() : Registries.BLOCK.getOptionalValue(id);
            if (b.isEmpty()) {
                unknown.add(e.getKey());
                continue;
            }
            set.add(b.get());
            colorById.put(e.getKey(), e.getValue());
            enabled.add(e.getKey());
        }
        scanner.setTargets(set);
        if (!unknown.isEmpty()) warn("Block ESP", "Unknown block ids ignored: " + String.join(", ", unknown));
    }

    private double rangeBlocks() {
        int loaded = (Math.max(2, MinecraftClient.getInstance().options.getClampedViewDistance()) + 1) * 16;
        return range.get().blocks() < 0 ? loaded : Math.min(range.get().blocks(), loaded);
    }

    @Override protected void onEnable() { dirty = true; }

    @Override protected void onDisable() {
        scanner.release();
        visible = List.of();
    }

    @Override public void onSessionReset() { visible = List.of(); }

    @Override
    public void onTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!Env.inWorld()) {
            setStatus("Not in a world");
            return;
        }
        if (dirty) rebuild();
        double r = rangeBlocks();
        scanner.tick(mc, Math.max(2, (int) Math.ceil(r / 16)), 2, 15);
        if (ticks++ % 5 == 0) {
            var cam = mc.gameRenderer.getCamera().getCameraPos();
            visible = scanner.index().nearest(cam.x, cam.y, cam.z, r, maxShown.intValue(), false, enabled::contains);
            setStatus(visible.size() + " within " + (int) r + " m");
        }
    }

    public int shownCount() { return visible.size(); }

    @Override
    public void collectBatches(Frame frame, List<Batch> out) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || visible.isEmpty()) return;
        List<OverlayCuller.Box> boxes = new ArrayList<>(visible.size());
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (XrayIndex.Hit h : visible) {
            pos.set(h.x(), h.y(), h.z());
            if (!scanner.targets().contains(mc.world.getBlockState(pos).getBlock())) {
                scanner.index().removeBlock(h.x(), h.y(), h.z());
                continue;
            }
            int c = colorById.getOrDefault(h.block(), color.argb());
            String label = null;
            if (labels.get()) {
                double dx = h.x() + 0.5 - frame.camX(), dy = h.y() + 0.5 - frame.camY(), dz = h.z() + 0.5 - frame.camZ();
                String name = h.block().startsWith("minecraft:") ? h.block().substring(10) : h.block();
                label = String.format(Locale.ROOT, "%s  %.0fm", name.replace('_', ' '), Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
            boxes.add(new OverlayCuller.Box(h.x(), h.y(), h.z(), h.x() + 1, h.y() + 1, h.z() + 1, c, label, 1));
        }
        out.add(new Batch(new Style(fillOpacity.get(), true, 2f, true, rangeBlocks(), maxShown.intValue(), labels.get(), 0xFFFFFFFF, 1f,
                false, tracers.get()), boxes));
    }
}
