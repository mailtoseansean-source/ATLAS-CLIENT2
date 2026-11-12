package dev.atlasclient.core.build;

import dev.atlasclient.core.analysis.BlockHit;
import dev.atlasclient.core.build.BlockTraits.PlacementClass;
import dev.atlasclient.core.schematic.SchematicPlacer.PlacedBlock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds an ordered placement plan.
 *
 * <p>Ordering: bottom layer first; within a layer, solid blocks before gravity blocks before
 * attached blocks, so supports always exist before what they hold. Inside each group positions are
 * ordered in a serpentine sweep to keep walking short. Each entry records whether its support is
 * provided by the world, by an earlier plan entry, or is missing.</p>
 */
public final class PlacementPlanner {
    public enum Support { NONE_REQUIRED, WORLD, PLAN, MISSING }

    public record Entry(int index, PlacedBlock target, PlacementClass placementClass, String item, int itemCount,
                        int extraInteractions, Support support, WorldProbe.Status initialStatus) { }

    public record Plan(List<Entry> entries, int alreadyCorrect, int wrongBlocks, int unplaceable, int autoParts,
                       int missingSupport) {
        public int actionable() {
            int n = 0;
            for (Entry e : entries) {
                if (e.initialStatus() != WorldProbe.Status.MATCHES) n++;
            }
            return n;
        }
    }

    private PlacementPlanner() { }

    public static Plan plan(List<PlacedBlock> targets, BlockTraits traits, WorldProbe probe) {
        List<Entry> work = new ArrayList<>();
        int unplaceable = 0, autoParts = 0;
        Set<Long> planned = new HashSet<>();
        for (PlacedBlock b : targets) {
            PlacementClass pc = traits.classify(b.state());
            switch (pc) {
                case AIR -> { continue; }
                case UNPLACEABLE -> { unplaceable++; continue; }
                case AUTO_PART -> { autoParts++; continue; }
                default -> { }
            }
            planned.add(BlockHit.pack(b.x(), b.y(), b.z()));
            work.add(new Entry(-1, b, pc, traits.requiredItem(b.state()), traits.itemCount(b.state()),
                    traits.extraInteractions(b.state()), Support.NONE_REQUIRED, null));
        }
        work.sort(Comparator
                .comparingInt((Entry e) -> e.target().y())
                .thenComparingInt(e -> priority(e.placementClass()))
                .thenComparingInt(e -> e.target().z())
                .thenComparingInt(e -> (e.target().z() & 1) == 0 ? e.target().x() : -e.target().x()));

        List<Entry> out = new ArrayList<>(work.size());
        int alreadyCorrect = 0, wrong = 0, missing = 0;
        for (Entry e : work) {
            PlacedBlock t = e.target();
            Support support = Support.NONE_REQUIRED;
            if (e.placementClass() == PlacementClass.GRAVITY) {
                support = supportBelow(t, probe, planned);
            } else if (e.placementClass() == PlacementClass.ATTACHED) {
                support = supportAny(t, probe, planned);
            }
            if (support == Support.MISSING) {
                missing++;
            }
            WorldProbe.Status status = probe.status(t);
            if (status == WorldProbe.Status.MATCHES) alreadyCorrect++;
            if (status == WorldProbe.Status.WRONG_BLOCK) wrong++;
            out.add(new Entry(out.size(), t, e.placementClass(), e.item(), e.itemCount(), e.extraInteractions(), support, status));
        }
        return new Plan(out, alreadyCorrect, wrong, unplaceable, autoParts, missing);
    }

    private static int priority(PlacementClass pc) {
        return switch (pc) {
            case SOLID -> 0;
            case GRAVITY -> 1;
            case ATTACHED -> 2;
            default -> 3;
        };
    }

    private static Support supportBelow(PlacedBlock t, WorldProbe probe, Set<Long> planned) {
        if (planned.contains(BlockHit.pack(t.x(), t.y() - 1, t.z()))) return Support.PLAN;
        if (probe.isSolid(t.x(), t.y() - 1, t.z())) return Support.WORLD;
        return Support.MISSING;
    }

    private static Support supportAny(PlacedBlock t, WorldProbe probe, Set<Long> planned) {
        int[][] dirs = {{0, -1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}};
        boolean planSupport = false;
        for (int[] d : dirs) {
            int x = t.x() + d[0], y = t.y() + d[1], z = t.z() + d[2];
            if (probe.isSolid(x, y, z)) return Support.WORLD;
            if (planned.contains(BlockHit.pack(x, y, z))) planSupport = true;
        }
        return planSupport ? Support.PLAN : Support.MISSING;
    }
}
