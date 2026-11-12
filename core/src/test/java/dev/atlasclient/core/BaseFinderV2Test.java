package dev.atlasclient.core;

import dev.atlasclient.core.analysis.AirGrid;
import dev.atlasclient.core.analysis.AlertThrottle;
import dev.atlasclient.core.analysis.BlockHit;
import dev.atlasclient.core.analysis.ChunkAssessment;
import dev.atlasclient.core.analysis.ChunkObservation;
import dev.atlasclient.core.analysis.ChunkScanQueue;
import dev.atlasclient.core.analysis.Confidence;
import dev.atlasclient.core.analysis.DiscoveryStore;
import dev.atlasclient.core.analysis.Evidence;
import dev.atlasclient.core.analysis.GeometryAnalyzer;
import dev.atlasclient.core.analysis.Indicator;
import dev.atlasclient.core.analysis.IndicatorRegistry;
import dev.atlasclient.core.analysis.RangePreset;
import dev.atlasclient.core.analysis.ScoringConfig;
import dev.atlasclient.core.analysis.SiteClusterer;
import dev.atlasclient.core.analysis.StructureSignatures;
import dev.atlasclient.core.analysis.SuspicionScorer;
import dev.atlasclient.core.render.OverlayCuller;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Base Finder v2 (1.2.0) on synthetic chunk data: growth near players, lived-in signs, shapes,
 * generated-structure recognition, multi-chunk sites, ranges, memory bounds, persistence, alerts.
 */
public class BaseFinderV2Test {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final ScoringConfig CFG = ScoringConfig.defaults();
    private static final String OW = "minecraft:overworld";
    private final SuspicionScorer scorer = new SuspicionScorer();

    // ------------------------------------------------------------------ fixtures

    static ChunkObservation obs(int cx, int cz, List<BlockHit> hits) {
        return new ChunkObservation(OW, cx, cz, hits, 24, 1000L, false);
    }

    static ChunkObservation obs(int cx, int cz, List<BlockHit> hits, Map<String, Integer> sig, Set<String> biomes, GeometryAnalyzer.Report geo) {
        return new ChunkObservation(OW, cx, cz, hits, 24, 1000L, false, sig, biomes, geo);
    }

    /** {@code n} blocks in a row along x starting at (x, y, z), local to chunk (cx, cz). */
    static void row(List<BlockHit> hits, Indicator ind, int key, int cx, int cz, int x, int y, int z, int n) {
        for (int i = 0; i < n; i++) hits.add(new BlockHit(ind, key, cx * 16 + x + i, y, cz * 16 + z, -1, -1));
    }

    /** Vine column of {@code len} hanging blocks at (x, z) from y down. */
    static void vine(List<BlockHit> hits, int x, int y, int z, int len) {
        for (int i = 0; i < len; i++) hits.add(new BlockHit(Indicator.VINE, 900, x, y - i, z, -1, -1));
    }

    /** Geode buds: {@code clusters} fully grown out of {@code buds}. */
    static void geode(List<BlockHit> hits, int buds, int clusters) {
        for (int i = 0; i < buds; i++) {
            Indicator ind = i < clusters ? Indicator.AMETHYST_CLUSTER : (i % 3 == 0 ? Indicator.SMALL_AMETHYST_BUD
                    : i % 3 == 1 ? Indicator.MEDIUM_AMETHYST_BUD : Indicator.LARGE_AMETHYST_BUD);
            hits.add(new BlockHit(ind, 800 + ind.ordinal(), i % 8, -20 + i / 64, (i / 8) % 8, -1, -1));
        }
        for (int i = 0; i < 6; i++) hits.add(new BlockHit(Indicator.BUDDING_AMETHYST, 810, 8 + i, -21, 3, -1, -1));
    }

    static AirGrid solidGrid(int cx, int cz) {
        int[] surface = new int[256];
        java.util.Arrays.fill(surface, 60);
        return new AirGrid(cx, cz, -20, 80, surface, 6);
    }

