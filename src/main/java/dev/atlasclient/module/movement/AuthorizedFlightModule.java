package dev.atlasclient.module.movement;

import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.setting.Settings.BooleanSetting;
import dev.atlasclient.core.setting.Settings.NumberSetting;
import dev.atlasclient.module.AtlasModule;
import dev.atlasclient.util.Env;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.Locale;
import java.util.UUID;

/**
 * Flight only where it is legitimately enabled:
 * <ul>
 *   <li>the server already allows flying (creative, spectator, or a server-granted /fly), or</li>
 *   <li>single-player: the module grants flight through your own integrated server (like a /fly
 *       command) and removes the grant when disabled.</li>
 * </ul>
 * On multiplayer the speed can only be at or below the vanilla flight speed the server set; there
 * is no bypass of server movement checks. Smooth acceleration, hover and ascend/descend are
 * applied by adjusting the player's velocity at the start of each tick.
 */
public final class AuthorizedFlightModule extends AtlasModule {
    final NumberSetting speed = add(new NumberSetting("Speed", "Multiplier of vanilla flight speed (capped at 1.0 on servers)", 1, 0.1, 5, 0.1));
    final NumberSetting acceleration = add(new NumberSetting("Acceleration", "How quickly velocity changes (1 = instant)", 0.35, 0.05, 1, 0.05));
    final NumberSetting vertical = add(new NumberSetting("Vertical speed", "Ascend/descend multiplier", 1, 0.1, 3, 0.1));
    final BooleanSetting hover = add(new BooleanSetting("Hover", "Stop drifting when no movement key is held", true));
    final BooleanSetting startFlying = add(new BooleanSetting("Start flying", "Begin flying immediately when enabled", true));
    private boolean grantedLocally;

    public AuthorizedFlightModule() {
        super("Flight", "Flight in creative/spectator, server-granted flight, or single-player", Category.MOVEMENT);
    }

    /** Flight depends on the world you are in, so a saved profile never switches it on at start-up. */
    @Override public com.google.gson.JsonObject toJson() {
        com.google.gson.JsonObject o = super.toJson();
        o.addProperty("enabled", false);
        return o;
    }

    @Override
    public String enableBlockedReason() {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p == null) return "Join a world first";
        if (p.getAbilities().allowFlying || Env.singleplayer()) return null;
        return "Flight is not permitted here: the server has not enabled flying for you";
    }

    @Override
    protected void onEnable() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity p = mc.player;
        if (!p.getAbilities().allowFlying && Env.singleplayer()) {
            grantOnIntegratedServer(mc, true);
            grantedLocally = true;
            info("Flight", "Flight granted by your single-player world");
        }
        if (startFlying.get() && (p.getAbilities().allowFlying || grantedLocally)) {
            p.getAbilities().flying = true;
            p.sendAbilitiesUpdate();
        }
    }

    @Override
    protected void onDisable() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (grantedLocally && mc.player != null) {
            grantOnIntegratedServer(mc, false);
            info("Flight", "Flight grant removed");
        }
        grantedLocally = false;
    }

    /** Runs on the integrated server thread: the user's own world, so this is a normal ability change. */
    private static void grantOnIntegratedServer(MinecraftClient mc, boolean allow) {
        IntegratedServer server = mc.getServer();
        if (server == null || mc.player == null) return;
        UUID id = UUID.fromString(mc.player.getUuidAsString());
        server.execute(() -> {
            ServerPlayerEntity sp = server.getPlayerManager().getPlayer(id);
            if (sp == null) return;
            if (sp.getAbilities().creativeMode || sp.isSpectator()) return; // already allowed, never revoke
            sp.getAbilities().allowFlying = allow;
            if (!allow) sp.getAbilities().flying = false;
            sp.sendAbilitiesUpdate();
        });
    }

    /** Called from START_CLIENT_TICK so the velocity applies to this tick's movement. */
    public void preTick(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (!isEnabled() || p == null) return;
        if (!p.getAbilities().allowFlying && !grantedLocally) {
            if (!Env.singleplayer()) {
                warn("Flight", "Server removed flight permission; disabling");
                setEnabled(false);
            }
            return;
        }
        if (!p.getAbilities().flying) return;
        boolean remote = !Env.singleplayer();
        float fly = p.getAbilities().getFlySpeed();
        double mult = remote ? Math.min(1.0, speed.get()) : speed.get();
        boolean sprinting = mc.options.sprintKey.isPressed();
        // Vanilla terminal horizontal flight speed ~= flySpeed * (sprint ? 2 : 1) * 0.98 / (1 - 0.91)
        double horizontalCap = fly * (sprinting ? 2 : 1) * 0.98 / 0.09;
        double vanillaAccel = fly * (sprinting ? 2 : 1) * 0.98;
        double target = horizontalCap * mult - vanillaAccel; // vanilla still adds its own input acceleration
        float forward = (mc.options.forwardKey.isPressed() ? 1 : 0) - (mc.options.backKey.isPressed() ? 1 : 0);
        float strafe = (mc.options.leftKey.isPressed() ? 1 : 0) - (mc.options.rightKey.isPressed() ? 1 : 0);
        double yawRad = Math.toRadians(p.getYaw());
        double fx = -Math.sin(yawRad), fz = Math.cos(yawRad);
        double sx = Math.cos(yawRad), sz = Math.sin(yawRad);
        double vx = fx * forward + sx * strafe, vz = fz * forward + sz * strafe;
        double len = Math.sqrt(vx * vx + vz * vz);
        if (len > 1e-6) {
            vx = vx / len * Math.max(0, target);
            vz = vz / len * Math.max(0, target);
        }
        double vy = 0;
        double vertCap = fly * 3 * 0.98 / 0.4 * (remote ? Math.min(1.0, vertical.get()) : vertical.get()) - fly * 3;
        if (mc.options.jumpKey.isPressed()) vy += Math.max(0, vertCap);
        if (mc.options.sneakKey.isPressed()) vy -= Math.max(0, vertCap);
        Vec3d v = p.getVelocity();
        boolean noInput = len < 1e-6 && vy == 0;
        if (noInput && !hover.get()) return; // vanilla drift
        double a = acceleration.get();
        double nx = v.x + (vx - v.x) * a, ny = v.y + (vy - v.y) * a, nz = v.z + (vz - v.z) * a;
        if (remote) {
            // Never exceed what vanilla flight would reach on its own.
            double h = Math.sqrt(nx * nx + nz * nz), hv = Math.sqrt(v.x * v.x + v.z * v.z);
            double limit = Math.max(hv, horizontalCap - vanillaAccel);
            if (h > limit && h > 0) {
                nx = nx / h * limit;
                nz = nz / h * limit;
            }
        }
        p.setVelocity(nx, ny, nz);
    }

    @Override
    public int renderHud(DrawContext ctx, int x, int y, float tickDelta) {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        if (p == null) return 0;
        String source = grantedLocally ? "single-player grant" : p.getAbilities().creativeMode ? "creative" : p.isSpectator() ? "spectator" : "server-granted";
        String text = String.format(Locale.ROOT, "Flight: %s (%s, x%.1f%s)", p.getAbilities().flying ? "flying" : "ready", source,
                Env.singleplayer() ? speed.get() : Math.min(1.0, speed.get()), Env.singleplayer() ? "" : " capped");
        ctx.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, text, x, y, 0xFF64B5F6);
        return 10;
    }

    @Override public void onSessionReset() {
        grantedLocally = false;
        if (isEnabled()) setEnabled(false);
    }
}
