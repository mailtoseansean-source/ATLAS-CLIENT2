package dev.atlasclient.module.render;

import dev.atlasclient.core.setting.Settings.BooleanSetting;
import dev.atlasclient.core.setting.Settings.ColorSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;

import java.util.List;

/** Mobs in loaded chunks, split into hostile, passive and other (villagers, golems...). */
public final class MobEspModule extends EspModule {
    private BooleanSetting hostile;
    private ColorSetting hostileColor;
    private BooleanSetting passive;
    private ColorSetting passiveColor;
    private BooleanSetting other;
    private ColorSetting otherColor;
    private BooleanSetting health;

    public MobEspModule() {
        super("Mob ESP", "Highlights hostile, passive and other mobs in loaded chunks", Mode.BOX, false, 64);
    }

    @Override
    protected void defineTargetSettings() {
        hostile = add(new BooleanSetting("Hostile", "Monsters", true));
        hostileColor = add(new ColorSetting("Hostile colour", "Colour for monsters", 0xFFFF6E40));
        passive = add(new BooleanSetting("Passive", "Animals, fish, bats, axolotls", true));
        passiveColor = add(new ColorSetting("Passive colour", "Colour for passive mobs", 0xFF69F0AE));
        other = add(new BooleanSetting("Other", "Villagers, golems and other mobs", true));
        otherColor = add(new ColorSetting("Other colour", "Colour for other mobs", 0xFF40C4FF));
        health = add(new BooleanSetting("Health", "Show health in labels", true));
    }

    @Override
    protected void gather(MinecraftClient mc, double range, List<Target> out) {
        double r2 = range * range;
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof LivingEntity) || e instanceof PlayerEntity || e instanceof ArmorStandEntity) continue;
            if (!e.isAlive() || e.squaredDistanceTo(mc.player) > r2) continue;
            SpawnGroup g = e.getType().getSpawnGroup();
            int color;
            if (g == SpawnGroup.MONSTER) {
                if (!hostile.get()) continue;
                color = hostileColor.argb();
            } else if (g == SpawnGroup.MISC) {
                if (!other.get()) continue;
                color = otherColor.argb();
            } else {
                if (!passive.get()) continue;
                color = passiveColor.argb();
            }
            out.add(new Target(e, e.getBoundingBox(), color, label(mc, e.getDisplayName().getString(),
                    health.get() ? healthText(e) : "", e.getX(), e.getY() + e.getHeight(), e.getZ())));
        }
    }
}
