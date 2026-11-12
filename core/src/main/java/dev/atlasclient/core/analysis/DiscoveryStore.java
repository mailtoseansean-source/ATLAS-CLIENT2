package dev.atlasclient.core.analysis;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.atlasclient.core.config.JsonFiles;
import dev.atlasclient.core.util.BoundedCache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Holds the latest assessment per chunk (bounded LRU) plus the user's decisions: saved findings
 * persist across sessions, hidden findings are not rendered, dismissed findings are excluded until
 * the evidence changes. Thread-safe.
 */
public final class DiscoveryStore {
    public enum Mark { NONE, SAVED, HIDDEN, DISMISSED }

    public enum SortKey { SCORE, DISTANCE, LAST_SEEN, CONFIDENCE, INDICATORS }

    /**
     * A saved finding, kept across sessions. Shown as historical (not currently detected) unless a
     * current scan sees it again. Fields added in 1.2.0 are absent (null / 0) in older files.
     *
     * @param worldId          server address or save name it was found in
     * @param lastObservedMillis when the scanner last saw it
     * @param lastState        what was seen last time ("High: storage, farm, tunnels")
     * @param evidence         the main reasons, in words
     */
    public record SavedFinding(String dimension, int chunkX, int chunkZ, double score, String confidence,
                               String status, String explanation, long savedAtMillis, String note,
                               String worldId, int blockX, int blockY, int blockZ, long lastObservedMillis,
                               String lastState, List<String> evidence, String type, int chunks) {
        public SavedFinding(String dimension, int chunkX, int chunkZ, double score, String confidence,
                            String status, String explanation, long savedAtMillis, String note) {
            this(dimension, chunkX, chunkZ, score, confidence, status, explanation, savedAtMillis, note,
                    "", chunkX * 16 + 8, 64, chunkZ * 16 + 8, savedAtMillis, confidence, List.of(), "", 1);
        }

        /** Fills fields missing in files written before 1.2.0. */
        public SavedFinding normalized() {
            return new SavedFinding(dimension, chunkX, chunkZ, score, confidence == null ? "" : confidence, status == null ? "" : status,
                    explanation == null ? "" : explanation, savedAtMillis, note == null ? "" : note, worldId == null ? "" : worldId,
                    blockX == 0 && blockZ == 0 ? chunkX * 16 + 8 : blockX, blockY == 0 ? 64 : blockY, blockX == 0 && blockZ == 0 ? chunkZ * 16 + 8 : blockZ,
                    lastObservedMillis, lastState == null ? (confidence == null ? "" : confidence) : lastState,
                    evidence == null ? List.of() : List.copyOf(evidence), type == null ? "" : type, Math.max(1, chunks));
        }

        public long lastSeen() { return lastObservedMillis > 0 ? lastObservedMillis : savedAtMillis; }

        public SavedFinding withObservation(SiteClusterer.Site s, long now) {
            return new SavedFinding(dimension, chunkX, chunkZ, s.score(), s.confidence().label(), status, explanation, savedAtMillis,
                    note, worldId, (int) s.centerX(), s.minY() == Integer.MAX_VALUE ? blockY : s.minY(), (int) s.centerZ(), now,
                    stateOf(s), reasonsOf(s), s.primary().label(), s.chunks().size());
        }

        public SavedFinding withNote(String n) {
            return new SavedFinding(dimension, chunkX, chunkZ, score, confidence, status, explanation, savedAtMillis, n,
                    worldId, blockX, blockY, blockZ, lastObservedMillis, lastState, evidence, type, chunks);
        }
    }

    static String stateOf(SiteClusterer.Site s) {
        return s.confidence().label() + ": " + s.reasons(3);
    }

    static List<String> reasonsOf(SiteClusterer.Site s) {
        List<String> r = new ArrayList<>();
        for (Evidence e : s.evidence()) {
            if (e.contribution() > 0 || e.id().equals("confirmed")) r.add(e.description());
            if (r.size() >= 8) break;
        }
        return r;
    }

