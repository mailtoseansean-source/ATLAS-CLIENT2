package dev.atlasclient.core.schematic.transform;

/**
 * Position transforms identical to Litematica's {@code PositionUtils.getTransformedBlockPos}: mirror
 * first, then rotate around the origin.
 */
public final class Transforms {
    private Transforms() { }

    public static int[] transform(int x, int y, int z, Mirror mirror, Rotation rotation) {
        switch (mirror) {
            case LEFT_RIGHT -> z = -z;
            case FRONT_BACK -> x = -x;
            default -> { }
        }
        return switch (rotation) {
            case CLOCKWISE_90 -> new int[]{-z, y, x};
            case COUNTERCLOCKWISE_90 -> new int[]{z, y, -x};
            case CLOCKWISE_180 -> new int[]{-x, y, -z};
            default -> new int[]{x, y, z};
        };
    }

    /** Inverse of {@link #transform}. */
    public static int[] reverse(int x, int y, int z, Mirror mirror, Rotation rotation) {
        int[] r = switch (rotation) {
            case CLOCKWISE_90 -> new int[]{z, y, -x};
            case COUNTERCLOCKWISE_90 -> new int[]{-z, y, x};
            case CLOCKWISE_180 -> new int[]{-x, y, -z};
            default -> new int[]{x, y, z};
        };
        switch (mirror) {
            case LEFT_RIGHT -> r[2] = -r[2];
            case FRONT_BACK -> r[0] = -r[0];
            default -> { }
        }
        return r;
    }

    /**
     * Block-state transform parameters for one region, using Litematica's rules: apply the main
     * mirror, then the sub-region mirror (axis-swapped when the main rotation is 90 degrees), then
     * the combined rotation.
     */
    public record StateTransform(Mirror mainMirror, Mirror subMirror, Rotation rotation) {
        public static final StateTransform IDENTITY = new StateTransform(Mirror.NONE, Mirror.NONE, Rotation.NONE);

        public static StateTransform of(Mirror mainMirror, Rotation mainRotation, Mirror subMirror, Rotation subRotation) {
            Mirror sub = subMirror;
            if (sub != Mirror.NONE && (mainRotation == Rotation.CLOCKWISE_90 || mainRotation == Rotation.COUNTERCLOCKWISE_90)) {
                sub = sub == Mirror.FRONT_BACK ? Mirror.LEFT_RIGHT : Mirror.FRONT_BACK;
            }
            return new StateTransform(mainMirror, sub, mainRotation.rotated(subRotation));
        }

        public boolean isIdentity() {
            return mainMirror == Mirror.NONE && subMirror == Mirror.NONE && rotation == Rotation.NONE;
        }
    }
}
