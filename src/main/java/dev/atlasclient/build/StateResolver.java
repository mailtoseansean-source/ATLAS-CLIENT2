package dev.atlasclient.build;

import dev.atlasclient.core.schematic.BlockStateString;
import dev.atlasclient.core.schematic.transform.Transforms;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Converts schematic block states to game block states and applies placement transforms with
 * vanilla's own {@code BlockState.mirror/rotate} (so every block, including stairs shapes, rails,
 * doors and wall connections, transforms exactly like Litematica does it).
 */
public final class StateResolver {
    /** Properties derived from neighbours or world state; not compared when verifying. */
    public static final Set<String> IGNORED = Set.of("shape", "north", "south", "east", "west", "up", "down",
            "waterlogged", "powered", "power", "distance", "persistent", "snowy", "occupied", "triggered", "enabled",
            "attached", "disarmed", "in_wall", "age", "stage", "moisture", "locked", "has_book", "honey_level",
            "bottom", "lit", "signal_fire", "has_record", "unstable", "drag", "extended", "short", "conditional",
            "instrument", "has_bottle_0", "has_bottle_1", "has_bottle_2", "berries", "hatch", "eye", "level",
            "charges", "bites", "candles_lit", "cracked", "tip", "thickness", "vertical_direction", "leaves");
    /** Properties fixable by right-clicking the placed block. */
    public static final Set<String> ADJUSTABLE = Set.of("delay", "mode", "note", "open", "inverted");

    private static final Map<String, String> RENAMES = Map.of("minecraft:grass", "minecraft:short_grass");
    private final Map<String, BlockState> cache = new HashMap<>();
    private final Map<String, Integer> unknown = new HashMap<>();

    public BlockState resolve(BlockStateString s, Transforms.StateTransform t) {
        String key = s + "|" + (t == null ? "" : t.mainMirror() + "," + t.subMirror() + "," + t.rotation());
        BlockState cached = cache.get(key);
        if (cached != null || cache.containsKey(key)) {
            return cached;
        }
        BlockState state = parse(s);
        if (state != null && t != null && !t.isIdentity()) {
            if (t.mainMirror() != dev.atlasclient.core.schematic.transform.Mirror.NONE) {
                state = state.mirror(toMc(t.mainMirror()));
            }
            if (t.subMirror() != dev.atlasclient.core.schematic.transform.Mirror.NONE) {
                state = state.mirror(toMc(t.subMirror()));
            }
            if (t.rotation() != dev.atlasclient.core.schematic.transform.Rotation.NONE) {
                state = state.rotate(toMc(t.rotation()));
            }
        }
        cache.put(key, state);
        return state;
    }

    /*
     * Explicit constant mapping instead of Enum.valueOf(name()): constant references are remapped by
     * Loom, while enum name strings are not something to rely on in an obfuscated production game.
     */
    public static BlockMirror toMc(dev.atlasclient.core.schematic.transform.Mirror m) {
        return switch (m) {
            case NONE -> BlockMirror.NONE;
            case LEFT_RIGHT -> BlockMirror.LEFT_RIGHT;
            case FRONT_BACK -> BlockMirror.FRONT_BACK;
        };
    }

    public static BlockRotation toMc(dev.atlasclient.core.schematic.transform.Rotation r) {
        return switch (r) {
            case NONE -> BlockRotation.NONE;
            case CLOCKWISE_90 -> BlockRotation.CLOCKWISE_90;
            case CLOCKWISE_180 -> BlockRotation.CLOCKWISE_180;
            case COUNTERCLOCKWISE_90 -> BlockRotation.COUNTERCLOCKWISE_90;
        };
    }

    public static dev.atlasclient.core.schematic.transform.Mirror fromMc(BlockMirror m) {
        if (m == BlockMirror.LEFT_RIGHT) return dev.atlasclient.core.schematic.transform.Mirror.LEFT_RIGHT;
        if (m == BlockMirror.FRONT_BACK) return dev.atlasclient.core.schematic.transform.Mirror.FRONT_BACK;
        return dev.atlasclient.core.schematic.transform.Mirror.NONE;
    }

    public static dev.atlasclient.core.schematic.transform.Rotation fromMc(BlockRotation r) {
        if (r == BlockRotation.CLOCKWISE_90) return dev.atlasclient.core.schematic.transform.Rotation.CLOCKWISE_90;
        if (r == BlockRotation.CLOCKWISE_180) return dev.atlasclient.core.schematic.transform.Rotation.CLOCKWISE_180;
        if (r == BlockRotation.COUNTERCLOCKWISE_90) return dev.atlasclient.core.schematic.transform.Rotation.COUNTERCLOCKWISE_90;
        return dev.atlasclient.core.schematic.transform.Rotation.NONE;
    }

    private BlockState parse(BlockStateString s) {
        String id = RENAMES.getOrDefault(s.id(), s.id());
        Identifier ident = Identifier.tryParse(id);
        Optional<Block> block = ident == null ? Optional.empty() : Registries.BLOCK.getOptionalValue(ident);
        if (block.isEmpty()) {
            unknown.merge(id, 1, Integer::sum);
            return null;
        }
        BlockState state = block.get().getDefaultState();
        for (Map.Entry<String, String> p : s.properties().entrySet()) {
            Property<?> prop = block.get().getStateManager().getProperty(p.getKey());
            if (prop != null) {
                state = with(state, prop, p.getValue());
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> prop, String value) {
        Optional<T> v = prop.parse(value);
        return v.isPresent() ? state.with(prop, v.get()) : state;
    }

    public Map<String, Integer> unknownBlocks() { return unknown; }

    public void clear() {
        cache.clear();
        unknown.clear();
    }

    /** Result of comparing what is in the world with what the schematic wants. */
    public enum Match { SAME, ADJUSTABLE_DIFFERENCE, DIFFERENT_STATE, DIFFERENT_BLOCK }

    public static Match compare(BlockState actual, BlockState wanted) {
        if (actual.getBlock() != wanted.getBlock()) {
            return Match.DIFFERENT_BLOCK;
        }
        boolean adjustable = false;
        for (Property<?> p : wanted.getProperties()) {
            String name = p.getName();
            if (IGNORED.contains(name)) {
                continue;
            }
            if (!actual.get(p).equals(wanted.get(p))) {
                if (ADJUSTABLE.contains(name)) {
                    adjustable = true;
                } else {
                    return Match.DIFFERENT_STATE;
                }
            }
        }
        return adjustable ? Match.ADJUSTABLE_DIFFERENCE : Match.SAME;
    }

    /**
     * True when the world holds one half of a slab that the schematic wants as a double slab:
     * the builder then places the same slab item a second time.
     */
    public static boolean isMissingSecondSlab(BlockState actual, BlockState wanted) {
        if (actual.getBlock() != wanted.getBlock()) return false;
        for (Property<?> p : wanted.getProperties()) {
            if (p.getName().equals("type")) {
                return valueName(wanted, p).equals("double") && !valueName(actual, p).equals("double");
            }
        }
        return false;
    }

    private static <T extends Comparable<T>> String valueName(BlockState s, Property<T> p) {
        return p.name(s.get(p));
    }

    /** Placement-relevant equality used when predicting placement results (adjustables ignored too). */
    public static boolean placementEquivalent(BlockState predicted, BlockState wanted) {
        Match m = compare(predicted, wanted);
        return m == Match.SAME || m == Match.ADJUSTABLE_DIFFERENCE;
    }
}
