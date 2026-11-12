package dev.atlasclient.build;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.atlasclient.Atlas;
import dev.atlasclient.core.config.JsonFiles;
import dev.atlasclient.core.schematic.Placement;
import dev.atlasclient.core.schematic.Schematic;
import dev.atlasclient.core.schematic.SchematicLoader;
import dev.atlasclient.core.schematic.SchematicPlacer;
import dev.atlasclient.core.schematic.transform.Mirror;
import dev.atlasclient.core.schematic.transform.Rotation;
import dev.atlasclient.util.Env;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The currently loaded schematic and its placement (shared by AutoBuilder, Schematic Preview and
 * Material Checklist). Placement settings are saved per world so they survive restarts.
 *
 * <p>Schematic folders: the game's {@code schematics/} folder - the same default folder Litematica
 * uses, so one file serves both mods - plus {@code config/atlasclient/schematics/}.</p>
 */
public final class SchematicSession {
    private Schematic schematic;
    private Path file;
    private Placement placement;
    private List<SchematicPlacer.PlacedBlock> blocksCache;
    private int[] boundsCache;

    public static List<Path> folders() {
        List<Path> dirs = new ArrayList<>();
        Path game = FabricLoader.getInstance().getGameDir();
        dirs.add(game.resolve("schematics"));
        dirs.add(Atlas.get().rootDir.resolve("schematics"));
        return dirs;
    }

    /** All supported files, as paths relative to their folder (duplicates removed). */
    public static List<String> available() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Path d : folders()) {
            out.addAll(SchematicLoader.list(d));
        }
        return new ArrayList<>(out);
    }

    public static Path locate(String relativeOrAbsolute) {
        Path p = Path.of(relativeOrAbsolute);
        if (p.isAbsolute() && Files.isRegularFile(p)) return p;
        for (Path d : folders()) {
            Path c = d.resolve(relativeOrAbsolute).normalize();
            if (c.startsWith(d.normalize()) && Files.isRegularFile(c)) return c;
        }
        return null;
    }

    public Schematic schematic() { return schematic; }
    public Placement placement() { return placement; }
    public Path file() { return file; }
    public boolean loaded() { return schematic != null && placement != null; }

    public void load(Path f, int ox, int oy, int oz) throws IOException {
        Schematic s = SchematicLoader.load(f);
        this.schematic = s;
        this.file = f;
        this.placement = new Placement(f.toString(), s.name(), ox, oy, oz).setDimension(Env.dimension());
        invalidate();
    }

    /** Adopts an existing placement (e.g. imported from Litematica). */
    public void load(Path f, Placement p) throws IOException {
        this.schematic = SchematicLoader.load(f);
        this.file = f;
        this.placement = p;
        if (p.dimension().isEmpty()) p.setDimension(Env.dimension());
        invalidate();
    }

    public void unload() {
        schematic = null;
        file = null;
        placement = null;
        invalidate();
    }

    public void setOrigin(int x, int y, int z) { if (placement != null) { placement.setOrigin(x, y, z); invalidate(); } }
    public void setRotation(Rotation r) { if (placement != null) { placement.setRotation(r); invalidate(); } }
    public void setMirror(Mirror m) { if (placement != null) { placement.setMirror(m); invalidate(); } }
    public void nudge(int dx, int dy, int dz) {
        if (placement != null) setOrigin(placement.originX() + dx, placement.originY() + dy, placement.originZ() + dz);
    }

    public void invalidate() {
        blocksCache = null;
        boundsCache = null;
    }

    /** Non-air target blocks in world space (cached until the placement changes). */
    public List<SchematicPlacer.PlacedBlock> blocks() {
        if (!loaded()) return List.of();
        if (blocksCache == null) blocksCache = SchematicPlacer.place(schematic, placement, false);
        return blocksCache;
    }

    public List<SchematicPlacer.PlacedBlock> blocksIncludingAir() {
        return loaded() ? SchematicPlacer.place(schematic, placement, true) : List.of();
    }

    public int[] bounds() {
        if (!loaded()) return null;
        if (boundsCache == null) boundsCache = SchematicPlacer.bounds(schematic, placement);
        return boundsCache;
    }

    // ------------------------------------------------------------------ persistence

    private static Path stateFile() { return Atlas.get().worldDir(Env.worldKey()).resolve("placement.json"); }

    public void save() {
        if (!loaded() || Env.worldKey().isEmpty()) return;
        try {
            JsonFiles.writeAtomic(stateFile(), placement.toJson());
        } catch (IOException e) {
            Atlas.LOG.warn("Could not save placement", e);
        }
    }

    /** Restores the last placement for this world, if its schematic file still exists. */
    public boolean restore() {
        if (Env.worldKey().isEmpty()) return false;
        Path f = stateFile();
        if (!Files.exists(f)) return false;
        try {
            JsonElement el = JsonFiles.read(f);
            Placement p = Placement.fromJson((JsonObject) el);
            Path schem = Path.of(p.schematicFile());
            if (!Files.isRegularFile(schem)) return false;
            load(schem, p);
            return true;
        } catch (Exception e) {
            Atlas.LOG.warn("Could not restore placement", e);
            return false;
        }
    }
}