    static void carve(AirGrid g, int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) g.setAir(x, y, z);
    }

    static boolean has(ChunkAssessment a, String id) {
        return a.evidence().stream().anyMatch(e -> e.id().equals(id));
    }

    // ------------------------------------------------------------------ 1-3 growth

    @Test
    public void naturalVineGrowthIsIgnored() {
        List<BlockHit> hits = new ArrayList<>();
        for (int i = 0; i < 12; i++) vine(hits, i, 70, 3, 1 + i % 4); // short leaf-decorator vines, max 4 long
        ChunkAssessment a = scorer.score(obs(0, 0, hits, Map.of(), Set.of("jungle"), null), CFG);
        assertFalse(has(a, "plants.vines"));
        assertFalse(a.confidence().atLeast(Confidence.LOW));
    }

    @Test
    public void longVinesOnBuiltBlocksAreGrowthNearPlayers() {
        List<BlockHit> hits = new ArrayList<>();
        for (int i = 0; i < 10; i++) vine(hits, i, 40, 5, 14); // 14 long: grew after generation
        row(hits, Indicator.CRAFTED_BUILDING, 501, 0, 0, 0, 40, 6, 12);
        row(hits, Indicator.CRAFTED_BUILDING, 501, 0, 0, 0, 39, 6, 12);
        ChunkAssessment a = scorer.score(obs(0, 0, hits), CFG);
        assertTrue(has(a, "plants.vines"));
        assertTrue(has(a, "layout.vines"));
        // The same vines in a jungle count much less.
        ChunkAssessment jungle = scorer.score(obs(0, 0, hits, Map.of(), Set.of("jungle"), null), CFG);
        assertTrue(jungle.score() < a.score());
    }

    @Test
    public void naturalGeodeVersusOvergrownGeode() {
        List<BlockHit> natural = new ArrayList<>();
        geode(natural, 40, 10); // 25%: what world generation leaves
        ChunkAssessment n = scorer.score(obs(0, 0, natural), CFG);
        assertFalse(has(n, "amethyst.growth"));
        assertFalse(n.confidence().atLeast(Confidence.LOW));

        List<BlockHit> grown = new ArrayList<>();
        geode(grown, 40, 36); // 90% fully grown: random ticks for a long time
        ChunkAssessment g = scorer.score(obs(0, 0, grown), CFG);
        assertTrue(has(g, "amethyst.growth"));
        assertTrue("overgrown geode " + g.confidence(), g.confidence().atLeast(Confidence.LOW));
        // Growth alone is one sign: at most Medium.
        assertFalse(g.confidence().atLeast(Confidence.HIGH));
        SiteClusterer.Site site = new SiteClusterer().cluster(List.of(g), OW, CFG, true).get(0);
        assertEquals(SiteClusterer.Type.OVERGROWN, site.primary());
        // Growth plus storage nearby: two signs, can be High.
        List<BlockHit> both = new ArrayList<>(grown);
        row(both, Indicator.STORAGE, 54, 0, 0, 2, -30, 12, 8);
        row(both, Indicator.WORKSTATION, 55, 0, 0, 2, -30, 13, 4);
        ChunkAssessment b = scorer.score(obs(0, 0, both), CFG);
        assertTrue("growth + storage " + b.confidence() + " " + b.score(), b.confidence().atLeast(Confidence.HIGH));
    }

    @Test
    public void ripeCropsAndAgedCopperAreGrowth() {
        List<BlockHit> hits = new ArrayList<>();
        for (int i = 0; i < 30; i++) hits.add(new BlockHit(Indicator.CROP, 70, i % 6, -30, i / 6, i < 27 ? 7 : 2, 7));
        for (int i = 0; i < 8; i++) hits.add(new BlockHit(Indicator.AGED_COPPER, 71, i, -31, 8, -1, -1));
        ChunkAssessment a = scorer.score(obs(0, 0, hits), CFG);
        assertTrue(has(a, "plants.crops"));
        assertTrue(has(a, "aging.copper"));
        assertEquals(Indicator.AGED_COPPER, IndicatorRegistry.vanillaDefaults().lookup("minecraft:weathered_cut_copper"));
        assertTrue(Indicator.AGED_COPPER.growth() && Indicator.KELP.growth() && !Indicator.STORAGE.growth());
    }

    // ------------------------------------------------------------------ 4 rooms

    @Test
    public void boxRoomIsFoundAndNaturalCaveIsNot() {
        AirGrid g = solidGrid(0, 0);
        carve(g, 3, 10, 3, 9, 12, 9); // 7x3x7 room 48 blocks under the surface
        GeometryAnalyzer.Report r = new GeometryAnalyzer().analyze(g);
        assertEquals(1, r.count(GeometryAnalyzer.Shape.ROOM));

        AirGrid cave = solidGrid(0, 0);
        Random rnd = new Random(7);
        // A blobby natural cave: random spheres joined together.
        for (int k = 0; k < 6; k++) {
            int cxx = 3 + rnd.nextInt(10), cy = 5 + rnd.nextInt(20), cz = 3 + rnd.nextInt(10), rad = 2 + rnd.nextInt(2);
            for (int y = cy - rad; y <= cy + rad; y++) for (int z = cz - rad; z <= cz + rad; z++) for (int x = cxx - rad; x <= cxx + rad; x++) {
                if (x < 0 || x > 15 || z < 0 || z > 15) continue;
                if ((x - cxx) * (x - cxx) + (y - cy) * (y - cy) + (z - cz) * (z - cz) <= rad * rad) cave.setAir(x, y, z);
            }
        }
        GeometryAnalyzer.Report c = new GeometryAnalyzer().analyze(cave);
        assertEquals(0, c.count(GeometryAnalyzer.Shape.ROOM));
        assertEquals(0, c.count(GeometryAnalyzer.Shape.EXCAVATION));
        assertTrue(c.irregular() + c.components() > 0);
    }

    @Test
    public void tunnelsShaftsStaircasesAndSurfaceAir() {
        AirGrid g = solidGrid(0, 0);
        carve(g, 0, 0, 7, 15, 1, 7);          // 1x2 branch mine across the chunk
        carve(g, 12, 2, 2, 12, 30, 2);        // vertical shaft
        for (int i = 0; i < 8; i++) carve(g, 2 + i, 30 - i, 12, 2 + i, 31 - i, 12); // staircase going down along +x
        carve(g, 0, 56, 0, 15, 59, 15);       // air just under the surface (within min depth): ignored
        GeometryAnalyzer.Report r = new GeometryAnalyzer().analyze(g);
        assertTrue("tunnel", r.count(GeometryAnalyzer.Shape.TUNNEL) >= 1);
        assertTrue("shaft", r.count(GeometryAnalyzer.Shape.SHAFT) >= 1);
        assertTrue("staircase", r.count(GeometryAnalyzer.Shape.STAIRCASE) >= 1);
        assertEquals(0, r.count(GeometryAnalyzer.Shape.EXCAVATION));
        GeometryAnalyzer.Feature t = r.features().stream().filter(f -> f.shape() == GeometryAnalyzer.Shape.TUNNEL).findFirst().get();
        assertEquals("touches west and east", 3, t.borders() & 3);
    }

    // ------------------------------------------------------------------ 5-8 lived-in signs and patterns

    @Test
    public void undergroundStorageAreaIsFound() {
        List<BlockHit> hits = new ArrayList<>();
        row(hits, Indicator.STORAGE, 54, 0, 0, 2, -40, 3, 8);
        row(hits, Indicator.STORAGE, 54, 0, 0, 2, -39, 3, 8);
        row(hits, Indicator.WORKSTATION, 61, 0, 0, 2, -40, 9, 4);
        row(hits, Indicator.LIGHT_SOURCE, 50, 0, 0, 0, -37, 6, 1);
        ChunkAssessment a = scorer.score(obs(0, 0, hits), CFG);
        assertTrue(has(a, "layout.storage"));
        assertTrue("storage room " + a.confidence(), a.confidence().atLeast(Confidence.MEDIUM));
        SiteClusterer.Site s = new SiteClusterer().cluster(List.of(a), OW, CFG, true).get(0);
        assertTrue(s.types().contains(SiteClusterer.Type.STORAGE));
        assertTrue(s.types().contains(SiteClusterer.Type.UNDERGROUND));
    }

    @Test
    public void redstoneMachineryCountsAsLivedIn() {
        List<BlockHit> hits = new ArrayList<>();
        row(hits, Indicator.REDSTONE, 30, 0, 0, 0, -30, 2, 14);
        row(hits, Indicator.REDSTONE, 31, 0, 0, 0, -29, 2, 10);
        row(hits, Indicator.STORAGE, 54, 0, 0, 0, -30, 4, 6);
        ChunkAssessment a = scorer.score(obs(0, 0, hits), CFG);
        assertTrue(a.details().groups().contains(Indicator.Group.REDSTONE));
        assertTrue(a.confidence().atLeast(Confidence.MEDIUM));
        assertTrue(new SiteClusterer().cluster(List.of(a), OW, CFG, true).get(0).types().contains(SiteClusterer.Type.REDSTONE));
    }

    @Test
    public void cropFarmPlotIsAFarm() {
        List<BlockHit> hits = new ArrayList<>();
        for (int z = 0; z < 6; z++) row(hits, Indicator.FARMLAND, 60, 0, 0, 2, -30, 2 + z, 6);
        for (int z = 0; z < 6; z++) for (int x = 0; x < 6; x++) hits.add(new BlockHit(Indicator.CROP, 61, 2 + x, -29, 2 + z, 7, 7));
        ChunkAssessment a = scorer.score(obs(0, 0, hits), CFG);
        assertTrue(has(a, "layout.farm"));
        assertTrue(has(a, "plants.crops"));
        assertTrue(new SiteClusterer().cluster(List.of(a), OW, CFG, true).get(0).types().contains(SiteClusterer.Type.FARM));
    }

    @Test
    public void repeatedPatternsAreRecognised() {
        List<BlockHit> hits = new ArrayList<>();
        for (int i = 0; i < 5; i++) hits.add(new BlockHit(Indicator.LIGHT_SOURCE, 50, i * 4, -30, 2, -1, -1)); // torch every 4
        for (int y = 0; y < 4; y++) row(hits, Indicator.CRAFTED_BUILDING, 98, 0, 0, 0, -30 + y, 0, 6); // 6x4 wall
        ChunkAssessment a = scorer.score(obs(0, 0, hits), CFG);
        assertTrue(has(a, "layout.lights"));
        assertTrue(has(a, "layout.wall"));
        // ...but walls and lights alone are building, not a base.
        assertFalse(a.confidence().atLeast(Confidence.MEDIUM));
    }

    // ------------------------------------------------------------------ 9-10 generated structures

    @Test
    public void generatedVillageIsLowered() {
        List<BlockHit> hits = new ArrayList<>();
        row(hits, Indicator.WORKSTATION, 61, 0, 0, 0, 70, 0, 6);
        row(hits, Indicator.BED, 62, 0, 0, 0, 70, 3, 3);
        row(hits, Indicator.STORAGE, 54, 0, 0, 0, 70, 6, 4);
        row(hits, Indicator.CROP, 63, 0, 0, 0, 69, 9, 14);
        Map<String, Integer> sig = Map.of("bell", 1, "dirt_path", 30, "hay_block", 5);
        ChunkAssessment plain = scorer.score(obs(0, 0, hits), CFG);
        ChunkAssessment village = scorer.score(obs(0, 0, hits, sig, Set.of("plains"), null), CFG);
        assertTrue(has(village, "caveat.generated"));
        assertTrue(village.score() < plain.score());
        assertFalse("village " + village.confidence(), village.confidence().atLeast(Confidence.HIGH));
        assertEquals(StructureSignatures.Kind.VILLAGE, village.details().structures().get(0).kind());
        ChunkAssessment ignored = scorer.score(obs(0, 0, hits, sig, Set.of("plains"), null),
                CFG.toBuilder().structureHandling(ScoringConfig.StructureHandling.IGNORE).build());
        assertFalse(ignored.confidence().atLeast(Confidence.LOW));
    }

    @Test
    public void villageSpreadOverManyChunksStaysLow() {
        // A real generated village (seen in-game): 20+ chunks of houses, farms, beds, workstations,
        // chests; the bell and paths in only some of them.
        List<ChunkAssessment> chunks = new ArrayList<>();
        for (int cx = 0; cx < 5; cx++) {
            for (int cz = 0; cz < 4; cz++) {
                List<BlockHit> hits = new ArrayList<>();
                row(hits, Indicator.WORKSTATION, 61, cx, cz, 0, 70, 0, 4);
                row(hits, Indicator.BED, 62, cx, cz, 0, 70, 3, 3);
                row(hits, Indicator.STORAGE, 54, cx, cz, 0, 70, 6, 3);
                for (int z = 0; z < 4; z++) row(hits, Indicator.FARMLAND, 60, cx, cz, 4, 69, 9 + z, 8);
                row(hits, Indicator.CRAFTED_BUILDING, 98, cx, cz, 0, 71, 14, 12);
                Map<String, Integer> sig = (cx + cz) % 3 == 0 ? Map.of("dirt_path", 20, "bell", cx == 0 && cz == 0 ? 1 : 0) : Map.of();
                chunks.add(scorer.score(obs(cx, cz, hits, sig, Set.of("plains"), null), CFG));
            }
        }
        List<SiteClusterer.Site> sites = new SiteClusterer().cluster(chunks, OW, CFG, false);
        assertEquals(1, sites.size());
        assertFalse("village site " + sites.get(0).confidence(), sites.get(0).confidence().atLeast(Confidence.MEDIUM));
        for (ChunkAssessment a : chunks) {
            if (!a.details().structures().isEmpty()) assertFalse(a.confidence().atLeast(Confidence.MEDIUM));
        }
    }

    @Test
    public void mineshaftWithoutRailsIsStillAMineshaft() {
        List<BlockHit> hits = new ArrayList<>();
        for (int z = 0; z < 16; z += 4) {
            hits.add(new BlockHit(Indicator.MINESHAFT_WOOD, 503, 7, -14, z, -1, -1));
            hits.add(new BlockHit(Indicator.MINESHAFT_WOOD, 503, 9, -14, z, -1, -1));
            hits.add(new BlockHit(Indicator.MINESHAFT_WOOD, 501, 8, -12, z, -1, -1));
        }
        Map<String, Integer> sig = Map.of("oak_fence", 8, "oak_planks", 4);
        AirGrid g = solidGrid(0, 0);
        carve(g, 7, -15, 0, 9, -13, 15); // its 3x3 corridor
        ChunkAssessment a = scorer.score(obs(0, 0, hits, sig, Set.of(), new GeometryAnalyzer().analyze(g)), CFG);
        assertTrue(has(a, "caveat.mineshaft"));
        assertTrue(a.details().features().stream().noneMatch(f -> f.shape() == GeometryAnalyzer.Shape.TUNNEL));
        assertFalse(a.confidence().atLeast(Confidence.LOW));
    }

    @Test
    public void dungeonsAncientCitiesTrialChambersAreNotBases() {
        AirGrid room = solidGrid(0, 0);
        carve(room, 4, 10, 4, 10, 13, 10);
        GeometryAnalyzer.Report geo = new GeometryAnalyzer().analyze(room);
        List<BlockHit> hits = new ArrayList<>();
        row(hits, Indicator.STORAGE, 54, 0, 0, 4, 10, 4, 2);
        Map<String, Map<String, Integer>> cases = new HashMap<>();
        cases.put("dungeon", Map.of("spawner", 1, "mossy_cobblestone", 30));
        cases.put("ancient city", Map.of("reinforced_deepslate", 4, "sculk", 40, "deepslate_tiles", 80));
        cases.put("trial chambers", Map.of("trial_spawner", 2, "tuff_bricks", 100, "waxed_copper_grate", 10));
        cases.put("stronghold", Map.of("stone_bricks", 200, "mossy_stone_bricks", 20, "iron_bars", 4));
        for (Map.Entry<String, Map<String, Integer>> c : cases.entrySet()) {
            ChunkAssessment a = scorer.score(obs(0, 0, hits, c.getValue(), Set.of(), geo), CFG);
            assertFalse(c.getKey() + " scored " + a.confidence(), a.confidence().atLeast(Confidence.MEDIUM));
            assertFalse(c.getKey() + " has no recognition", a.details().structures().isEmpty());
        }
    }

    // ------------------------------------------------------------------ 11 multi-chunk

    @Test
    public void neighbouringChunksAreJudgedTogether() {
        List<BlockHit> storage = new ArrayList<>();
        row(storage, Indicator.STORAGE, 54, 0, 0, 2, -30, 3, 10);
        List<BlockHit> farm = new ArrayList<>();
        for (int z = 0; z < 5; z++) row(farm, Indicator.FARMLAND, 60, 1, 0, 2, -30, 2 + z, 5);
        row(farm, Indicator.BED, 62, 1, 0, 2, -29, 10, 4);
        row(farm, Indicator.WORKSTATION, 61, 1, 0, 2, -29, 12, 5);
        ChunkAssessment a = scorer.score(obs(0, 0, storage), CFG);
        ChunkAssessment b = scorer.score(obs(1, 0, farm), CFG);
        ChunkAssessment far = scorer.score(obs(9, 9, storage), CFG);
        List<SiteClusterer.Site> sites = new SiteClusterer().cluster(List.of(a, b, far), OW, CFG, true);
        assertEquals(2, sites.size());
        SiteClusterer.Site joined = sites.stream().filter(s -> s.chunks().size() == 2).findFirst().get();
        assertTrue(joined.raw() >= a.details().raw() + b.details().raw() - 1e-9);
        assertTrue("joined " + joined.confidence() + " vs " + a.confidence() + "/" + b.confidence(),
                joined.confidence().ordinal() >= Math.max(a.confidence().ordinal(), b.confidence().ordinal()));
        assertTrue(joined.groups().containsAll(Set.of(Indicator.Group.STORAGE, Indicator.Group.FARMING)));
    }

    @Test
    public void tunnelCrossingChunksIsLinked() {
        AirGrid g0 = solidGrid(0, 0), g1 = solidGrid(1, 0), g2 = solidGrid(2, 0);
        for (AirGrid g : List.of(g0, g1, g2)) carve(g, 0, 0, 7, 15, 1, 7);
        List<ChunkAssessment> parts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            GeometryAnalyzer.Report r = new GeometryAnalyzer().analyze(List.of(g0, g1, g2).get(i));
            parts.add(scorer.score(obs(i, 0, List.of(), Map.of(), Set.of(), r), CFG));
        }
        SiteClusterer.Site s = new SiteClusterer().cluster(parts, OW, CFG, true).get(0);
        assertEquals(3, s.chunks().size());
        assertTrue(s.evidence().stream().anyMatch(e -> e.id().equals("shape.multichunk")));
        // A tunnel alone is a shape, not a lived-in place.
        assertFalse(s.confidence().atLeast(Confidence.HIGH));
    }

    // ------------------------------------------------------------------ 12-13 confidence and false positives

    @Test
    public void randomlyPlacedBlocksAreNotABase() {
        List<BlockHit> hits = new ArrayList<>();
        Random rnd = new Random(3);
        for (int i = 0; i < 80; i++) {
            hits.add(new BlockHit(Indicator.PLAYER_ONLY, 777, rnd.nextInt(16), -40 + rnd.nextInt(60), rnd.nextInt(16), -1, -1));
        }
        for (int i = 0; i < 40; i++) {
            hits.add(new BlockHit(Indicator.CRAFTED_BUILDING, 501, rnd.nextInt(16), -40 + rnd.nextInt(60), rnd.nextInt(16), -1, -1));
        }
        ChunkAssessment a = scorer.score(obs(0, 0, hits), CFG);
        assertTrue(has(a, "caveat.scattered"));
        assertFalse("random spam " + a.confidence(), a.confidence().atLeast(Confidence.MEDIUM));
        // A solid concrete wall (built, not scattered) is still only "placed blocks": no lived-in signs.
        List<BlockHit> wall = new ArrayList<>();
        for (int y = 0; y < 5; y++) row(wall, Indicator.PLAYER_ONLY, 777, 0, 0, 0, -30 + y, 0, 12);
        ChunkAssessment w = scorer.score(obs(0, 0, wall), CFG);
        assertNotEquals(Confidence.CONFIRMED, w.confidence());
        assertFalse(w.confidence().atLeast(Confidence.MEDIUM));
        assertTrue(has(w, "caveat.not_lived_in"));
        // The wall around storage and a workstation: confirmed player base.
        row(wall, Indicator.STORAGE, 54, 0, 0, 0, -30, 2, 6);
        row(wall, Indicator.WORKSTATION, 61, 0, 0, 0, -30, 4, 4);
        assertEquals(Confidence.CONFIRMED, scorer.score(obs(0, 0, wall), CFG).confidence());
    }

    @Test
    public void confidenceLevelsAreOrdered() {
        assertEquals(7, Confidence.values().length);
        assertTrue(Confidence.CONFIRMED.atLeast(Confidence.VERY_HIGH));
        assertTrue(Confidence.VERY_LOW.atLeast(Confidence.NONE) && !Confidence.VERY_LOW.atLeast(Confidence.LOW));
        assertEquals("Confirmed Structure", Confidence.CONFIRMED.label());
        ChunkAssessment empty = scorer.score(obs(0, 0, List.of()), CFG);
        assertEquals(Confidence.NONE, empty.confidence());
    }

    // ------------------------------------------------------------------ 14-17 range, memory, cache, ESP

    @Test
    public void rangePresetsNeverExceedLoadedData() {
        assertEquals(4, RangePreset.SMALL.chunks(12));
        assertEquals(12, RangePreset.ULTRA.chunks(12)); // server sends 12 chunks: 1024 is not possible
        assertEquals(12, RangePreset.MAXIMUM.chunks(12));
        assertEquals(192, RangePreset.ULTRA.effectiveBlocks(12));
        assertEquals(512, RangePreset.EXTREME.blocks());
        assertEquals(Double.MAX_VALUE, RangePreset.MAXIMUM.distance(), 0);
        ChunkScanQueue q = new ChunkScanQueue();
        for (int x = -40; x <= 40; x++) for (int z = -40; z <= 40; z++) q.enqueue(x, z, 0);
        List<int[]> got = q.poll(50, 0, 0, RangePreset.LARGE.chunks(64), 1);
        assertEquals(50, got.size());
        for (int[] c : got) assertTrue(c[0] * c[0] + c[1] * c[1] <= 16 * 16);
        assertTrue("nearest first", Math.abs(got.get(0)[0]) + Math.abs(got.get(0)[1]) <= 1);
    }

    @Test
    public void memoryStaysBounded() {
        DiscoveryStore store = new DiscoveryStore(500);
        for (int i = 0; i < 3000; i++) {
            List<BlockHit> hits = new ArrayList<>();
            row(hits, Indicator.STORAGE, 54, i, 0, 0, -30, 0, 6);
            store.update(scorer.score(obs(i, 0, hits), CFG), Confidence.HIGH);
        }
        assertTrue(store.size() <= 500);
        for (int i = 0; i < DiscoveryStore.MAX_SAVED + 300; i++) {
            store.setMark(scorer.score(obs(10_000 + i, 0, List.of()), CFG), DiscoveryStore.Mark.SAVED, i);
        }
        assertTrue(store.savedFindings().size() <= DiscoveryStore.MAX_SAVED);
    }

    @Test
    public void changedChunksAreRescannedAndSitesUpdate() {
        ChunkScanQueue q = new ChunkScanQueue();
        q.enqueue(0, 0, 0);
        assertEquals(1, q.poll(5, 0, 0, 8, 1).size());
        assertTrue(q.poll(5, 0, 0, 8, 2).isEmpty());
        q.invalidate(0, 0, 3);
        assertEquals("changed chunk queued again", 1, q.poll(5, 0, 0, 8, 4).size());

        DiscoveryStore store = new DiscoveryStore(100);
        List<BlockHit> hits = new ArrayList<>();
        row(hits, Indicator.STORAGE, 54, 0, 0, 0, -30, 0, 10);
        row(hits, Indicator.WORKSTATION, 61, 0, 0, 0, -30, 2, 6);
        store.update(scorer.score(obs(0, 0, hits), CFG), Confidence.HIGH);
        assertEquals(1, store.sites(OW, CFG, true, 0).size());
        store.update(scorer.score(obs(0, 0, List.of()), CFG), Confidence.HIGH); // base was removed
        assertEquals(0, store.sites(OW, CFG, true, 0).size());
    }

    @Test
    public void espDistanceFilteringAndCap() {
        List<OverlayCuller.Box> boxes = new ArrayList<>();
        for (int i = 0; i < 2000; i++) boxes.add(new OverlayCuller.Box(i, 64, 0, i + 1, 65, 1, 0xFFFFFFFF, null, 1));
        List<OverlayCuller.Box> small = OverlayCuller.cull(boxes, 0, 64, 0, RangePreset.SMALL.blocks(), 10_000, 0, 0, 0, 0);
        assertEquals(64, small.size());
        List<OverlayCuller.Box> ultra = OverlayCuller.cull(boxes, 0, 64, 0, RangePreset.ULTRA.blocks(), 100, 0, 0, 0, 0);
        assertEquals("entity cap keeps the nearest", 100, ultra.size());
        assertEquals(0, ultra.get(0).minX(), 0);
    }

    // ------------------------------------------------------------------ 18-20 world switch, persistence, alerts

    @Test
    public void worldSwitchClearsResultsButKeepsSaved() {
        DiscoveryStore store = new DiscoveryStore(100);
        List<BlockHit> hits = new ArrayList<>();
        row(hits, Indicator.STORAGE, 54, 0, 0, 0, -30, 0, 10);
        row(hits, Indicator.BED, 62, 0, 0, 0, -30, 2, 3);
        ChunkAssessment a = scorer.score(obs(0, 0, hits), CFG);
        store.update(a, Confidence.HIGH);
        store.saveSite(store.sites(OW, CFG, true, 5).get(0), "server.example", 5);
        store.clearSession();
        assertEquals(0, store.size());
        assertTrue(store.sites(OW, CFG, true, 6).isEmpty());
        assertEquals(1, store.savedFindings().size());
        assertEquals("shown as historical", 1, store.historical(OW).size());
    }

    @Test
    public void discoveriesPersistWithEvidenceAndOldFilesStillLoad() throws Exception {
        DiscoveryStore store = new DiscoveryStore(100);
        List<BlockHit> hits = new ArrayList<>();
        row(hits, Indicator.STORAGE, 54, 2, 3, 0, -30, 0, 10);
        row(hits, Indicator.WORKSTATION, 61, 2, 3, 0, -30, 2, 6);
        store.update(scorer.score(obs(2, 3, hits), CFG), Confidence.HIGH);
        SiteClusterer.Site s = store.sites(OW, CFG, true, 100).get(0);
        store.saveSite(s, "play.example.net", 100);
        store.setNote(s.key(), "checked, has a farm");
        Path f = tmp.newFolder().toPath().resolve("discoveries.json");
        store.save(f);
        DiscoveryStore back = new DiscoveryStore(100);
        back.load(f);
        DiscoveryStore.SavedFinding sf = back.savedFindings().get(0);
        assertEquals("play.example.net", sf.worldId());
        assertEquals(OW, sf.dimension());
        assertEquals(100, sf.lastSeen());
        assertEquals(-30, sf.blockY());
        assertFalse(sf.evidence().isEmpty());
        assertEquals("checked, has a farm", sf.note());
        assertEquals(1, back.historical(OW).size());

        Path old = f.getParent().resolve("old.json");
        Files.writeString(old, "{\"formatVersion\":1,\"saved\":[{\"dimension\":\"minecraft:overworld\",\"chunkX\":4,\"chunkZ\":5,"
                + "\"score\":60,\"confidence\":\"High\",\"status\":\"Unknown\",\"explanation\":\"x\",\"savedAtMillis\":7,\"note\":\"\"}],\"marks\":{}}");
        DiscoveryStore legacy = new DiscoveryStore(100);
        legacy.load(old);
        DiscoveryStore.SavedFinding lf = legacy.savedFindings().get(0);
        assertEquals(72, lf.blockX());
        assertEquals(7, lf.lastSeen());
        assertNotNull(lf.evidence());
    }

    @Test
    public void alertsAreGroupedAndCooledDown() {
        AlertThrottle t = new AlertThrottle();
        t.configure(20_000, 96);
        assertEquals(AlertThrottle.Decision.NOTIFY, t.offer("Storage area", "a", 0, 0, Confidence.MEDIUM, 0));
        assertEquals("same site, same level", AlertThrottle.Decision.SKIP, t.offer("Storage area", "a", 0, 0, Confidence.MEDIUM, 1000));
        assertEquals("nearby find grouped", AlertThrottle.Decision.GROUPED, t.offer("Storage area", "b", 40, 30, Confidence.MEDIUM, 2000));
        assertEquals("far but in cooldown", AlertThrottle.Decision.GROUPED, t.offer("Storage area", "c", 900, 0, Confidence.MEDIUM, 3000));
        assertEquals("other kind", AlertThrottle.Decision.NOTIFY, t.offer("Unusual growth", "d", 2000, 0, Confidence.MEDIUM, 4000));
        assertEquals("site levelled up", AlertThrottle.Decision.NOTIFY, t.offer("Possible base found", "a", 0, 0, Confidence.HIGH, 5000));
        assertEquals(2, t.takeGrouped());
        assertEquals(0, t.takeGrouped());
        assertEquals("after cooldown", AlertThrottle.Decision.NOTIFY, t.offer("Storage area", "e", 5000, 0, Confidence.MEDIUM, 40_000));
        // Burst limit: at most 3 in 10 s.
        AlertThrottle burst = new AlertThrottle();
        burst.configure(0, 0);
        int shown = 0;
        for (int i = 0; i < 10; i++) {
            if (burst.offer("k" + i, "s" + i, i * 1000, 0, Confidence.HIGH, i * 100) == AlertThrottle.Decision.NOTIFY) shown++;
        }
        assertEquals(3, shown);
    }

    @Test
    public void newBlocksAreMapped() {
        IndicatorRegistry r = IndicatorRegistry.vanillaDefaults();
        assertEquals(Indicator.BUILDING_PART, r.lookup("minecraft:spruce_stairs"));
        assertEquals(Indicator.BUILDING_PART, r.lookup("minecraft:cobblestone_wall"));
        assertEquals(Indicator.DECORATION, r.lookup("minecraft:oak_wall_sign"));
        assertEquals(Indicator.DECORATION, r.lookup("minecraft:potted_poppy"));
        assertEquals(Indicator.SAPLING, r.lookup("minecraft:cherry_sapling"));
        assertEquals(Indicator.FARM_PLANT, r.lookup("minecraft:sugar_cane"));
        assertEquals("exact ids win over suffix rules", Indicator.MINESHAFT_WOOD, r.lookup("minecraft:oak_fence"));
        assertNull(r.lookup("minecraft:stone"));
        assertNull("mods are not guessed", r.lookup("othermod:fancy_stairs"));
        assertTrue(StructureSignatures.inVocabulary("bell"));
    }
}
