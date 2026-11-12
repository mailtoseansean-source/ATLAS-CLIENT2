package dev.atlasclient.core.schematic;

import java.util.List;

/** A loaded schematic: metadata plus one or more regions. Immutable. */
public record Schematic(String name, String author, String description, Format format, int dataVersion,
                        List<SchematicRegion> regions, String sourceFile) {
    public enum Format { SPONGE_V1, SPONGE_V2, SPONGE_V3, LITEMATIC, BUILT_IN }

    public Schematic {
        if (regions.isEmpty()) {
            throw new IllegalArgumentException("Schematic has no regions");
        }
        regions = List.copyOf(regions);
    }

    public SchematicRegion region(String regionName) {
        for (SchematicRegion r : regions) {
            if (r.name().equals(regionName)) return r;
        }
        return null;
    }

    public long totalVolume() {
        long v = 0;
        for (SchematicRegion r : regions) v += r.volume();
        return v;
    }

    public long totalNonAir() {
        long v = 0;
        for (SchematicRegion r : regions) v += r.countNonAir();
        return v;
    }

    /** Enclosing box size of all regions (untransformed). */
    public int[] enclosingSize() {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (SchematicRegion r : regions) {
            minX = Math.min(minX, r.minX());
            minY = Math.min(minY, r.minY());
            minZ = Math.min(minZ, r.minZ());
            maxX = Math.max(maxX, r.minX() + r.absX() - 1);
            maxY = Math.max(maxY, r.minY() + r.absY() - 1);
            maxZ = Math.max(maxZ, r.minZ() + r.absZ() - 1);
        }
        return new int[]{maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1};
    }
}
