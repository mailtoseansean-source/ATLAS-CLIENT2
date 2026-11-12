package dev.atlasclient.core;

import dev.atlasclient.core.analysis.AmethystAnalyzer;
import dev.atlasclient.core.analysis.BaseStatus;
import dev.atlasclient.core.analysis.BlockHit;
import dev.atlasclient.core.analysis.ChunkAssessment;
import dev.atlasclient.core.analysis.ChunkObservation;
import dev.atlasclient.core.analysis.ChunkScanQueue;
import dev.atlasclient.core.analysis.Confidence;
import dev.atlasclient.core.analysis.DiscoveryStore;
import dev.atlasclient.core.analysis.Evidence;
import dev.atlasclient.core.analysis.Indicator;
import dev.atlasclient.core.analysis.IndicatorRegistry;
import dev.atlasclient.core.analysis.ScoringConfig;
import dev.atlasclient.core.analysis.SuspicionScorer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.Assert.*;

/** Requirements 5 (suspicious chunk scoring) and 6 (amethyst evidence analysis). */
public class WorldAnalysisTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final ScoringConfig CFG = ScoringConfig.defaults();
    private final SuspicionScorer scorer = new SuspicionScorer();

    static ChunkObservation obs(String dim, List<BlockHit> hits, boolean authoritative) {
        return new ChunkObservation(dim, 0, 0, hits, 24, 1000L, authoritative);
    }

    static void add(List<BlockHit> hits, Indicator ind, int count, int y) {
        for (int i = 0; i < count; i++) {
            hits.add(new BlockHit(ind, ind.ordinal(), i % 16, y + i / 256, (i / 16) % 16, -1, -1));
        }
    }

    /** A plausible lived-in base: storage, beds, workstations, farm, redstone, glass walls, beehives. */
    static List<BlockHit> baseHits() {
        List<BlockHit> hits = new ArrayList<>();
        add(hits, Indicator.STORAGE, 12, 64);
        add(hits, Indicator.BED, 2, 65);
        add(hits, Indicator.WORKSTATION, 5, 66);
        add(hits, Indicator.FARMLAND, 40, 63);
        add(hits, Indicator.CROP, 40, 64);
        add(hits, Indicator.REDSTONE, 20, 60);
        add(hits, Indicator.BEEHIVE, 3, 70);
        // A 10-long straight wall line of concrete (player-only block)
        for (int x = 0; x < 10; x++) {
            hits.add(new BlockHit(Indicator.PLAYER_ONLY, 777, x, 80, 3, -1, -1));
        }
        return hits;
    }

    @Test
    public void emptyChunkScoresNothing() {
        ChunkAssessment a = scorer.score(obs("minecraft:overworld", List.of(), false), CFG);
        assertEquals(0, a.score(), 1e-9);
        assertEquals(Confidence.NONE, a.confidence());
        assertEquals(BaseStatus.UNKNOWN, a.status().status());
        assertTrue(a.explanation().startsWith("No meaningful"));
    }

    @Test
    public void naturalBaselinesDoNotTriggerAlone() {
        List<BlockHit> hits = new ArrayList<>();
        add(hits, Indicator.CRAFTED_BUILDING, 10, 40); // below baseline 12 (e.g. mineshaft planks)
        add(hits, Indicator.BEE_NEST, 1, 70);
        ChunkAssessment a = scorer.score(obs("minecraft:overworld", hits, false), CFG);
        assertFalse("natural amounts stay below Low, got " + a.confidence(), a.confidence().atLeast(Confidence.LOW));
    }

    @Test
    public void livedInBaseScoresVeryHighWithEvidence() {
        ChunkAssessment a = scorer.score(obs("minecraft:overworld", baseHits(), false), CFG);
        assertTrue("score " + a.score(), a.score() >= CFG.veryHighThreshold);
        // Concrete in a straight wall plus beehives: blocks world generation never places, in a built
        // arrangement, so this one is a confirmed structure (Very High would be the estimate otherwise).
        assertEquals(Confidence.CONFIRMED, a.confidence());
        assertTrue(a.evidence().stream().anyMatch(e -> e.id().equals("confirmed")));
        assertEquals(12, (int) a.counts().get(Indicator.STORAGE));
        assertTrue(a.indicatorTypes() >= 6);
        assertTrue(a.evidence().stream().anyMatch(e -> e.kind() == Evidence.Kind.OBSERVATION && e.count() == 12));
        assertTrue(a.evidence().stream().anyMatch(e -> e.id().equals("arrangement.runs")));
        assertTrue("non-authoritative data is flagged",
                a.evidence().stream().anyMatch(e -> e.id().equals("caveat.client_data")));
        assertTrue(a.explanation().contains("observed directly"));
        assertEquals(BaseStatus.LIKELY_INTACT, a.status().status());
        // Without the player-only blocks the same base is a Very High estimate, not confirmed.
        List<BlockHit> estimate = new ArrayList<>(baseHits());
        estimate.removeIf(h -> h.indicator() == Indicator.PLAYER_ONLY || h.indicator() == Indicator.BEEHIVE);
        add(estimate, Indicator.BED, 4, 90);
        ChunkAssessment e = scorer.score(obs("minecraft:overworld", estimate, false), CFG);
        assertTrue("estimate " + e.confidence() + " " + e.score(), e.confidence().atLeast(Confidence.HIGH));
        assertNotEquals(Confidence.CONFIRMED, e.confidence());
        assertTrue(e.explanation().contains("not proof"));
    }

    @Test
    public void structureMarkersDampScoreAndBlockStatus() {
        List<BlockHit> hits = baseHits();
        ChunkAssessment plain = scorer.score(obs("minecraft:overworld", hits, false), CFG);
        add(hits, Indicator.STRUCTURE_MARKER, 1, 70); // village bell
        ChunkAssessment village = scorer.score(obs("minecraft:overworld", hits, false), CFG);
        assertTrue(village.score() < plain.score());
        assertTrue(village.evidence().stream().anyMatch(e -> e.id().equals("caveat.structure")));
        assertEquals(BaseStatus.UNKNOWN, village.status().status());
    }

    /** Abandoned mineshaft corridor: lots of oak planks and fences in straight runs, rails, cobwebs. */
    static List<BlockHit> mineshaftHits() {
        List<BlockHit> hits = new ArrayList<>();
        for (int z = 0; z < 16; z++) {
            hits.add(new BlockHit(Indicator.MINESHAFT_WOOD, 501, 7, -12, z, -1, -1)); // plank ceiling line
            hits.add(new BlockHit(Indicator.MINESHAFT_WOOD, 501, 9, -12, z, -1, -1));
            hits.add(new BlockHit(Indicator.MINESHAFT_SIGN, 502, 8, -15, z, -1, -1)); // rail line
        }
        for (int z = 0; z < 16; z += 4) {
            hits.add(new BlockHit(Indicator.MINESHAFT_WOOD, 503, 7, -14, z, -1, -1)); // fence posts
            hits.add(new BlockHit(Indicator.MINESHAFT_WOOD, 503, 7, -13, z, -1, -1));
            hits.add(new BlockHit(Indicator.MINESHAFT_WOOD, 503, 9, -14, z, -1, -1));
            hits.add(new BlockHit(Indicator.MINESHAFT_WOOD, 503, 9, -13, z, -1, -1));
        }
        hits.add(new BlockHit(Indicator.MINESHAFT_SIGN, 504, 8, -13, 3, -1, -1)); // cobweb
        return hits;
    }

    @Test
    public void mineshaftIsNotABase() {
        ChunkAssessment a = scorer.score(obs("minecraft:overworld", mineshaftHits(), false), CFG);
        assertFalse("mineshaft scored " + a.confidence() + " " + a.score(), a.confidence().atLeast(Confidence.MEDIUM));
        assertTrue(a.evidence().stream().anyMatch(e -> e.id().equals("caveat.mineshaft")));
        IndicatorRegistry r = IndicatorRegistry.vanillaDefaults();
        assertEquals(Indicator.MINESHAFT_SIGN, r.lookup("minecraft:rail"));
        assertEquals(Indicator.MINESHAFT_SIGN, r.lookup("minecraft:cobweb"));
        assertEquals(Indicator.MINESHAFT_WOOD, r.lookup("minecraft:oak_planks"));
        assertEquals(Indicator.STRUCTURE_MARKER, r.lookup("minecraft:infested_stone_bricks"));
    }

    @Test
    public void baseNextToMineshaftIsStillFound() {
        List<BlockHit> hits = mineshaftHits();
        add(hits, Indicator.STORAGE, 10, -20);
        add(hits, Indicator.BED, 2, -21);
        add(hits, Indicator.WORKSTATION, 4, -22);
        ChunkAssessment a = scorer.score(obs("minecraft:overworld", hits, false), CFG);
        assertTrue("underground base by a mineshaft scored " + a.confidence() + " " + a.score(), a.confidence().atLeast(Confidence.MEDIUM));
    }

    @Test
    public void buildingBlocksAloneStayLow() {
        List<BlockHit> hits = new ArrayList<>();
        add(hits, Indicator.CRAFTED_BUILDING, 200, 30); // e.g. a stronghold corridor: bricks and torches only
        add(hits, Indicator.LIGHT_SOURCE, 20, 31);
        ChunkAssessment a = scorer.score(obs("minecraft:overworld", hits, false), CFG);
        assertEquals(Confidence.LOW, a.confidence());
        assertTrue(a.evidence().stream().anyMatch(e -> e.id().equals("caveat.building_only")));
        // Concrete proves a player placed blocks here, not that it is a base: without storage, beds,
        // workstations, farms, redstone or growth it stays Low (random building, pillars, griefing).
        add(hits, Indicator.PLAYER_ONLY, 30, 32);
        ChunkAssessment spam = scorer.score(obs("minecraft:overworld", hits, false), CFG);
        assertFalse("placed blocks alone: " + spam.confidence(), spam.confidence().atLeast(Confidence.MEDIUM));
        // With storage and workstations it is a lived-in place.
        add(hits, Indicator.STORAGE, 6, 33);
        add(hits, Indicator.WORKSTATION, 4, 34);
        assertTrue(scorer.score(obs("minecraft:overworld", hits, false), CFG).confidence().atLeast(Confidence.MEDIUM));
    }

    @Test
    public void singleEvidenceGroupCannotReachHigh() {
        List<BlockHit> hits = new ArrayList<>();
        add(hits, Indicator.STORAGE, 400, 10); // a huge storage room, nothing else
        ChunkAssessment defaults = scorer.score(obs("minecraft:overworld", hits, false), CFG);
        assertFalse("one indicator type alone stays below High with default weights",
                defaults.confidence().atLeast(Confidence.HIGH));
        ScoringConfig heavy = CFG.toBuilder().weight(Indicator.STORAGE, 10).build();
        ChunkAssessment a = scorer.score(obs("minecraft:overworld", hits, false), heavy);
        assertTrue(a.score() >= CFG.veryHighThreshold);
        assertEquals("diversity cap", Confidence.MEDIUM, a.confidence());
        assertTrue(a.evidence().stream().anyMatch(e -> e.id().equals("caveat.diversity")));
    }

    @Test
    public void raidedLookingBaseNeedsTwoSignals() {
        List<BlockHit> hits = new ArrayList<>();
        add(hits, Indicator.PLAYER_ONLY, 40, 70);
        add(hits, Indicator.WORKSTATION, 4, 71);
        add(hits, Indicator.REDSTONE, 10, 72);
        ChunkAssessment noStorage = scorer.score(obs("minecraft:overworld", hits, false), CFG);
        assertEquals(BaseStatus.POSSIBLY_RAIDED, noStorage.status().status());
        add(hits, Indicator.FIRE, 6, 73);
        ChunkAssessment burned = scorer.score(obs("minecraft:overworld", hits, false), CFG);
        assertEquals(BaseStatus.LIKELY_RAIDED, burned.status().status());
        // Fire in the Nether is natural and ignored.
        ChunkAssessment nether = scorer.score(obs("minecraft:the_nether", hits, false), CFG);
        assertEquals(BaseStatus.POSSIBLY_RAIDED, nether.status().status());
        assertTrue(burned.status().reasons().stream().anyMatch(r -> r.contains("server-side")));
    }

    @Test
    public void weightsAreConfigurable() {
        List<BlockHit> hits = new ArrayList<>();
        add(hits, Indicator.STORAGE, 10, 64);
        ChunkAssessment normal = scorer.score(obs("minecraft:overworld", hits, false), CFG);
        ScoringConfig heavy = CFG.toBuilder().weight(Indicator.STORAGE, 10).build();
        ChunkAssessment boosted = scorer.score(obs("minecraft:overworld", hits, false), heavy);
        assertTrue(boosted.score() > normal.score());
        ScoringConfig off = CFG.toBuilder().weight(Indicator.STORAGE, 0).build();
        assertEquals(0, scorer.score(obs("minecraft:overworld", hits, false), off).score(), 1e-9);
    }

    // ------------------------------------------------------------- amethyst

    /** Buddings in a row at y=30, each with buds above it at the given stages. */
    static List<BlockHit> geode(int buds, int clusters, int budding) {
        List<BlockHit> hits = new ArrayList<>();
        for (int i = 0; i < budding; i++) {
            hits.add(BlockHit.of(Indicator.BUDDING_AMETHYST, i % 16, 30, i / 16));
        }
        for (int i = 0; i < buds; i++) {
            Indicator stage = i < clusters ? Indicator.AMETHYST_CLUSTER
                    : new Indicator[]{Indicator.SMALL_AMETHYST_BUD, Indicator.MEDIUM_AMETHYST_BUD, Indicator.LARGE_AMETHYST_BUD}[i % 3];
            hits.add(BlockHit.of(stage, i % 16, 31, i / 16));
        }
        for (int i = 0; i < 40; i++) {
            hits.add(BlockHit.of(Indicator.AMETHYST_BLOCK, i % 16, 29, i / 16));
            hits.add(BlockHit.of(Indicator.CALCITE, i % 16, 28, i / 16));
        }
        return hits;
    }

    @Test
    public void naturalGeodeGrowthIsNotSuspicious() {
        AmethystAnalyzer.Report r = new AmethystAnalyzer().analyze(geode(40, 10, 40), CFG); // 25% clusters
        assertTrue(r.geodeVisible());
        assertEquals(10, r.clusters());
        assertEquals(0.25, r.clusterShare(), 1e-9);
        assertEquals(0, r.contribution(), 1e-9);
        assertTrue(r.evidence().stream().anyMatch(e -> e.kind() == Evidence.Kind.OBSERVATION));
        assertFalse(r.evidence().stream().anyMatch(e -> e.id().equals("amethyst.growth")));
    }

    @Test
    public void fullyGrownGeodeIsInferenceNotProof() {
        AmethystAnalyzer.Report r = new AmethystAnalyzer().analyze(geode(40, 38, 40), CFG); // 95% clusters
        assertTrue(r.zScore() > 4);
        assertTrue(r.contribution() > 0);
        Evidence growth = r.evidence().stream().filter(e -> e.id().equals("amethyst.growth")).findFirst().orElseThrow();
        assertEquals(Evidence.Kind.INFERENCE, growth.kind());
        assertTrue(growth.description().contains("not proof"));
        // Growth alone never reaches High confidence.
        ChunkAssessment a = new SuspicionScorer().score(obs("minecraft:overworld", geode(40, 38, 40), false), CFG);
        assertFalse(a.confidence().atLeast(Confidence.HIGH));
    }

    @Test
    public void tooFewBudsGivesCaveat() {
        AmethystAnalyzer.Report r = new AmethystAnalyzer().analyze(geode(4, 4, 4), CFG);
        assertEquals(0, r.contribution(), 1e-9);
        assertTrue(r.evidence().stream().anyMatch(e -> e.id().equals("amethyst.growth.insufficient")));
    }

    @Test
    public void harvestedAndMinedGeodePatterns() {
        AmethystAnalyzer.Report harvested = new AmethystAnalyzer().analyze(geode(0, 0, 20), CFG);
        assertTrue(harvested.evidence().stream().anyMatch(e -> e.id().equals("amethyst.harvested")));
        assertEquals(20, harvested.bareBudding());
        AmethystAnalyzer.Report mined = new AmethystAnalyzer().analyze(geode(0, 0, 0), CFG);
        assertTrue(mined.evidence().stream().anyMatch(e -> e.id().equals("amethyst.no_budding")));
    }

    @Test
    public void tallKelpIsGrowthEvidence() {
        List<BlockHit> hits = new ArrayList<>();
        for (int col = 0; col < 6; col++) {
            for (int y = 40; y < 58; y++) { // 18 tall: impossible from generation (max 10)
                hits.add(BlockHit.of(Indicator.KELP, col, y, 5));
            }
        }
        for (int y = 40; y < 45; y++) {
            hits.add(BlockHit.of(Indicator.KELP, 10, y, 10)); // normal height
        }
        ChunkAssessment a = scorer.score(obs("minecraft:overworld", hits, false), CFG);
        Evidence kelp = a.evidence().stream().filter(e -> e.id().equals("plants.kelp")).findFirst().orElseThrow();
        assertEquals(6, kelp.count());
        assertEquals(Evidence.Kind.INFERENCE, kelp.kind());
    }

    @Test
    public void registryMapsVanillaIds() {
        IndicatorRegistry r = IndicatorRegistry.vanillaDefaults();
        assertEquals(Indicator.BEEHIVE, r.lookup("minecraft:beehive"));
        assertEquals(Indicator.BUDDING_AMETHYST, r.lookup("budding_amethyst"));
        assertEquals(Indicator.PLAYER_ONLY, r.lookup("minecraft:lime_concrete"));
        assertEquals(Indicator.STRUCTURE_MARKER, r.lookup("minecraft:bell"));
        assertNull(r.lookup("minecraft:stone"));
        assertNull("cave vines generate long naturally and are excluded", r.lookup("minecraft:cave_vines"));
    }

    // ------------------------------------------------------------- discovery store & queue

    @Test
    public void discoveryStoreSaveHideDismissAndSort() throws Exception {
        DiscoveryStore store = new DiscoveryStore(100);
        ChunkAssessment strong = new ChunkAssessment("minecraft:overworld", 10, 10, 90, Confidence.VERY_HIGH,
                java.util.Map.of(Indicator.STORAGE, 5), List.of(), "x", BaseStatus.estimate(Confidence.NONE, java.util.Map.of(), false, false),
                null, 5000, false);
        ChunkAssessment weak = new ChunkAssessment("minecraft:overworld", 1, 1, 30, Confidence.MEDIUM,
                java.util.Map.of(Indicator.STORAGE, 1), List.of(), "y", BaseStatus.estimate(Confidence.NONE, java.util.Map.of(), false, false),
                null, 9000, false);
        assertTrue(store.update(strong, Confidence.HIGH));
        assertFalse("announced only once", store.update(strong, Confidence.HIGH));
        assertFalse(store.update(weak, Confidence.HIGH));
        assertEquals(List.of(strong, weak), store.list("minecraft:overworld", Confidence.LOW, DiscoveryStore.SortKey.SCORE, 0, 0, false));
        assertEquals(List.of(weak, strong), store.list("minecraft:overworld", Confidence.LOW, DiscoveryStore.SortKey.DISTANCE, 0, 0, false));
        assertEquals(List.of(weak, strong), store.list("minecraft:overworld", Confidence.LOW, DiscoveryStore.SortKey.LAST_SEEN, 0, 0, false));
        store.setMark(weak, DiscoveryStore.Mark.HIDDEN, 0);
        assertEquals(List.of(strong), store.list("minecraft:overworld", Confidence.LOW, DiscoveryStore.SortKey.SCORE, 0, 0, false));
        store.setMark(strong, DiscoveryStore.Mark.SAVED, 123);
        Path file = tmp.getRoot().toPath().resolve("world/discoveries.json");
        store.save(file);
        DiscoveryStore reloaded = new DiscoveryStore(100);
        reloaded.load(file);
        assertEquals(1, reloaded.savedFindings().size());
        assertEquals(10, reloaded.savedFindings().get(0).chunkX());
        reloaded.setMark(strong, DiscoveryStore.Mark.DISMISSED, 0);
        reloaded.update(strong, Confidence.HIGH);
        assertTrue(reloaded.list("minecraft:overworld", Confidence.LOW, DiscoveryStore.SortKey.SCORE, 0, 0, true).isEmpty());
    }

    @Test
    public void scanQueueIsNearestFirstAndBudgeted() {
        ChunkScanQueue q = new ChunkScanQueue();
        for (int x = -10; x <= 10; x++) {
            q.enqueue(x, 0, 0);
        }
        List<int[]> first = q.poll(3, 0, 0, 32, 0);
        assertEquals(3, first.size());
        assertEquals(0, first.get(0)[0]);
        assertTrue(Math.abs(first.get(1)[0]) == 1 && Math.abs(first.get(2)[0]) == 1);
        assertEquals(18, q.pendingCount());
        assertTrue(q.wasScanned(0, 0));
        // out-of-range chunks are dropped
        q.enqueue(500, 500, 0);
        q.poll(100, 0, 0, 12, 0);
        assertEquals(0, q.pendingCount());
        // rescan only after interval
        q.setRescanIntervalMillis(1000);
        q.requeueStale(List.of(new long[]{0, 0}), 500);
        assertEquals(0, q.pendingCount());
        q.requeueStale(List.of(new long[]{0, 0}), 1500);
        assertEquals(1, q.pendingCount());
    }

    @Test
    public void scoringIsDeterministicOnRandomChunks() {
        Random rnd = new Random(42);
        List<BlockHit> hits = new ArrayList<>();
        Indicator[] values = Indicator.values();
        for (int i = 0; i < 500; i++) {
            Indicator ind = values[rnd.nextInt(values.length)];
            hits.add(new BlockHit(ind, ind.ordinal(), rnd.nextInt(16), rnd.nextInt(100), rnd.nextInt(16), rnd.nextInt(3), 2));
        }
        ChunkAssessment a = scorer.score(obs("minecraft:overworld", hits, false), CFG);
        ChunkAssessment b = scorer.score(obs("minecraft:overworld", hits, false), CFG);
        assertEquals(a.score(), b.score(), 0);
        assertTrue(a.score() >= 0 && a.score() <= 100);
    }
}