    /** Most saved findings kept per world (oldest dropped first). */
    public static final int MAX_SAVED = 2000;

    private final SiteClusterer clusterer = new SiteClusterer();
    private long version;
    private long sitesVersion = -1;
    private String sitesFor = "";
    private List<SiteClusterer.Site> sites = List.of();

    private final BoundedCache<String, ChunkAssessment> latest;
    private final Map<String, Mark> marks = new HashMap<>();
    private final Map<String, SavedFinding> saved = new HashMap<>();
    private final Map<String, Double> dismissedAtScore = new HashMap<>();
    private final Set<String> notified = new HashSet<>();

    public DiscoveryStore(int capacity) {
        this.latest = new BoundedCache<>(capacity);
    }

    public static String key(String dimension, int cx, int cz) { return dimension + "|" + cx + "|" + cz; }

    /**
     * Records a new assessment. Returns true when this is a newly suspicious chunk that should be
     * announced (crossed the notify threshold for the first time).
     */
    public synchronized boolean update(ChunkAssessment a, Confidence notifyAt) {
        String k = key(a.dimension(), a.chunkX(), a.chunkZ());
        latest.put(k, a);
        version++;
        Double dismissedScore = dismissedAtScore.get(k);
        if (dismissedScore != null && a.score() > dismissedScore + 15) {
            // Evidence changed substantially: un-dismiss.
            dismissedAtScore.remove(k);
            marks.remove(k);
        }

        if (a.confidence().atLeast(notifyAt) && mark(k) != Mark.DISMISSED && notified.add(k)) {
            return true;
        }
        return false;
    }

    public synchronized ChunkAssessment get(String dimension, int cx, int cz) {
        return latest.get(key(dimension, cx, cz));
    }

    public synchronized Mark mark(String key) { return marks.getOrDefault(key, Mark.NONE); }

    public synchronized void setMark(ChunkAssessment a, Mark mark, long nowMillis) {
        String k = key(a.dimension(), a.chunkX(), a.chunkZ());
        switch (mark) {
            case NONE -> {
                marks.remove(k);
                saved.remove(k);
                dismissedAtScore.remove(k);
            }
            case SAVED -> {
                marks.put(k, Mark.SAVED);
                saved.put(k, new SavedFinding(a.dimension(), a.chunkX(), a.chunkZ(), a.score(), a.confidence().label(),
                        a.status().status().label(), a.explanation(), nowMillis, ""));
                trimSaved();
            }
            case HIDDEN -> marks.put(k, Mark.HIDDEN);
            case DISMISSED -> {
                marks.put(k, Mark.DISMISSED);
                dismissedAtScore.put(k, a.score());
            }
        }
    }

    /**
     * Sites (neighbouring chunks joined) in a dimension, recomputed only when assessments changed.
     * Saved findings covered by a current site are refreshed with what was seen now.
     */
    public synchronized List<SiteClusterer.Site> sites(String dimension, ScoringConfig cfg, boolean undergroundScan, long now) {
        String forKey = dimension + "|" + undergroundScan + "|" + System.identityHashCode(cfg);
        if (version != sitesVersion || !forKey.equals(sitesFor)) {
            sites = List.copyOf(clusterer.cluster(latest.values(), dimension, cfg, undergroundScan));
            sitesVersion = version;
            sitesFor = forKey;
            for (SiteClusterer.Site s : sites) {
                for (ChunkAssessment a : s.chunks()) {
                    String k = key(a.dimension(), a.chunkX(), a.chunkZ());
                    SavedFinding f = saved.get(k);
                    if (f != null) saved.put(k, f.withObservation(s, now));
                }
            }
        }
        return sites;
    }

