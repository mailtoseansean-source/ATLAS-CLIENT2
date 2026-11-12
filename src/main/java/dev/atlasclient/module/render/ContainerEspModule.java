package dev.atlasclient.module.render;

import dev.atlasclient.core.setting.Settings.BooleanSetting;
import dev.atlasclient.core.setting.Settings.ColorSetting;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BrewingStandBlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.CrafterBlockEntity;
import net.minecraft.block.entity.DispenserBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Storage blocks in loaded chunks, from the block entities the server sent. The list is refreshed
 * twice a second (not every frame) so large bases stay cheap.
 */
public final class ContainerEspModule extends EspModule {
    private record Kind(String name, BooleanSetting on, ColorSetting color) { }

    private Kind chests, barrels, shulkers, enderChests, hoppers, furnaces, dispensers, crafters, brewing;
    private final List<Target> cached = new ArrayList<>();
    private int ticks;

    public ContainerEspModule() {
        super("Container ESP", "Highlights chests, barrels, shulker boxes and other storage in loaded chunks", Mode.BOX_AND_FILL, false, 64);
    }

    private Kind kind(String name, boolean on, int color) {
        return new Kind(name, add(new BooleanSetting(name, "Show " + name.toLowerCase(java.util.Locale.ROOT), on)),
                add(new ColorSetting(name + " colour", "Colour for " + name.toLowerCase(java.util.Locale.ROOT), color)));
    }

    @Override
    protected void defineTargetSettings() {
        chests = kind("Chests", true, 0xFFFFA726);
        barrels = kind("Barrels", true, 0xFFBCAAA4);
        shulkers = kind("Shulker boxes", true, 0xFFE040FB);
        enderChests = kind("Ender chests", true, 0xFF7C4DFF);
        hoppers = kind("Hoppers", false, 0xFF90A4AE);
        furnaces = kind("Furnaces", false, 0xFF8D6E63);
        dispensers = kind("Dispensers and droppers", false, 0xFF78909C);
        crafters = kind("Crafters", false, 0xFFA1887F);
        brewing = kind("Brewing stands", false, 0xFFFFD54F);
    }

    private Kind classify(BlockEntity be) {
        if (be instanceof ChestBlockEntity) return chests;       // includes trapped and copper chests
        if (be instanceof BarrelBlockEntity) return barrels;
        if (be instanceof ShulkerBoxBlockEntity) return shulkers;
        if (be instanceof EnderChestBlockEntity) return enderChests;
        if (be instanceof HopperBlockEntity) return hoppers;
        if (be instanceof AbstractFurnaceBlockEntity) return furnaces; // furnace, smoker, blast furnace
        if (be instanceof DispenserBlockEntity) return dispensers;     // dropper extends dispenser
        if (be instanceof CrafterBlockEntity) return crafters;
        if (be instanceof BrewingStandBlockEntity) return brewing;
        return null;
    }

    @Override public void onSessionReset() {
        super.onSessionReset();
        cached.clear();
    }

    @Override protected void onDisable() {
        super.onDisable();
        cached.clear();
    }

    @Override
    protected void gather(MinecraftClient mc, double range, List<Target> out) {
        if (ticks++ % 10 == 0) {
            cached.clear();
            ClientWorld world = mc.world;
            double r2 = range * range;
            int r = (int) Math.ceil(range / 16.0);
            int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
            for (int cx = pcx - r; cx <= pcx + r; cx++) {
                for (int cz = pcz - r; cz <= pcz + r; cz++) {
                    if (!world.getChunkManager().isChunkLoaded(cx, cz)) continue;
                    Chunk c = world.getChunk(cx, cz);
                    if (!(c instanceof WorldChunk wc)) continue;
                    for (BlockEntity be : wc.getBlockEntities().values()) {
                        Kind k = classify(be);
                        if (k == null || !k.on().get()) continue;
                        BlockPos p = be.getPos();
                        if (mc.player.squaredDistanceTo(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5) > r2) continue;
                        boolean small = be instanceof ChestBlockEntity || be instanceof EnderChestBlockEntity;
                        double in = small ? 0.0625 : 0.0;
                        Box box = new Box(p.getX() + in, p.getY(), p.getZ() + in, p.getX() + 1 - in, p.getY() + (small ? 0.875 : 1), p.getZ() + 1 - in);
                        cached.add(new Target(null, box, k.color().argb(),
                                label(mc, k.name(), "", p.getX() + 0.5, p.getY() + 1, p.getZ() + 0.5)));
                    }
                }
            }
        }
        out.addAll(cached);
    }
}
