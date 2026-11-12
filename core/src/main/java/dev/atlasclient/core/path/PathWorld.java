package dev.atlasclient.core.path;

/** Block-level world view for pathfinding. Implemented by the game layer on the client thread. */
public interface PathWorld {
    /** The player's body can occupy this block (not solid, not dangerous). */
    boolean isPassable(int x, int y, int z);

    /** The block can be stood on (solid top surface). */
    boolean isStandable(int x, int y, int z);

    /** Lava, fire, magma, cactus, powder snow, sweet berry bushes... */
    boolean isDangerous(int x, int y, int z);

    boolean isWater(int x, int y, int z);

    /** Whether the chunk containing the block is loaded; unloaded space is never entered. */
    boolean isLoaded(int x, int z);

    /** Extra cost of putting the body at this node (digging through, bridging...). 0 for plain walking. */
    default double moveCost(int x, int y, int z) { return 0; }
}
