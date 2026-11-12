package dev.atlasclient.core.analysis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides which chunks to scan next under a per-tick budget. Chunks closest to the player go first;
 * already-scanned chunks are re-queued only after the rescan interval. Client thread only.
 */
public final class ChunkScanQueue {
    private final Map<Long, Long> pending = new HashMap<>();      // key -> enqueue time
    private final Map<Long, Long> lastScanned = new HashMap<>();  // key -> scan time
    private long rescanIntervalMillis = 30_000;
    private int maxTracked = 20_000;

    public void setRescanIntervalMillis(long millis) { this.rescanIntervalMillis = Math.max(1000, millis); }

    public void setMaxTracked(int max) { this.maxTracked = Math.max(256, max); }

    public void enqueue(int cx, int cz, long now) {
        pending.putIfAbsent(ChunkObservation.chunkKey(cx, cz), now);
    }

    /** Forces a rescan regardless of interval (e.g. after block updates). */
    public void invalidate(int cx, int cz, long now) {
        long k = ChunkObservation.chunkKey(cx, cz);
        lastScanned.remove(k);
        pending.put(k, now);
    }

    public void remove(int cx, int cz) {
        long k = ChunkObservation.chunkKey(cx, cz);
        pending.remove(k);
        lastScanned.remove(k);
    }

    /** Re-queues loaded chunks whose last scan is older than the interval. */
    public void requeueStale(Iterable<long[]> loadedChunks, long now) {
        for (long[] c : loadedChunks) {
            long k = ChunkObservation.chunkKey((int) c[0], (int) c[1]);
            Long last = lastScanned.get(k);
            if (last == null || now - last >= rescanIntervalMillis) {
                pending.putIfAbsent(k, now);
            }
        }
    }

    /**
     * Pops up to {@code budget} chunks nearest to the player chunk, marking them as scanned.
     * Returns [cx, cz] pairs.
     */
    public List<int[]> poll(int budget, int playerCx, int playerCz, int maxRadius, long now) {
        List<int[]> out = new ArrayList<>();
        if (budget <= 0 || pending.isEmpty()) {
            return out;
        }
        List<Map.Entry<Long, Long>> entries = new ArrayList<>(pending.entrySet());
        entries.sort((a, b) -> Long.compare(dist2(a.getKey(), playerCx, playerCz), dist2(b.getKey(), playerCx, playerCz)));
        long maxR2 = (long) maxRadius * maxRadius;
        for (Map.Entry<Long, Long> e : entries) {
            long k = e.getKey();
            if (dist2(k, playerCx, playerCz) > maxR2) {
                pending.remove(k); // out of range: drop, will be re-added on load
                continue;
            }
            if (out.size() >= budget) {
                break;
            }
            pending.remove(k);
            lastScanned.put(k, now);
            out.add(new int[]{ChunkObservation.keyX(k), ChunkObservation.keyZ(k)});
        }
        if (lastScanned.size() > maxTracked) {
            lastScanned.clear();
        }
        return out;
    }

    private static long dist2(long key, int px, int pz) {
        long dx = ChunkObservation.keyX(key) - px;
        long dz = ChunkObservation.keyZ(key) - pz;
        return dx * dx + dz * dz;
    }

    public int pendingCount() { return pending.size(); }

    public boolean wasScanned(int cx, int cz) { return lastScanned.containsKey(ChunkObservation.chunkKey(cx, cz)); }

    public void clear() {
        pending.clear();
        lastScanned.clear();
    }
}