    /** Sites at or above a confidence floor, without dismissed/hidden ones (any member marked counts). */
    public synchronized List<SiteClusterer.Site> visibleSites(String dimension, ScoringConfig cfg, boolean undergroundScan, Confidence floor,
                                                            boolean includeHidden, long now) {
        List<SiteClusterer.Site> out = new ArrayList<>();
        for (SiteClusterer.Site s : sites(dimension, cfg, undergroundScan, now)) {
            if (!s.confidence().atLeast(floor)) continue;
            Mark m = siteMark(s);
            if (m == Mark.DISMISSED || (m == Mark.HIDDEN && !includeHidden)) continue;
            out.add(s);
        }
        return out;
    }

    public synchronized Mark siteMark(SiteClusterer.Site s) {
        Mark best = Mark.NONE;
        for (ChunkAssessment a : s.chunks()) {
            Mark m = mark(key(a.dimension(), a.chunkX(), a.chunkZ()));
            if (m == Mark.DISMISSED) return m;
            if (m.ordinal() > best.ordinal()) best = m;
        }
        return best;
    }

    /** Saves a site (keyed by its anchor chunk); its other chunks are marked saved too. */
    public synchronized void saveSite(SiteClusterer.Site s, String worldId, long now) {
        String k = s.key();
        SavedFinding old = saved.get(k);
        SavedFinding f = new SavedFinding(s.dimension(), s.anchorX(), s.anchorZ(), s.score(), s.confidence().label(), "",
                s.reasons(3), old == null ? now : old.savedAtMillis(), old == null ? "" : old.note(), worldId == null ? "" : worldId,
                (int) s.centerX(), s.minY() == Integer.MAX_VALUE ? 64 : s.minY(), (int) s.centerZ(), now, stateOf(s), reasonsOf(s),
                s.primary().label(), s.chunks().size());
        saved.put(k, f);
        for (ChunkAssessment a : s.chunks()) marks.put(key(a.dimension(), a.chunkX(), a.chunkZ()), Mark.SAVED);
        trimSaved();
    }

    public synchronized void markSite(SiteClusterer.Site s, Mark mark, long now) {
        if (mark == Mark.SAVED) {
            saveSite(s, "", now);
            return;
        }
        for (ChunkAssessment a : s.chunks()) setMark(a, mark, now);
    }

    /** Removes a saved finding (and its mark) whose chunk is not in the current results. */
    public synchronized void forgetSaved(String dimension, int cx, int cz) {
        String k = key(dimension, cx, cz);
        saved.remove(k);
        marks.remove(k);
    }

    public synchronized void setNote(String key, String note) {
        SavedFinding f = saved.get(key);
        if (f != null) saved.put(key, f.withNote(note == null ? "" : note));
    }

    /** Saved findings in this dimension that no current site covers: shown as historical. */
    public synchronized List<SavedFinding> historical(String dimension) {
        List<SavedFinding> out = new ArrayList<>();
        for (SavedFinding f : saved.values()) {
            if (!dimension.equals(f.dimension())) continue;
            boolean current = false;
            for (SiteClusterer.Site s : sites) {
                if (s.dimension().equals(dimension) && s.contains(f.chunkX(), f.chunkZ())) {
                    current = true;
                    break;
                }
            }
            if (!current) out.add(f);
        }
        out.sort(Comparator.comparingLong(SavedFinding::lastSeen).reversed());
        return out;
    }

    private void trimSaved() {
        if (saved.size() <= MAX_SAVED) return;
        List<Map.Entry<String, SavedFinding>> all = new ArrayList<>(saved.entrySet());
        all.sort(Comparator.comparingLong(e -> e.getValue().lastSeen()));
        for (int i = 0; i < all.size() - MAX_SAVED; i++) {
            saved.remove(all.get(i).getKey());
            marks.remove(all.get(i).getKey());
        }
    }

