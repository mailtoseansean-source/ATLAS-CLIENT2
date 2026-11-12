package dev.atlasclient.module.render;

import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.analysis.RangePreset;
import dev.atlasclient.core.render.OverlayCuller;
import dev.atlasclient.core.setting.Settings.BooleanSetting;
import dev.atlasclient.core.setting.Settings.ColorSetting;
import dev.atlasclient.core.setting.Settings.EnumSetting;
import dev.atlasclient.core.setting.Settings.NumberSetting;
import dev.atlasclient.module.AtlasModule;
import dev.atlasclient.render.EspGlow;
import dev.atlasclient.util.Env;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared ESP behaviour: targets are gathered once per tick from what the client already has
 * (entities in the client world, block entities in loaded chunks), then drawn every frame with
 * interpolated positions. Modes: box outline, filled box, both, or the vanilla glow outline.
 */
public abstract class EspModule extends AtlasModule {
    public enum Mode { BOX, FILLED, BOX_AND_FILL, GLOW }

    /** One thing to highlight. {@code entity} is null for block targets (then the box is fixed). */
    protected record Target(Entity entity, Box box, int color, String label) { }

    protected final EnumSetting<Mode> mode;
    protected final NumberSetting lineWidth;
    protected final NumberSetting fillOpacity;
    protected final BooleanSetting seeThrough;
    protected final EnumSetting<RangePreset> range;
    protected final BooleanSetting tracers;
    protected final NumberSetting maxTargets;
    protected final BooleanSetting labels;
    protected final BooleanSetting showDistance;
    protected final NumberSetting labelScale;
    protected final ColorSetting labelColor;

    private final List<Target> targets = new ArrayList<>();

    protected EspModule(String name, String description, Mode defaultMode, boolean defaultLabels, double defaultRange) {
        super(name, description, Category.RENDER);
        defineTargetSettings();
        mode = add(new EnumSetting<>("Mode", "BOX outline, FILLED box, both, or GLOW (vanilla outline effect)", defaultMode));
        lineWidth = add(new NumberSetting("Line width", "Outline width", 2, 1, 6, 0.5));
        fillOpacity = add(new NumberSetting("Fill opacity", "Opacity of filled boxes", 0.25, 0.05, 1, 0.05));
        seeThrough = add(new BooleanSetting("Through walls", "Draw boxes and labels through blocks", true));
        range = add(new EnumSetting<>("Range", "Maximum distance. Only what the server sends is shown (entities: its tracking range; "
                + "containers: loaded chunks)", defaultRange <= 64 ? RangePreset.SMALL : defaultRange <= 128 ? RangePreset.MEDIUM : RangePreset.LARGE));
        tracers = add(new BooleanSetting("Tracers", "Lines from your view to each target (shows which way to look)", false));
        maxTargets = add(new NumberSetting("Max shown", "Nearest targets drawn", 100, 1, 500, 1));
        labels = add(new BooleanSetting("Labels", "Name label above each target", defaultLabels));
        showDistance = add(new BooleanSetting("Distance", "Add the distance to labels", true));
        labelScale = add(new NumberSetting("Label scale", "Label size", 1, 0.5, 3, 0.1));
        labelColor = add(new ColorSetting("Label colour", "Label text colour", 0xFFFFFFFF));
    }

    /** Subclasses add their own category toggles and colours first (shown at the top of the card). */
    protected abstract void defineTargetSettings();

    /** Collects this tick's targets (client thread). */
    protected abstract void gather(MinecraftClient mc, double range, List<Target> out);

    protected static String distanceText(MinecraftClient mc, double x, double y, double z) {
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        double d = Math.sqrt(cam.squaredDistanceTo(x, y, z));
        return String.format(Locale.ROOT, "%.0fm", d);
    }

    protected static String healthText(Entity e) {
        if (e instanceof LivingEntity le && le.getMaxHealth() > 0) {
            float hp = le.getHealth() + le.getAbsorptionAmount();
            return String.format(Locale.ROOT, "%.1f/%.0f HP", hp, le.getMaxHealth());
        }
        return "";
    }

    protected String label(MinecraftClient mc, String name, String extra, double x, double y, double z) {
        if (!labels.get()) return null;
        StringBuilder sb = new StringBuilder(name);
        if (extra != null && !extra.isEmpty()) sb.append("  ").append(extra);
        if (showDistance.get()) sb.append("  ").append(distanceText(mc, x, y, z));
        return sb.toString();
    }

    @Override public void onSessionReset() {
        targets.clear();
    }

    @Override protected void onDisable() {
        targets.clear();
    }

    @Override
    public void onTick() {
        targets.clear();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!Env.inWorld()) {
            setStatus("Not in a world");
            return;
        }
        gather(mc, rangeBlocks(), targets);
        if (mode.get() == Mode.GLOW) {
            for (Target t : targets) if (t.entity() != null) EspGlow.request(t.entity(), t.color());
        }
        String glow = mode.get() == Mode.GLOW && !EspGlow.hookActive() && !targets.isEmpty()
                ? " (glow hook not active: use BOX if nothing shows)" : "";
        setStatus(targets.size() + " in range" + glow);
    }

    @Override
    public void collectBatches(Frame frame, List<Batch> out) {
        if (targets.isEmpty()) return;
        Mode m = mode.get();
        // GLOW draws through the vanilla outline pass; labels are still drawn here.
        boolean boxes = m != Mode.GLOW;
        List<OverlayCuller.Box> list = new ArrayList<>(targets.size());
        for (Target t : targets) {
            Box b = t.box();
            if (t.entity() != null) {
                Entity e = t.entity();
                if (e.isRemoved()) continue;
                Vec3d lerp = e.getLerpedPos(frame.tickDelta());
                b = e.getBoundingBox().offset(lerp.x - e.getX(), lerp.y - e.getY(), lerp.z - e.getZ());
            }
            list.add(new OverlayCuller.Box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, t.color(), t.label(), 1));
        }
        double fill = m == Mode.FILLED || m == Mode.BOX_AND_FILL ? fillOpacity.get() : 0;
        boolean outline = m == Mode.BOX || m == Mode.BOX_AND_FILL;
        if (!boxes) {
            // Labels only: zero-alpha boxes are skipped by the fill pass and outlines are off.
            fill = 0;
            outline = false;
        }
        out.add(new Batch(new Style(fill, outline, lineWidth.floatValue(), seeThrough.get(), rangeBlocks(), maxTargets.intValue(),
                labels.get(), labelColor.argb(), labelScale.floatValue(), false, tracers.get()), list));
    }

    protected List<Target> currentTargets() { return targets; }

    /** The range preset in blocks; "Maximum" = everything the client has loaded around it. */
    public double rangeBlocks() {
        int loaded = (Math.max(2, MinecraftClient.getInstance().options.getClampedViewDistance()) + 1) * 16;
        return range.get().blocks() < 0 ? loaded : range.get().blocks();
    }

    /** Number of targets found in the last tick (used by the in-game tests and the status line). */
    public int targetCount() { return targets.size(); }
}
