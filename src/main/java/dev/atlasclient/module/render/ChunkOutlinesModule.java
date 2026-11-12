package dev.atlasclient.module.render;

import dev.atlasclient.core.analysis.ChunkAssessment;
import dev.atlasclient.core.analysis.DiscoveryStore;
import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.render.OverlayCuller;
import dev.atlasclient.core.setting.Settings.BooleanSetting;
import dev.atlasclient.core.setting.Settings.ColorSetting;
import dev.atlasclient.core.setting.Settings.NumberSetting;
import dev.atlasclient.module.AtlasModule;
import dev.atlasclient.module.world.BaseFinderModule;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Chunk borders that are easy to read: a square on the ground at your feet for your chunk (bright)
 * and the chunks around it (faint), tall posts at your chunk's corners, small marks every 4 blocks
 * along your chunk's edges, and the chunk coordinates in the middle. Optionally also the Base
 * Finder's chunks.
 */
public final class ChunkOutlinesModule extends AtlasModule {
    final NumberSetting neighbours = add(new NumberSetting("Rings around you", "How many rings of chunks around yours get a faint square", 1, 0, 4, 1));
    final NumberSetting height = add(new NumberSetting("Post height", "Height of the corner posts of your chunk", 24, 2, 128, 2));
    final BooleanSetting ticks = add(new BooleanSetting("Marks every 4 blocks", "Small marks along the edges of your chunk", true));
    final BooleanSetting label = add(new BooleanSetting("Chunk coordinates", "Show the chunk's coordinates in its middle", true));
    final BooleanSetting suspicious = add(new BooleanSetting("Base Finder chunks", "Also outline the Base Finder's chunks on the ground", false));
    final ColorSetting color = add(new ColorSetting("Color", "Color of your chunk", 0xFF4FC3F7));

    public ChunkOutlinesModule() {
        super("Chunk Outlines", "Chunk borders on the ground, corner posts and chunk coordinates", Category.RENDER);
    }

    @Override
    public void collectBatches(Frame f, List<Batch> out) {
        MinecraftClient mc = MinecraftClient.getInstance();
        double feet = mc.player != null ? Math.floor(mc.player.getY()) : Math.floor(f.camY() - 1.62);
        int cx = (int) Math.floor(f.camX()) >> 4, cz = (int) Math.floor(f.camZ()) >> 4;
        int rgb = color.argb() & 0xFFFFFF;
        // Lines are drawn opaque, so the surrounding chunks get a darker shade instead of transparency.
        int dim = 0xFF000000 | ((((rgb >> 16) & 0xFF) * 45 / 100) << 16) | ((((rgb >> 8) & 0xFF) * 45 / 100) << 8) | ((rgb & 0xFF) * 45 / 100);
        double gy = feet + 0.02; // just above the ground

        List<OverlayCuller.Box> lines = new ArrayList<>();
        int r = neighbours.intValue();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx == 0 && dz == 0) continue;
                double x0 = (cx + dx) * 16, z0 = (cz + dz) * 16;
                lines.add(new OverlayCuller.Box(x0, gy, z0, x0 + 16, gy, z0 + 16, dim, null, 0));
            }
        }
        double x0 = cx * 16, z0 = cz * 16;
        List<OverlayCuller.Box> mine = new ArrayList<>();
        mine.add(new OverlayCuller.Box(x0, gy, z0, x0 + 16, gy, z0 + 16, 0xFF000000 | rgb, null, 0));
        double top = feet + height.get();
        double bottom = feet - Math.min(8, height.get() / 4);
        for (double[] c : new double[][]{{x0, z0}, {x0 + 16, z0}, {x0, z0 + 16}, {x0 + 16, z0 + 16}}) {
            mine.add(new OverlayCuller.Box(c[0] - 0.03, bottom, c[1] - 0.03, c[0] + 0.03, top, c[1] + 0.03, 0xFF000000 | rgb, null, 0));
        }
        if (ticks.get()) {
            for (int i = 4; i < 16; i += 4) {
                double t = 0.35;
                mine.add(new OverlayCuller.Box(x0 + i, gy, z0, x0 + i, gy + t, z0, 0xFF000000 | rgb, null, 0));
                mine.add(new OverlayCuller.Box(x0 + i, gy, z0 + 16, x0 + i, gy + t, z0 + 16, 0xFF000000 | rgb, null, 0));
                mine.add(new OverlayCuller.Box(x0, gy, z0 + i, x0, gy + t, z0 + i, 0xFF000000 | rgb, null, 0));
                mine.add(new OverlayCuller.Box(x0 + 16, gy, z0 + i, x0 + 16, gy + t, z0 + i, 0xFF000000 | rgb, null, 0));
            }
        }
        if (label.get()) {
            mine.add(new OverlayCuller.Box(x0 + 8, gy + 1.5, z0 + 8, x0 + 8, gy + 1.5, z0 + 8, 0x00000000,
                    String.format(Locale.ROOT, "Chunk %d, %d", cx, cz), 0));
        }
        if (suspicious.get()) {
            BaseFinderModule bf = atlas().modules.get(BaseFinderModule.class);
            if (bf.isEnabled()) {
                for (ChunkAssessment a : bf.visibleFindings(DiscoveryStore.SortKey.DISTANCE)) {
                    if (a.horizontalDistanceTo(f.camX(), f.camZ()) > 256) break;
                    double ax = a.chunkX() * 16, az = a.chunkZ() * 16;
                    lines.add(new OverlayCuller.Box(ax, gy, az, ax + 16, gy, az + 16, bf.colorFor(a.confidence()) | 0xFF000000, null, 0));
                }
            }
        }
        out.add(new Batch(new Style(0, true, 1.5f, false, 512, 4096, false, 0, 1f, false), lines));
        out.add(new Batch(new Style(0, true, 2.5f, false, 512, 256, true, 0xFFFFFFFF, 1.2f, false), mine));
    }
}
