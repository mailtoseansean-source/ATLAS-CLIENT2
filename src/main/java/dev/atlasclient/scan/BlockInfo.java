package dev.atlasclient.scan;

import dev.atlasclient.core.analysis.Indicator;
import dev.atlasclient.core.analysis.IndicatorRegistry;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import dev.atlasclient.core.analysis.StructureSignatures;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Property;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-Block facts used by scanning, computed once and cached. Thread-safe: blocks, registries and
 * block states are immutable after game start, so background threads may read them.
 */
public final class BlockInfo {
    public static final String ANCIENT_DEBRIS = "minecraft:ancient_debris";

    private static final Map<Block, BlockInfo> CACHE = new ConcurrentHashMap<>();
    private static volatile IndicatorRegistry registry = IndicatorRegistry.vanillaDefaults();

    public final String id;
    public final int rawId;
    public final Indicator indicator;      // null if not an indicator
    public final boolean debris;
    public final boolean naturalNetherOre;  // nether quartz / nether gold ore
    public final IntProperty ageProperty;  // "age" (or bamboo "stage"), may be null
    public final int maxAge;
    /** Block id path when it is in the structure-recognition vocabulary, else null. */
    public final String signature;
    /** Leaves: their "persistent" property (true only when a player placed them), else null. */
    public final BooleanProperty persistent;
    public final boolean air;

    private BlockInfo(Block block) {
        this.id = Registries.BLOCK.getId(block).toString();
        this.rawId = Registries.BLOCK.getRawId(block);
        this.indicator = registry.lookup(id);
        this.debris = id.equals(ANCIENT_DEBRIS);
        this.naturalNetherOre = id.equals("minecraft:nether_quartz_ore") || id.equals("minecraft:nether_gold_ore");
        IntProperty age = null;
        int max = -1;
        String wanted = id.equals("minecraft:bamboo") ? "stage" : "age";
        for (Property<?> p : block.getStateManager().getProperties()) {
            if (p instanceof IntProperty ip && ip.getName().equals(wanted)) {
                age = ip;
                List<Integer> values = ip.getValues();
                for (int v : values) {
                    max = Math.max(max, v);
                }
            }
        }
        this.ageProperty = indicator != null ? age : null;
        this.maxAge = indicator != null ? max : -1;
        String path = id.startsWith("minecraft:") ? id.substring(10) : null;
        this.signature = path != null && StructureSignatures.inVocabulary(path) ? path : null;
        BooleanProperty pers = null;
        if (indicator == null && id.endsWith("_leaves")) {
            for (Property<?> p : block.getStateManager().getProperties()) {
                if (p instanceof BooleanProperty bp && bp.getName().equals("persistent")) pers = bp;
            }
        }
        this.persistent = pers;
        this.air = block.getDefaultState().isAir();
    }

    public static BlockInfo of(Block block) {
        return CACHE.computeIfAbsent(block, BlockInfo::new);
    }

    public static BlockInfo of(BlockState state) { return of(state.getBlock()); }

    /** Replaces the indicator mapping (e.g. user-defined custom indicators) and clears the cache. */
    public static void setRegistry(IndicatorRegistry r) {
        registry = r;
        CACHE.clear();
    }

    public static IndicatorRegistry registry() { return registry; }

    public int stage(BlockState state) {
        return ageProperty == null ? -1 : state.get(ageProperty);
    }

    public boolean interesting(boolean indicators, boolean debrisWanted) {
        return (indicators && (indicator != null || signature != null)) || (debrisWanted && (debris || naturalNetherOre));
    }

    /** Per-state check (placed leaves are interesting, natural ones are not). */
    public boolean interesting(BlockState state, boolean indicators, boolean debrisWanted) {
        if (interesting(indicators, debrisWanted)) return true;
        return indicators && placedLeaves(state);
    }

    public boolean placedLeaves(BlockState state) {
        return persistent != null && state.get(persistent);
    }
}
