package dev.atlasclient.scan;

import dev.atlasclient.Atlas;
import dev.atlasclient.core.event.CoreEvents;
import dev.atlasclient.core.util.BackgroundWorker;
import dev.atlasclient.core.util.SessionLifecycle;
import dev.atlasclient.core.xray.XrayIndex;
import dev.atlasclient.util.Env;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds X-Ray target blocks in chunks the client has loaded. On the client thread it only copies
 * the palette containers of sections that contain a target (plus their vertical neighbours for the
 * "exposed" test); the block-by-block search runs on one background thread. Chunks are rescanned on
 * a rolling basis so blocks that change are picked up, and the renderer drops stale entries.
 */
public final class XrayScanner implements SessionLifecycle.Resettable {
    private record Job(String dim, int cx, int cz, Set<Block> targets, Map<Integer, PalettedContainer<BlockState>> sections,
                       Set<Integer> targetSections, long generation) { }

    private record Result(String dim, int cx, int cz, List<XrayIndex.Hit> hits, long generation) { }

    private static final Map<Block, String> IDS = new ConcurrentHashMap<>();

    private final XrayIndex index = new XrayIndex();
    private final BackgroundWorker<Result> worker = new BackgroundWorker<>("Atlas-XRay", 512);
    /** Loaded chunk -> last scan time (0 = never). */
    private final Map<Long, Long> loaded = new LinkedHashMap<>();
    private Set<Block> targets = Set.of();
    private long generation;
    private int scannedTotal;

    public XrayIndex index() { return index; }
    public int scannedChunks() { return scannedTotal; }

    static String id(Block b) { return IDS.computeIfAbsent(b, k -> Registries.BLOCK.getId(k).toString()); }

    /** Changing the target set invalidates everything found so far. */
    public void setTargets(Set<Block> next) {
        if (!next.equals(targets)) {
            targets = Set.copyOf(next);
            invalidateAll();
        }
    }

    public Set<Block> targets() { return targets; }

    public void invalidateAll() {
        generation++;
        worker.newGeneration();
        index.clear();
        loaded.replaceAll((k, v) -> 0L);
    }

    /** {chunk key, time (ms)} at which to re-read a chunk (after you break a block, anti-xray servers send the real blocks). */
    private final List<long[]> rescans = new ArrayList<>();

    /** Re-read the chunk of this block 1 and 3 seconds from now. */
    public void onBlockBroken(int x, int z) {
        long k = XrayIndex.chunkKey(x >> 4, z >> 4);
        long now = System.currentTimeMillis();
        rescans.add(new long[]{k, now + 1000});
        rescans.add(new long[]{k, now + 3000});
        while (rescans.size() > 256) rescans.remove(0);
    }

    public void onChunkLoad(WorldChunk chunk) {
        loaded.put(XrayIndex.chunkKey(chunk.getPos().x, chunk.getPos().z), 0L);
    }

    public void onChunkUnload(WorldChunk chunk) {
        loaded.remove(XrayIndex.chunkKey(chunk.getPos().x, chunk.getPos().z));
        index.remove(chunk.getPos().x, chunk.getPos().z);
    }

