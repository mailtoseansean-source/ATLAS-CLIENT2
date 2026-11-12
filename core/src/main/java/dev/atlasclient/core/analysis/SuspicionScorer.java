package dev.atlasclient.core.analysis;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Weighted, explainable scoring of chunk observations. Pure function of its inputs; safe to call
 * from background threads.
 *
 * <p>Raw score = indicator contributions (weight x log2(1 + count above natural baseline), adjusted
 * for the biome) + amethyst, plant-growth, arrangement, layout (walls, spacing, symmetry, storage
 * rows, clusters) and shape (underground rooms, tunnels, dug-out areas) contributions. Evidence in a
 * recognised generated structure is damped, except blocks world generation cannot make. The final
 * 0..100 score is {@code 100 * (1 - exp(-raw / scale))}. Confidence is then capped by evidence
 * diversity: High needs two independent evidence groups, Very High three plus a strong signal.
 * {@link Confidence#CONFIRMED} is only given for directly observed player-only blocks in an
 * artificial arrangement.</p>
 */
public final class SuspicionScorer {
    private final AmethystAnalyzer amethyst = new AmethystAnalyzer();
    private final PlantGrowthAnalyzer plants = new PlantGrowthAnalyzer();
    private final ArrangementAnalyzer arrangement = new ArrangementAnalyzer();
    private final LayoutAnalyzer layout = new LayoutAnalyzer();

    /** Blocks no world generation places: their evidence is never damped and can confirm a structure. */
    static boolean playerOnly(Indicator i) {
        return i == Indicator.PLAYER_ONLY || i == Indicator.BEEHIVE || i == Indicator.PLACED_LEAVES || i == Indicator.SAPLING;
    }

    public ChunkAssessment score(ChunkObservation obs, ScoringConfig cfg) {
        Map<Indicator, Integer> counts = new EnumMap<>(Indicator.class);
        Map<Indicator, List<long[]>> samples = new EnumMap<>(Indicator.class);
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (BlockHit h : obs.hits()) {
            counts.merge(h.indicator(), 1, Integer::sum);
            List<long[]> s = samples.computeIfAbsent(h.indicator(), k -> new ArrayList<>());
            if (s.size() < 5) {
                s.add(new long[]{h.x(), h.y(), h.z()});
            }
            if (h.indicator().group() != Indicator.Group.CONFOUNDER) {
                minY = Math.min(minY, h.y());
                maxY = Math.max(maxY, h.y());
            }
        }
        Set<String> biomes = obs.biomes();
        List<Evidence> evidence = new ArrayList<>();
        Set<Indicator.Group> groups = EnumSet.noneOf(Indicator.Group.class);
        Map<Indicator.Group, Double> byGroup = new EnumMap<>(Indicator.Group.class);
        List<StructureSignatures.Match> structures = StructureSignatures.recognize(obs.signatures(), counts, biomes, obs.dimension());

        // Abandoned mineshaft: rails or cobwebs together with oak/dark-oak planks and fences. Those
        // blocks are then world-generated and are ignored (including their straight runs).
        int shaftSigns = counts.getOrDefault(Indicator.MINESHAFT_SIGN, 0);
        int shaftWood = counts.getOrDefault(Indicator.MINESHAFT_WOOD, 0);
        boolean mineshaft = (shaftSigns > 0 && shaftWood > 0)
                || structures.stream().anyMatch(m -> m.kind() == StructureSignatures.Kind.MINESHAFT);
        List<BlockHit> arrangementHits = obs.hits();
        if (mineshaft) {
            arrangementHits = new ArrayList<>();
            for (BlockHit h : obs.hits()) {
                if (h.indicator() != Indicator.MINESHAFT_WOOD) arrangementHits.add(h);
            }
            evidence.add(new Evidence("caveat.mineshaft", Evidence.Kind.CAVEAT, Evidence.Strength.MODERATE, 0, shaftWood,
                    shaftSigns + " rails/cobwebs next to " + shaftWood + " oak or dark-oak planks/fences: looks like an "
                            + "abandoned mineshaft, so those blocks are ignored.",
                    samples.get(Indicator.MINESHAFT_SIGN)));
        }
        boolean strong = false;
        double raw = 0;
        double protectedRaw = 0;
        int playerOnlyCount = 0;
        double scatter = scatterFactor(arrangementHits);
        if (scatter < 1) {
            evidence.add(new Evidence("caveat.scattered", Evidence.Kind.CAVEAT, Evidence.Strength.MODERATE, 0, 0,
                    "Placed blocks are scattered one here, one there instead of being built into something (random placing, "
                            + "pillars, griefing): they count " + Math.round(scatter * 100) + "%.", List.of()));
        }

        for (Map.Entry<Indicator, Integer> e : counts.entrySet()) {
            Indicator ind = e.getKey();
            int count = e.getValue();
            if (ind.group() == Indicator.Group.CONFOUNDER || ind.group() == Indicator.Group.AMETHYST
                    || ind.group() == Indicator.Group.PLANT_GROWTH) {
                continue;
            }
            if (mineshaft && ind == Indicator.MINESHAFT_WOOD) {
                continue;
            }
            double biome = biomeFactor(ind, biomes);
            double w = cfg.weight(ind) * biome;
            if (ind.group() == Indicator.Group.BUILDING || ind == Indicator.PLAYER_ONLY) w *= scatter;
            int baseline = cfg.baseline(ind);
            double c = w * Stats.excessLog(count, baseline);
            Evidence.Strength strength = Evidence.Strength.WEAK;
            boolean po = playerOnly(ind);
            if (po) {
                playerOnlyCount += count;
            }
            if (po && c > 0 && scatter >= 1) {
                strength = Evidence.Strength.STRONG;
            } else if (c >= 6) {
                strength = Evidence.Strength.MODERATE;
            }
            String desc = String.format(Locale.ROOT, "%d x %s observed%s%s", count, ind.label(),
                    baseline > 0 ? " (natural baseline " + baseline + " per chunk)" : "",
                    biome < 1 ? " (common in this biome, counts " + Math.round(biome * 100) + "%)" : "");
            evidence.add(new Evidence("count." + ind.name().toLowerCase(Locale.ROOT), Evidence.Kind.OBSERVATION,
                    strength, c, count, desc, samples.get(ind)));
            if (c > 0) {
                raw += c;
                if (po) protectedRaw += c;
                groups.add(ind.group());
                byGroup.merge(ind.group(), c, Double::sum);
                if (strength == Evidence.Strength.STRONG) {
                    strong = true;
                }
            }
        }

        AmethystAnalyzer.Report am = amethyst.analyze(obs.hits(), cfg);
        evidence.addAll(am.evidence());
        if (am.contribution() > 0) {
            raw += am.contribution();
            groups.add(Indicator.Group.AMETHYST);
            byGroup.merge(Indicator.Group.AMETHYST, am.contribution(), Double::sum);
        }

        PlantGrowthAnalyzer.Report pl = plants.analyze(obs.hits(), biomes, cfg);
        evidence.addAll(pl.evidence());
        if (pl.contribution() > 0) {
            raw += pl.contribution();
            groups.add(Indicator.Group.PLANT_GROWTH);
            byGroup.merge(Indicator.Group.PLANT_GROWTH, pl.contribution(), Double::sum);
        }

        ArrangementAnalyzer.Report ar = arrangement.analyze(arrangementHits, cfg);
        evidence.addAll(ar.evidence());
        if (ar.contribution() > 0) {
            raw += ar.contribution();
            groups.add(Indicator.Group.BUILDING);
            byGroup.merge(Indicator.Group.BUILDING, ar.contribution(), Double::sum);
        }

        LayoutAnalyzer.Report lay = layout.analyze(arrangementHits, biomes, cfg);
        boolean flatSurfaces = false;
        for (LayoutAnalyzer.Pattern p : lay.patterns()) {
            evidence.add(new Evidence(p.id(), Evidence.Kind.INFERENCE, p.contribution() >= 3 ? Evidence.Strength.MODERATE : Evidence.Strength.WEAK,
                    p.contribution(), 1, p.description(), p.samples()));
            if (p.contribution() > 0) {
                raw += p.contribution();
                groups.add(p.group());
                byGroup.merge(p.group(), p.contribution(), Double::sum);
            }
            if (p.id().equals("layout.wall") || p.id().equals("layout.floor")) flatSurfaces = true;
        }

        List<GeometryAnalyzer.Feature> features = obs.geometry().features();
        double geo = 0;
        if (mineshaft) {
            // Mineshaft corridors are straight 3x3 tunnels: not counted where a mineshaft is recognised.
            features = features.stream().filter(f -> f.shape() != GeometryAnalyzer.Shape.TUNNEL).toList();
        }
        for (GeometryAnalyzer.Feature f : features) {
            double c = switch (f.shape()) {
                case ROOM -> 4 + Math.min(4, f.volume() / 60.0);
                case TUNNEL -> 3 + Math.min(3, f.length() / 10.0);
                case EXCAVATION -> 7;
                case SHAFT, CHANNEL -> 3;
                case STAIRCASE -> 2.5;
            } * cfg.geometryWeight;
            c = Math.max(0, Math.min(c, 16 - geo));
            geo += c;
            evidence.add(new Evidence("shape." + f.shape().name().toLowerCase(Locale.ROOT), Evidence.Kind.INFERENCE,
                    f.shape() == GeometryAnalyzer.Shape.ROOM || f.shape() == GeometryAnalyzer.Shape.EXCAVATION ? Evidence.Strength.MODERATE
                            : Evidence.Strength.WEAK, c, 1,
                    f.describe() + " underground at Y " + f.minY() + ". Natural caves are irregular; this shape looks dug or built.",
                    List.<long[]>of(new long[]{(f.minX() + f.maxX()) / 2, f.minY(), (f.minZ() + f.maxZ()) / 2})));
            minY = Math.min(minY, f.minY());
            maxY = Math.max(maxY, f.maxY());
        }
        if (geo > 0) {
            raw += geo;
            groups.add(Indicator.Group.GEOMETRY);
            byGroup.merge(Indicator.Group.GEOMETRY, geo, Double::sum);
        }

        int markers = counts.getOrDefault(Indicator.STRUCTURE_MARKER, 0);
        // Mineshafts are handled above (their wood and corridors are left out); others damp everything.
        List<StructureSignatures.Match> damping = structures.stream().filter(m -> m.kind() != StructureSignatures.Kind.MINESHAFT).toList();
        boolean recognised = !damping.isEmpty();
        if (recognised && cfg.structureHandling != ScoringConfig.StructureHandling.COUNT) {
            double d = cfg.structureHandling == ScoringConfig.StructureHandling.IGNORE ? 0.05 : cfg.structureDamping;
            double before = raw;
            raw = protectedRaw + (raw - protectedRaw) * d;
            evidence.add(new Evidence("caveat.generated", Evidence.Kind.CAVEAT, Evidence.Strength.MODERATE, 0, damping.size(),
                    "Looks like a generated " + StructureSignatures.describe(damping) + ". Its blocks and shapes are world generation, so "
                            + String.format(Locale.ROOT, "the score counts %.0f%% of them", d * 100)
                            + (protectedRaw > 0 ? " (blocks world generation never places still count fully)" : "")
                            + String.format(Locale.ROOT, " (%.1f -> %.1f).", before, raw),
                    samples.get(Indicator.STRUCTURE_MARKER)));
        } else if (markers > 0) {
            raw = protectedRaw + (raw - protectedRaw) * cfg.confounderDamping;
            evidence.add(new Evidence("caveat.structure", Evidence.Kind.CAVEAT, Evidence.Strength.MODERATE, 0, markers,
                    markers + " generated-structure markers (bell, spawner, trial chamber, ancient city...) present. "
                            + "Many indicators may be world-generated; score reduced by "
                            + Math.round((1 - cfg.confounderDamping) * 100) + "%.",
                    samples.get(Indicator.STRUCTURE_MARKER)));
        }
        if (!obs.authoritative()) {
            evidence.add(new Evidence("caveat.client_data", Evidence.Kind.CAVEAT, Evidence.Strength.WEAK, 0, 0,
                    "Based on block data the server sent to this client. Servers with anti-xray or limited view distance may hide blocks.",
                    List.of()));
        }

        double score = 100 * (1 - Math.exp(-raw / cfg.scale));
        Confidence confidence = toConfidence(score, cfg);
        Confidence capped = capByDiversity(confidence, groups.size(), strong);
        if (capped != confidence) {
            evidence.add(new Evidence("caveat.diversity", Evidence.Kind.CAVEAT, Evidence.Strength.WEAK, 0, groups.size(),
                    "Confidence limited to " + capped.label() + ": only " + groups.size()
                            + " independent evidence group(s)" + (strong ? "" : " and no strong signal") + ".",
                    List.of()));
        }
        capped = livedInGate(capped, groups, strong, evidence);
        if (recognised) capped = structureCap(capped, cfg, protectedRaw > 0, evidence);

        // Confirmed: player-only blocks built into something AND signs that people used the place.
        boolean confirmed = playerOnlyCount >= 4 && scatter >= 1
                && (ar.runsFound() > 0 || flatSurfaces || geo > 0)
                && livedInSigns(groups) >= 2;
        if (confirmed) {
            capped = Confidence.CONFIRMED;
            evidence.add(new Evidence("confirmed", Evidence.Kind.OBSERVATION, Evidence.Strength.STRONG, 0, playerOnlyCount,
                    playerOnlyCount + " blocks that world generation never places (concrete, placed leaves, beehives, saplings) "
                            + "were received in a built arrangement, together with signs of use (" + livedInLabel(groups) + "): "
                            + "a player-made base.",
                    samples.getOrDefault(Indicator.PLAYER_ONLY, samples.get(Indicator.PLACED_LEAVES))));
        }

        boolean nether = obs.dimension().endsWith("the_nether");
        BaseStatus.Estimate status = BaseStatus.estimate(capped, counts, nether, markers > 0 || recognised);
        String explanation = explain(score, capped, groups, evidence);
        ChunkAssessment.Details details = new ChunkAssessment.Details(raw, protectedRaw, groups, strong, structures, features,
                minY, maxY, confirmed, byGroup);
        return new ChunkAssessment(obs.dimension(), obs.chunkX(), obs.chunkZ(), round1(score), capped, counts,
                evidence, explanation, status, am, obs.observedAtMillis(), obs.authoritative(), details);
    }

    /** How much an indicator counts in these biomes (1 = normal). */
    static double biomeFactor(Indicator ind, Set<String> biomes) {
        if (biomes == null || biomes.isEmpty()) return 1;
        return switch (ind) {
            case TERRACOTTA -> has(biomes, "badlands", "eroded_badlands", "wooded_badlands") ? 0 : 1;
            case FARM_PLANT -> has(biomes, "jungle", "bamboo_jungle", "sparse_jungle", "desert", "taiga", "old_growth_spruce_taiga") ? 0.4 : 1;
            case BEE_NEST -> has(biomes, "plains", "sunflower_plains", "meadow", "flower_forest", "cherry_grove", "forest", "birch_forest") ? 0.3 : 1;
            case MINESHAFT_WOOD -> has(biomes, "badlands", "eroded_badlands", "wooded_badlands") ? 0.3 : 1;
            default -> 1;
        };
    }

    private static boolean has(Set<String> biomes, String... names) {
        for (String n : names) if (biomes.contains(n)) return true;
        return false;
    }

    /**
     * In a recognised generated structure, chests, beds, farms and rooms are what the structure is
     * made of. Only blocks world generation never places can lift it above Low (to Medium at most).
     */
    static Confidence structureCap(Confidence c, ScoringConfig cfg, boolean playerOnly, List<Evidence> evidence) {
        if (cfg.structureHandling == ScoringConfig.StructureHandling.COUNT || c == Confidence.CONFIRMED) return c;
        Confidence max = cfg.structureHandling == ScoringConfig.StructureHandling.IGNORE ? Confidence.VERY_LOW
                : playerOnly ? Confidence.MEDIUM : Confidence.LOW;
        if (c.ordinal() <= max.ordinal()) return c;
        if (evidence != null) {
            evidence.add(new Evidence("caveat.structure_cap", Evidence.Kind.CAVEAT, Evidence.Strength.MODERATE, 0, 0,
                    "Inside a recognised generated structure: stays at " + max.label()
                            + (playerOnly ? " (some blocks here are player-placed)." : " unless player-only blocks are found."), List.of()));
        }
        return max;
    }

    /** Groups that show people used a place, not just put blocks there. */
    static final Set<Indicator.Group> LIVED_IN = EnumSet.of(Indicator.Group.STORAGE, Indicator.Group.UTILITY_BLOCKS,
            Indicator.Group.FARMING, Indicator.Group.REDSTONE, Indicator.Group.BEES);

    static int livedInSigns(Set<Indicator.Group> groups) {
        int n = 0;
        for (Indicator.Group g : groups) if (LIVED_IN.contains(g)) n++;
        return n;
    }

    static boolean growth(Set<Indicator.Group> groups) {
        return groups.contains(Indicator.Group.AMETHYST) || groups.contains(Indicator.Group.PLANT_GROWTH);
    }

    private static String livedInLabel(Set<Indicator.Group> groups) {
        List<String> n = new ArrayList<>();
        for (Indicator.Group g : groups) if (LIVED_IN.contains(g)) n.add(g.label().toLowerCase(Locale.ROOT));
        return String.join(", ", n);
    }

    /**
     * Placed blocks prove someone was there, not that it is a base (it could be random building, a
     * pillar, griefing). Without signs of use a place stays Low; one sign (or growth alone) allows
     * Medium; High and above need two (growth counts as one).
     */
    static Confidence livedInGate(Confidence c, Set<Indicator.Group> groups, boolean strong, List<Evidence> evidence) {
        if (c == Confidence.CONFIRMED) return c;
        c = capBuildingOnly(c, groups, strong, evidence);
        int signs = livedInSigns(groups) + (growth(groups) ? 1 : 0);
        Confidence max = signs == 0 ? Confidence.LOW : signs == 1 ? Confidence.MEDIUM : Confidence.VERY_HIGH;
        if (c.ordinal() <= max.ordinal()) return c;
        evidence.add(new Evidence("caveat.not_lived_in", Evidence.Kind.CAVEAT, Evidence.Strength.MODERATE, 0, signs,
                signs == 0 ? "Only placed blocks and shapes: no storage, beds, workstations, farms, redstone or growth. "
                        + "That can be random building, so it stays at " + max.label() + "."
                        : "Only one sign that people used this place (" + (growth(groups) && livedInSigns(groups) == 0 ? "growth"
                        : livedInLabel(groups)) + "); High needs two, so it stays at " + max.label() + ".",
                List.of()));
        return max;
    }

    /**
     * 1 when placed blocks touch each other (built into walls, floors, rooms), less when most stand
     * alone with no other placed block next to them.
     */
    static double scatterFactor(List<BlockHit> hits) {
        java.util.Set<Long> built = new java.util.HashSet<>();
        for (BlockHit h : hits) {
            if (h.indicator().group() == Indicator.Group.BUILDING || h.indicator() == Indicator.PLAYER_ONLY) built.add(h.packedPos());
        }
        if (built.size() < 8) return 1;
        int touching = 0;
        for (long p : built) {
            int x = BlockHit.unpackX(p), y = BlockHit.unpackY(p), z = BlockHit.unpackZ(p);
            boolean any = false;
            for (int dx = -1; dx <= 1 && !any; dx++)
                for (int dy = -1; dy <= 1 && !any; dy++)
                    for (int dz = -1; dz <= 1 && !any; dz++)
                        if ((dx != 0 || dy != 0 || dz != 0) && built.contains(BlockHit.pack(x + dx, y + dy, z + dz))) any = true;
            if (any) touching++;
        }
        double share = touching / (double) built.size();
        return share >= 0.6 ? 1 : share <= 0.2 ? 0.25 : 0.25 + (share - 0.2) / 0.4 * 0.75;
    }

    static Confidence capBuildingOnly(Confidence c, Set<Indicator.Group> groups, boolean strong, List<Evidence> evidence) {
        if (strong || !c.atLeast(Confidence.MEDIUM)) return c;
        // Building blocks alone (planks, bricks, glass, torches) are what generated structures are
        // made of too. Without anything players bring (storage, beds, workstations, farms, redstone,
        // bees, concrete...) a chunk stays Low; with an artificial shape as well, at most Medium.
        if (groups.equals(EnumSet.of(Indicator.Group.BUILDING))) {
            evidence.add(new Evidence("caveat.building_only", Evidence.Kind.CAVEAT, Evidence.Strength.WEAK, 0, 1,
                    "Only building blocks were seen (no storage, beds, workstations, farms or redstone); "
                            + "generated structures look like this too, so confidence stays Low.",
                    List.of()));
            return Confidence.LOW;
        }
        Set<Indicator.Group> shapeOnly = EnumSet.of(Indicator.Group.BUILDING, Indicator.Group.GEOMETRY, Indicator.Group.DECOR);
        if (shapeOnly.containsAll(groups) && c.atLeast(Confidence.HIGH)) {
            evidence.add(new Evidence("caveat.shape_only", Evidence.Kind.CAVEAT, Evidence.Strength.WEAK, 0, 1,
                    "Built shapes and building blocks, but nothing players use (storage, beds, workstations, farms, redstone): "
                            + "confidence stays at Medium.",
                    List.of()));
            return Confidence.MEDIUM;
        }
        return c;
    }

    static Confidence toConfidence(double score, ScoringConfig cfg) {
        if (score >= cfg.veryHighThreshold) return Confidence.VERY_HIGH;
        if (score >= cfg.highThreshold) return Confidence.HIGH;
        if (score >= cfg.mediumThreshold) return Confidence.MEDIUM;
        if (score >= cfg.lowThreshold) return Confidence.LOW;
        if (score >= cfg.veryLowThreshold) return Confidence.VERY_LOW;
        return Confidence.NONE;
    }

    static Confidence capByDiversity(Confidence c, int groups, boolean strong) {
        if (c == Confidence.VERY_HIGH && (groups < 3 || !strong)) {
            c = Confidence.HIGH;
        }
        if (c == Confidence.HIGH && groups < 2) {
            c = Confidence.MEDIUM;
        }
        return c;
    }

    /** Score for a raw total (same curve as chunks); used for multi-chunk sites. */
    public static double curve(double raw, ScoringConfig cfg) {
        return round1(100 * (1 - Math.exp(-raw / cfg.scale)));
    }

    /** Confidence for a site from its combined raw total, groups and strong flag. */
    public static Confidence siteConfidence(double raw, Set<Indicator.Group> groups, boolean strong, boolean confirmed, ScoringConfig cfg) {
        if (confirmed) return Confidence.CONFIRMED;
        Confidence c = capByDiversity(toConfidence(curve(raw, cfg), cfg), groups.size(), strong);
        return livedInGate(c, groups, strong, new ArrayList<>());
    }

    private static String explain(double score, Confidence c, Set<Indicator.Group> groups, List<Evidence> evidence) {
        if (c == Confidence.NONE) {
            return "No meaningful indicators of player activity in the visible block data.";
        }
        List<Evidence> top = new ArrayList<>();
        for (Evidence e : evidence) {
            if (e.contribution() > 0) {
                top.add(e);
            }
        }
        top.sort((a, b) -> Double.compare(b.contribution(), a.contribution()));
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "Heuristic score %.0f/100 (%s confidence) from %d evidence group(s). Main factors: ",
                score, c.label(), groups.size()));
        for (int i = 0; i < Math.min(3, top.size()); i++) {
            if (i > 0) sb.append("; ");
            Evidence e = top.get(i);
            sb.append(String.format(Locale.ROOT, "%s (+%.1f)", shortLabel(e), e.contribution()));
        }
        sb.append(c == Confidence.CONFIRMED ? ". Player-only blocks were observed directly." : ". This is an estimate, not proof of a base.");
        return sb.toString();
    }

    private static String shortLabel(Evidence e) {
        String d = e.description();
        int cut = d.indexOf('.');
        return cut > 0 && cut < 90 ? d.substring(0, cut) : (d.length() > 90 ? d.substring(0, 90) + "..." : d);
    }

    static double round1(double v) { return Math.round(v * 10) / 10.0; }
}
