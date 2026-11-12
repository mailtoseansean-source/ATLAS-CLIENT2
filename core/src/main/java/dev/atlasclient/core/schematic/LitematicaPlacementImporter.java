package dev.atlasclient.core.schematic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.atlasclient.core.config.JsonFiles;
import dev.atlasclient.core.schematic.transform.Mirror;
import dev.atlasclient.core.schematic.transform.Rotation;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only importer for placements saved by Litematica. Litematica writes per-world/per-dimension
 * data to {@code config/litematica/litematica_<world>[_dim_<dimension>].json} when you leave a
 * world, with the structure (from Litematica's DataManager / SchematicPlacementManager source):
 *
 * <pre>
 * { "placements": { "placements": [ {
 *       "schematic": "/abs/path/house.litematic", "name": "...", "origin": [x,y,z],
 *       "rotation": "CLOCKWISE_90", "mirror": "NONE", "enabled": true,
 *       "placements": [ { "name": "region", "placement": {
 *            "pos": [x,y,z], "name": "region", "rotation": "NONE", "mirror": "NONE", "enabled": true } } ]
 * } ] } }
 * </pre>
 *
 * This class never writes to Litematica's files.
 */
public final class LitematicaPlacementImporter {
    public record Imported(Placement placement, Path sourceFile, boolean schematicExists) { }

    private LitematicaPlacementImporter() { }

    public static List<Path> findDataFiles(Path litematicaConfigDir) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(litematicaConfigDir)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(litematicaConfigDir, "litematica_*.json")) {
            for (Path p : ds) {
                out.add(p);
            }
        } catch (IOException ignored) {
            // nothing to import
        }
        out.sort(null);
        return out;
    }

    public static List<Imported> read(Path dataFile) throws IOException {
        JsonElement el = JsonFiles.read(dataFile);
        List<Imported> out = new ArrayList<>();
        if (!el.isJsonObject()) {
            return out;
        }
        JsonObject root = el.getAsJsonObject();
        if (!root.has("placements") || !root.get("placements").isJsonObject()) {
            return out;
        }
        JsonObject mgr = root.getAsJsonObject("placements");
        if (!mgr.has("placements") || !mgr.get("placements").isJsonArray()) {
            return out;
        }
        for (JsonElement pe : mgr.getAsJsonArray("placements")) {
            if (!pe.isJsonObject()) {
                continue;
            }
            JsonObject o = pe.getAsJsonObject();
            try {
                String schematic = o.get("schematic").getAsString();
                JsonArray origin = o.getAsJsonArray("origin");
                Placement p = new Placement(schematic, o.has("name") ? o.get("name").getAsString() : "litematica",
                        origin.get(0).getAsInt(), origin.get(1).getAsInt(), origin.get(2).getAsInt());
                p.setRotation(Rotation.valueOf(o.get("rotation").getAsString()));
                p.setMirror(Mirror.valueOf(o.get("mirror").getAsString()));
                if (o.has("placements") && o.get("placements").isJsonArray()) {
                    for (JsonElement se : o.getAsJsonArray("placements")) {
                        JsonObject wrapper = se.getAsJsonObject();
                        JsonObject sub = wrapper.getAsJsonObject("placement");
                        if (sub == null || !sub.has("pos")) {
                            continue;
                        }
                        JsonArray pos = sub.getAsJsonArray("pos");
                        String regionName = wrapper.has("name") ? wrapper.get("name").getAsString() : sub.get("name").getAsString();
                        p.putRegion(new Placement.RegionPlacement(regionName, pos.get(0).getAsInt(), pos.get(1).getAsInt(),
                                pos.get(2).getAsInt(), Rotation.valueOf(sub.get("rotation").getAsString()),
                                Mirror.valueOf(sub.get("mirror").getAsString()),
                                !sub.has("enabled") || sub.get("enabled").getAsBoolean()));
                    }
                }
                out.add(new Imported(p, dataFile, Files.isRegularFile(Path.of(schematic))));
            } catch (RuntimeException e) {
                // Malformed entry (missing keys / unknown enum): skip it, keep the rest.
            }
        }
        return out;
    }
}
