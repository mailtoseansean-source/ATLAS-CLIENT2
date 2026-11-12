package dev.atlasclient.module.render;

import dev.atlasclient.core.setting.Settings.ColorSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;

import java.util.List;

/** Dropped items in loaded chunks, labelled with item name and count. */
public final class ItemEspModule extends EspModule {
    private ColorSetting color;

    public ItemEspModule() {
        super("Item ESP", "Highlights dropped items in loaded chunks with name and count", Mode.BOX, true, 48);
    }

    @Override
    protected void defineTargetSettings() {
        color = add(new ColorSetting("Colour", "Item highlight colour", 0xFFFFD740));
    }

    @Override
    protected void gather(MinecraftClient mc, double range, List<Target> out) {
        double r2 = range * range;
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof ItemEntity item) || e.squaredDistanceTo(mc.player) > r2) continue;
            ItemStack s = item.getStack();
            if (s.isEmpty()) continue;
            String name = s.getName().getString() + (s.getCount() > 1 ? " x" + s.getCount() : "");
            out.add(new Target(e, e.getBoundingBox(), color.argb(), label(mc, name, "", e.getX(), e.getY() + 0.5, e.getZ())));
        }
    }
}
