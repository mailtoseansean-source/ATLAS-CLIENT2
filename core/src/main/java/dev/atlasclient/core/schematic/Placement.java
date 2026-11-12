package dev.atlasclient.core.schematic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.atlasclient.core.schematic.transform.Mirror;
import dev.atlasclient.core.schematic.transform.Rotation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where and how a schematic is placed in the world. Mirrors Litematica's SchematicPlacement /
 * SubRegionPlacement model so placements can be imported from Litematica and produce identical
 * world coordinates.
 */
public final class Placement {
    /** Per-region override, equivalent to Litematica's SubRegionPlacement. */
    public record RegionPlacement(String name, int x, int y, int z, Rotation rotation, Mirror mirror, boolean enabled) { }

    private String schematicFile;
    private String name;
    private int originX, originY, originZ;
    private Rotation rotation = Rotation.NONE;
    private Mirror mirror = Mirror.NONE;
    private final Map<String, RegionPlacement> regions = new LinkedHashMap<>();
    private String dimension = "";

    public Placement(String schematicFile, String name, int x, int y, int z) {
        this.schematicFile = schematicFile;
        this.name = name;
        this.originX = x;
        this.originY = y;
        this.originZ = z;
    }

    public String schematicFile() { return schematicFile; }
    public String name() { return name; }
    public int originX() { return originX; }
    public int originY() { return originY; }
    public int originZ() { return originZ; }
    public Rotation rotation() { return rotation; }
    public Mirror mirror() { return mirror; }
    public String dimension() { return dimension; }

    public Placement setOrigin(int x, int y, int z) {
        originX = x;
        originY = y;
        originZ = z;
        return this;
    }

    public Placement setRotation(Rotation r) { this.rotation = r; return this; }
    public Placement setMirror(Mirror m) { this.mirror = m; return this; }
    public Placement setDimension(String d) { this.dimension = d == null ? "" : d; return this; }
    public Placement setSchematicFile(String file) { this.schematicFile = file; return this; }

    public Placement putRegion(RegionPlacement rp) {
        regions.put(rp.name(), rp);
        return this;
    }

    /** Region placement for a region; defaults to the region's own position with no transform. */
    public RegionPlacement regionPlacement(SchematicRegion r) {
        RegionPlacement rp = regions.get(r.name());
        return rp != null ? rp : new RegionPlacement(r.name(), r.posX(), r.posY(), r.posZ(), Rotation.NONE, Mirror.NONE, true);
    }

    public List<RegionPlacement> regionOverrides() { return new ArrayList<>(regions.values()); }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("schematic", schematicFile);
        o.addProperty("name", name);
        JsonArray origin = new JsonArray();
        origin.add(originX);
        origin.add(originY);
        origin.add(originZ);
        o.add("origin", origin);
        o.addProperty("rotation", rotation.name());
        o.addProperty("mirror", mirror.name());
        o.addProperty("dimension", dimension);
        JsonArray arr = new JsonArray();
        for (RegionPlacement rp : regions.values()) {
            JsonObject r = new JsonObject();
            r.addProperty("name", rp.name());
            JsonArray pos = new JsonArray();
            pos.add(rp.x());
            pos.add(rp.y());
            pos.add(rp.z());
            r.add("pos", pos);
            r.addProperty("rotation", rp.rotation().name());
            r.addProperty("mirror", rp.mirror().name());
            r.addProperty("enabled", rp.enabled());
            arr.add(r);
        }
        o.add("regions", arr);
        return o;
    }

    public static Placement fromJson(JsonObject o) {
        JsonArray origin = o.getAsJsonArray("origin");
        Placement p = new Placement(o.get("schematic").getAsString(),
                o.has("name") ? o.get("name").getAsString() : "placement",
                origin.get(0).getAsInt(), origin.get(1).getAsInt(), origin.get(2).getAsInt());
        p.rotation = Rotation.parse(o.get("rotation").getAsString());
        p.mirror = Mirror.parse(o.get("mirror").getAsString());
        if (o.has("dimension")) {
            p.dimension = o.get("dimension").getAsString();
        }
        if (o.has("regions")) {
            for (JsonElement e : o.getAsJsonArray("regions")) {
                JsonObject r = e.getAsJsonObject();
                JsonArray pos = r.getAsJsonArray("pos");
                p.putRegion(new RegionPlacement(r.get("name").getAsString(), pos.get(0).getAsInt(), pos.get(1).getAsInt(),
                        pos.get(2).getAsInt(), Rotation.parse(r.get("rotation").getAsString()),
                        Mirror.parse(r.get("mirror").getAsString()), !r.has("enabled") || r.get("enabled").getAsBoolean()));
            }
        }
        return p;
    }
}
