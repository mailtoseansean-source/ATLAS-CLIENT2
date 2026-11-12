package dev.atlasclient.core.analysis;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Joins neighbouring suspicious chunks into sites, so a base whose storage is in one chunk and its
 * farm or tunnels in the next is judged as one place with all of its evidence. Also links
 * tunnels and rooms that continue across chunk borders. Pure function; client or background thread.
 */
public final class SiteClusterer {
    /** What kind of place a site mostly looks like (for filters and sorting). */
    public enum Type {
        OVERGROWN("Overgrown (growth near players)"),
        UNDERGROUND("Underground structure"),
        STORAGE("Storage area"),
        FARM("Farm"),
        REDSTONE("Redstone machinery"),
        ARTIFICIAL_TERRAIN("Artificial terrain"),
        BUILDING("Building"),
        OTHER("Other");

        private final String label;

        Type(String label) { this.label = label; }

        public String label() { return label; }
    }

    /**
     * One site: one or more neighbouring chunks.
     *
     * @param anchorX/anchorZ chunk with the most evidence (used as the site's key)
     */
    public record Site(String dimension, int anchorX, int anchorZ, List<ChunkAssessment> chunks, double raw, double score,
                       Confidence confidence, Set<Indicator.Group> groups, boolean strong, boolean confirmed,
                       List<StructureSignatures.Match> structures, List<GeometryAnalyzer.Feature> features,
                       List<Evidence> evidence, int minY, int maxY, double centerX, double centerZ,
                       int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ, long observedAtMillis,
                       Set<Type> types, Type primary, Map<Indicator, Integer> counts) {
        public String key() { return DiscoveryStore.key(dimension, anchorX, anchorZ); }

        public double horizontalDistanceTo(double x, double z) {
            double dx = centerX - x, dz = centerZ - z;
            return Math.sqrt(dx * dx + dz * dz);
        }

        public int evidenceCount() {
            int n = 0;
            for (Evidence e : evidence) if (e.contribution() > 0) n++;
            return n;
        }

        public int blockCount() {
            int n = 0;
            for (int c : counts.values()) n += c;
            return n;
        }

        public boolean contains(int cx, int cz) {
            for (ChunkAssessment a : chunks) if (a.chunkX() == cx && a.chunkZ() == cz) return true;
            return false;
        }

        /** Short text for labels: "storage, farm, tunnels". */
        public String reasons(int max) {
            List<String> r = new ArrayList<>();
            for (Evidence e : evidence) {
                if (e.contribution() <= 0) continue;
                String d = e.description();
                int cut = d.indexOf('(');
                int dot = d.indexOf('.');
                if (dot > 0 && (cut < 0 || dot < cut)) cut = dot;
                r.add((cut > 0 ? d.substring(0, cut) : d).trim());
                if (r.size() >= max) break;
            }
            return String.join("; ", r);
        }
    }

