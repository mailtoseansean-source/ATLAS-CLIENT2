package dev.atlasclient.core.region;

import dev.atlasclient.core.analysis.BlockHit;
import dev.atlasclient.core.analysis.ChunkObservation;
import dev.atlasclient.core.analysis.Indicator;
import dev.atlasclient.core.analysis.IndicatorRegistry;
import dev.atlasclient.core.debris.DebrisRecord;
import dev.atlasclient.core.debris.DebrisTracker;
import dev.atlasclient.core.nbt.Nbt;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Converts region-file chunks of an owned world into the same observations the live scanner
 * produces: ancient debris finds (with exposure) and base-finder indicator hits.
 */
public final class LocalWorldAnalyzer {
    public static final String DEBRIS = "minecraft:ancient_debris";
    private static final Set<String> NATURAL_NETHER_ORES = Set.of("minecraft:nether_quartz_ore", "minecraft:nether_gold_ore");
    /** Blocks that do not occlude a neighbour. Conservative: anything else counts as enclosing. */
    private static final Set<String> NON_OCCLUDING = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air",
            "minecraft:lava", "minecraft:water", "minecraft:fire", "minecraft:soul_fire");

    /** Known maximum "age" values, since save files do not record property ranges. */
    private static final Map<String, Integer> MAX_AGE = Map.ofEntries(
            Map.entry("minecraft:kelp", 25), Map.entry("minecraft:cocoa", 2), Map.entry("minecraft:wheat", 7),
            Map.entry("minecraft:carrots", 7), Map.entry("minecraft:potatoes", 7), Map.entry("minecraft:beetroots", 3),
            Map.entry("minecraft:nether_wart", 3), Map.entry("minecraft:melon_stem", 7),
            Map.entry("minecraft:pumpkin_stem", 7), Map.entry("minecraft:torchflower_crop", 1),
            Map.entry("minecraft:pitcher_crop", 4), Map.entry("minecraft:bamboo", 1));

    public record DebrisChunk(int chunkX, int chunkZ, List<DebrisTracker.Found> found, int naturalOres, int sections) { }

    private final IndicatorRegistry registry;

    public LocalWorldAnalyzer(IndicatorRegistry registry) {
        this.registry = registry;
    }

    public RegionScanner.Stats scanDebris(Path regionDir, int cx, int cz, int radius, Consumer<DebrisChunk> sink,
                                          BooleanSupplier cancelled) {
        RegionScanner scanner = new RegionScanner();
        return scanner.scan(regionDir, cx, cz, radius,
                n -> n.equals(DEBRIS) || NATURAL_NETHER_ORES.contains(n),
                chunk -> sink.accept(debrisIn(chunk)), cancelled);
    }

    static DebrisChunk debrisIn(RegionScanner.DecodedChunk chunk) {
        List<DebrisTracker.Found> found = new ArrayList<>();
        int ores = 0;
        int baseX = chunk.chunkX() << 4, baseZ = chunk.chunkZ() << 4;
        for (RegionScanner.Section s : chunk.sections()) {
            boolean hasDebris = s.palette().contains(DEBRIS);
            boolean hasOre = s.paletteContains(NATURAL_NETHER_ORES::contains);
            if (!hasDebris && !hasOre) {
                continue;
            }
            int baseY = s.sectionY() << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        String n = s.nameAt(x, y, z);
                        if (DEBRIS.equals(n)) {
                            boolean exposed = exposed(chunk, x, baseY + y, z);
                            found.add(new DebrisTracker.Found(baseX + x, baseY + y, baseZ + z,
                                    exposed ? DebrisRecord.Exposure.EXPOSED : DebrisRecord.Exposure.ENCLOSED));
                        } else if (NATURAL_NETHER_ORES.contains(n)) {
                            ores++;
                        }
                    }
                }
            }
        }
        return new DebrisChunk(chunk.chunkX(), chunk.chunkZ(), found, ores, chunk.sectionCount());
    }

    static boolean exposed(RegionScanner.DecodedChunk chunk, int x, int y, int z) {
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : dirs) {
            String n = chunk.nameAt(x + d[0], y + d[1], z + d[2]);
            if (n != null && NON_OCCLUDING.contains(n)) {
                return true;
            }
        }
        return false;
    }

    /** Produces base-finder observations for chunks of an owned save. */
    public RegionScanner.Stats scanIndicators(Path regionDir, String dimension, int cx, int cz, int radius,
                                              Consumer<ChunkObservation> sink, BooleanSupplier cancelled, long now) {
        Map<String, Indicator> ids = registry.asMap();
        RegionScanner scanner = new RegionScanner();
        return scanner.scan(regionDir, cx, cz, radius, ids::containsKey, chunk -> {
            List<BlockHit> hits = new ArrayList<>();
            int baseX = chunk.chunkX() << 4, baseZ = chunk.chunkZ() << 4;
            int scanned = 0;
            for (RegionScanner.Section s : chunk.sections()) {
                if (!s.paletteContains(ids::containsKey)) {
                    continue;
                }
                scanned++;
                int baseY = s.sectionY() << 4;
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            String n = s.nameAt(x, y, z);
                            Indicator ind = ids.get(n);
                            if (ind == null) {
                                continue;
                            }
                            int stage = -1, max = -1;
                            Integer m = MAX_AGE.get(n);
                            if (m != null) {
                                Nbt.Compound props = s.propertiesAt(x, y, z);
                                String age = props.getString(n.equals("minecraft:bamboo") ? "stage" : "age", null);
                                if (age != null) {
                                    try {
                                        stage = Integer.parseInt(age);
                                        max = m;
                                    } catch (NumberFormatException ignored) {
                                        stage = -1;
                                    }
                                }
                            }
                            hits.add(new BlockHit(ind, n.hashCode(), baseX + x, baseY + y, baseZ + z, stage, max));
                        }
                    }
                }
            }
            sink.accept(new ChunkObservation(dimension, chunk.chunkX(), chunk.chunkZ(), hits,
                    Math.max(scanned, chunk.sectionCount()), now, true));
        }, cancelled);
    }
}
