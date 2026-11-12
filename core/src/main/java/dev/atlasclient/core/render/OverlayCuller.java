package dev.atlasclient.core.render;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Render-side filtering shared by all overlays: distance culling, a hard per-frame cap (nearest
 * first) and a simple view-cone test, so overlay cost stays bounded regardless of how many
 * detections exist.
 */
public final class OverlayCuller {
    /** Axis-aligned box in world coordinates with an ARGB color and optional label. */
    public record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int argb,
                      String label, int layer) {
        public double centerX() { return (minX + maxX) / 2; }
        public double centerY() { return (minY + maxY) / 2; }
        public double centerZ() { return (minZ + maxZ) / 2; }
    }

    private OverlayCuller() { }

    /**
     * @param lookX/lookY/lookZ normalized camera look vector; pass all zeros to disable cone culling
     * @param cosHalfFov cosine of the half field of view plus margin (e.g. cos(70deg))
     */
    public static List<Box> cull(List<Box> boxes, double camX, double camY, double camZ, double maxDistance, int maxCount,
                                 double lookX, double lookY, double lookZ, double cosHalfFov) {
        double max2 = maxDistance * maxDistance;
        boolean cone = lookX != 0 || lookY != 0 || lookZ != 0;
        List<Box> keep = new ArrayList<>();
        for (Box b : boxes) {
            double dx = b.centerX() - camX, dy = b.centerY() - camY, dz = b.centerZ() - camZ;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > max2) {
                continue;
            }
            if (cone && d2 > 64) { // never cull boxes right next to the camera
                double len = Math.sqrt(d2);
                double radius = Math.max(b.maxX() - b.minX(), Math.max(b.maxY() - b.minY(), b.maxZ() - b.minZ()));
                double dot = (dx * lookX + dy * lookY + dz * lookZ) / len;
                double slack = Math.min(1, radius / len);
                if (dot < cosHalfFov - slack) {
                    continue;
                }
            }
            keep.add(b);
        }
        if (keep.size() > maxCount) {
            keep.sort(Comparator.comparingDouble(b -> {
                double dx = b.centerX() - camX, dy = b.centerY() - camY, dz = b.centerZ() - camZ;
                return dx * dx + dy * dy + dz * dz;
            }));
            return new ArrayList<>(keep.subList(0, Math.max(0, maxCount)));
        }
        return keep;
    }

    /** Applies an alpha multiplier (0..1) to an ARGB color. */
    public static int withAlpha(int argb, double alphaFactor) {
        int a = (int) Math.round(((argb >>> 24) & 0xFF) * Math.max(0, Math.min(1, alphaFactor)));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    /** Color for a confidence level index 0..4 from a palette, clamped. */
    public static int pick(int[] palette, int index) {
        return palette[Math.max(0, Math.min(palette.length - 1, index))];
    }
}
