package dev.atlasclient.scan;

import dev.atlasclient.Atlas;
import dev.atlasclient.core.analysis.ChunkAssessment;
import dev.atlasclient.core.analysis.ChunkScanQueue;
import dev.atlasclient.core.analysis.Confidence;
import dev.atlasclient.core.analysis.DiscoveryStore;
import dev.atlasclient.core.analysis.RangePreset;
import dev.atlasclient.core.analysis.SiteClusterer;
import dev.atlasclient.core.analysis.ScoringConfig;
import dev.atlasclient.core.analysis.SuspicionScorer;
import dev.atlasclient.core.debris.DebrisRecord;
import dev.atlasclient.core.debris.DebrisTracker;
import dev.atlasclient.core.event.CoreEvents;
import dev.atlasclient.core.util.BackgroundWorker;
import dev.atlasclient.core.util.SessionLifecycle;
import dev.atlasclient.util.Env;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Incremental world analysis shared by the Base Finder, Suspicious Chunk Analyzer and Ancient
 * Debris Finder. Per tick it snapshots at most {@code budget} loaded chunks on the client thread,
 * analyses them on one background thread, and applies results back on the client thread.
 *
 * <p>Only data the server already sent to this client is read. Nothing is requested from the
 * server and no hidden data is reconstructed.</p>
 */
public final class ScanService implements SessionLifecycle.Resettable {
    public record Result(ChunkAssessment assessment, ChunkSnapshot.Extracted extracted) { }

    private final ChunkScanQueue queue = new ChunkScanQueue();
    private final BackgroundWorker<Result> worker = new BackgroundWorker<>("Atlas-Analysis", 64);
    private final SuspicionScorer scorer = new SuspicionScorer();
    private final DiscoveryStore discoveries = new DiscoveryStore(8192);
    private final DebrisTracker debris = new DebrisTracker(20_000);

    private volatile ScoringConfig scoring = ScoringConfig.defaults();
    private boolean wantIndicators;
    private boolean wantDebris;
    private int budgetPerTick = 2;
    private int radiusChunks = 0; // 0 = every chunk the client has loaded
    private int undergroundDepth = -1;
    private long tickCounter;
    private Confidence notifyAt = Confidence.HIGH;
    private Consumer<ChunkAssessment> onNewSuspicious = a -> { };
    private Consumer<List<DebrisRecord>> onNewDebris = l -> { };
    private Consumer<ChunkAssessment> onAmethystEvidence = a -> { };
    private long scanned;
    private RangePreset range = RangePreset.MAXIMUM;
    private int everyTicks = 1;
    private double cpuBudgetMs = 3.0;
    private int fpsFloor = 30;
    private boolean throttled;
    private boolean lowFps;
    // Performance figures for the dashboard (exponential moving averages).
    private volatile double analysisMs;
    private double snapshotMs;
    private final java.util.ArrayDeque<Long> recentScans = new java.util.ArrayDeque<>();

    public ScanService() {
        worker.setErrorHandler(t -> Atlas.LOG.error("Chunk analysis failed", t));
    }

    public DiscoveryStore discoveries() { return discoveries; }
    public DebrisTracker debris() { return debris; }
    public ChunkScanQueue queue() { return queue; }
    public long scannedCount() { return scanned; }
    public int queuedWork() { return worker.queued(); }

    public void setScoring(ScoringConfig cfg) { this.scoring = cfg; }
    public ScoringConfig scoring() { return scoring; }
    public void setBudget(int perTick) { this.budgetPerTick = Math.max(1, Math.min(16, perTick)); }
    public void setRadius(int chunks) { this.radiusChunks = chunks <= 0 ? 0 : Math.max(2, Math.min(64, chunks)); }

