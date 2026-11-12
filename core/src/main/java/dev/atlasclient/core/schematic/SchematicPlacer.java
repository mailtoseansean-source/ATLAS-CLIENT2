package dev.atlasclient.core.schematic;

import dev.atlasclient.core.schematic.transform.Transforms;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes world positions for every block of a placed schematic using exactly Litematica's math
 * (SchematicPlacingUtils.placeBlocksWithinChunk):
 *
 * <pre>
 *   rel   = (regionMin - regionPos) + (x, y, z)            // relative to the region's origin corner
 *   world = T_sub(T_main(rel)) + T_main(regionPos) + origin
 * </pre>
 * where {@code T(p) = rotate(mirror(p))}. Structure-void cells are skipped like Litematica does.
 */
public final class SchematicPlacer {
    private SchematicPlacer() { }

    /** One target block in world space. */
    public record PlacedBlock(int x, int y, int z, BlockStateString state, Transforms.StateTransform transform, String region) { }

    public static List<PlacedBlock> place(Schematic schematic, Placement placement, boolean includeAir) {
        List<PlacedBlock> out = new ArrayList<>();
        for (SchematicRegion region : schematic.regions()) {
            Placement.RegionPlacement rp = placement.regionPlacement(region);
            if (!rp.enabled()) {
                continue;
            }
            int[] regionPosT = Transforms.transform(rp.x(), rp.y(), rp.z(), placement.mirror(), placement.rotation());
            int offX = regionPosT[0] + placement.originX();
            int offY = regionPosT[1] + placement.originY();
            int offZ = regionPosT[2] + placement.originZ();
            // Region min corner relative to region origin corner, using the placement's region pos.
            int endX = rp.x() + (region.sizeX() >= 0 ? region.sizeX() - 1 : region.sizeX() + 1);
            int endY = rp.y() + (region.sizeY() >= 0 ? region.sizeY() - 1 : region.sizeY() + 1);
            int endZ = rp.z() + (region.sizeZ() >= 0 ? region.sizeZ() - 1 : region.sizeZ() + 1);
            int minRelX = Math.min(rp.x(), endX) - rp.x();
            int minRelY = Math.min(rp.y(), endY) - rp.y();
            int minRelZ = Math.min(rp.z(), endZ) - rp.z();
            Transforms.StateTransform st = Transforms.StateTransform.of(placement.mirror(), placement.rotation(), rp.mirror(), rp.rotation());
            for (int y = 0; y < region.absY(); y++) {
                for (int z = 0; z < region.absZ(); z++) {
                    for (int x = 0; x < region.absX(); x++) {
                        BlockStateString state = region.stateAt(x, y, z);
                        if (state.isStructureVoid() || (!includeAir && state.isAir())) {
                            continue;
                        }
                        int[] p = Transforms.transform(minRelX + x, minRelY + y, minRelZ + z, placement.mirror(), placement.rotation());
                        p = Transforms.transform(p[0], p[1], p[2], rp.mirror(), rp.rotation());
                        out.add(new PlacedBlock(p[0] + offX, p[1] + offY, p[2] + offZ, state, st, region.name()));
                    }
                }
            }
        }
        return out;
    }

    /** World-space bounding box {minX, minY, minZ, maxX, maxY, maxZ} of all enabled regions. */
    public static int[] bounds(Schematic schematic, Placement placement) {
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (SchematicRegion region : schematic.regions()) {
            Placement.RegionPlacement rp = placement.regionPlacement(region);
            if (!rp.enabled()) {
                continue;
            }
            int[] regionPosT = Transforms.transform(rp.x(), rp.y(), rp.z(), placement.mirror(), placement.rotation());
            int endX = rp.x() + (region.sizeX() >= 0 ? region.sizeX() - 1 : region.sizeX() + 1);
            int endY = rp.y() + (region.sizeY() >= 0 ? region.sizeY() - 1 : region.sizeY() + 1);
            int endZ = rp.z() + (region.sizeZ() >= 0 ? region.sizeZ() - 1 : region.sizeZ() + 1);
            int[][] corners = {{rp.x(), rp.y(), rp.z()}, {endX, endY, endZ}};
            for (int[] c1 : corners) {
                for (int[] c2 : corners) {
                    for (int[] c3 : corners) {
                        int[] rel = {c1[0] - rp.x(), c2[1] - rp.y(), c3[2] - rp.z()};
                        int[] p = Transforms.transform(rel[0], rel[1], rel[2], placement.mirror(), placement.rotation());
                        p = Transforms.transform(p[0], p[1], p[2], rp.mirror(), rp.rotation());
                        int wx = p[0] + regionPosT[0] + placement.originX();
                        int wy = p[1] + regionPosT[1] + placement.originY();
                        int wz = p[2] + regionPosT[2] + placement.originZ();
                        b[0] = Math.min(b[0], wx); b[1] = Math.min(b[1], wy); b[2] = Math.min(b[2], wz);
                        b[3] = Math.max(b[3], wx); b[4] = Math.max(b[4], wy); b[5] = Math.max(b[5], wz);
                    }
                }
            }
        }
        return b;
    }
}
