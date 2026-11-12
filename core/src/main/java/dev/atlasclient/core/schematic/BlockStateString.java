package dev.atlasclient.core.schematic;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Game-independent block state: namespaced id plus string properties, e.g.
 * {@code minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]}.
 * Properties are kept sorted so equal states have equal strings.
 */
public final class BlockStateString {
    public static final BlockStateString AIR = new BlockStateString("minecraft:air", Map.of());

    private final String id;
    private final Map<String, String> properties;
    private final String canonical;

    public BlockStateString(String id, Map<String, String> properties) {
        this.id = normalizeId(Objects.requireNonNull(id));
        TreeMap<String, String> sorted = new TreeMap<>(properties);
        this.properties = Collections.unmodifiableMap(sorted);
        StringBuilder sb = new StringBuilder(this.id);
        if (!sorted.isEmpty()) {
            sb.append('[');
            boolean first = true;
            for (Map.Entry<String, String> e : sorted.entrySet()) {
                if (!first) sb.append(',');
                sb.append(e.getKey()).append('=').append(e.getValue());
                first = false;
            }
            sb.append(']');
        }
        this.canonical = sb.toString();
    }

    public static BlockStateString of(String id) { return new BlockStateString(id, Map.of()); }

    /** Parses {@code id[key=value,...]}. Throws IllegalArgumentException on malformed input. */
    public static BlockStateString parse(String text) {
        String s = text.trim();
        int bracket = s.indexOf('[');
        if (bracket < 0) {
            if (s.isEmpty()) throw new IllegalArgumentException("empty block state");
            return of(s);
        }
        if (!s.endsWith("]")) {
            throw new IllegalArgumentException("unterminated properties: " + text);
        }
        String id = s.substring(0, bracket);
        String body = s.substring(bracket + 1, s.length() - 1).trim();
        Map<String, String> props = new TreeMap<>();
        if (!body.isEmpty()) {
            for (String part : body.split(",")) {
                int eq = part.indexOf('=');
                if (eq <= 0 || eq == part.length() - 1) {
                    throw new IllegalArgumentException("bad property '" + part + "' in " + text);
                }
                props.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
            }
        }
        return new BlockStateString(id, props);
    }

    public static String normalizeId(String id) {
        return id.indexOf(':') >= 0 ? id : "minecraft:" + id;
    }

    public String id() { return id; }
    public Map<String, String> properties() { return properties; }
    public String property(String key) { return properties.get(key); }

    public boolean isAir() {
        return id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air");
    }

    /** Litematica uses structure_void to mark "do not touch" cells. */
    public boolean isStructureVoid() { return id.equals("minecraft:structure_void"); }

    public BlockStateString with(String key, String value) {
        TreeMap<String, String> p = new TreeMap<>(properties);
        p.put(key, value);
        return new BlockStateString(id, p);
    }

    @Override public String toString() { return canonical; }

    @Override public boolean equals(Object o) {
        return o instanceof BlockStateString b && b.canonical.equals(canonical);
    }

    @Override public int hashCode() { return canonical.hashCode(); }
}