    /**
     * @param assessments latest chunk assessments (any dimension; others are skipped)
     * @param undergroundScan the scan only counted buried blocks
     */
    public List<Site> cluster(Collection<ChunkAssessment> assessments, String dimension, ScoringConfig cfg, boolean undergroundScan) {
        Map<Long, ChunkAssessment> members = new HashMap<>();
        for (ChunkAssessment a : assessments) {
            if (!a.dimension().equals(dimension)) continue;
            if (a.details().raw() <= 0 && a.confidence() == Confidence.NONE) continue;
            if (!a.confidence().atLeast(Confidence.VERY_LOW)) continue;
            members.put(a.chunkKey(), a);
        }
        Map<Long, Long> parent = new HashMap<>();
        for (long k : members.keySet()) parent.put(k, k);
        for (ChunkAssessment a : members.values()) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    long n = ChunkObservation.chunkKey(a.chunkX() + dx, a.chunkZ() + dz);
                    if (members.containsKey(n)) union(parent, a.chunkKey(), n);
                }
            }
        }
        Map<Long, List<ChunkAssessment>> groups = new HashMap<>();
        for (ChunkAssessment a : members.values()) {
            groups.computeIfAbsent(find(parent, a.chunkKey()), k -> new ArrayList<>()).add(a);
        }
        List<Site> out = new ArrayList<>();
        for (List<ChunkAssessment> g : groups.values()) {
            out.add(build(g, dimension, cfg, undergroundScan));
        }
        out.sort(Comparator.comparing(Site::confidence).reversed().thenComparing(Comparator.comparingDouble(Site::score).reversed()));
        return out;
    }

    private Site build(List<ChunkAssessment> chunks, String dimension, ScoringConfig cfg, boolean undergroundScan) {
        chunks.sort(Comparator.comparingDouble((ChunkAssessment a) -> a.details().raw()).reversed()
                .thenComparingLong(ChunkAssessment::chunkKey));
        ChunkAssessment anchor = chunks.get(0);
        // A large generated structure (village, stronghold, mineshaft...) recognised in one chunk
        // covers its neighbours too: their evidence is damped the same way.
        boolean largeStructure = false;
        List<StructureSignatures.Match> structures = new ArrayList<>();
        Set<StructureSignatures.Kind> kinds = EnumSet.noneOf(StructureSignatures.Kind.class);
        for (ChunkAssessment a : chunks) {
            for (StructureSignatures.Match m : a.details().structures()) {
                if (kinds.add(m.kind())) structures.add(m);
                if (m.kind().large() && m.kind() != StructureSignatures.Kind.MINESHAFT) largeStructure = true;
            }
        }
        double damp = cfg.structureHandling == ScoringConfig.StructureHandling.IGNORE ? 0.05
                : cfg.structureHandling == ScoringConfig.StructureHandling.COUNT ? 1 : cfg.structureDamping;
        double raw = 0, protectedSum = 0;
        boolean strong = false, confirmed = false;
        Set<Indicator.Group> groups = EnumSet.noneOf(Indicator.Group.class);
        Map<Indicator.Group, Double> byGroup = new EnumMap<>(Indicator.Group.class);
        List<GeometryAnalyzer.Feature> features = new ArrayList<>();
        Map<Indicator, Integer> counts = new EnumMap<>(Indicator.class);
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        double wx = 0, wz = 0, wsum = 0;
        int minCX = Integer.MAX_VALUE, minCZ = Integer.MAX_VALUE, maxCX = Integer.MIN_VALUE, maxCZ = Integer.MIN_VALUE;
        long observed = 0;
        for (ChunkAssessment a : chunks) {
            ChunkAssessment.Details d = a.details();
            double r = d.raw();
            if (largeStructure && d.structures().stream().noneMatch(m -> m.kind().large() && m.kind() != StructureSignatures.Kind.MINESHAFT)) {
                r = d.protectedRaw() + (r - d.protectedRaw()) * damp;
            }
            raw += r;
            protectedSum += d.protectedRaw();
            strong |= d.strong();
            confirmed |= d.confirmed();
            groups.addAll(d.groups());
            d.byGroup().forEach((k, v) -> byGroup.merge(k, v, Double::sum));
            features.addAll(d.features());
            a.counts().forEach((k, v) -> counts.merge(k, v, Integer::sum));
            minY = Math.min(minY, d.minY());
            maxY = Math.max(maxY, d.maxY());
            double w = Math.max(0.1, r);
            wx += a.centerX() * w;
            wz += a.centerZ() * w;
            wsum += w;
            minCX = Math.min(minCX, a.chunkX());
            minCZ = Math.min(minCZ, a.chunkZ());
            maxCX = Math.max(maxCX, a.chunkX());
            maxCZ = Math.max(maxCZ, a.chunkZ());
            observed = Math.max(observed, a.observedAtMillis());
        }
        List<Evidence> evidence = new ArrayList<>();
        // Tunnels / rooms that continue across chunk borders.
        int[] crossing = crossings(features);
        if (crossing[0] >= 2) {
            double c = Math.min(8, 2.0 * (crossing[0] - 1)) * cfg.geometryWeight;
            raw += c;
            groups.add(Indicator.Group.GEOMETRY);
            byGroup.merge(Indicator.Group.GEOMETRY, c, Double::sum);
            evidence.add(new Evidence("shape.multichunk", Evidence.Kind.INFERENCE, Evidence.Strength.MODERATE, c, crossing[0],
                    String.format(Locale.ROOT, "A dug tunnel/room continues across %d chunks (%d blocks of it found). Natural caves rarely stay this straight.",
                            crossing[0], crossing[1]), List.of()));
        }
        // Evidence from the members, strongest first (caveats once each).
        List<Evidence> all = new ArrayList<>();
        for (ChunkAssessment a : chunks) all.addAll(a.evidence());
        all.sort(Comparator.comparingDouble(Evidence::contribution).reversed());
        Set<String> caveats = new HashSet<>();
        for (Evidence e : all) {
            if (e.kind() == Evidence.Kind.CAVEAT) {
                if (caveats.add(e.id())) evidence.add(e);
            } else if (e.contribution() > 0 || e.id().equals("confirmed")) {
                if (evidence.size() < 24) evidence.add(e);
            }
        }
        evidence.sort(Comparator.comparingInt((Evidence e) -> e.kind() == Evidence.Kind.CAVEAT ? 1 : 0)
                .thenComparing(Comparator.comparingDouble(Evidence::contribution).reversed()));
        if (chunks.size() > 1) {
            evidence.add(new Evidence("site.chunks", Evidence.Kind.CAVEAT, Evidence.Strength.WEAK, 0, chunks.size(),
                    "Evidence from " + chunks.size() + " neighbouring chunks is combined into this one site.", List.of()));
        }
        double score = SuspicionScorer.curve(raw, cfg);
        Confidence conf = SuspicionScorer.siteConfidence(raw, groups, strong, confirmed, cfg);
        if (chunks.size() == 1 && crossing[0] < 2) {
            conf = anchor.confidence(); // one chunk: exactly its own assessment
            score = anchor.score();
        } else if (largeStructure) {
            // A village or stronghold spread over many chunks adds up a lot of world-generated evidence.
            conf = SuspicionScorer.structureCap(conf, cfg, protectedSum > 0, evidence);
        }
        Set<Type> types = EnumSet.noneOf(Type.class);
        if (groups.contains(Indicator.Group.AMETHYST) || groups.contains(Indicator.Group.PLANT_GROWTH)) types.add(Type.OVERGROWN);
        if (undergroundScan || groups.contains(Indicator.Group.GEOMETRY)) types.add(Type.UNDERGROUND);
        if (groups.contains(Indicator.Group.STORAGE)) types.add(Type.STORAGE);
        if (groups.contains(Indicator.Group.FARMING) || groups.contains(Indicator.Group.BEES)) types.add(Type.FARM);
        if (groups.contains(Indicator.Group.REDSTONE)) types.add(Type.REDSTONE);
        if (groups.contains(Indicator.Group.GEOMETRY)) types.add(Type.ARTIFICIAL_TERRAIN);
        if (groups.contains(Indicator.Group.BUILDING) || groups.contains(Indicator.Group.DECOR) || groups.contains(Indicator.Group.UTILITY_BLOCKS)) {
            types.add(Type.BUILDING);
        }
        Type primary = primary(byGroup);
        if (types.isEmpty()) types.add(Type.OTHER);
        return new Site(dimension, anchor.chunkX(), anchor.chunkZ(), List.copyOf(chunks), raw, score, conf, groups, strong, confirmed,
                structures, features, evidence, minY, maxY, wx / wsum, wz / wsum, minCX, minCZ, maxCX, maxCZ, observed,
                types, primary, counts);
    }

    private static Type primary(Map<Indicator.Group, Double> byGroup) {
        Indicator.Group best = null;
        double bestV = 0;
        for (Map.Entry<Indicator.Group, Double> e : byGroup.entrySet()) {
            if (e.getValue() > bestV) {
                bestV = e.getValue();
                best = e.getKey();
            }
        }
        if (best == null) return Type.OTHER;
        return switch (best) {
            case AMETHYST, PLANT_GROWTH -> Type.OVERGROWN;
            case STORAGE -> Type.STORAGE;
            case FARMING, BEES -> Type.FARM;
            case REDSTONE -> Type.REDSTONE;
            case GEOMETRY -> Type.ARTIFICIAL_TERRAIN;
            case BUILDING, DECOR, UTILITY_BLOCKS -> Type.BUILDING;
            default -> Type.OTHER;
        };
    }

    /**
     * Links shapes that touch a shared chunk border (overlapping in height and along the border).
     * Returns {chunks spanned by the biggest linked shape, its total length}.
     */
    static int[] crossings(List<GeometryAnalyzer.Feature> features) {
        int n = features.size();
        if (n < 2) return new int[]{n, n == 1 ? features.get(0).length() : 0};
        int[] p = new int[n];
        for (int i = 0; i < n; i++) p[i] = i;
        for (int i = 0; i < n; i++) {
            GeometryAnalyzer.Feature a = features.get(i);
            for (int j = i + 1; j < n; j++) {
                GeometryAnalyzer.Feature b = features.get(j);
                if (touches(a, b) || touches(b, a)) {
                    int ra = root(p, i), rb = root(p, j);
                    p[ra] = rb;
                }
            }
        }
        Map<Integer, Set<Long>> chunksOf = new HashMap<>();
        Map<Integer, Integer> lengthOf = new HashMap<>();
        for (int i = 0; i < n; i++) {
            GeometryAnalyzer.Feature f = features.get(i);
            int r = root(p, i);
            chunksOf.computeIfAbsent(r, k -> new LinkedHashSet<>()).add(ChunkObservation.chunkKey(f.minX() >> 4, f.minZ() >> 4));
            lengthOf.merge(r, f.length(), Integer::sum);
        }
        int best = 0, len = 0;
        for (Map.Entry<Integer, Set<Long>> e : chunksOf.entrySet()) {
            if (e.getValue().size() > best) {
                best = e.getValue().size();
                len = lengthOf.get(e.getKey());
            }
        }
        return new int[]{best, len};
    }

    /** a's east (or south) border face meets b's west (or north) border face. */
    private static boolean touches(GeometryAnalyzer.Feature a, GeometryAnalyzer.Feature b) {
        boolean yOverlap = a.minY() <= b.maxY() && b.minY() <= a.maxY();
        if (!yOverlap) return false;
        if ((a.borders() & 2) != 0 && (b.borders() & 1) != 0 && a.maxX() + 1 == b.minX()
                && a.minZ() <= b.maxZ() && b.minZ() <= a.maxZ()) return true;
        return (a.borders() & 8) != 0 && (b.borders() & 4) != 0 && a.maxZ() + 1 == b.minZ()
                && a.minX() <= b.maxX() && b.minX() <= a.maxX();
    }

    private static int root(int[] p, int i) {
        while (p[i] != i) {
            p[i] = p[p[i]];
            i = p[i];
        }
        return i;
    }

    private static long find(Map<Long, Long> parent, long k) {
        long r = k;
        while (parent.get(r) != r) r = parent.get(r);
        while (parent.get(k) != r) {
            long next = parent.get(k);
            parent.put(k, r);
            k = next;
        }
        return r;
    }

    private static void union(Map<Long, Long> parent, long a, long b) {
        long ra = find(parent, a), rb = find(parent, b);
        if (ra != rb) parent.put(ra, rb);
    }
}
