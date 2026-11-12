package dev.atlasclient.core.build;

import dev.atlasclient.core.schematic.BlockStateString;

/**
 * Placement-relevant knowledge about blocks. The Fabric layer implements this with vanilla block
 * classes; {@link DefaultBlockTraits} is an id-based fallback used in tests and for unknown blocks.
 */
public interface BlockTraits {
    enum PlacementClass {
        /** Nothing to place. */
        AIR,
        /** Ordinary block that can be placed against any solid neighbour. */
        SOLID,
        /** Falls without support below (sand, gravel, concrete powder, anvils...). */
        GRAVITY,
        /** Needs a supporting neighbour (torches, buttons, rails, carpets, redstone, crops...). */
        ATTACHED,
        /** Second half of a multi-block that appears automatically (upper door, bed head...). */
        AUTO_PART,
        /** Cannot be placed by normal interaction (fluids sources need buckets, portals, piston heads...). */
        UNPLACEABLE
    }

    PlacementClass classify(BlockStateString state);

    /** Item id needed to place this state, or null when no item exists. */
    String requiredItem(BlockStateString state);

    /** Number of items consumed (2 for double slabs, candle count, sea pickle count...). */
    int itemCount(BlockStateString state);

    /** How many extra "use" interactions after placement are needed to reach this state (repeater delay...). */
    default int extraInteractions(BlockStateString state) { return 0; }
}
