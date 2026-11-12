package dev.atlasclient.core.debris;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.atlasclient.core.analysis.BlockHit;
import dev.atlasclient.core.analysis.ChunkObservation;
import dev.atlasclient.core.config.JsonFiles;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks ancient debris per dimension without duplicates, keyed by block position. Each scan of a
 * chunk replaces that chunk's records, so mined debris disappears. Also gathers the statistics used
 * to decide whether the server is masking ore data. Thread-safe.
 */
public final class DebrisTracker {
    /** Per-chunk scan statistics used for availability detection. */
    public record ChunkStats(int debris, int enclosed, int naturalOres, int scannedSections) { }

    public record NewFinds(List<DebrisRecord> added, int removed) { }

    private final Map<String, Map<Long, DebrisRecord>> byDimension = new HashMap<>();
    private final Map<String, Map<Long, ChunkStats>> stats = new HashMap<>();
    private final int maxRecords;
    private boolean authoritative;

    /** Average debris per scanned Nether chunk above which fake-ore anti-xray is suspected. */
    private double fakeDensityThreshold = 12.0;
    /** Minimum scanned Nether chunks before judging hiding. */
    private int minChunksForJudgement = 16;
    /** Nether quartz/gold ore per chunk below which ores are considered hidden. */
    private double hiddenOreThreshold = 2.0;

    public DebrisTracker(int maxRecords) { this.maxRecords = maxRecords; }

    public synchronized void setAuthoritative(boolean value) { this.authoritative = value; }

    public synchronized void setThresholds(double fakeDensity, int minChunks, double hiddenOre) {
        this.fakeDensityThreshold = fakeDensity;
        this.minChunksForJudgement = Math.max(1, minChunks);
        this.hiddenOreThreshold = hiddenOre;
    }

