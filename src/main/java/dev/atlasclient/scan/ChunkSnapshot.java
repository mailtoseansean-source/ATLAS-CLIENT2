package dev.atlasclient.scan;

import dev.atlasclient.core.analysis.AirGrid;
import dev.atlasclient.core.analysis.BlockHit;
import dev.atlasclient.core.analysis.GeometryAnalyzer;
import dev.atlasclient.core.analysis.Indicator;
import dev.atlasclient.core.analysis.ChunkObservation;
import dev.atlasclient.core.debris.DebrisRecord;
import dev.atlasclient.core.debris.DebrisTracker;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Copy of the block data of one loaded chunk, restricted to sections that contain something
 * interesting (plus their vertical neighbours, needed for debris exposure).
 *
 * <p>{@link #take} runs on the client thread and only copies palette containers (cheap: a
 * section's packed long array). {@link #extract} runs on the background analysis thread and reads
 * only the copies, never live game state.</p>
 */
public final class ChunkSnapshot {
    public final String dimension;
    public final int chunkX;
    public final int chunkZ;
    public final long takenAt;
    public final boolean authoritative;
    public final boolean wantIndicators;
    public final boolean wantDebris;
    private final Map<Integer, PalettedContainer<BlockState>> sections;
    private final Set<Integer> targets;
    private final int nonEmptySections;
    /** Highest non-air block per column (x + z * 16), or null when not filtering by depth. */
    private final int[] surface;
    private final int undergroundDepth;
    /** Highest non-air block per column including water columns (for underground shapes), or null. */
    private final int[] terrain;
    private final int bottomY;
    private final Set<String> biomes;

    private ChunkSnapshot(String dimension, int chunkX, int chunkZ, long takenAt, boolean authoritative,
                          boolean wantIndicators, boolean wantDebris, Map<Integer, PalettedContainer<BlockState>> sections,
                          Set<Integer> targets, int nonEmptySections, int[] surface, int undergroundDepth, int[] terrain,
                          int bottomY, Set<String> biomes) {
        this.dimension = dimension;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.takenAt = takenAt;
        this.authoritative = authoritative;
        this.wantIndicators = wantIndicators;
        this.wantDebris = wantDebris;
        this.sections = sections;
        this.targets = targets;
        this.nonEmptySections = nonEmptySections;
        this.surface = surface;
        this.undergroundDepth = undergroundDepth;
        this.terrain = terrain;
        this.bottomY = bottomY;
        this.biomes = biomes;
    }

    /**
     * Client thread only.
     *
     * @param undergroundDepth when >= 0, base indicators only count if they are at least this many
     *                         blocks below the surface of their column (the highest non-air block the
     *                         server sent), so buried bases are found and surface builds are ignored.
     */
    public static ChunkSnapshot take(String dimension, WorldChunk chunk, boolean wantIndicators, boolean wantDebris,
                                     boolean authoritative, long now, int undergroundDepth) {
        ChunkSection[] array = chunk.getSectionArray();
        int bottom = chunk.getBottomSectionCoord();
        Set<Integer> targets = new HashSet<>();
        int nonEmpty = 0;
        for (int i = 0; i < array.length; i++) {
            ChunkSection s = array[i];
            if (s == null || s.isEmpty()) {
                continue;
            }
            nonEmpty++;
            if (s.hasAny(state -> BlockInfo.of(state).interesting(state, wantIndicators, wantDebris))) {
                targets.add(i);
            }
        }
        Map<Integer, PalettedContainer<BlockState>> copies = new HashMap<>();
        int[] terrain = null;
        Set<String> biomes = new HashSet<>();
        if (wantIndicators) {
            // Underground shapes need the air of every section below the surface, not only the
            // sections with indicator blocks.
            terrain = new int[256];
            int maxTop = Integer.MIN_VALUE;
            try {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int h = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x, z);
                        terrain[x + z * 16] = h;
                        maxTop = Math.max(maxTop, h);
                    }
                }
            } catch (RuntimeException e) {
                terrain = null;
            }
            for (int i = 0; i < array.length; i++) {
                ChunkSection s = array[i];
                int sy = bottom + i;
                if (s == null || s.isEmpty() || copies.containsKey(sy)) continue;
                if (terrain != null && (sy << 4) > maxTop) continue;
                copies.put(sy, s.getBlockStateContainer().copy());
            }
            try {
                int bx = chunk.getPos().getStartX(), bz = chunk.getPos().getStartZ();
                int top = terrain == null ? 64 : terrain[8 + 8 * 16];
                int[] ys = {top, top - 16, 0, -32, -54, 40};
                for (int[] c : new int[][]{{4, 4}, {12, 12}, {4, 12}, {12, 4}}) {
                    for (int y : ys) {
                        var entry = chunk.getBiomeForNoiseGen((bx + c[0]) >> 2, y >> 2, (bz + c[1]) >> 2);
                        if (entry == null) continue;
                        String biomeId = entry.getIdAsString();
                        if (biomeId != null) biomes.add(biomeId.startsWith("minecraft:") ? biomeId.substring(10) : biomeId);
                    }
                }
            } catch (RuntimeException e) {
                // no biome data for this chunk: scored without biome context
            }
        }
        for (int i : targets) {
            for (int j = i - 1; j <= i + 1; j++) {
                if (j < 0 || j >= array.length || copies.containsKey(bottom + j)) {
                    continue;
                }
                ChunkSection s = array[j];
                if (s != null && !s.isEmpty()) {
                    copies.put(bottom + j, s.getBlockStateContainer().copy());
                }
            }
        }
        Set<Integer> targetSectionYs = new HashSet<>();
        for (int i : targets) {
            targetSectionYs.add(bottom + i);
        }
        int[] surface = null;
        if (undergroundDepth >= 0 && wantIndicators) {
            try {
                surface = new int[256];
                BlockPos.Mutable top = new BlockPos.Mutable();
                int baseX = chunk.getPos().getStartX(), baseZ = chunk.getPos().getStartZ();
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int h = chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x, z);
                        // Under water or lava (oceans, lakes): shipwrecks and ruins, not buried bases.
                        boolean wet = !chunk.getBlockState(top.set(baseX + x, h, baseZ + z)).getFluidState().isEmpty();
                        surface[x + z * 16] = wet ? Integer.MIN_VALUE + 1024 : h;
                    }
                }
            } catch (RuntimeException e) {
                surface = null; // no surface data for this chunk: do not filter rather than guess
            }
        }
        return new ChunkSnapshot(dimension, chunk.getPos().x, chunk.getPos().z, now, authoritative,
                wantIndicators, wantDebris, copies, targetSectionYs, nonEmpty, surface, undergroundDepth, terrain,
                bottom << 4, biomes);
    }

    /** Result of background extraction. */
    public record Extracted(ChunkObservation observation, List<DebrisTracker.Found> debris, int naturalOres,
                            ChunkSnapshot source) { }

    /** Background thread. Reads only the copied containers. */
    public Extracted extract() {
        List<BlockHit> hits = new ArrayList<>();
        Map<String, Integer> signatures = new HashMap<>();
        List<DebrisTracker.Found> debris = new ArrayList<>();
        int ores = 0;
        int baseX = chunkX << 4, baseZ = chunkZ << 4;
        for (int sy : targets) {
            PalettedContainer<BlockState> c = sections.get(sy);
            if (c == null) {
                continue;
            }
            int baseY = sy << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = c.get(x, y, z);
                        BlockInfo info = BlockInfo.of(state);
                        if (wantIndicators) {
                            Indicator ind = info.indicator;
                            if (ind == null && info.placedLeaves(state)) ind = Indicator.PLACED_LEAVES;
                            // Growth (amethyst, vines, kelp, cocoa, copper aging) counts at any depth.
                            if (ind != null && (surface == null || ind.growth() || baseY + y <= surface[x + z * 16] - undergroundDepth)) {
                                hits.add(new BlockHit(ind, info.rawId, baseX + x, baseY + y, baseZ + z,
                                        info.stage(state), info.maxAge));
                            }
                            if (info.signature != null) signatures.merge(info.signature, 1, Integer::sum);
                        }
                        if (wantDebris) {
                            if (info.debris) {
                                debris.add(new DebrisTracker.Found(baseX + x, baseY + y, baseZ + z,
                                        exposed(sy, x, y, z) ? DebrisRecord.Exposure.EXPOSED : DebrisRecord.Exposure.ENCLOSED));
                            } else if (info.naturalNetherOre) {
                                ores++;
                            }
                        }
                    }
                }
            }
        }
        GeometryAnalyzer.Report geometry = wantIndicators ? geometry() : GeometryAnalyzer.Report.EMPTY;
        ChunkObservation obs = new ChunkObservation(dimension, chunkX, chunkZ, hits, nonEmptySections, takenAt, authoritative,
                signatures, biomes, geometry);
        return new Extracted(obs, debris, ores, this);
    }

    /** Underground air/water of the copied sections; missing sections inside the range are all air. */
    private GeometryAnalyzer.Report geometry() {
        if (terrain == null || sections.isEmpty()) return GeometryAnalyzer.Report.EMPTY;
        int maxTop = Integer.MIN_VALUE;
        for (int h : terrain) maxTop = Math.max(maxTop, h);
        int minSection = Integer.MAX_VALUE;
        for (int sy : sections.keySet()) minSection = Math.min(minSection, sy);
        int minY = Math.max(bottomY, minSection << 4);
        int height = maxTop - minY + 1;
        if (height <= 0) return GeometryAnalyzer.Report.EMPTY;
        AirGrid g = new AirGrid(chunkX, chunkZ, minY, height, terrain, undergroundDepth);
        for (int y = minY; y < minY + height; y++) {
            PalettedContainer<BlockState> c = sections.get(y >> 4);
            int ly = y & 15;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (c == null) {
                        g.setAir(x, y, z);
                        continue;
                    }
                    BlockState st = c.get(x, ly, z);
                    if (BlockInfo.of(st).air) g.setAir(x, y, z);
                    else if (!st.getFluidState().isEmpty() && st.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.WATER)
                            && st.getFluidState().isStill()) g.setWater(x, y, z);
                }
            }
        }
        return new GeometryAnalyzer().analyze(g);
    }

    public int nonEmptySections() { return nonEmptySections; }

    private BlockState stateAt(int sectionY, int x, int y, int z) {
        int sy = sectionY;
        if (y < 0) { sy--; y += 16; }
        if (y > 15) { sy++; y -= 16; }
        PalettedContainer<BlockState> c = sections.get(sy);
        return c == null ? null : c.get(x, y, z);
    }

    /**
     * A debris block counts as exposed when a neighbour inside this chunk does not fully occlude it.
     * Neighbours in other chunks are unknown here and treated as occluding (conservative).
     */
    private boolean exposed(int sectionY, int x, int y, int z) {
        int[][] d = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] o : d) {
            int nx = x + o[0], ny = y + o[1], nz = z + o[2];
            if (nx < 0 || nx > 15 || nz < 0 || nz > 15) {
                continue;
            }
            BlockState n = stateAt(sectionY, nx, ny, nz);
            if (n == null) {
                // Section absent: empty (all air) if the chunk simply has no blocks there.
                return true;
            }
            if (!n.isOpaqueFullCube()) {
                return true;
            }
        }
        return false;
    }
}
