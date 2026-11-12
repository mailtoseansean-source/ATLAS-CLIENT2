package dev.atlasclient.core.schematic.transform;

/**
 * Same constant names as Minecraft's BlockMirror. LEFT_RIGHT flips the Z axis (north/south),
 * FRONT_BACK flips the X axis (east/west), matching Litematica's PositionUtils.
 */
public enum Mirror {
    NONE, LEFT_RIGHT, FRONT_BACK;

    public Mirror next() { return values()[(ordinal() + 1) % values().length]; }

    public static Mirror parse(String s) {
        String t = s.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (t) {
            case "X" -> FRONT_BACK;
            case "Z" -> LEFT_RIGHT;
            default -> valueOf(t);
        };
    }
}
