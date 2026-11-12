package dev.atlasclient.module.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.atlasclient.Atlas;
import dev.atlasclient.core.config.JsonFiles;
import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.render.OverlayCuller;
import dev.atlasclient.core.setting.Settings.BooleanSetting;
import dev.atlasclient.core.setting.Settings.ColorSetting;
import dev.atlasclient.core.setting.Settings.NumberSetting;
import dev.atlasclient.module.AtlasModule;
import dev.atlasclient.util.Env;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Local waypoints, stored per world in config/atlasclient/worlds/<world>/waypoints.json and drawn
 * as beams with distance labels. Base Finder, Debris Finder and commands can add waypoints.
 */
public final class WaypointsModule extends AtlasModule {
    public record Waypoint(String name, String dimension, int x, int y, int z, int color, String source, boolean visible) { }

    final BooleanSetting beams = add(new BooleanSetting("Beams", "Draw a vertical beam above each waypoint", true));
    final NumberSetting beamHeight = add(new NumberSetting("Beam height", "Beam height in blocks", 64, 4, 320, 4));
    final ColorSetting defaultColor = add(new ColorSetting("Default color", "Color for manual waypoints", 0xFF7C4DFF));
    private final List<Waypoint> waypoints = new ArrayList<>();
    private String loadedFor = "";

    public WaypointsModule() {
        super("Waypoints", "Saved locations with beams and distance labels", Category.RENDER);
        setEnabled(true);
    }

    public List<Waypoint> all() {
        ensureLoaded();
        return List.copyOf(waypoints);
    }

    public void add(String name, int x, int y, int z, int color, String source) {
        ensureLoaded();
        waypoints.removeIf(w -> w.name().equalsIgnoreCase(name) && w.dimension().equals(Env.dimension()));
        waypoints.add(new Waypoint(name, Env.dimension(), x, y, z, color == 0 ? defaultColor.argb() : color, source, true));
        save();
        info("Waypoint added", String.format(Locale.ROOT, "%s at %d %d %d", name, x, y, z));
    }

    public boolean remove(String name) {
        ensureLoaded();
        boolean removed = waypoints.removeIf(w -> w.name().equalsIgnoreCase(name));
        if (removed) save();
        return removed;
    }

    @Override
    public void collectOverlay(Frame f, List<OverlayCuller.Box> out) {
        ensureLoaded();
        for (Waypoint w : waypoints) {
            if (!w.visible() || !w.dimension().equals(f.dimension())) continue;
            double dx = w.x() + 0.5 - f.camX(), dy = w.y() + 0.5 - f.camY(), dz = w.z() + 0.5 - f.camZ();
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            String label = String.format(Locale.ROOT, "%s (%.0fm)", w.name(), d);
            out.add(new OverlayCuller.Box(w.x(), w.y(), w.z(), w.x() + 1, w.y() + 1, w.z() + 1, w.color(), label, 3));
            if (beams.get()) {
                out.add(new OverlayCuller.Box(w.x() + 0.4, w.y() + 1, w.z() + 0.4, w.x() + 0.6, w.y() + 1 + beamHeight.get(),
                        w.z() + 0.6, (w.color() & 0xFFFFFF) | 0x60000000, null, 3));
            }
        }
    }

    /** File of the world whose waypoints are loaded (never a different world's file). */
    private Path file() {
        return Atlas.get().worldDir(loadedFor).resolve("waypoints.json");
    }

    private void ensureLoaded() {
        String key = Env.worldKey();
        if (key.equals(loadedFor)) return;
        loadedFor = key;
        waypoints.clear();
        if (key.isEmpty()) return;
        Path f = file();
        if (!Files.exists(f)) return;
        try {
            JsonElement el = JsonFiles.read(f);
            for (JsonElement e : el.getAsJsonObject().getAsJsonArray("waypoints")) {
                JsonObject o = e.getAsJsonObject();
                waypoints.add(new Waypoint(o.get("name").getAsString(), o.get("dimension").getAsString(), o.get("x").getAsInt(),
                        o.get("y").getAsInt(), o.get("z").getAsInt(), o.get("color").getAsInt(),
                        o.has("source") ? o.get("source").getAsString() : "manual", !o.has("visible") || o.get("visible").getAsBoolean()));
            }
        } catch (Exception e) {
            Atlas.LOG.warn("Could not read waypoints {}", f, e);
        }
    }

    private void save() {
        if (loadedFor.isEmpty()) return;
        JsonObject root = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Waypoint w : waypoints) {
            JsonObject o = new JsonObject();
            o.addProperty("name", w.name());
            o.addProperty("dimension", w.dimension());
            o.addProperty("x", w.x());
            o.addProperty("y", w.y());
            o.addProperty("z", w.z());
            o.addProperty("color", w.color());
            o.addProperty("source", w.source());
            o.addProperty("visible", w.visible());
            arr.add(o);
        }
        root.add("waypoints", arr);
        try {
            JsonFiles.writeAtomic(file(), root);
        } catch (Exception e) {
            error("Waypoints", "Could not save: " + e.getMessage());
        }
    }

    @Override public void onSessionReset() {
        loadedFor = "";
        waypoints.clear();
    }
}
