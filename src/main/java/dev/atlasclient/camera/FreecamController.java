package dev.atlasclient.camera;

import dev.atlasclient.Atlas;
import dev.atlasclient.core.camera.CameraMotion;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

/**
 * Detached camera built on vanilla's own "camera entity" mechanism (the one spectator mode uses to
 * look through a mob). A client-only marker entity that is never added to the world becomes the
 * camera; the player stays where it is.
 *
 * <p>While the camera is detached, vanilla itself stops applying movement input to the player and
 * stops sending the player's position and rotation (both only happen for the camera entity), so no
 * movement packets are sent. Movement and interaction keys are additionally released every tick so
 * nothing is clicked from the camera's point of view and no input packets change. The camera only
 * shows chunks the client already has; the player's rotation is restored on exit.</p>
 */
public final class FreecamController {
    /** Movement tuning, read every tick from the Camera Speed module. */
    public record Tuning(double speed, double boost, double vertical, double smoothing, boolean stayLoaded, double maxDistance) { }

    private static Entity camera;
    private static final CameraMotion motion = new CameraMotion();
    private static float savedYaw, savedPitch;
    private static Perspective savedPerspective;
    private static boolean active;

    private FreecamController() { }

    public static boolean active() { return active; }

    public static Vec3d position() {
        return active ? new Vec3d(motion.x(), motion.y(), motion.z()) : null;
    }

    public static double speedBlocksPerSecond() { return motion.speedBlocksPerTick() * 20; }

