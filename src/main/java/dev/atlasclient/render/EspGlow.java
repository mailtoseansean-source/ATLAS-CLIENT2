package dev.atlasclient.render;

import net.minecraft.entity.Entity;

import java.util.HashMap;
import java.util.Map;

/**
 * Entities that ESP modules want outlined with the vanilla glow effect, and in which colour.
 * Rebuilt every client tick by the enabled ESP modules; read by the two small mixins
 * ({@code MinecraftClient.hasOutline} and {@code EntityRenderer.updateRenderState}). Purely visual and
 * client-side: nothing is sent to the server and the entity's own glowing flag is never changed.
 */
public final class EspGlow {
    private static final Map<Integer, Integer> COLORS = new HashMap<>();
    private static final Map<Integer, Integer> NEXT = new HashMap<>();
    private static boolean hookSeen;

    private EspGlow() { }

    /** Called by ESP modules during a tick. */
    public static void request(Entity e, int argb) {
        NEXT.put(e.getId(), argb & 0xFFFFFF);
    }

    /** Called once at the end of each client tick: the requests of this tick become active. */
    public static void commit() {
        COLORS.clear();
        COLORS.putAll(NEXT);
        NEXT.clear();
    }

    public static void clear() {
        COLORS.clear();
        NEXT.clear();
    }

    public static boolean shouldGlow(Entity e) {
        hookSeen = true;
        return COLORS.containsKey(e.getId());
    }

    /** RGB colour for a glowing ESP entity, or -1 if this entity is not ours. */
    public static int colorFor(Entity e) {
        Integer c = COLORS.get(e.getId());
        return c == null ? -1 : c;
    }

    /** False until the outline mixin has run once (it is optional and may be skipped by the mixin system). */
    public static boolean hookActive() { return hookSeen; }

    public static boolean anyRequested() { return !COLORS.isEmpty(); }
}
