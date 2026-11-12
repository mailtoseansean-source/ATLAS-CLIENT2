package dev.atlasclient.core.build;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Required vs available materials for the remaining part of a build. */
public final class MaterialList {
    public record Line(String item, int required, int available) {
        public int missing() { return Math.max(0, required - available); }
        public boolean satisfied() { return available >= required; }
    }

    private final List<Line> lines;

    private MaterialList(List<Line> lines) { this.lines = List.copyOf(lines); }

    /**
     * @param entries   plan entries still to be placed
     * @param inventory item id -> count currently held
     * @param creative  creative mode: everything is available
     */
    public static MaterialList compute(Iterable<PlacementPlanner.Entry> entries, Map<String, Integer> inventory, boolean creative) {
        Map<String, Integer> req = new TreeMap<>();
        for (PlacementPlanner.Entry e : entries) {
            if (e.item() != null && e.itemCount() > 0) {
                req.merge(e.item(), e.itemCount(), Integer::sum);
            }
        }
        List<Line> lines = new ArrayList<>();
        for (Map.Entry<String, Integer> r : req.entrySet()) {
            int have = creative ? r.getValue() : inventory.getOrDefault(r.getKey(), 0);
            lines.add(new Line(r.getKey(), r.getValue(), have));
        }
        lines.sort(Comparator.comparingInt(Line::missing).reversed().thenComparing(Line::item));
        return new MaterialList(lines);
    }

    public List<Line> lines() { return lines; }

    public List<Line> missingLines() {
        List<Line> m = new ArrayList<>();
        for (Line l : lines) {
            if (!l.satisfied()) m.add(l);
        }
        return m;
    }

    public int totalRequired() {
        int n = 0;
        for (Line l : lines) n += l.required();
        return n;
    }

    public int totalMissing() {
        int n = 0;
        for (Line l : lines) n += l.missing();
        return n;
    }

    public boolean complete() { return totalMissing() == 0; }
}