    /** Client thread. */
    public void tick(MinecraftClient mc, int radiusChunks, int chunksPerTick, int rescanSeconds) {
        worker.drainResults(64, r -> {
            if (r.generation() == generation && loaded.containsKey(XrayIndex.chunkKey(r.cx(), r.cz()))) {
                index.put(r.dim(), r.cx(), r.cz(), r.hits());
                scannedTotal++;
            }
        });
        ClientWorld world = mc.world;
        if (world == null || mc.player == null || targets.isEmpty()) return;
        int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
        long now = System.currentTimeMillis();
        long stale = rescanSeconds * 1000L;
        // Nearest due chunks first.
        List<long[]> due = new ArrayList<>();
        for (var it = rescans.iterator(); it.hasNext(); ) {
            long[] r = it.next();
            if (now < r[1]) continue;
            if (loaded.containsKey(r[0])) loaded.put(r[0], 0L);
            it.remove();
        }
        for (Map.Entry<Long, Long> e : loaded.entrySet()) {
            long k = e.getKey();
            int cx = (int) (k >> 32), cz = (int) k;
            int d = Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz));
            if (d > radiusChunks) continue;
            if (e.getValue() == 0L || now - e.getValue() >= stale) due.add(new long[]{k, d});
        }
        if (due.isEmpty()) return;
        due.sort((a, b) -> Long.compare(a[1], b[1]));
        String dim = Env.dimension();
        int submitted = 0;
        for (long[] d : due) {
            if (submitted >= chunksPerTick || worker.queued() > 256) break;
            int cx = (int) (d[0] >> 32), cz = (int) d[0];
            if (!world.getChunkManager().isChunkLoaded(cx, cz)) {
                loaded.remove(d[0]);
                continue;
            }
            Chunk c = world.getChunk(cx, cz);
            if (!(c instanceof WorldChunk chunk)) continue;
            Job job = snapshot(dim, chunk);
            loaded.put(d[0], now);
            submitted++;
            if (job == null) {
                index.put(dim, cx, cz, List.of()); // nothing of interest in this chunk
                continue;
            }
            worker.submit(() -> extract(job));
        }
    }

    private Job snapshot(String dim, WorldChunk chunk) {
        Set<Block> t = targets;
        ChunkSection[] array = chunk.getSectionArray();
        int bottom = chunk.getBottomSectionCoord();
        Set<Integer> targetSections = new java.util.HashSet<>();
        for (int i = 0; i < array.length; i++) {
            ChunkSection s = array[i];
            if (s != null && !s.isEmpty() && s.hasAny(state -> t.contains(state.getBlock()))) {
                targetSections.add(i);
            }
        }
        if (targetSections.isEmpty()) return null;
        Map<Integer, PalettedContainer<BlockState>> copies = new HashMap<>();
        for (int i : targetSections) {
            for (int j = i - 1; j <= i + 1; j++) {
                if (j < 0 || j >= array.length || copies.containsKey(bottom + j)) continue;
                ChunkSection s = array[j];
                if (s != null && !s.isEmpty()) copies.put(bottom + j, s.getBlockStateContainer().copy());
            }
        }
        Set<Integer> ys = new java.util.HashSet<>();
        for (int i : targetSections) ys.add(bottom + i);
        return new Job(dim, chunk.getPos().x, chunk.getPos().z, t, copies, ys, generation);
    }

    /** Background thread: reads only the copied containers. */
    private static Result extract(Job job) {
        List<XrayIndex.Hit> hits = new ArrayList<>();
        int bx = job.cx() << 4, bz = job.cz() << 4;
        for (int sy : job.targetSections()) {
            PalettedContainer<BlockState> c = job.sections().get(sy);
            if (c == null) continue;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        Block b = c.get(x, y, z).getBlock();
                        if (job.targets().contains(b)) {
                            hits.add(new XrayIndex.Hit(bx + x, (sy << 4) + y, bz + z, id(b), exposed(job, sy, x, y, z)));
                            if (hits.size() >= XrayIndex.MAX_PER_CHUNK) {
                                return new Result(job.dim(), job.cx(), job.cz(), hits, job.generation());
                            }
                        }
                    }
                }
            }
        }
        return new Result(job.dim(), job.cx(), job.cz(), hits, job.generation());
    }

    /** True when a neighbour inside this chunk is not a full opaque block (unknown neighbours count as solid). */
    private static boolean exposed(Job job, int sy, int x, int y, int z) {
        int[][] d = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] o : d) {
            int nx = x + o[0], ny = y + o[1], nz = z + o[2];
            if (nx < 0 || nx > 15 || nz < 0 || nz > 15) continue;
            int s = sy;
            if (ny < 0) { s--; ny += 16; }
            if (ny > 15) { s++; ny -= 16; }
            PalettedContainer<BlockState> c = job.sections().get(s);
            if (c == null) return true; // empty section: air
            if (!c.get(nx, ny, nz).isOpaqueFullCube()) return true;
        }
        return false;
    }

    @Override public String resetName() { return "xray-scanner"; }

    @Override
    public void reset(CoreEvents.SessionReset.Reason reason) {
        generation++;
        worker.newGeneration();
        index.clear();
        loaded.clear();
        scannedTotal = 0;
    }

    /** Frees memory when X-Ray is switched off. */
    public void release() {
        generation++;
        worker.newGeneration();
        index.clear();
        loaded.replaceAll((k, v) -> 0L);
    }

    public void shutdown() {
        try {
            worker.close();
        } catch (Exception e) {
            Atlas.LOG.debug("X-Ray worker close", e);
        }
    }
}