    /** Findings at or above the confidence floor, excluding dismissed ones, sorted. */
    public synchronized List<ChunkAssessment> list(String dimension, Confidence floor, SortKey sort,
                                                   double playerX, double playerZ, boolean includeHidden) {
        List<ChunkAssessment> out = new ArrayList<>();
        for (ChunkAssessment a : latest.values()) {
            if (!a.dimension().equals(dimension) || !a.confidence().atLeast(floor)) {
                continue;
            }
            Mark m = mark(key(a.dimension(), a.chunkX(), a.chunkZ()));
            if (m == Mark.DISMISSED || (m == Mark.HIDDEN && !includeHidden)) {
                continue;
            }
            out.add(a);
        }
        Comparator<ChunkAssessment> cmp = switch (sort) {
            case SCORE -> Comparator.comparingDouble(ChunkAssessment::score).reversed();
            case DISTANCE -> Comparator.comparingDouble(a -> a.horizontalDistanceTo(playerX, playerZ));
            case LAST_SEEN -> Comparator.comparingLong(ChunkAssessment::observedAtMillis).reversed();
            case CONFIDENCE -> Comparator.comparing(ChunkAssessment::confidence).reversed()
                    .thenComparing(Comparator.comparingDouble(ChunkAssessment::score).reversed());
            case INDICATORS -> Comparator.comparingInt(ChunkAssessment::indicatorTotal).reversed();
        };
        out.sort(cmp);
        return out;
    }

    public synchronized List<SavedFinding> savedFindings() { return new ArrayList<>(saved.values()); }

    public synchronized int size() { return latest.size(); }

    public int capacity() { return latest.capacity(); }

    /** Clears per-session analysis results; user marks and saved findings are kept. */
    public synchronized void clearSession() {
        latest.clear();
        notified.clear();
        sites = List.of();
        version++;
    }

    public synchronized void clearAll() {
        clearSession();
        marks.clear();
        saved.clear();
        dismissedAtScore.clear();
    }

    // ------------------------------------------------------------ persistence (per world)

    public synchronized void save(Path file) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", 2);
        JsonArray s = new JsonArray();
        for (SavedFinding f : saved.values()) {
            s.add(JsonFiles.GSON.toJsonTree(f));
        }
        root.add("saved", s);
        JsonObject m = new JsonObject();
        for (Map.Entry<String, Mark> e : marks.entrySet()) {
            if (e.getValue() == Mark.HIDDEN || e.getValue() == Mark.DISMISSED) {
                m.addProperty(e.getKey(), e.getValue().name());
            }
        }
        root.add("marks", m);
        JsonFiles.writeAtomic(file, root);
    }

    public synchronized void load(Path file) throws IOException {
        marks.clear();
        saved.clear();
        dismissedAtScore.clear();
        if (!Files.exists(file)) {
            return;
        }
        JsonElement el = JsonFiles.read(file);
        if (!el.isJsonObject()) {
            return;
        }
        JsonObject root = el.getAsJsonObject();
        if (root.has("saved") && root.get("saved").isJsonArray()) {
            for (JsonElement e : root.getAsJsonArray("saved")) {
                try {
                    SavedFinding f = JsonFiles.GSON.fromJson(e, SavedFinding.class);
                    if (f != null && f.dimension() != null) {
                        f = f.normalized();
                        String k = key(f.dimension(), f.chunkX(), f.chunkZ());
                        saved.put(k, f);
                        marks.put(k, Mark.SAVED);
                    }
                } catch (RuntimeException ignored) {
                    // skip malformed entry
                }
            }
        }
        if (root.has("marks") && root.get("marks").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("marks").entrySet()) {
                try {
                    Mark mk = Mark.valueOf(e.getValue().getAsString());
                    marks.put(e.getKey(), mk);
                    if (mk == Mark.DISMISSED) {
                        dismissedAtScore.put(e.getKey(), 0.0);
                    }
                } catch (RuntimeException ignored) {
                    // skip
                }
            }
        }
    }
}
