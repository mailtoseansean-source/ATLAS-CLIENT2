package dev.atlasclient.core.build;

import dev.atlasclient.core.schematic.SchematicPlacer.PlacedBlock;

/** Read-only view of the world used by the planner and verifier. Implemented by the game layer. */
public interface WorldProbe {
    enum Status {
        /** Target position is empty or replaceable (air, grass, water...). */
        EMPTY,
        /** The correct block is already there (orientation-relevant properties match). */
        MATCHES,
        /** Correct block type, but a property that can be fixed by interacting differs (repeater delay...). */
        NEEDS_ADJUSTMENT,
        /** A different block occupies the position. */
        WRONG_BLOCK,
        /** Chunk not loaded / outside the world height. */
        UNAVAILABLE
    }

    Status status(PlacedBlock target);

    /** Whether the block at a position can support an attached or falling block. */
    boolean isSolid(int x, int y, int z);
}
