package dev.atlasclient.module.movement;

import dev.atlasclient.build.McWorld;
import dev.atlasclient.build.MovementController;
import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.path.PathFollower;
import dev.atlasclient.core.path.Pathfinder;
import dev.atlasclient.core.render.OverlayCuller;
import dev.atlasclient.core.setting.Settings.ActionSetting;
import dev.atlasclient.core.setting.Settings.BooleanSetting;
import dev.atlasclient.core.setting.Settings.ChoiceSetting;
import dev.atlasclient.core.setting.Settings.NumberSetting;
import dev.atlasclient.module.AtlasModule;
import dev.atlasclient.module.building.AutoBuilderModule;
import dev.atlasclient.module.render.WaypointsModule;
import dev.atlasclient.util.Env;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Guidance toward a waypoint: HUD direction/distance, a rendered path preview, and optional
 * auto-walk along an A* path using the normal movement keys.
 */
public final class NavigationAssistModule extends AtlasModule {
    final ChoiceSetting target = add(new ChoiceSetting("Target waypoint", "Waypoint to guide to", "", NavigationAssistModule::waypointNames));
    final BooleanSetting showPath = add(new BooleanSetting("Show path", "Render the planned path", true));
    final BooleanSetting sprint = add(new BooleanSetting("Sprint", "Sprint while auto-walking", false));
    final NumberSetting nodes = add(new NumberSetting("Search budget", "Max pathfinder nodes per plan", 6000, 500, 40000, 500));
    final ActionSetting go = add(new ActionSetting("Auto-walk", "Walk to the target waypoint", this::go));
    final ActionSetting stop = add(new ActionSetting("Stop walking", "Release movement keys", this::stopWalking));
    private final MovementController movement = new MovementController();
    private List<Pathfinder.Node> preview = List.of();

    public NavigationAssistModule() {
        super("Navigation Assistance", "Direction, distance and optional auto-walk to waypoints", Category.MOVEMENT);
    }

    private static List<String> waypointNames() {
        List<String> out = new ArrayList<>();
        for (WaypointsModule.Waypoint w : dev.atlasclient.Atlas.get().modules.get(WaypointsModule.class).all()) {
            if (w.dimension().equals(Env.dimension())) out.add(w.name());
        }
        return out;
    }

    private WaypointsModule.Waypoint current() {
        for (WaypointsModule.Waypoint w : atlas().modules.get(WaypointsModule.class).all()) {
            if (w.name().equals(target.get()) && w.dimension().equals(Env.dimension())) return w;
        }
        return null;
    }

    private void go() {
        MinecraftClient mc = MinecraftClient.getInstance();
        WaypointsModule.Waypoint w = current();
        if (w == null || mc.player == null || mc.world == null) {
            warn("Navigation", "Choose a target waypoint first");
            return;
        }
        if (!isEnabled()) setEnabled(true);
        McWorld world = new McWorld(mc.world, atlas().modules.get(AutoBuilderModule.class).resolver());
        movement.navigate(world, mc.player, new Pathfinder.Goal(w.x(), w.y(), w.z(), 2.5), sprint.get(), nodes.intValue());
        preview = movement.follower().path();
        if (!movement.active()) warn("Navigation", "No path found within the search budget (unloaded chunks are never entered)");
    }

    private void stopWalking() {
        movement.stop();
        preview = List.of();
    }

    @Override public void onTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (movement.active()) {
            if (mc.currentScreen != null) return;
            PathFollower.Status st = movement.tick(mc);
            if (st == PathFollower.Status.STUCK) {
                McWorld world = new McWorld(mc.world, atlas().modules.get(AutoBuilderModule.class).resolver());
                if (!movement.replan(world, mc.player, sprint.get(), nodes.intValue())) {
                    warn("Navigation", "Stuck - replanning failed, stopping");
                }
                preview = movement.follower().path();
            } else if (st == PathFollower.Status.ARRIVED) {
                success("Navigation", "Arrived at " + target.get());
                preview = List.of();
            }
        }
    }

    @Override protected void onDisable() { stopWalking(); }

    @Override public void onSessionReset() { stopWalking(); }

    @Override
    public void collectOverlay(Frame f, List<OverlayCuller.Box> out) {
        if (!showPath.get()) return;
        for (Pathfinder.Node n : preview) {
            out.add(new OverlayCuller.Box(n.x() + 0.35, n.y(), n.z() + 0.35, n.x() + 0.65, n.y() + 0.1, n.z() + 0.65, 0xC000E5FF, null, 1));
        }
    }

    @Override
    public int renderHud(DrawContext ctx, int x, int y, float tickDelta) {
        ClientPlayerEntity p = MinecraftClient.getInstance().player;
        WaypointsModule.Waypoint w = current();
        if (p == null || w == null) return 0;
        double dx = w.x() + 0.5 - p.getX(), dz = w.z() + 0.5 - p.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yawTo = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float rel = MathHelper.wrapDegrees(yawTo - p.getYaw());
        String dir = Math.abs(rel) < 10 ? "ahead" : rel > 0 ? String.format(Locale.ROOT, "%.0f deg right", rel) : String.format(Locale.ROOT, "%.0f deg left", -rel);
        ctx.drawTextWithShadow(MinecraftClient.getInstance().textRenderer, String.format(Locale.ROOT, "Nav: %s %.0fm, %s, dy %d%s",
                w.name(), dist, dir, w.y() - p.getBlockPos().getY(), movement.active() ? " (walking)" : ""), x, y, 0xFF00E5FF);
        return 10;
    }
}
