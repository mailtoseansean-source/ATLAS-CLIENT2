package dev.atlasclient.core.schematic;

import java.util.List;
import java.util.Objects;

/**
 * One box of blocks. Follows Litematica's model: {@code position} is the region's origin corner
 * relative to the schematic origin and {@code size} may be negative on any axis, meaning the box
 * extends in the negative direction from that corner. Blocks are stored relative to the region's
 * minimum corner with index {@code (y * sizeZ + z) * sizeX + x} using absolute sizes.
 */
public final class SchematicRegion {
    private final String name;
    private final int posX, posY, posZ;
    private final int sizeX, sizeY, sizeZ; // signed
    private final List<BlockStateString> palette;
    private final int[] blocks;

    public SchematicRegion(String name, int posX, int posY, int posZ, int sizeX, int sizeY, int sizeZ,
                           List<BlockStateString> palette, int[] blocks) {
        if (sizeX == 0 || sizeY == 0 || sizeZ == 0) {
            throw new IllegalArgumentException("Region '" + name + "' has a zero dimension");
        }
        long volume = (long) Math.abs(sizeX) * Math.abs(sizeY) * Math.abs(sizeZ);
        if (volume > 64L * 1024 * 1024) {
            throw new IllegalArgumentException("Region '" + name + "' is too large: " + volume + " blocks");
        }
        if (blocks.length != volume) {
            throw new IllegalArgumentException("Region '" + name + "' block data length " + blocks.length + " != volume " + volume);
        }
        if (palette.isEmpty()) {
            throw new IllegalArgumentException("Region '" + name + "' has an empty palette");
        }
        for (int b : blocks) {
            if (b < 0 || b >= palette.size()) {
                throw new IllegalArgumentException("Region '" + name + "' references palette index " + b + " of " + palette.size());
            }
        }
        this.name = Objects.requireNonNull(name);
        this.posX = posX;
        this.posY = posY;
        this.posZ = posZ;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.palette = List.copyOf(palette);
        this.blocks = blocks;
    }

    public String name() { return name; }
    public int posX() { return posX; }
    public int posY() { return posY; }
    public int posZ() { return posZ; }
    public int sizeX() { return sizeX; }
    public int sizeY() { return sizeY; }
    public int sizeZ() { return sizeZ; }
    public int absX() { return Math.abs(sizeX); }
    public int absY() { return Math.abs(sizeY); }
    public int absZ() { return Math.abs(sizeZ); }
    public long volume() { return (long) absX() * absY() * absZ(); }
    public List<BlockStateString> palette() { return palette; }

    /** End corner relative to the schematic origin (Litematica: getRelativeEndPositionFromAreaSize + pos). */
    public int endX() { return posX + (sizeX >= 0 ? sizeX - 1 : sizeX + 1); }
    public int endY() { return posY + (sizeY >= 0 ? sizeY - 1 : sizeY + 1); }
    public int endZ() { return posZ + (sizeZ >= 0 ? sizeZ - 1 : sizeZ + 1); }

    /** Minimum corner relative to the schematic origin. */
    public int minX() { return Math.min(posX, endX()); }
    public int minY() { return Math.min(posY, endY()); }
    public int minZ() { return Math.min(posZ, endZ()); }

    public int index(int x, int y, int z) { return (y * absZ() + z) * absX() + x; }

    /** State at coordinates relative to the region's minimum corner. */
    public BlockStateString stateAt(int x, int y, int z) { return palette.get(blocks[index(x, y, z)]); }

    public int paletteIndexAt(int x, int y, int z) { return blocks[index(x, y, z)]; }

    public int[] rawBlocks() { return blocks.clone(); }

    public long countNonAir() {
        long n = 0;
        boolean[] air = new boolean[palette.size()];
        for (int i = 0; i < air.length; i++) {
            air[i] = palette.get(i).isAir() || palette.get(i).isStructureVoid();
        }
        for (int b : blocks) {
            if (!air[b]) n++;
        }
        return n;
    }
}
