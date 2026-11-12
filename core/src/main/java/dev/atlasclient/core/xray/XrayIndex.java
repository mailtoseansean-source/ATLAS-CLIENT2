package dev.atlasclient.core.xray;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/**
 * Positions of highlighted blocks in loaded chunks, as reported by the client's own chunk data.
 * Bounded in chunks and in blocks per chunk; thread-safe (results arrive from the scan worker and are
 * read by the renderer on the client thread).
 *
 * <p>It also judges how trustworthy the data looks. Servers with anti-xray either hide enclosed ores
 * (then everything visible is exposed to air) or add fake ones (then ore density is far above
 * natural generation). Both are reported; nothing hidden is reconstructed.</p>
 */
public final class XrayIndex {
    /** One highlighted block. {@code exposed}: at least one neighbour inside the chunk is not a full opaque block. */
    public record Hit(int x, int y, int z, String block, boolean exposed) { }

    public enum Availability {
        /** Single-player: the client sees the real world. */
        AUTHORITATIVE_LOCAL("Local world: complete"),
        NORMAL("Server data looks normal"),
        /** Every visible ore touches air: enclosed ores are probably replaced by the server. */
        LIKELY_HIDDEN("Only exposed ores visible: the server probably hides the rest (anti-xray)"),
        /** Ore counts far above natural generation: the server probably sends fake ores. */
        SUSPECT_FAKE("Ore density far above normal: the server probably sends fake ores (anti-xray)"),
        UNKNOWN("Not enough data yet");

        private final String description;
        Availability(String d) { this.description = d; }
        public String description() { return description; }
    }

    public static final int MAX_CHUNKS = 4096;
    public static final int MAX_PER_CHUNK = 4096;

    private final Map<Long, List<Hit>> byChunk = new LinkedHashMap<>(256, 0.75f, true);
    private final Map<Long, int[]> chunkStats = new HashMap<>(); // {diamondLike, total, exposed}
    private String dimension = "";
    private long version;

    public static long chunkKey(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL); }

    /** Replaces a chunk's hits. A different dimension clears everything first. */
    public synchronized void put(String dim, int cx, int cz, List<Hit> hits) {
        if (!dim.equals(dimension)) {
            clear();
            dimension = dim;
        }
        long k = chunkKey(cx, cz);
        List<Hit> copy = new ArrayList<>(hits.size() > MAX_PER_CHUNK ? hits.subList(0, MAX_PER_CHUNK) : hits);
        byChunk.put(k, copy);
        int diamonds = 0, exposed = 0;
        for (Hit h : copy) {
            if (h.block().endsWith("diamond_ore")) diamonds++;
            if (h.exposed()) exposed++;
        }
        chunkStats.put(k, new int[]{diamonds, copy.size(), exposed});
        while (byChunk.size() > MAX_CHUNKS) {
            Long eldest = byChunk.keySet().iterator().next();
            byChunk.remove(eldest);
            chunkStats.remove(eldest);
        }
        version++;
    }

    public synchronized void remove(int cx, int cz) {
        long k = chunkKey(cx, cz);
        if (byChunk.remove(k) != null) version++;
        chunkStats.remove(k);
    }

    /** Drops one block that is no longer there (mined, or replaced). */
    public synchronized void removeBlock(int x, int y, int z) {
        List<Hit> list = byChunk.get(chunkKey(x >> 4, z >> 4));
        if (list != null && list.removeIf(h -> h.x() == x && h.y() == y && h.z() == z)) version++;
    }

    public synchronized void clear() {
        byChunk.clear();
        chunkStats.clear();
        version++;
    }

    public synchronized int chunkCount() { return byChunk.size(); }
    public synchronized long version() { return version; }
    public synchronized String dimension() { return dimension; }

    public synchronized int size() {
        int n = 0;
        for (List<Hit> l : byChunk.values()) n += l.size();
        return n;
    }

    /** Nearest hits first, within {@code maxDistance} of the point, limited to {@code max}. */
    public synchronized List<Hit> nearest(double px, double py, double pz, double maxDistance, int max, boolean exposedOnly,
                                          Predicate<String> enabled) {
        double max2 = maxDistance * maxDistance;
        int r = (int) Math.ceil(maxDistance / 16.0) + 1;
        int pcx = (int) Math.floor(px) >> 4, pcz = (int) Math.floor(pz) >> 4;
        List<Hit> out = new ArrayList<>();
        for (Map.Entry<Long, List<Hit>> e : byChunk.entrySet()) {
            long k = e.getKey();
            int cx = (int) (k >> 32), cz = (int) k;
            if (Math.abs(cx - pcx) > r || Math.abs(cz - pcz) > r) continue;
            for (Hit h : e.getValue()) {
                if (exposedOnly && !h.exposed()) continue;
                if (!enabled.test(h.block())) continue;
                double dx = h.x() + 0.5 - px, dy = h.y() + 0.5 - py, dz = h.z() + 0.5 - pz;
                if (dx * dx + dy * dy + dz * dz <= max2) out.add(h);
            }
        }
        out.sort(Comparator.comparingDouble(h -> {
            double dx = h.x() + 0.5 - px, dy = h.y() + 0.5 - py, dz = h.z() + 0.5 - pz;
            return dx * dx + dy * dy + dz * dz;
        }));
        return out.size() > max ? new ArrayList<>(out.subList(0, Math.max(0, max))) : out;
    }

    /** Count per block id within a distance (for the HUD). */
    public synchronized Map<String, Integer> counts(double px, double py, double pz, double maxDistance, Predicate<String> enabled) {
        Map<String, Integer> m = new TreeMap<>();
        for (Hit h : nearest(px, py, pz, maxDistance, Integer.MAX_VALUE, false, enabled)) m.merge(h.block(), 1, Integer::sum);
        return m;
    }

    /**
     * Assessment over the scanned chunks. Natural generation gives a few diamond ores per chunk at
     * most (veins of up to ~10, rarely two veins); dozens per chunk only happen with fake ores.
     */
    public synchronized Availability availability(boolean authoritative) {
        if (authoritative) return Availability.AUTHORITATIVE_LOCAL;
        int chunks = 0, diamonds = 0, total = 0, exposed = 0, heavy = 0;
        for (int[] s : chunkStats.values()) {
            chunks++;
            diamonds += s[0];
            total += s[1];
            exposed += s[2];
            if (s[0] > 40) heavy++;
        }
        if (chunks < 8 || total < 30) return Availability.UNKNOWN;
        if (heavy >= 2 || (double) diamonds / chunks > 12.0) return Availability.SUSPECT_FAKE;
        if (exposed == total) return Availability.LIKELY_HIDDEN;
        return Availability.NORMAL;
    }
}
