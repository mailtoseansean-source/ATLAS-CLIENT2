package dev.atlasclient.module.building;

import dev.atlasclient.build.McWorld;
import dev.atlasclient.build.SchematicSession;
import dev.atlasclient.core.build.WorldProbe;
import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.render.OverlayCuller;
import dev.atlasclient.core.schematic.SchematicPlacer.PlacedBlock;
import dev.atlasclient.core.setting.Settings.BooleanSetting;
import dev.atlasclient.core.setting.Settings.ColorSetting;
import dev.atlasclient.core.setting.Settings.NumberSetting;
import dev.atlasclient.module.AtlasModule;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Locale;

/**
 * Ghost preview of the loaded schematic: missing blocks as translucent cubes tinted with the
 * block's map color, wrong blocks in red, the bounding box outlined. World comparison is refreshed
 * incrementally (a budget of blocks per tick) so large schematics do not stall the game.
 */
public final class SchematicPreviewModule extends AtlasModule {
    final NumberSetting maxGhosts = add(new NumberSetting("Max ghost blocks", "Nearest ghost blocks drawn per frame", 800, 50, 5000, 50));
    final NumberSetting ghostAlpha = add(new NumberSetting("Ghost opacity", "Transparency of ghost blocks", 0.35, 0.05, 1, 0.05));
    final BooleanSetting showCorrect = add(new BooleanSetting("Show correct", "Also mark already-correct blocks", false));
    final BooleanSetting tintByBlock = add(new BooleanSetting("Tint by block", "Color ghosts with the block's map color", true));
    final ColorSetting wrongColor = add(new ColorSetting("Wrong block color", "Positions holding a different block", 0xFFFF4040));
    final ColorSetting adjustColor = add(new ColorSetting("Adjust color", "Right block, needs a property change", 0xFFFFC040));
    final ColorSetting boundsColor = add(new ColorSetting("Bounds color", "Schematic bounding box", 0xFF40C4FF));
    final NumberSetting refreshPerTick = add(new NumberSetting("Checks per tick", "World comparisons per tick", 1500, 100, 20000, 100));

    private WorldProbe.Status[] statuses = new WorldProbe.Status[0];
    private List<PlacedBlock> lastBlocks;
    private int cursor;
    private int missing, wrong, correct;

    public SchematicPreviewModule() {
        super("Schematic Preview", "Ghost blocks and bounding box for the loaded schematic", Category.BUILDING);
    }

    private SchematicSession session() { return atlas().modules.get(AutoBuilderModule.class).session; }

    @Override
    public void onTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        SchematicSession s = session();
        if (!s.loaded() || mc.world == null) {
            setStatus("No schematic loaded");
            return;
        }
        List<PlacedBlock> blocks = s.blocks();
        if (blocks != lastBlocks) {
            lastBlocks = blocks;
            statuses = new WorldProbe.Status[blocks.size()];
            cursor = 0;
        }
        McWorld probe = new McWorld(mc.world, atlas().modules.get(AutoBuilderModule.class).resolver());
        int budget = Math.min(blocks.size(), refreshPerTick.intValue());
        for (int i = 0; i < budget; i++) {
            cursor = (cursor + 1) % blocks.size();
            statuses[cursor] = probe.status(blocks.get(cursor));
        }
        missing = wrong = correct = 0;
        for (WorldProbe.Status st : statuses) {
            if (st == null) continue;
            switch (st) {
                case EMPTY -> missing++;
                case WRONG_BLOCK, NEEDS_ADJUSTMENT -> wrong++;
                case MATCHES -> correct++;
                default -> { }
            }
        }
        setStatus(String.format(Locale.ROOT, "%d missing, %d incorrect, %d correct", missing, wrong, correct));
    }

    @Override
    public void collectOverlay(Frame f, List<OverlayCuller.Box> out) {
        SchematicSession s = session();
        if (!s.loaded() || lastBlocks == null) return;
        int[] b = s.bounds();
        if (b != null) {
            out.add(new OverlayCuller.Box(b[0], b[1], b[2], b[3] + 1, b[4] + 1, b[5] + 1, boundsColor.argb(),
                    s.schematic().name() + " (" + s.placement().rotation().name().toLowerCase(Locale.ROOT) + ", "
                            + s.placement().mirror().name().toLowerCase(Locale.ROOT) + ")", 0));
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        int alpha = (int) (ghostAlpha.get() * 255);
        int limit = maxGhosts.intValue();
        int n = 0;
        double max2 = 64 * 64;
        var resolver = atlas().modules.get(AutoBuilderModule.class).resolver();
        for (int i = 0; i < lastBlocks.size() && n < limit * 4; i++) {
            WorldProbe.Status st = i < statuses.length ? statuses[i] : null;
            if (st == null || st == WorldProbe.Status.UNAVAILABLE) continue;
            if (st == WorldProbe.Status.MATCHES && !showCorrect.get()) continue;
            PlacedBlock pb = lastBlocks.get(i);
            double dx = pb.x() + 0.5 - f.camX(), dy = pb.y() + 0.5 - f.camY(), dz = pb.z() + 0.5 - f.camZ();
            if (dx * dx + dy * dy + dz * dz > max2) continue;
            int color;
            int layer = 1;
            switch (st) {
                case EMPTY -> {
                    int rgb = 0x6080FF;
                    if (tintByBlock.get() && mc.world != null) {
                        BlockState state = resolver.resolve(pb.state(), pb.transform());
                        if (state != null) {
                            int mapColor = state.getMapColor(mc.world, new BlockPos(pb.x(), pb.y(), pb.z())).color;
                            if (mapColor != 0) rgb = mapColor;
                        }
                    }
                    color = (alpha << 24) | (rgb & 0xFFFFFF);
                }
                case WRONG_BLOCK -> color = wrongColor.argb();
                case NEEDS_ADJUSTMENT -> color = adjustColor.argb();
                case MATCHES -> { color = 0x4040FF60; layer = 0; }
                default -> { continue; }
            }
            out.add(new OverlayCuller.Box(pb.x() + 0.02, pb.y() + 0.02, pb.z() + 0.02, pb.x() + 0.98, pb.y() + 0.98, pb.z() + 0.98,
                    color, null, layer));
            n++;
        }
    }

    @Override public void onSessionReset() {
        statuses = new WorldProbe.Status[0];
        lastBlocks = null;
    }
}
