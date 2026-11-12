package dev.atlasclient.core.analysis;

/**
 * Which cells of one chunk are air and which are water, as copied from the block data the server
 * sent. Immutable once built; read on the analysis thread. Index = (y - minY) * 256 + z * 16 + x.
 */
public final class AirGrid {
    public final int chunkX, chunkZ, minY, height;
    private final long[] air;
    private final long[] water;
    /** Highest non-air block per column (x + z * 16); geometry is only looked for this far below it. */
    private final int[] surface;
    /** Cells must be at least this many blocks under the surface to count as underground. */
    public final int depth;

    public AirGrid(int chunkX, int chunkZ, int minY, int height, int[] surface, int depth) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minY = minY;
        this.height = height;
        this.air = new long[(height * 256 + 63) / 64];
        this.water = new long[(height * 256 + 63) / 64];
        this.surface = surface;
        this.depth = Math.max(3, depth);
    }

    private int index(int x, int y, int z) { return (y - minY) * 256 + z * 16 + x; }

    public boolean inside(int x, int y, int z) {
        return x >= 0 && x < 16 && z >= 0 && z < 16 && y >= minY && y < minY + height;
    }

    /** Builder side (snapshot thread). */
    public void setAir(int x, int y, int z) {
        int i = index(x, y, z);
        air[i >>> 6] |= 1L << (i & 63);
    }

    public void setWater(int x, int y, int z) {
        int i = index(x, y, z);
        water[i >>> 6] |= 1L << (i & 63);
    }

    public boolean air(int x, int y, int z) {
        if (!inside(x, y, z)) return false;
        int i = index(x, y, z);
        return (air[i >>> 6] & (1L << (i & 63))) != 0;
    }

    public boolean water(int x, int y, int z) {
        if (!inside(x, y, z)) return false;
        int i = index(x, y, z);
        return (water[i >>> 6] & (1L << (i & 63))) != 0;
    }

    /** Neither air nor water (a block), or outside the chunk vertically. */
    public boolean solid(int x, int y, int z) {
        if (y < minY || y >= minY + height) return true;
        return !air(x, y, z) && !water(x, y, z);
    }

    public int surface(int x, int z) { return surface == null ? Integer.MAX_VALUE : surface[x + z * 16]; }

    public boolean underground(int x, int y, int z) { return y <= surface(x, z) - depth; }
}
