package dev.atlasclient.core;

import com.google.gson.JsonObject;
import dev.atlasclient.core.TestModules.Harness;
import dev.atlasclient.core.TestModules.SampleModule;
import dev.atlasclient.core.analysis.BlockHit;
import dev.atlasclient.core.analysis.ChunkAssessment;
import dev.atlasclient.core.analysis.ChunkObservation;
import dev.atlasclient.core.analysis.ChunkScanQueue;
import dev.atlasclient.core.analysis.Confidence;
import dev.atlasclient.core.analysis.DiscoveryStore;
import dev.atlasclient.core.analysis.Indicator;
import dev.atlasclient.core.analysis.ScoringConfig;
import dev.atlasclient.core.analysis.SuspicionScorer;
import dev.atlasclient.core.debris.DebrisRecord;
import dev.atlasclient.core.debris.DebrisTracker;
import dev.atlasclient.core.event.CoreEvents;
import dev.atlasclient.core.event.EventBus;
import dev.atlasclient.core.gui.Animator;
import dev.atlasclient.core.gui.GuiModel;
import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.notify.Notification;
import dev.atlasclient.core.notify.NotificationManager;
import dev.atlasclient.core.path.PathFollower;
import dev.atlasclient.core.path.PathWorld;
import dev.atlasclient.core.path.Pathfinder;
import dev.atlasclient.core.render.OverlayCuller;
import dev.atlasclient.core.setting.Keybind;
import dev.atlasclient.core.util.BackgroundWorker;
import dev.atlasclient.core.util.BoundedCache;
import dev.atlasclient.core.util.SessionLifecycle;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/**
 * Requirements 11 (rendering initialization logic), 12 (GUI interactions), 13 (world disconnect
 * cleanup) and 14 (performance with many loaded chunks), plus pathfinding and notifications.
 */
public class RuntimeBehaviourTest {

    // ------------------------------------------------------------------ GUI

    @Test
    public void guiSearchCategoriesAndKeyCapture() {
        Harness h = new Harness();
        SampleModule bf = h.modules.register(new SampleModule("Base Finder", Category.WORLD));
        h.modules.register(new SampleModule("Waypoints", Category.RENDER));
        GuiModel gui = new GuiModel(h.modules);
        assertEquals(List.of(bf), gui.visibleModules());
        gui.selectCategory(Category.RENDER);
        assertEquals("Waypoints", gui.visibleModules().get(0).name());
        gui.focusSearch(true);
        for (char c : "base".toCharArray()) gui.typeChar(c);
        assertEquals("search spans categories", List.of(bf), gui.visibleModules());
        gui.keyPressed(Keybind.KEY_BACKSPACE);
        assertEquals("bas", gui.search());
        gui.clearSearch();

        gui.clickModule(bf, 0);
        assertTrue("left click toggles", bf.isEnabled());
        gui.clickModule(bf, 1);
        assertSame("right click expands", bf, gui.expanded());

        gui.beginKeyCapture(bf.keybind());
        assertTrue(gui.keyPressed(71));
        assertEquals(71, bf.keybind().key());
        gui.beginKeyCapture(bf.keybind());
        gui.keyPressed(Keybind.KEY_ESCAPE);
        assertEquals("escape cancels capture", 71, bf.keybind().key());
        gui.beginKeyCapture(bf.keybind());
        gui.keyPressed(Keybind.KEY_DELETE);
        assertEquals("delete unbinds", Keybind.NONE, bf.keybind().key());
    }

    @Test
    public void guiSlidersDropdownsScrollAndPrefs() {
        Harness h = new Harness();
        SampleModule m = h.modules.register(new SampleModule("M", Category.WORLD));
        assertEquals(1.0, GuiModel.sliderValue(m.range, -50, 0, 100), 1e-9);
        assertEquals(32.0, GuiModel.sliderValue(m.range, 500, 0, 100), 1e-9);
        assertEquals(17.0, GuiModel.sliderValue(m.range, 51.6, 0, 100), 1e-9);
        assertEquals(Category.RENDER, m.cat.cycle(1));
        assertEquals(Category.WORLD, m.cat.cycle(-1));
        assertEquals(Category.CLIENT, m.cat.cycle(-1));
        GuiModel gui = new GuiModel(h.modules);
        gui.toggleDropdown("M.Mode");
        assertEquals("M.Mode", gui.openDropdown());
        gui.toggleDropdown("M.Mode");
        assertNull(gui.openDropdown());
        gui.setMaxScroll(100);
        gui.scrollBy(250);
        assertEquals(100, gui.scroll(), 1e-9);
        gui.scrollBy(-500);
        assertEquals(0, gui.scroll(), 1e-9);
        gui.selectCategory(Category.BUILDING);
        JsonObject prefs = new JsonObject();
        gui.savePrefs(prefs);
        GuiModel other = new GuiModel(h.modules);
        other.loadPrefs(prefs);
        assertEquals(Category.BUILDING, other.category());
    }

