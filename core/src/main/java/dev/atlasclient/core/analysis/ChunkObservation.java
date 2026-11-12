package dev.atlasclient.core.analysis;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything the scanner saw in one chunk at one moment. Immutable and safe to hand to background
 * threads.
 *
 * @param dimension     dimension id, e.g. {@code minecraft:the_nether}
 * @param authoritative true when the data comes from the integrated server or a local save the
 *                      user owns (no anti-xray masking possible)
 * @param signatures    counts of {@link StructureSignatures#VOCABULARY} blocks (any depth)
 * @param biomes        biome ids (path only) sampled in the chunk
 * @param geometry      shapes found in the chunk's underground air
 */
public record ChunkObservation(String dimension, int chunkX, int chunkZ, List<BlockHit> hits,
                               int sectionsScanned, long observedAtMillis, boolean authoritative,
                               Map<String, Integer> signatures, Set<String> biomes, GeometryAnalyzer.Report geometry) {
    public ChunkObservation {
        hits = List.copyOf(hits);
        signatures = signatures == null ? Map.of() : Map.copyOf(signatures);
        biomes = biomes == null ? Set.of() : Set.copyOf(biomes);
        geometry = geometry == null ? GeometryAnalyzer.Report.EMPTY : geometry;
    }

    /** Block hits only (no structure counts, biomes or geometry). */
    public ChunkObservation(String dimension, int chunkX, int chunkZ, List<BlockHit> hits,
                            int sectionsScanned, long observedAtMillis, boolean authoritative) {
        this(dimension, chunkX, chunkZ, hits, sectionsScanned, observedAtMillis, authoritative, Map.of(), Set.of(), null);
    }

    public long chunkKey() { return chunkKey(chunkX, chunkZ); }

    /** Same packing as Minecraft's ChunkPos.toLong. */
    public static long chunkKey(int cx, int cz) {
        return (long) cx & 0xFFFFFFFFL | ((long) cz & 0xFFFFFFFFL) << 32;
    }

    public static int keyX(long key) { return (int) key; }
    public static int keyZ(long key) { return (int) (key >>> 32); }
}