    /** Chunks the client has around it: the server's view distance or your render distance, whichever is smaller. */
    public int loadedRadius() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return Math.max(2, Math.min(64, mc.options.getClampedViewDistance() + 1));
    }

    /** The scan radius in chunks: the analyzer's fixed setting, or the Base Finder range preset (capped by what is loaded). */
    public int radius() {
        if (radiusChunks > 0) return radiusChunks;
        return range.chunks(loadedRadius());
    }

    public void setRange(RangePreset preset) { this.range = preset == null ? RangePreset.MAXIMUM : preset; }
    public RangePreset range() { return range; }
    public void setEveryTicks(int n) { this.everyTicks = Math.max(1, Math.min(40, n)); }
    public void setCpuBudgetMs(double ms) { this.cpuBudgetMs = Math.max(0.5, Math.min(20, ms)); }
    public void setFpsFloor(int fps) { this.fpsFloor = Math.max(0, fps); }
    public boolean throttled() { return throttled; }
    public void setLowFps(boolean low) { this.lowFps = low; }
    public double analysisMs() { return analysisMs; }
    public double snapshotMs() { return snapshotMs; }

    /** Chunks analysed in the last 10 seconds, per second. */
    public double chunksPerSecond() {
        long now = System.currentTimeMillis();
        while (!recentScans.isEmpty() && now - recentScans.peekFirst() > 10_000) recentScans.pollFirst();
        return recentScans.size() / 10.0;
    }

    /** Neighbouring chunks joined into sites, for the current dimension. */
    public List<SiteClusterer.Site> sites(boolean undergroundScan) {
        return discoveries.sites(Env.dimension(), scoring, undergroundScan, System.currentTimeMillis());
    }
    public void setRescanSeconds(int s) { queue.setRescanIntervalMillis(Math.max(5, s) * 1000L); }
    public void setNotifyAt(Confidence c) { this.notifyAt = c; }

    /**
     * Base indicators must be at least this many blocks below the surface (-1 = anywhere). Changing
     * it re-analyses every loaded chunk.
     */
    public void setUndergroundDepth(int depth) {
        if (depth == undergroundDepth) return;
        undergroundDepth = depth;
        worker.newGeneration();
        discoveries.clearSession();
        queue.clear();
        if (active()) enqueueLoaded(true);
    }

    public int undergroundDepth() { return undergroundDepth; }
    public void onNewSuspicious(Consumer<ChunkAssessment> c) { this.onNewSuspicious = c; }
    public void onNewDebris(Consumer<List<DebrisRecord>> c) { this.onNewDebris = c; }
    public void onAmethystEvidence(Consumer<ChunkAssessment> c) { this.onAmethystEvidence = c; }

    /** Turns analysis kinds on/off. Enabling re-queues loaded chunks so nothing is missed. */
    public void setWanted(boolean indicators, boolean debrisWanted) {
        boolean newlyOn = (indicators && !wantIndicators) || (debrisWanted && !wantDebris);
        this.wantIndicators = indicators;
        this.wantDebris = debrisWanted;
        if (newlyOn) {
            queue.clear();
            enqueueLoaded(true);
        }
    }

    public boolean active() { return wantIndicators || wantDebris; }

    public void onChunkLoad(WorldChunk chunk) {
        if (active()) {
            queue.enqueue(chunk.getPos().x, chunk.getPos().z, System.currentTimeMillis());
        }
    }

    public void onChunkUnload(WorldChunk chunk) {
        queue.remove(chunk.getPos().x, chunk.getPos().z);
    }

    /** Forces a rescan of chunks around a position (used after the builder/excavator changes blocks). */
    public void invalidateAround(int blockX, int blockZ, int radiusChunks) {
        long now = System.currentTimeMillis();
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                queue.invalidate((blockX >> 4) + dx, (blockZ >> 4) + dz, now);
            }
        }
    }

    public void tick(MinecraftClient mc) {
        if (!active() || mc.world == null || mc.player == null) {
            return;
        }
        tickCounter++;
        long now = System.currentTimeMillis();
        long start = System.nanoTime();
        // Slow down when the game is struggling: fewer chunks, every other tick.
        throttled = lowFps || (fpsFloor > 0 && mc.getCurrentFps() > 0 && mc.getCurrentFps() < fpsFloor);
        int every = throttled ? Math.max(2, everyTicks * 2) : everyTicks;
        boolean scanThisTick = tickCounter % every == 0;
        ClientWorld world = mc.world;
        int pcx = mc.player.getBlockPos().getX() >> 4;
        int pcz = mc.player.getBlockPos().getZ() >> 4;
        if (tickCounter % 100 == 0) {
            enqueueLoaded(false);
        }
        String dim = Env.dimension();
        boolean authoritative = Env.singleplayer();
        debris.setAuthoritative(authoritative);
        int budget = throttled ? 1 : budgetPerTick;
        for (int[] c : scanThisTick ? queue.poll(budget, pcx, pcz, radius(), now) : List.<int[]>of()) {
            if ((System.nanoTime() - start) / 1e6 > cpuBudgetMs) {
                queue.invalidate(c[0], c[1], now); // over this tick's time budget: next tick
                continue;
            }
            WorldChunk chunk = world.getChunkManager().getWorldChunk(c[0], c[1], false);
            if (chunk == null) {
                continue;
            }
            ChunkSnapshot snap = ChunkSnapshot.take(dim, chunk, wantIndicators, wantDebris, authoritative, now, undergroundDepth);
            ScoringConfig cfg = scoring;
            boolean scoreIt = wantIndicators;
            if (!worker.submit(() -> {
                long t0 = System.nanoTime();
                ChunkSnapshot.Extracted ex = snap.extract();
                ChunkAssessment a = scoreIt ? scorer.score(ex.observation(), cfg) : null;
                analysisMs = analysisMs * 0.9 + (System.nanoTime() - t0) / 1e6 * 0.1;
                return new Result(a, ex);
            })) {
                queue.invalidate(c[0], c[1], now); // worker busy: retry later
                break;
            }
        }
        Atlas.get().perf.record("scan/snapshot", System.nanoTime() - start);
        snapshotMs = snapshotMs * 0.95 + (System.nanoTime() - start) / 1e6 * 0.05;
        long applyStart = System.nanoTime();
        worker.drainResults(16, this::apply);
        Atlas.get().perf.record("scan/apply", System.nanoTime() - applyStart);
        Atlas.get().perf.gauge("scan/pending", queue.pendingCount());
        Atlas.get().perf.gauge("scan/worker-queue", worker.queued());
        Atlas.get().perf.gauge("scan/discoveries", discoveries.size());
    }

    private void apply(Result r) {
        scanned++;
        recentScans.addLast(System.currentTimeMillis());
        if (recentScans.size() > 4096) recentScans.pollFirst();
        ChunkSnapshot.Extracted ex = r.extracted();
        if (r.assessment() != null) {
            ChunkAssessment a = r.assessment();
            if (discoveries.update(a, notifyAt)) {
                onNewSuspicious.accept(a);
            }
            if (a.amethyst() != null && a.amethyst().contribution() > 0) {
                onAmethystEvidence.accept(a);
            }
        }
        if (ex.source().wantDebris) {
            DebrisRecord.Source src = ex.source().authoritative ? DebrisRecord.Source.INTEGRATED_SERVER : DebrisRecord.Source.CLIENT_CHUNK;
            DebrisTracker.NewFinds finds = debris.observeChunk(ex.source().dimension, ex.source().chunkX, ex.source().chunkZ,
                    ex.debris(), ex.naturalOres(), ex.source().nonEmptySections(), src, ex.source().takenAt);
            if (!finds.added().isEmpty()) {
                onNewDebris.accept(finds.added());
            }
        }
    }

    private void enqueueLoaded(boolean force) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) {
            return;
        }
        int pcx = mc.player.getBlockPos().getX() >> 4;
        int pcz = mc.player.getBlockPos().getZ() >> 4;
        List<long[]> loaded = new ArrayList<>();
        long now = System.currentTimeMillis();
        int radiusChunks = radius();
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                int cx = pcx + dx, cz = pcz + dz;
                if (mc.world.getChunkManager().isChunkLoaded(cx, cz)) {
                    if (force) {
                        queue.enqueue(cx, cz, now);
                    } else {
                        loaded.add(new long[]{cx, cz});
                    }
                }
            }
        }
        if (!force) {
            queue.requeueStale(loaded, now);
        }
    }

    @Override public String resetName() { return "scan-service"; }

    @Override
    public void reset(CoreEvents.SessionReset.Reason reason) {
        // Chunks of the previous world are gone: drop queued work and in-flight results.
        worker.newGeneration();
        queue.clear();
        scanned = 0;
        if (reason != CoreEvents.SessionReset.Reason.DIMENSION_CHANGE) {
            // Discoveries and debris are tagged with their dimension, so they survive a trip to the
            // Nether and back on the same server; anything else starts clean.
            discoveries.clearSession();
            debris.clear();
        }
    }

    public void shutdown() { worker.close(); }
}
