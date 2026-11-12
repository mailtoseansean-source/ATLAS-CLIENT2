package dev.atlasclient.core.schematic.transform;

/** Same constant names as Minecraft's BlockRotation and Litematica's placement JSON. */
public enum Rotation {
    NONE, CLOCKWISE_90, CLOCKWISE_180, COUNTERCLOCKWISE_90;

    public int quarterTurns() {
        return switch (this) {
            case NONE -> 0;
            case CLOCKWISE_90 -> 1;
            case CLOCKWISE_180 -> 2;
            case COUNTERCLOCKWISE_90 -> 3;
        };
    }

    public static Rotation ofQuarterTurns(int turns) {
        return switch (Math.floorMod(turns, 4)) {
            case 1 -> CLOCKWISE_90;
            case 2 -> CLOCKWISE_180;
            case 3 -> COUNTERCLOCKWISE_90;
            default -> NONE;
        };
    }

    /** Equivalent of Minecraft's BlockRotation.rotate(other). */
    public Rotation rotated(Rotation other) { return ofQuarterTurns(quarterTurns() + other.quarterTurns()); }

    public Rotation next() { return rotated(CLOCKWISE_90); }

    public static Rotation parse(String s) {
        try {
            return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return switch (s.trim()) {
                case "0" -> NONE;
                case "90" -> CLOCKWISE_90;
                case "180" -> CLOCKWISE_180;
                case "270", "-90" -> COUNTERCLOCKWISE_90;
                default -> throw e;
            };
        }
    }
}
