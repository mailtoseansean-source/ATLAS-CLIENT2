package dev.atlasclient.core.analysis;

/**
 * One observed indicator block in absolute world coordinates.
 *
 * @param blockKey  stable integer identifying the exact block type (registry raw id in game,
 *                  arbitrary in tests). Used to detect runs of identical blocks.
 * @param stage     growth stage ("age" property) or -1 when not applicable
 * @param maxStage  maximum growth stage for the block, or -1
 */
public record BlockHit(Indicator indicator, int blockKey, int x, int y, int z, int stage, int maxStage) {
    public static BlockHit of(Indicator indicator, int x, int y, int z) {
        return new BlockHit(indicator, indicator.ordinal(), x, y, z, -1, -1);
    }

    public boolean isMature() { return maxStage > 0 && stage >= maxStage; }

    public long packedPos() { return pack(x, y, z); }

    /** Same packing scheme as Minecraft's BlockPos.asLong (26/12/26 bits). */
    public static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    public static int unpackX(long p) { return (int) (p >> 38); }
    public static int unpackY(long p) { return (int) (p << 52 >> 52); }
    public static int unpackZ(long p) { return (int) (p << 26 >> 38); }
}
