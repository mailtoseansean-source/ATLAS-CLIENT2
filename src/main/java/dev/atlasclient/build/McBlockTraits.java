package dev.atlasclient.build;

import dev.atlasclient.core.build.DefaultBlockTraits;
import dev.atlasclient.core.schematic.BlockStateString;
import net.minecraft.block.BlockState;
import net.minecraft.block.FallingBlock;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;

/**
 * Block traits backed by vanilla where it is authoritative: gravity comes from {@link FallingBlock},
 * the required item from {@code Block.asItem()}. Attachment rules fall back to the core's
 * name-based classification.
 */
public final class McBlockTraits extends DefaultBlockTraits {
    private final StateResolver resolver;

    public McBlockTraits(StateResolver resolver) { this.resolver = resolver; }

    @Override
    public PlacementClass classify(BlockStateString s) {
        PlacementClass base = super.classify(s);
        if (base == PlacementClass.AIR || base == PlacementClass.AUTO_PART || base == PlacementClass.UNPLACEABLE) {
            return base;
        }
        BlockState state = resolver.resolve(s, null);
        if (state == null) {
            return PlacementClass.UNPLACEABLE; // unknown block id in this game version
        }
        if (state.getBlock() instanceof FallingBlock) {
            return PlacementClass.GRAVITY;
        }
        if (state.getBlock().asItem() == Items.AIR && super.requiredItem(s) == null) {
            return PlacementClass.UNPLACEABLE;
        }
        return base;
    }

    @Override
    public String requiredItem(BlockStateString s) {
        String fallback = super.requiredItem(s);
        BlockState state = resolver.resolve(s, null);
        if (state == null) {
            return fallback;
        }
        Item item = state.getBlock().asItem();
        if (item == Items.AIR) {
            return fallback; // e.g. wall torch -> torch, redstone wire -> redstone (core mapping)
        }
        return Registries.ITEM.getId(item).toString();
    }
}