    @Test
    public void animatorIsFrameRateIndependent() {
        Animator slow = new Animator(), fast = new Animator();
        slow.frame(0);
        fast.frame(0);
        slow.approach("k", 0);
        fast.approach("k", 0);
        double s = 0, f = 0;
        for (int i = 1; i <= 3; i++) { slow.frame(i * 33_333_333L); s = slow.approach("k", 1); }
        for (int i = 1; i <= 12; i++) { fast.frame(i * 8_333_333L); f = fast.approach("k", 1); }
        assertEquals(s, f, 0.02);
    }

    // ------------------------------------------------------------------ rendering logic

    @Test
    public void overlayCullingLimitsByDistanceCountAndView() {
        List<OverlayCuller.Box> boxes = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            boxes.add(new OverlayCuller.Box(i, 0, 0, i + 1, 1, 1, 0xFFFFFFFF, null, 0));
        }
        List<OverlayCuller.Box> near = OverlayCuller.cull(boxes, 0, 0, 0, 100, 10_000, 0, 0, 0, 0);
        assertEquals(100, near.size());
        List<OverlayCuller.Box> capped = OverlayCuller.cull(boxes, 0, 0, 0, 1000, 25, 0, 0, 0, 0);
        assertEquals(25, capped.size());
        assertEquals("nearest kept", 0.0, capped.get(0).minX(), 1e-9);
        List<OverlayCuller.Box> behind = OverlayCuller.cull(boxes, 0, 0, 0, 1000, 10_000, -1, 0, 0, Math.cos(Math.toRadians(70)));
        assertTrue("boxes behind the camera are culled", behind.size() < 10);
        assertEquals(0x40FFFFFF, OverlayCuller.withAlpha(0x80FFFFFF, 0.5));
    }

    // ------------------------------------------------------------------ cleanup

    @Test
    public void disconnectClearsAllPerWorldState() throws Exception {
        Harness h = new Harness();
        SampleModule m = h.modules.register(new SampleModule("M", Category.WORLD) {
            boolean reset;
            @Override public void onSessionReset() { reset = true; enables = -1; }
        });
        DiscoveryStore store = new DiscoveryStore(100);
        DebrisTracker debris = new DebrisTracker(100);
        ChunkScanQueue queue = new ChunkScanQueue();
        BackgroundWorker<String> worker = new BackgroundWorker<>("test-worker", 16);
        SessionLifecycle life = new SessionLifecycle();
        life.register(new Res("store", store::clearSession));
        life.register(new Res("debris", debris::clear));
        life.register(new Res("queue", queue::clear));
        life.register(new Res("worker", worker::newGeneration));
        life.register(new Res("modules", h.modules::sessionReset));
        life.register(new Res("broken", () -> { throw new IllegalStateException("x"); }));

        store.update(new SuspicionScorer().score(new ChunkObservation("minecraft:overworld", 0, 0,
                List.of(BlockHit.of(Indicator.BEEHIVE, 1, 64, 1)), 1, 0, false), ScoringConfig.defaults()), Confidence.LOW);
        debris.observeChunk("minecraft:the_nether", 0, 0, List.of(new DebrisTracker.Found(1, 1, 1, DebrisRecord.Exposure.EXPOSED)),
                0, 8, DebrisRecord.Source.CLIENT_CHUNK, 0);
        queue.enqueue(1, 1, 0);
        // Work submitted before the reset must not leak into the new world.
        java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        worker.submit(() -> { try { gate.await(); } catch (InterruptedException ignored) { } return "old world"; });

        List<String> failures = life.reset(CoreEvents.SessionReset.Reason.DISCONNECT, "");
        gate.countDown();
        Thread.sleep(100);
        assertEquals("one broken part does not stop the others", 1, failures.size());
        assertEquals(0, store.size());
        assertEquals(0, debris.count("minecraft:the_nether"));
        assertEquals(0, queue.pendingCount());
        assertEquals(0, worker.drainResults(10, r -> fail("stale result " + r)));
        assertEquals(-1, m.enables);
        worker.close();
    }

    record Res(String resetName, Runnable action) implements SessionLifecycle.Resettable {
        @Override public void reset(CoreEvents.SessionReset.Reason reason) { action.run(); }
    }

    @Test
    public void backgroundWorkerDeliversResultsOnDrain() throws Exception {
        try (BackgroundWorker<Integer> w = new BackgroundWorker<>("w", 4)) {
            for (int i = 0; i < 4; i++) {
                final int v = i;
                assertTrue(w.submit(() -> v * v));
            }
            List<Integer> got = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 2000;
            while (got.size() < 4 && System.currentTimeMillis() < deadline) {
                w.drainResults(10, got::add);
                Thread.sleep(5);
            }
            assertEquals(Set.of(0, 1, 4, 9), new HashSet<>(got));
        }
    }

    @Test
    public void eventBusIsolatesListenerFailures() {
        EventBus bus = new EventBus();
        List<String> got = new ArrayList<>();
        AtomicLong errors = new AtomicLong();
        bus.setErrorHandler((e, t) -> errors.incrementAndGet());
        bus.subscribe(String.class, s -> { throw new RuntimeException("bad"); });
        EventBus.Subscription sub = bus.subscribe(String.class, got::add);
        assertEquals(1, bus.post("hello"));
        assertEquals(1, errors.get());
        sub.close();
        bus.post("again");
        assertEquals(List.of("hello"), got);
    }

    @Test
    public void notificationsExpireAndKeepHistory() {
        long[] now = {0};
        NotificationManager n = new NotificationManager(() -> now[0]);
        n.setDurationMillis(1000);
        n.setMaxVisible(2);
        n.discovery("Suspicious chunk", "a");
        n.info("Schematic loaded", "b");
        n.warn("Missing materials", "c");
        assertEquals(2, n.active().size());
        assertEquals(3, n.history().size());
        assertEquals("newest first", "Missing materials", n.history().get(0).title());
        now[0] = 1500;
        assertTrue(n.active().isEmpty());
        n.setMuted(Set.of(Notification.Level.INFO));
        n.info("x", "y");
        assertTrue(n.active().isEmpty());
        assertEquals(4, n.history().size());
    }

    // ------------------------------------------------------------------ pathfinding

    /** Flat floor at y=63 (stand at y=64) with a wall at x=5 except a gap at z=8. */
    static final class GridWorld implements PathWorld {
        final Set<Long> solid = new HashSet<>();

        GridWorld() {
            for (int x = -20; x <= 20; x++) for (int z = -20; z <= 20; z++) solid.add(BlockHit.pack(x, 63, z));
            for (int z = -20; z <= 20; z++) {
                if (z == 8) continue;
                solid.add(BlockHit.pack(5, 64, z));
                solid.add(BlockHit.pack(5, 65, z));
            }
        }

        @Override public boolean isPassable(int x, int y, int z) { return !solid.contains(BlockHit.pack(x, y, z)); }
        @Override public boolean isStandable(int x, int y, int z) { return solid.contains(BlockHit.pack(x, y, z)); }
        @Override public boolean isDangerous(int x, int y, int z) { return false; }
        @Override public boolean isWater(int x, int y, int z) { return false; }
        @Override public boolean isLoaded(int x, int z) { return Math.abs(x) <= 20 && Math.abs(z) <= 20; }
    }

    @Test
    public void pathfinderAvoidsObstaclesAndReplans() {
        GridWorld w = new GridWorld();
        Pathfinder pf = new Pathfinder(w);
        Pathfinder.Result r = pf.find(new Pathfinder.Node(0, 64, 0), new Pathfinder.Goal(10, 64, 0, 1.2));
        assertTrue(r.reachedGoal());
        assertTrue("goes through the gap", r.path().stream().anyMatch(n -> n.x() == 5 && n.z() == 8));
        // Block the gap: no full path, best partial path returned.
        w.solid.add(BlockHit.pack(5, 64, 8));
        w.solid.add(BlockHit.pack(5, 65, 8));
        Pathfinder.Result blocked = pf.maxNodes(3000).find(new Pathfinder.Node(0, 64, 0), new Pathfinder.Goal(10, 64, 0, 1.2));
        assertFalse(blocked.reachedGoal());
        assertTrue(blocked.found());
        // A one-high step is climbable.
        w.solid.remove(BlockHit.pack(5, 65, 8));
        Pathfinder.Result step = pf.find(new Pathfinder.Node(0, 64, 0), new Pathfinder.Goal(10, 64, 0, 1.2));
        assertTrue(step.reachedGoal());
        assertTrue(step.path().stream().anyMatch(n -> n.y() == 65));
        // Flight mode ignores floors.
        Pathfinder.Result fly = new Pathfinder(w).canFly(true).find(new Pathfinder.Node(0, 70, 0), new Pathfinder.Goal(0, 75, 0, 1.2));
        assertTrue(fly.reachedGoal());
        // A goal may forbid standing on the target (used before mining it).
        Pathfinder.Result notOn = new Pathfinder(w).find(new Pathfinder.Node(0, 64, 0), new Pathfinder.Goal(0, 63, 0, 4.5, true));
        assertTrue(notOn.reachedGoal());
        Pathfinder.Node end = notOn.path().get(notOn.path().size() - 1);
        assertFalse(end.x() == 0 && end.z() == 0 && end.y() == 64);
    }

    @Test
    public void pathfinderKeepsAwayFromLavaAndPricesDigging() {
        // Two ways from x=0 to x=10: the straight one runs along a lava pool (z=1 is lava in the floor).
        GridWorld base = new GridWorld();
        base.solid.removeIf(k -> true);
        for (int x = -20; x <= 20; x++) for (int z = -20; z <= 20; z++) base.solid.add(BlockHit.pack(x, 63, z));
        Set<Long> lava = new HashSet<>();
        for (int x = 2; x <= 8; x++) lava.add(BlockHit.pack(x, 63, 1));
        PathWorld w = new PathWorld() {
            @Override public boolean isPassable(int x, int y, int z) { return base.isPassable(x, y, z) && !lava.contains(BlockHit.pack(x, y, z)); }
            @Override public boolean isStandable(int x, int y, int z) { return base.isStandable(x, y, z) && !lava.contains(BlockHit.pack(x, y, z)); }
            @Override public boolean isDangerous(int x, int y, int z) { return lava.contains(BlockHit.pack(x, y, z)); }
            @Override public boolean isWater(int x, int y, int z) { return false; }
            @Override public boolean isLoaded(int x, int z) { return base.isLoaded(x, z); }
        };
        Pathfinder.Result r = new Pathfinder(w).find(new Pathfinder.Node(0, 64, 0), new Pathfinder.Goal(10, 64, 0, 1.2));
        assertTrue(r.reachedGoal());
        for (Pathfinder.Node n : r.path()) {
            assertFalse("walked right next to the lava at " + n, n.x() >= 1 && n.x() <= 9 && Math.abs(n.z() - 1) <= 1);
        }
        // Dig-through cost: a wall that may be dug is crossed only when there is no other way.
        GridWorld g = new GridWorld();
        Set<Long> wall = new HashSet<>();
        for (int z = -20; z <= 20; z++) { wall.add(BlockHit.pack(5, 64, z)); wall.add(BlockHit.pack(5, 65, z)); }
        PathWorld dig = new PathWorld() {
            @Override public boolean isPassable(int x, int y, int z) { return g.isPassable(x, y, z) || wall.contains(BlockHit.pack(x, y, z)); }
            @Override public boolean isStandable(int x, int y, int z) { return g.isStandable(x, y, z); }
            @Override public boolean isDangerous(int x, int y, int z) { return false; }
            @Override public boolean isWater(int x, int y, int z) { return false; }
            @Override public boolean isLoaded(int x, int z) { return g.isLoaded(x, z); }
            @Override public double moveCost(int x, int y, int z) {
                return (g.solid.contains(BlockHit.pack(x, y, z)) ? 2.5 : 0) + (g.solid.contains(BlockHit.pack(x, y + 1, z)) ? 2.5 : 0);
            }
        };
        Pathfinder.Result viaGap = new Pathfinder(dig).find(new Pathfinder.Node(0, 64, 8), new Pathfinder.Goal(10, 64, 8, 1.2));
        assertTrue(viaGap.reachedGoal());
        assertTrue("uses the open gap at z=8", viaGap.path().stream().anyMatch(n -> n.x() == 5 && n.z() == 8));
        Pathfinder.Result through = new Pathfinder(dig).find(new Pathfinder.Node(0, 64, -10), new Pathfinder.Goal(10, 64, -10, 1.2));
        assertTrue(through.reachedGoal());
        assertTrue("digs straight through rather than walking 18 blocks round", through.path().stream().anyMatch(n -> n.x() == 5 && n.z() < 0));
    }

    @Test
    public void followerSteersAndDetectsStuck() {
        PathFollower f = new PathFollower();
        f.setStuckTicksLimit(10);
        f.follow(List.of(new Pathfinder.Node(0, 64, 0), new Pathfinder.Node(0, 64, 5)));
        PathFollower.Intent i = f.tick(0.5, 64, 0.5);
        assertTrue(i.forward());
        assertEquals("facing +Z is yaw 0", 0, i.yaw(), 1);
        for (int t = 0; t < 12; t++) f.tick(0.5, 64, 0.5); // not moving
        assertEquals(PathFollower.Status.STUCK, f.status());
        f.follow(List.of(new Pathfinder.Node(0, 64, 0), new Pathfinder.Node(1, 64, 0)));
        f.tick(1.5, 64, 0.5);
        assertEquals(PathFollower.Status.ARRIVED, f.status());
    }

    // ------------------------------------------------------------------ performance

    @Test(timeout = 20_000)
    public void scoresManyChunksWithinBudget() {
        SuspicionScorer scorer = new SuspicionScorer();
        ScoringConfig cfg = ScoringConfig.defaults();
        DiscoveryStore store = new DiscoveryStore(4096);
        java.util.Random rnd = new java.util.Random(1);
        Indicator[] inds = Indicator.values();
        int chunks = 5000;
        long start = System.nanoTime();
        for (int c = 0; c < chunks; c++) {
            List<BlockHit> hits = new ArrayList<>(200);
            for (int i = 0; i < 200; i++) {
                Indicator ind = inds[rnd.nextInt(inds.length)];
                hits.add(new BlockHit(ind, ind.ordinal(), c * 16 + rnd.nextInt(16), rnd.nextInt(256), rnd.nextInt(16), rnd.nextInt(4), 3));
            }
            ChunkAssessment a = scorer.score(new ChunkObservation("minecraft:overworld", c, 0, hits, 24, c, false), cfg);
            store.update(a, Confidence.HIGH);
        }
        double perChunkMs = (System.nanoTime() - start) / 1e6 / chunks;
        assertEquals("cache stays bounded", 4096, store.size());
        assertTrue("per chunk " + perChunkMs + " ms", perChunkMs < 2.0);
    }

    @Test(timeout = 10_000)
    public void scanQueueHandlesManyLoadedChunks() {
        ChunkScanQueue q = new ChunkScanQueue();
        for (int x = -60; x < 60; x++) for (int z = -60; z < 60; z++) q.enqueue(x, z, 0);
        assertEquals(14_400, q.pendingCount());
        long start = System.nanoTime();
        int polled = 0;
        for (int tick = 0; tick < 100; tick++) polled += q.poll(4, 0, 0, 64, tick).size();
        double avgMs = (System.nanoTime() - start) / 1e6 / 100;
        assertEquals("budget respected", 400, polled);
        assertTrue("avg poll " + avgMs + " ms", avgMs < 25);
        BoundedCache<Integer, Integer> cache = new BoundedCache<>(1000);
        for (int i = 0; i < 100_000; i++) cache.put(i, i);
        assertEquals(1000, cache.size());
        assertEquals(99_000, cache.evictions());
    }
}
