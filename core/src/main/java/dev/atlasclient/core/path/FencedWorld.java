package dev.atlasclient.core.path;

/**
 * A {@link PathWorld} limited to a box: the body may not enter blocks outside it. Used by Auto Mine
 * so walks stay inside the area being dug and never wander off into the caves around it.
 */
public final class FencedWorld implements PathWorld {
    private final PathWorld inner;
    private final int minX, maxX, minY, maxY, minZ, maxZ;
    private final boolean dry;

    public FencedWorld(PathWorld inner, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        this(inner, minX, maxX, minY, maxY, minZ, maxZ, false);
    }

    /** @param dry also keep out of water (nothing can be mined from inside it) */
    public FencedWorld(PathWorld inner, int minX, int maxX, int minY, int maxY, int minZ, int maxZ, boolean dry) {
        this.inner = inner;
        this.dry = dry;
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.minZ = minZ;
        this.maxZ = maxZ;
    }

    public boolean inside(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    @Override public boolean isPassable(int x, int y, int z) {
        return inside(x, y, z) && inner.isPassable(x, y, z) && !(dry && inner.isWater(x, y, z));
    }
    @Override public boolean isStandable(int x, int y, int z) { return inner.isStandable(x, y, z); }
    @Override public boolean isDangerous(int x, int y, int z) { return inner.isDangerous(x, y, z); }
    @Override public boolean isWater(int x, int y, int z) { return inner.isWater(x, y, z); }
    @Override public boolean isLoaded(int x, int z) { return inner.isLoaded(x, z); }
    @Override public double moveCost(int x, int y, int z) { return inner.moveCost(x, y, z); }
}
