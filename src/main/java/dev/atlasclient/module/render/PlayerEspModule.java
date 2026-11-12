package dev.atlasclient.module.render;

import dev.atlasclient.core.setting.Settings.BooleanSetting;
import dev.atlasclient.core.setting.Settings.ColorSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

import java.util.List;

/** Other players the client knows about (entities the server sent), with name, health and distance. */
public final class PlayerEspModule extends EspModule {
    private ColorSetting color;
    private BooleanSetting health;
    private BooleanSetting invisible;
    private BooleanSetting spectators;

    public PlayerEspModule() {
        super("Player ESP", "Highlights other players in loaded chunks, with name, health and distance", Mode.BOX, true, 128);
    }

    @Override
    protected void defineTargetSettings() {
        color = add(new ColorSetting("Colour", "Player highlight colour", 0xFFFF5252));
        health = add(new BooleanSetting("Health", "Show health in labels (when the server sends it)", true));
        invisible = add(new BooleanSetting("Invisible players", "Include players with the invisibility effect", false));
        spectators = add(new BooleanSetting("Spectators", "Include players in spectator mode", false));
    }

    @Override
    protected void gather(MinecraftClient mc, double range, List<Target> out) {
        double r2 = range * range;
        for (Entity e : mc.world.getEntities()) {
            if (!(e instanceof PlayerEntity p) || e == mc.player || e == mc.getCameraEntity()) continue;
            if (!invisible.get() && p.isInvisible()) continue;
            if (!spectators.get() && p.isSpectator()) continue;
            if (e.squaredDistanceTo(mc.player) > r2) continue;
            String name = p.getName().getString();
            out.add(new Target(e, e.getBoundingBox(), color.argb(),
                    label(mc, name, health.get() ? healthText(e) : "", e.getX(), e.getY() + e.getHeight(), e.getZ())));
        }
    }
}
