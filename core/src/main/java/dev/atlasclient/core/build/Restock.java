package dev.atlasclient.core.build;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decisions for shulker-box restocking and dropping unneeded blocks. Pure logic: the game layer
 * supplies inventory snapshots and carries out the chosen actions with normal clicks.
 */
public final class Restock {
    private Restock() { }

    /** When unneeded blocks are thrown out. */
    public enum DropMode { OFF, WHEN_FULL, ALWAYS }

    /** Which unneeded blocks may be thrown out. */
    public enum DropScope { JUNK_ONLY, ANY_UNNEEDED_BLOCK }

    /**
     * One inventory or container slot.
     *
     * @param slot          inventory index 0-35 (hotbar 0-8), or container slot 0-26
     * @param isBlock       the item places a block
     * @param protectedItem never thrown: shulker boxes, renamed or enchanted items, items holding other items
     */
    public record Stack(int slot, String item, int count, boolean isBlock, boolean protectedItem) { }

    /** A shulker box item in the player's inventory and what it holds. */
    public record Shulker(int slot, String item, Map<String, Integer> contents) { }

    /** Common excavation drops. Only these are thrown in {@link DropScope#JUNK_ONLY}. */
    public static final List<String> DEFAULT_JUNK = List.of(
            "minecraft:cobblestone", "minecraft:cobbled_deepslate", "minecraft:dirt", "minecraft:gravel",
            "minecraft:netherrack", "minecraft:granite", "minecraft:diorite", "minecraft:andesite", "minecraft:tuff",
            "minecraft:stone", "minecraft:deepslate", "minecraft:blackstone", "minecraft:basalt", "minecraft:calcite",
            "minecraft:sand", "minecraft:red_sand", "minecraft:sandstone", "minecraft:end_stone", "minecraft:soul_sand",
            "minecraft:soul_soil", "minecraft:clay", "minecraft:mud", "minecraft:dripstone_block", "minecraft:smooth_basalt");

    /** Never thrown, whatever the scope. */
    public static final List<String> DEFAULT_NEVER_DROP = List.of(
            "minecraft:diamond_block", "minecraft:netherite_block", "minecraft:emerald_block", "minecraft:gold_block",
            "minecraft:iron_block", "minecraft:lapis_block", "minecraft:redstone_block", "minecraft:copper_block",
            "minecraft:raw_iron_block", "minecraft:raw_gold_block", "minecraft:raw_copper_block", "minecraft:ancient_debris",
            "minecraft:beacon", "minecraft:spawner", "minecraft:trial_spawner", "minecraft:vault", "minecraft:conduit",
            "minecraft:dragon_egg", "minecraft:dragon_head", "minecraft:ender_chest", "minecraft:enchanting_table",
            "minecraft:anvil", "minecraft:respawn_anchor", "minecraft:lodestone", "minecraft:heavy_core",
            "minecraft:sculk_shrieker", "minecraft:sculk_catalyst", "minecraft:budding_amethyst");

    /** Parses a comma separated id list ("cobblestone, minecraft:dirt") into full ids. */
    public static Set<String> parseIds(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return out;
        for (String part : text.split(",")) {
            String s = part.trim().toLowerCase(java.util.Locale.ROOT);
            if (s.isEmpty()) continue;
            out.add(s.contains(":") ? s : "minecraft:" + s);
        }
        return out;
    }

    public static String joinIds(List<String> ids) {
        List<String> shortIds = new ArrayList<>();
        for (String id : ids) shortIds.add(id.startsWith("minecraft:") ? id.substring(10) : id);
        return String.join(", ", shortIds);
    }

    /** How many of the missing items a shulker would supply. */
    public static int usefulCount(Shulker s, Map<String, Integer> missing) {
        int n = 0;
        for (Map.Entry<String, Integer> m : missing.entrySet()) {
            if (m.getValue() <= 0) continue;
            n += Math.min(m.getValue(), s.contents().getOrDefault(m.getKey(), 0));
        }
        return n;
    }

    /** The shulker that supplies the most missing items (lowest slot on ties), or null if none helps. */
    public static Shulker chooseShulker(List<Shulker> shulkers, Map<String, Integer> missing) {
        Shulker best = null;
        int bestUseful = 0;
        for (Shulker s : shulkers) {
            int u = usefulCount(s, missing);
            if (u > bestUseful || (u == bestUseful && u > 0 && best != null && s.slot() < best.slot())) {
                best = s;
                bestUseful = u;
            }
        }
        return best;
    }

