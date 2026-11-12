package dev.atlasclient.module.camera;

import com.google.gson.JsonObject;
import dev.atlasclient.camera.FreecamController;
import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.render.OverlayCuller;
import dev.atlasclient.module.AtlasModule;
import dev.atlasclient.util.Env;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.Locale;

/**
 * Detaches the camera so it can fly freely (WASD, jump/sneak for up/down, sprint to boost) while your
 * player stays exactly where it is. Nothing can be clicked from the camera. Turning it off returns
 * the view to your player.
 */
public final class FreecamModule extends AtlasModule {
    private float lastHealth = -1;

    public static final int DEFAULT_KEY = 71; // G

    public FreecamModule() {
        super("Freecam", "Detached camera: fly around while your player stays put", Category.CAMERA, DEFAULT_KEY);
    }

    private static CameraSettingsModule cameraSettings() { return atlas().modules.get(CameraSettingsModule.class); }

    @Override
    public String enableBlockedReason() {
        if (!Env.inWorld()) return "Join a world first";
        return null;
    }

    @Override protected void onEnable() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!FreecamController.enable(mc)) {
            throw new IllegalStateException("The camera could not be detached right now");
        }
        lastHealth = mc.player.getHealth();
    }

    @Override protected void onDisable() {
        FreecamController.disable(MinecraftClient.getInstance());
    }

    @Override public void onSessionReset() {
        if (isEnabled()) setEnabled(false);
        FreecamController.disable(MinecraftClient.getInstance());
    }

    /** Never restored as "on" from a saved profile: freecam starts only when you ask for it. */
    @Override public JsonObject toJson() {
        JsonObject o = super.toJson();
        o.addProperty("enabled", false);
        return o;
    }

    @Override
    public void onTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!FreecamController.active()) {
            setEnabled(false);
            return;
        }
        float h = mc.player.getHealth();
        if (cameraSettings().exitOnDamage.get() && lastHealth >= 0 && h < lastHealth) {
            setEnabled(false);
            info("Freecam", "Back to your player: it took damage");
            return;
        }
        lastHealth = h;
        Vec3d c = FreecamController.position();
        if (c != null) {
            setStatus(String.format(Locale.ROOT, "Camera %.0f %.0f %.0f, %.0f m from you", c.x, c.y, c.z,
                    Math.sqrt(c.squaredDistanceTo(mc.player.getEyePos()))));
        }
    }

    @Override
    public void collectBatches(Frame frame, List<Batch> out) {
        CameraSettingsModule s = cameraSettings();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!FreecamController.active() || !s.playerMarker.get() || mc.player == null) return;
        Vec3d lerp = mc.player.getLerpedPos(frame.tickDelta());
        Box b = mc.player.getBoundingBox().offset(lerp.x - mc.player.getX(), lerp.y - mc.player.getY(), lerp.z - mc.player.getZ());
        out.add(new Batch(new Style(0.15, true, 2f, true, 1024, 1, true, s.markerColor.argb(), 1f, false),
                List.of(new OverlayCuller.Box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, s.markerColor.argb(), "You", 1))));
    }

    @Override
    public int renderHud(DrawContext ctx, int x, int y, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Vec3d c = FreecamController.position();
        if (!cameraSettings().hudInfo.get() || c == null || mc.player == null) return 0;
        var tr = mc.textRenderer;
        ctx.drawTextWithShadow(tr, "Freecam", x, y, 0xFF40C4FF);
        ctx.drawTextWithShadow(tr, String.format(Locale.ROOT, "%.1f %.1f %.1f", c.x, c.y, c.z), x + 4, y + 10, 0xFFE0E0E0);
        ctx.drawTextWithShadow(tr, String.format(Locale.ROOT, "%.0f m from you, %.1f b/s", Math.sqrt(c.squaredDistanceTo(mc.player.getEyePos())),
                FreecamController.speedBlocksPerSecond()), x + 4, y + 19, 0xFFE0E0E0);
        if (FreecamController.atEdge(mc)) {
            ctx.drawTextWithShadow(tr, "Edge of the loaded world", x + 4, y + 28, 0xFFFFB050);
            return 39;
        }
        return 30;
    }
}
