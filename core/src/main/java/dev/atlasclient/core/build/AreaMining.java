package dev.atlasclient.core.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Safety logic for area-mining tools such as the 3x3 "Amethyst Pickaxe" (server-side custom item,
 * mods, or datapack enchantments). Such tools break the targeted block plus the 8 blocks around it
 * in the plane perpendicular to the clicked face: clicking a top/bottom face clears a horizontal
 * 3x3 layer, clicking a side face clears a vertical 3x3 wall.
 *
 * <p>The excavator only swings an area tool when every cell of that plane is either scheduled for
 * removal or already empty, and none of them borders a fluid. Otherwise it falls back to a normal
 * single-block tool.</p>
 */
public final class AreaMining {
    private AreaMining() { }

    /** Faces as unit normals, in Minecraft's Direction order: DOWN, UP, NORTH, SOUTH, WEST, EAST. */
    public static final int[][] FACES = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};

    /**
     * User-selected excavation tool mode. REGULAR never swings an area tool (so a 3x3 pickaxe in the
     * hotbar is ignored); AMETHYST prefers the area tool wherever the 3x3 plane is safe and falls
     * back to a regular pickaxe elsewhere (if enabled).
     */
    public enum PickaxeMode { REGULAR, AMETHYST }

    /** The 9 cells broken when clicking {@code face} of the block at x/y/z with a radius-1 area tool. */
    public static List<int[]> plane(int x, int y, int z, int face, int radius) {
        int[] n = FACES[face];
        List<int[]> out = new ArrayList<>();
        for (int a = -radius; a <= radius; a++) {
            for (int b = -radius; b <= radius; b++) {
                if (n[1] != 0) {
                    out.add(new int[]{x + a, y, z + b});       // horizontal layer
                } else if (n[0] != 0) {
                    out.add(new int[]{x, y + a, z + b});       // wall in the Y/Z plane
                } else {
                    out.add(new int[]{x + a, y + b, z});       // wall in the X/Y plane
                }
            }
        }
        return out;
    }

    /**
     * Whether swinging an area tool at this face is safe.
     *
     * @param allowedCell a cell that may be broken (scheduled for removal) or is already empty
     * @param fluidNear   a cell that has lava/water as a neighbour
     */
    public static boolean planeIsSafe(int x, int y, int z, int face, int radius,
                                      Predicate<int[]> allowedCell, Predicate<int[]> fluidNear) {
        for (int[] c : plane(x, y, z, face, radius)) {
            if (!allowedCell.test(c) || fluidNear.test(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Picks the face to click: exposed (the neighbour across the face is not solid), visible from the
     * eye position, and - for area tools - safe. Returns the face index or -1.
     *
     * @param faceExposed predicate on the face index: neighbour across this face is air/non-occluding
     */
    public static int chooseFace(int x, int y, int z, double eyeX, double eyeY, double eyeZ, boolean areaTool, int radius,
                                 Predicate<Integer> faceExposed, Predicate<int[]> allowedCell, Predicate<int[]> fluidNear) {
        int best = -1;
        double bestDot = -2;
        double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;
        double vx = eyeX - cx, vy = eyeY - cy, vz = eyeZ - cz;
        double len = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (len < 1e-6) {
            return -1;
        }
        vx /= len;
        vy /= len;
        vz /= len;
        for (int f = 0; f < 6; f++) {
            int[] n = FACES[f];
            double dot = n[0] * vx + n[1] * vy + n[2] * vz;
            if (dot <= 0.05) continue;                    // face points away from the player
            if (!faceExposed.test(f)) continue;           // can't reach a buried face
            if (areaTool && !planeIsSafe(x, y, z, f, radius, allowedCell, fluidNear)) continue;
            if (dot > bestDot) {
                bestDot = dot;
                best = f;
            }
        }
        return best;
    }

    /**
     * Heuristic identification of an area-mining pickaxe from what the client can see: item id,
     * display name, lore lines and enchantment ids. On DonutSMP-style servers the Amethyst Pickaxe is
     * a vanilla pickaxe with a custom name/lore, so the default keyword "amethyst" matches it.
     */
    public static boolean looksLikeAreaTool(List<String> keywords, String itemId, String displayName,
                                            List<String> lore, List<String> enchantmentIds) {
        if (itemId == null || !itemId.endsWith("pickaxe")) {
            return false;
        }
        for (String k : keywords) {
            String kw = k.trim().toLowerCase(Locale.ROOT);
            if (kw.isEmpty()) continue;
            if (itemId.toLowerCase(Locale.ROOT).contains(kw)) return true;
            if (displayName != null && displayName.toLowerCase(Locale.ROOT).contains(kw)) return true;
            if (contains(lore, kw) || contains(enchantmentIds, kw)) return true;
        }
        return false;
    }

    private static boolean contains(List<String> values, String kw) {
        if (values == null) return false;
        for (String v : values) {
            if (v != null && v.toLowerCase(Locale.ROOT).contains(kw)) return true;
        }
        return false;
    }

    /** A hotbar/inventory candidate tool as seen by the client. */
    public record Tool(int slot, boolean areaTool, double speed, int durabilityLeft) { }

    /**
     * Chooses the tool for one block. Returns null when no acceptable tool exists.
     *
     * @param areaSafe whether a 3x3 swing at this block is safe (see {@link #planeIsSafe})
     * @param minDurability tools at or below this remaining durability are not used (0 = no limit)
     */
    public static Tool chooseTool(PickaxeMode mode, List<Tool> tools, boolean areaSafe, boolean edgeFallback, int minDurability) {
        Tool bestArea = null, bestRegular = null;
        for (Tool t : tools) {
            if (minDurability > 0 && t.durabilityLeft() >= 0 && t.durabilityLeft() <= minDurability) continue;
            if (t.areaTool()) {
                if (bestArea == null || t.speed() > bestArea.speed()) bestArea = t;
            } else if (bestRegular == null || t.speed() > bestRegular.speed()) {
                bestRegular = t;
            }
        }
        if (mode == PickaxeMode.REGULAR) {
            return bestRegular;
        }
        if (areaSafe && bestArea != null) {
            // The 3x3 pickaxe only where it is the right tool: a clearly faster regular tool (a shovel on
            // gravel, sand or dirt; an axe on wood) is used instead.
            if (bestRegular != null && bestRegular.speed() > bestArea.speed() * 1.2) return bestRegular;
            return bestArea;
        }
        return edgeFallback ? bestRegular : null;
    }
}