    /**
     * Container slots to shift-click out, in order, so each missing item is covered while at most
     * {@code maxStacks} stacks are moved (one inventory slot each in the worst case).
     */
    public static List<Integer> withdrawals(List<Stack> container, Map<String, Integer> missing, int maxStacks) {
        Map<String, Integer> left = new HashMap<>();
        for (Map.Entry<String, Integer> m : missing.entrySet()) {
            if (m.getValue() > 0) left.put(m.getKey(), m.getValue());
        }
        List<Stack> candidates = new ArrayList<>();
        for (Stack s : container) {
            if (s.count() > 0 && left.containsKey(s.item())) candidates.add(s);
        }
        // Bigger stacks first: fewer clicks and fewer slots used.
        candidates.sort(Comparator.comparingInt(Stack::count).reversed().thenComparingInt(Stack::slot));
        List<Integer> out = new ArrayList<>();
        for (Stack s : candidates) {
            if (out.size() >= maxStacks) break;
            int need = left.getOrDefault(s.item(), 0);
            if (need <= 0) continue;
            out.add(s.slot());
            left.put(s.item(), need - s.count());
        }
        return out;
    }

    /** True if a stack may be thrown out under these settings. */
    public static boolean droppable(Stack s, Map<String, Integer> stillNeeded, DropScope scope, Set<String> junk, Set<String> neverDrop) {
        if (s.count() <= 0 || !s.isBlock() || s.protectedItem()) return false;
        if (neverDrop.contains(s.item())) return false;
        if (stillNeeded.getOrDefault(s.item(), 0) > 0) return false;
        return scope == DropScope.ANY_UNNEEDED_BLOCK || junk.contains(s.item());
    }

    /**
     * Inventory slots to throw out now. {@code WHEN_FULL} only throws until {@code keepFree} slots are
     * empty; {@code ALWAYS} throws every droppable stack. Junk-list items go first, bigger stacks first.
     */
    public static List<Integer> drops(List<Stack> inventory, Map<String, Integer> stillNeeded, DropMode mode, DropScope scope,
                                      Set<String> junk, Set<String> neverDrop, int keepFree) {
        if (mode == DropMode.OFF) return List.of();
        int free = 0;
        List<Stack> candidates = new ArrayList<>();
        for (Stack s : inventory) {
            if (s.count() <= 0) {
                free++;
            } else if (droppable(s, stillNeeded, scope, junk, neverDrop)) {
                candidates.add(s);
            }
        }
        candidates.sort(Comparator.comparing((Stack s) -> !junk.contains(s.item()))
                .thenComparing(Comparator.comparingInt(Stack::count).reversed())
                .thenComparingInt(Stack::slot));
        List<Integer> out = new ArrayList<>();
        if (mode == DropMode.ALWAYS) {
            for (Stack s : candidates) out.add(s.slot());
            return out;
        }
        for (Stack s : candidates) {
            if (free >= keepFree) break;
            out.add(s.slot());
            free++;
        }
        return out;
    }

    public static int freeSlots(List<Stack> inventory) {
        int free = 0;
        for (Stack s : inventory) if (s.count() <= 0) free++;
        return free;
    }

    /** World queries needed to choose where the shulker box goes down. */
    public interface SpotProbe {
        boolean empty(int x, int y, int z);       // air or replaceable
        boolean solid(int x, int y, int z);       // full block to place on
        boolean inBuild(int x, int y, int z);     // inside the schematic volume
        boolean fluidNear(int x, int y, int z);   // water or lava in or next to the cell
    }

    /**
     * Picks a cell for the shulker box near the player: empty, with a solid block below and an
     * empty cell above (the lid opens upward), outside the build, away from fluids, not where the
     * player stands, and within {@code reach} of the eyes. Returns {x,y,z} or null.
     */
    public static int[] chooseSpot(int feetX, int feetY, int feetZ, double eyeX, double eyeY, double eyeZ, double reach, SpotProbe probe) {
        int[] best = null;
        double bestD = Double.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    int x = feetX + dx, y = feetY + dy, z = feetZ + dz;
                    if (dx == 0 && dz == 0) continue; // never in the player's own column
                    if (!probe.empty(x, y, z) || !probe.empty(x, y + 1, z) || !probe.solid(x, y - 1, z)) continue;
                    if (probe.inBuild(x, y, z) || probe.inBuild(x, y + 1, z)) continue;
                    if (probe.fluidNear(x, y, z)) continue;
                    double cx = x + 0.5 - eyeX, cy = y + 0.5 - eyeY, cz = z + 0.5 - eyeZ;
                    double d = Math.sqrt(cx * cx + cy * cy + cz * cz);
                    if (d > reach - 0.5) continue;
                    // Prefer the same level and adjacent cells: easy to click and to walk onto afterwards.
                    double score = d + Math.abs(dy) * 0.75;
                    if (score < bestD) {
                        bestD = score;
                        best = new int[]{x, y, z};
                    }
                }
            }
        }
        return best;
    }
}