    /** Detaches the camera at the player's eyes. Returns false (and changes nothing) if that is not possible. */
    public static boolean enable(MinecraftClient mc) {
        if (active) return true;
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.getCameraEntity() != p) return false;
        Entity marker = EntityType.MARKER.create(mc.world, SpawnReason.LOAD);
        if (marker == null) return false;
        Vec3d eye = p.getEyePos();
        motion.reset(eye.x, eye.y, eye.z);
        savedYaw = p.getYaw();
        savedPitch = p.getPitch();
        place(marker, eye.x, eye.y, eye.z, eye.x, eye.y, eye.z, savedYaw, savedPitch);
        savedPerspective = mc.options.getPerspective();
        mc.options.setPerspective(Perspective.FIRST_PERSON);
        releaseKeys(mc.options);
        camera = marker;
        mc.setCameraEntity(marker);
        active = true;
        return true;
    }

    /** Returns the camera to the player and restores the player's look direction and perspective. */
    public static void disable(MinecraftClient mc) {
        if (!active) return;
        active = false;
        ClientPlayerEntity p = mc.player;
        if (p != null) {
            p.setYaw(savedYaw);
            p.setPitch(savedPitch);
            p.lastYaw = savedYaw;
            p.lastPitch = savedPitch;
            p.setHeadYaw(savedYaw);
            if (mc.getCameraEntity() == camera) {
                mc.setCameraEntity(p);
            }
        }
        if (savedPerspective != null) {
            mc.options.setPerspective(savedPerspective);
            savedPerspective = null;
        }
        releaseKeys(mc.options);
        camera = null;
    }

    private static void place(Entity e, double x, double y, double z, double lx, double ly, double lz, float yaw, float pitch) {
        e.setPos(x, y, z);
        e.lastX = lx;
        e.lastY = ly;
        e.lastZ = lz;
        e.lastRenderX = lx;
        e.lastRenderY = ly;
        e.lastRenderZ = lz;
        e.setYaw(yaw);
        e.setPitch(pitch);
        e.lastYaw = yaw;
        e.lastPitch = pitch;
    }

    private static boolean physicallyDown(MinecraftClient mc, KeyBinding kb) {
        InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(kb);
        if (key == null || key.getCode() < 0) return false;
        long handle = mc.getWindow().getHandle();
        if (key.getCategory() == InputUtil.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(handle, key.getCode()) == GLFW.GLFW_PRESS;
        }
        if (key.getCategory() == InputUtil.Type.KEYSYM) {
            return InputUtil.isKeyPressed(mc.getWindow(), key.getCode());
        }
        return false;
    }

    private static void releaseKeys(GameOptions o) {
        for (KeyBinding kb : new KeyBinding[]{o.forwardKey, o.backKey, o.leftKey, o.rightKey, o.jumpKey, o.sneakKey, o.sprintKey,
                o.attackKey, o.useKey, o.pickItemKey}) {
            kb.setPressed(false);
            while (kb.wasPressed()) {
                // drain queued presses so they are not applied by the player
            }
        }
    }

    /**
     * Start of every client tick, before the game handles input: moves the camera from the movement
     * keys and releases those keys (plus attack/use/pick) so the player does nothing.
     */
    public static void preTick(MinecraftClient mc, Tuning t) {
        if (!active) return;
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || camera == null || mc.getCameraEntity() != camera) {
            // The game moved the camera elsewhere (death screen, world change, another mod): give up cleanly.
            disable(mc);
            Atlas.get().modules.all().stream().filter(m -> m instanceof dev.atlasclient.module.camera.FreecamModule)
                    .findFirst().ifPresent(m -> m.setEnabled(false));
            return;
        }
        GameOptions o = mc.options;
        boolean typing = mc.currentScreen != null;
        boolean fwd = !typing && physicallyDown(mc, o.forwardKey), back = !typing && physicallyDown(mc, o.backKey);
        boolean left = !typing && physicallyDown(mc, o.leftKey), right = !typing && physicallyDown(mc, o.rightKey);
        boolean up = !typing && physicallyDown(mc, o.jumpKey), down = !typing && physicallyDown(mc, o.sneakKey);
        boolean boost = !typing && physicallyDown(mc, o.sprintKey);
        releaseKeys(o);
        float yaw = p.getYaw();
        CameraMotion.Bounds bounds = (x, y, z) -> {
            if (y < mc.world.getBottomY() - 16 || y > mc.world.getTopYInclusive() + 64) return false;
            if (t.stayLoaded() && !renderable(mc, (int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4)) {
                return false;
            }
            if (t.maxDistance() > 0) {
                double dx = x - p.getX(), dy = y - p.getEyeY(), dz = z - p.getZ();
                if (dx * dx + dy * dy + dz * dz > t.maxDistance() * t.maxDistance()) return false;
            }
            return true;
        };
        double lx = motion.x(), ly = motion.y(), lz = motion.z();
        motion.tick(new CameraMotion.Input(fwd, back, left, right, up, down, boost, yaw, t.speed(), t.boost(), t.vertical(),
                t.smoothing()), bounds);
        place(camera, motion.x(), motion.y(), motion.z(), lx, ly, lz, p.getYaw(), p.getPitch());
    }

    /**
     * The camera may go as far as the world around it can actually be drawn: its chunk and the two
     * rings of chunks around it must be loaded (a chunk is only drawn when its neighbours are
     * loaded too). The server only sends chunks around your player, so this is the real limit.
     */
    private static boolean renderable(MinecraftClient mc, int cx, int cz) {
        for (int dx = -EDGE_MARGIN; dx <= EDGE_MARGIN; dx++) {
            for (int dz = -EDGE_MARGIN; dz <= EDGE_MARGIN; dz++) {
                if (!mc.world.getChunkManager().isChunkLoaded(cx + dx, cz + dz)) return false;
            }
        }
        return true;
    }

    private static final int EDGE_MARGIN = 2;

    /** True when the camera is at the edge of the loaded world (for the HUD). */
    public static boolean atEdge(MinecraftClient mc) {
        if (!active || mc.world == null) return false;
        double[] c = {motion.x(), motion.z()};
        return !renderable(mc, ((int) Math.floor(c[0]) >> 4) + 1, (int) Math.floor(c[1]) >> 4)
                || !renderable(mc, ((int) Math.floor(c[0]) >> 4) - 1, (int) Math.floor(c[1]) >> 4)
                || !renderable(mc, (int) Math.floor(c[0]) >> 4, ((int) Math.floor(c[1]) >> 4) + 1)
                || !renderable(mc, (int) Math.floor(c[0]) >> 4, ((int) Math.floor(c[1]) >> 4) - 1);
    }

    /**
     * Every frame: the camera looks where the mouse points. The mouse turns the (non-camera) player
     * client-side only; nothing is sent because vanilla only reports the camera entity's rotation,
     * and the original rotation is restored when freecam ends.
     */
    public static void frame(MinecraftClient mc) {
        if (!active || camera == null || mc.player == null) return;
        float yaw = mc.player.getYaw(), pitch = mc.player.getPitch();
        camera.setYaw(yaw);
        camera.setPitch(pitch);
        camera.lastYaw = yaw;
        camera.lastPitch = pitch;
    }
}
