package dev.atlasclient.build;

import dev.atlasclient.core.build.ExcavationPlanner;
import dev.atlasclient.core.build.WorldProbe;
import dev.atlasclient.core.path.PathWorld;
import dev.atlasclient.core.schematic.SchematicPlacer.PlacedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Game-backed implementations of the core's world views (build probe, excavation probe and
 * pathfinding world). Reads only the client world; client thread only.
 */
public final class McWorld implements WorldProbe, ExcavationPlanner.Probe, PathWorld {
    private final ClientWorld world;
    private final StateResolver resolver;
    private final BlockPos.Mutable pos = new BlockPos.Mutable();

    public McWorld(ClientWorld world, StateResolver resolver) {
        this.world = world;
        this.resolver = resolver;
    }

    public ClientWorld world() { return world; }

    private BlockState at(int x, int y, int z) { return world.getBlockState(pos.set(x, y, z)); }

    private boolean loadedAt(int x, int y, int z) {
        return world.getChunkManager().isChunkLoaded(x >> 4, z >> 4) && !world.isOutOfHeightLimit(y);
    }

    // ------------------------------------------------------------------ WorldProbe

    @Override
    public Status status(PlacedBlock t) {
        if (!loadedAt(t.x(), t.y(), t.z())) {
            return Status.UNAVAILABLE;
        }
        BlockState wanted = resolver.resolve(t.state(), t.transform());
        BlockState actual = at(t.x(), t.y(), t.z());
        if (wanted == null) {
            return Status.UNAVAILABLE;
        }
        if (actual.isAir() || (actual.isReplaceable() && actual.getBlock() != wanted.getBlock())
                || StateResolver.isMissingSecondSlab(actual, wanted)) {
            return Status.EMPTY;
        }
        return switch (StateResolver.compare(actual, wanted)) {
            case SAME -> Status.MATCHES;
            case ADJUSTABLE_DIFFERENCE -> Status.NEEDS_ADJUSTMENT;
            default -> Status.WRONG_BLOCK;
        };
    }

    @Override
    public boolean isSolid(int x, int y, int z) {
        BlockState s = at(x, y, z);
        return !s.isAir() && !s.isReplaceable() && s.getFluidState().isEmpty();
    }

    // ------------------------------------------------------------------ ExcavationPlanner.Probe

    @Override
    public Cell classify(PlacedBlock t) {
        if (!loadedAt(t.x(), t.y(), t.z())) {
            return Cell.UNAVAILABLE;
        }
        BlockState actual = at(t.x(), t.y(), t.z());
        if (!actual.getFluidState().isEmpty() && (actual.isAir() || actual.isReplaceable() || actual.isOf(Blocks.WATER) || actual.isOf(Blocks.LAVA))) {
            return Cell.FLUID;
        }
        if (actual.isAir() || actual.isReplaceable()) {
            return Cell.EMPTY;
        }
        if (!t.state().isAir()) {
            BlockState wanted = resolver.resolve(t.state(), t.transform());
            if (wanted != null && StateResolver.compare(actual, wanted) != StateResolver.Match.DIFFERENT_BLOCK
                    && StateResolver.compare(actual, wanted) != StateResolver.Match.DIFFERENT_STATE) {
                return Cell.KEEP;
            }
        }
        float hardness = actual.getHardness(world, pos.set(t.x(), t.y(), t.z()));
        if (hardness < 0) {
            return Cell.UNBREAKABLE;
        }
        return Cell.REMOVE;
    }

    @Override
    public boolean neighbourHasFluid(int x, int y, int z) {
        for (Direction d : Direction.values()) {
            FluidState f = at(x + d.getOffsetX(), y + d.getOffsetY(), z + d.getOffsetZ()).getFluidState();
            if (!f.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ PathWorld

    @Override
    public boolean isPassable(int x, int y, int z) {
        BlockState s = at(x, y, z);
        return s.getCollisionShape(world, pos.set(x, y, z)).isEmpty() && !s.getFluidState().isIn(FluidTags.LAVA);
    }

    @Override
    public boolean isStandable(int x, int y, int z) {
        BlockState s = at(x, y, z);
        if (s.isAir() || s.isOf(Blocks.MAGMA_BLOCK)) return false; // never plan to stand on magma
        var shape = s.getCollisionShape(world, pos.set(x, y, z));
        return !shape.isEmpty() && shape.getMax(Direction.Axis.Y) >= 0.5;
    }

    @Override
    public boolean isDangerous(int x, int y, int z) {
        BlockState s = at(x, y, z);
        if (s.getFluidState().isIn(FluidTags.LAVA)) return true;
        return s.isOf(Blocks.FIRE) || s.isOf(Blocks.SOUL_FIRE) || s.isOf(Blocks.CACTUS) || s.isOf(Blocks.SWEET_BERRY_BUSH)
                || s.isOf(Blocks.POWDER_SNOW) || s.isOf(Blocks.WITHER_ROSE) || s.isOf(Blocks.CAMPFIRE) || s.isOf(Blocks.SOUL_CAMPFIRE);
    }

    @Override
    public boolean isWater(int x, int y, int z) {
        return at(x, y, z).getFluidState().isIn(FluidTags.WATER);
    }

    @Override
    public boolean isLoaded(int x, int z) {
        return world.getChunkManager().isChunkLoaded(x >> 4, z >> 4);
    }
}