    /**
     * Replaces all records in the given chunk with the debris just observed there.
     *
     * @param found        debris observed in the chunk (positions + exposure)
     * @param naturalOres  count of nether quartz + nether gold ore seen in the same chunk
     */
    public synchronized NewFinds observeChunk(String dimension, int cx, int cz, List<Found> found, int naturalOres,
                                              int scannedSections, DebrisRecord.Source source, long now) {
        Map<Long, DebrisRecord> dim = byDimension.computeIfAbsent(dimension, k -> new LinkedHashMap<>());
        int minX = cx << 4, minZ = cz << 4;
        int removed = 0;
        Map<Long, DebrisRecord> previous = new HashMap<>();
        var it = dim.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            DebrisRecord r = e.getValue();
            if (r.x() >= minX && r.x() < minX + 16 && r.z() >= minZ && r.z() < minZ + 16) {
                previous.put(e.getKey(), r);
                it.remove();
            }
        }
        List<DebrisRecord> added = new ArrayList<>();
        int enclosed = 0;
        for (Found f : found) {
            long k = BlockHit.pack(f.x(), f.y(), f.z());
            DebrisRecord prev = previous.remove(k);
            DebrisRecord rec = prev != null ? prev.seenAgain(now, f.exposure())
                    : new DebrisRecord(dimension, f.x(), f.y(), f.z(), f.exposure(), source, now, now);
            if (prev == null) {
                added.add(rec);
            }
            if (f.exposure() == DebrisRecord.Exposure.ENCLOSED) {
                enclosed++;
            }
            dim.put(k, rec);
        }
        removed = previous.size();
        if (dim.size() > maxRecords) {
            // Full (a server sending fake debris fills it fast): drop enclosed records first, oldest first,
            // down to 90% so this doesn't run for every chunk. Exposed debris is what can be trusted.
            int target = maxRecords * 9 / 10;
            for (var ev = dim.entrySet().iterator(); ev.hasNext() && dim.size() > target; ) {
                if (ev.next().getValue().exposure() == DebrisRecord.Exposure.ENCLOSED) ev.remove();
            }
            for (var ev = dim.entrySet().iterator(); ev.hasNext() && dim.size() > target; ) {
                ev.next();
                ev.remove();
            }
        }
        stats.computeIfAbsent(dimension, k -> new HashMap<>())
                .put(ChunkObservation.chunkKey(cx, cz), new ChunkStats(found.size(), enclosed, naturalOres, scannedSections));
        return new NewFinds(added, removed);
    }

    /** Raw observation used as input to {@link #observeChunk}. */
    public record Found(int x, int y, int z, DebrisRecord.Exposure exposure) { }

    public synchronized DataAvailability availability(String dimension) {
        if (!dimension.endsWith("the_nether")) {
            return DataAvailability.NOT_IN_NETHER;
        }
        if (authoritative) {
            return DataAvailability.AUTHORITATIVE_LOCAL;
        }
        Map<Long, ChunkStats> s = stats.get(dimension);
        if (s == null || s.isEmpty()) {
            return DataAvailability.NO_DATA;
        }
        int chunks = 0;
        long debris = 0, ores = 0;
        for (ChunkStats cs : s.values()) {
            if (cs.scannedSections() < 4) {
                continue; // mostly-empty chunk data, not informative
            }
            chunks++;
            debris += cs.debris();
            ores += cs.naturalOres();
        }
        if (chunks == 0) {
            return DataAvailability.NO_DATA;
        }
        double debrisPerChunk = debris / (double) chunks;
        double oresPerChunk = ores / (double) chunks;
        if (debrisPerChunk > fakeDensityThreshold) {
            return DataAvailability.SUSPECT_FAKE_ORES;
        }
        if (chunks >= minChunksForJudgement && oresPerChunk < hiddenOreThreshold) {
            return DataAvailability.UNAVAILABLE_HIDDEN;
        }
        return DataAvailability.CONSISTENT;
    }

    public synchronized int scannedChunks(String dimension) {
        Map<Long, ChunkStats> s = stats.get(dimension);
        return s == null ? 0 : s.size();
    }

    /**
     * Records in a dimension within {@code maxDistance} of the player, nearest first.
     *
     * @param exposedOnly when true, return only exposed debris (useful on anti-xray servers)
     */
    public synchronized List<DebrisRecord> query(String dimension, double px, double py, double pz,
                                                 double maxDistance, boolean exposedOnly) {
        Map<Long, DebrisRecord> dim = byDimension.get(dimension);
        List<DebrisRecord> out = new ArrayList<>();
        if (dim == null) {
            return out;
        }
        for (DebrisRecord r : dim.values()) {
            if (exposedOnly && r.exposure() != DebrisRecord.Exposure.EXPOSED) {
                continue;
            }
            if (r.distanceTo(px, py, pz) <= maxDistance) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparingDouble(r -> r.distanceTo(px, py, pz)));
        return out;
    }

    public synchronized List<DebrisRecord> all(String dimension) {
        Map<Long, DebrisRecord> dim = byDimension.get(dimension);
        return dim == null ? List.of() : new ArrayList<>(dim.values());
    }

    public synchronized int count(String dimension) {
        Map<Long, DebrisRecord> dim = byDimension.get(dimension);
        return dim == null ? 0 : dim.size();
    }

    public synchronized void forgetChunk(String dimension, int cx, int cz) {
        Map<Long, ChunkStats> s = stats.get(dimension);
        if (s != null) {
            s.remove(ChunkObservation.chunkKey(cx, cz));
        }
    }

    public synchronized void clear() {
        byDimension.clear();
        stats.clear();
    }

    // ------------------------------------------------------------------ export

    public enum ExportFormat { CSV, JSON }

    /** Writes discoveries to a local file. Returns the number of records written. */
    public int export(Path file, String dimension, ExportFormat format, double px, double py, double pz) throws IOException {
        List<DebrisRecord> list;
        synchronized (this) {
            list = query(dimension, px, py, pz, Double.MAX_VALUE, false);
        }
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (format == ExportFormat.CSV) {
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write("dimension,x,y,z,exposure,source,distance,first_seen_ms,last_seen_ms\n");
                for (DebrisRecord r : list) {
                    w.write(String.format(java.util.Locale.ROOT, "%s,%d,%d,%d,%s,%s,%.1f,%d,%d%n", r.dimension(), r.x(), r.y(), r.z(),
                            r.exposure(), r.source(), r.distanceTo(px, py, pz), r.firstSeenMillis(), r.lastSeenMillis()));
                }
            }
        } else {
            JsonObject root = new JsonObject();
            root.addProperty("dimension", dimension);
            root.addProperty("availability", availability(dimension).name());
            JsonArray arr = new JsonArray();
            for (DebrisRecord r : list) {
                JsonObject o = new JsonObject();
                o.addProperty("x", r.x());
                o.addProperty("y", r.y());
                o.addProperty("z", r.z());
                o.addProperty("exposure", r.exposure().name());
                o.addProperty("source", r.source().name());
                o.addProperty("firstSeenMillis", r.firstSeenMillis());
                o.addProperty("lastSeenMillis", r.lastSeenMillis());
                arr.add(o);
            }
            root.add("debris", arr);
            JsonFiles.writeAtomic(file, root);
        }
        return list.size();
    }
}
