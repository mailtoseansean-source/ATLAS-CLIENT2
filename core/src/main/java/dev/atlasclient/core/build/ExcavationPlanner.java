package dev.atlasclient.core.build;

import dev.atlasclient.core.schematic.SchematicPlacer.PlacedBlock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Plans the "dig out the space" phase that runs before building, e.g. for underground builds.
 *
 * <p>Every position inside the schematic (including positions the schematic marks as air) whose
 * current block does not belong there is scheduled for removal, top layer first so falling blocks
 * from above are mined again. Positions holding fluid, unbreakable blocks, or blocks whose removal
 * would open onto lava/water are not mined and are reported instead.</p>
 */
public final class ExcavationPlanner {
    /** What the world currently holds at a target position, as seen by the client. */
    public interface Probe {
        enum Cell {
            /** Air or replaceable (grass, snow layer...) - nothing to dig. */
            EMPTY,
            /** Already the exact target block - keep it. */
            KEEP,
            /** A breakable block that must go. */
            REMOVE,
            /** Bedrock, barrier, end portal frame... */
            UNBREAKABLE,
            /** Water or lava occupies the cell. */
            FLUID,
            /** Chunk not loaded / outside world height. */
            UNAVAILABLE
        }

        Cell classify(PlacedBlock target);

        /** True if a neighbour of this position (outside the dig set) contains lava or water. */
        boolean neighbourHasFluid(int x, int y, int z);
    }

    public enum Skip { NONE, FLUID_CELL, FLUID_NEIGHBOUR, UNBREAKABLE, UNAVAILABLE }

    public record Entry(int index, int x, int y, int z, Skip skip) { }

    public record Plan(List<Entry> entries, int toRemove, int fluidCells, int fluidNeighbours, int unbreakable, int unavailable) {
        public boolean nothingToDo() { return toRemove == 0; }
    }

    private ExcavationPlanner() { }

    /**
     * @param targets  every schematic position, including air (use {@code SchematicPlacer.place(..., true)})
     * @param avoidFluidBreach when true, blocks next to lava/water are not mined
     */
    public static Plan plan(List<PlacedBlock> targets, Probe probe, boolean avoidFluidBreach) {
        int fluid = 0, fluidN = 0, unbreakable = 0, unavailable = 0, remove = 0;
        List<Entry> tmp = new ArrayList<>();
        for (PlacedBlock t : targets) {
            Probe.Cell cell = probe.classify(t);
            Skip skip;
            switch (cell) {
                case EMPTY, KEEP -> { continue; }
                case FLUID -> { skip = Skip.FLUID_CELL; fluid++; }
                case UNBREAKABLE -> { skip = Skip.UNBREAKABLE; unbreakable++; }
                case UNAVAILABLE -> { skip = Skip.UNAVAILABLE; unavailable++; }
                default -> {
                    if (avoidFluidBreach && probe.neighbourHasFluid(t.x(), t.y(), t.z())) {
                        skip = Skip.FLUID_NEIGHBOUR;
                        fluidN++;
                    } else {
                        skip = Skip.NONE;
                        remove++;
                    }
                }
            }
            tmp.add(new Entry(-1, t.x(), t.y(), t.z(), skip));
        }
        tmp.sort(Comparator.comparingInt((Entry e) -> -e.y())
                .thenComparingInt(Entry::z)
                .thenComparingInt(e -> (e.z() & 1) == 0 ? e.x() : -e.x()));
        List<Entry> out = new ArrayList<>(tmp.size());
        for (Entry e : tmp) {
            out.add(new Entry(out.size(), e.x(), e.y(), e.z(), e.skip()));
        }
        return new Plan(out, remove, fluid, fluidN, unbreakable, unavailable);
    }
}
