package dev.atlasclient.core.build;

import dev.atlasclient.core.schematic.BlockStateString;

import java.util.Map;
import java.util.Set;

/** Id/name-based block traits. Used by tests and as a fallback; the game layer uses vanilla classes. */
public class DefaultBlockTraits implements BlockTraits {
    private static final Set<String> GRAVITY = Set.of("sand", "red_sand", "gravel", "anvil", "chipped_anvil",
            "damaged_anvil", "dragon_egg", "pointed_dripstone", "suspicious_sand", "suspicious_gravel", "scaffolding");
    private static final Set<String> UNPLACEABLE = Set.of("water", "lava", "bubble_column", "nether_portal",
            "end_portal", "end_gateway", "piston_head", "moving_piston", "fire", "soul_fire", "frosted_ice", "barrier",
            "structure_void", "light", "command_block", "chain_command_block", "repeating_command_block", "jigsaw",
            "structure_block", "bedrock", "budding_amethyst", "spawner", "trial_spawner", "vault", "reinforced_deepslate",
            "end_portal_frame", "petrified_oak_slab", "infested_stone",
            // Created by using a tool on another block, not by placing an item:
            "farmland", "dirt_path");
    private static final Map<String, String> ITEM_OVERRIDES = Map.ofEntries(
            Map.entry("redstone_wire", "redstone"), Map.entry("wall_torch", "torch"),
            Map.entry("soul_wall_torch", "soul_torch"), Map.entry("redstone_wall_torch", "redstone_torch"),
            Map.entry("tripwire", "string"), Map.entry("wheat", "wheat_seeds"), Map.entry("carrots", "carrot"),
            Map.entry("potatoes", "potato"), Map.entry("beetroots", "beetroot_seeds"), Map.entry("cocoa", "cocoa_beans"),
            Map.entry("melon_stem", "melon_seeds"), Map.entry("pumpkin_stem", "pumpkin_seeds"),
            Map.entry("attached_melon_stem", "melon_seeds"), Map.entry("attached_pumpkin_stem", "pumpkin_seeds"),
            Map.entry("kelp_plant", "kelp"), Map.entry("bamboo_sapling", "bamboo"), Map.entry("sweet_berry_bush", "sweet_berries"),
            Map.entry("cave_vines", "glow_berries"), Map.entry("cave_vines_plant", "glow_berries"),
            Map.entry("powder_snow", "powder_snow_bucket"), Map.entry("big_dripleaf_stem", "big_dripleaf"),
            Map.entry("torchflower_crop", "torchflower_seeds"), Map.entry("pitcher_crop", "pitcher_pod"));

    @Override
    public PlacementClass classify(BlockStateString s) {
        if (s.isAir() || s.isStructureVoid()) {
            return PlacementClass.AIR;
        }
        String path = path(s.id());
        if (UNPLACEABLE.contains(path)) {
            return PlacementClass.UNPLACEABLE;
        }
        if ("upper".equals(s.property("half")) && (path.endsWith("_door") || isTallPlant(path))) {
            return PlacementClass.AUTO_PART;
        }
        if (path.endsWith("_bed") && "head".equals(s.property("part"))) {
            return PlacementClass.AUTO_PART;
        }
        if (path.endsWith("_concrete_powder") || GRAVITY.contains(path)) {
            return PlacementClass.GRAVITY;
        }
        if (isAttached(path)) {
            return PlacementClass.ATTACHED;
        }
        return PlacementClass.SOLID;
    }

    private static boolean isTallPlant(String path) {
        return path.equals("tall_grass") || path.equals("large_fern") || path.equals("sunflower") || path.equals("lilac")
                || path.equals("rose_bush") || path.equals("peony") || path.equals("tall_seagrass") || path.equals("pitcher_plant");
    }

    protected static boolean isAttached(String p) {
        return p.endsWith("torch") || p.endsWith("_button") || p.equals("lever") || p.endsWith("rail")
                || p.endsWith("_carpet") || p.equals("redstone_wire") || p.equals("repeater") || p.equals("comparator")
                || p.endsWith("_pressure_plate") || p.endsWith("_sign") || p.endsWith("_banner") || p.equals("ladder")
                || p.equals("vine") || p.endsWith("_sapling") || p.endsWith("_door") || p.endsWith("_bed")
                || p.equals("wheat") || p.equals("carrots") || p.equals("potatoes") || p.equals("beetroots")
                || p.equals("sugar_cane") || p.equals("cactus") || p.equals("cocoa") || p.equals("tripwire_hook")
                || p.equals("tripwire") || p.equals("flower_pot") || p.startsWith("potted_") || p.endsWith("_coral_fan")
                || p.endsWith("_wall_fan") || p.equals("lantern") || p.equals("soul_lantern") || p.equals("bell")
                || p.equals("snow") || p.equals("lily_pad") || p.equals("kelp") || p.equals("kelp_plant")
                || p.equals("seagrass") || p.endsWith("_mushroom") || p.equals("nether_wart")
                || p.equals("melon_stem") || p.equals("pumpkin_stem") || p.startsWith("attached_")
                || p.equals("dandelion") || p.equals("poppy") || p.endsWith("_tulip") || p.equals("cornflower")
                || p.equals("short_grass") || p.equals("fern") || p.equals("dead_bush") || p.endsWith("_candle")
                || p.equals("candle") || p.equals("item_frame") || p.equals("glow_item_frame") || p.equals("painting");
    }

    @Override
    public String requiredItem(BlockStateString s) {
        if (classify(s) == PlacementClass.AIR || classify(s) == PlacementClass.UNPLACEABLE) {
            return null;
        }
        String path = path(s.id());
        String ns = s.id().substring(0, s.id().indexOf(':'));
        String override = ITEM_OVERRIDES.get(path);
        if (override != null) {
            return ns + ":" + override;
        }
        if (path.endsWith("_wall_sign")) {
            return ns + ":" + path.replace("_wall_sign", "_sign");
        }
        if (path.endsWith("_wall_hanging_sign")) {
            return ns + ":" + path.replace("_wall_hanging_sign", "_hanging_sign");
        }
        if (path.endsWith("_wall_banner")) {
            return ns + ":" + path.replace("_wall_banner", "_banner");
        }
        if (path.endsWith("_wall_head") || path.endsWith("_wall_skull")) {
            return ns + ":" + path.replace("_wall_", "_");
        }
        if (path.endsWith("_wall_fan")) {
            return ns + ":" + path.replace("_wall_fan", "_fan");
        }
        if (path.startsWith("potted_")) {
            return ns + ":flower_pot";
        }
        return s.id();
    }

    @Override
    public int itemCount(BlockStateString s) {
        if (requiredItem(s) == null || classify(s) == PlacementClass.AUTO_PART) {
            return 0;
        }
        if ("double".equals(s.property("type")) && path(s.id()).endsWith("_slab")) {
            return 2;
        }
        String candles = s.property("candles");
        if (candles != null) {
            return parse(candles, 1);
        }
        String pickles = s.property("pickles");
        if (pickles != null) {
            return parse(pickles, 1);
        }
        String eggs = s.property("eggs");
        if (eggs != null) {
            return parse(eggs, 1);
        }
        String layers = s.property("layers");
        if (layers != null && path(s.id()).equals("snow")) {
            return parse(layers, 1);
        }
        return 1;
    }

    @Override
    public int extraInteractions(BlockStateString s) {
        String path = path(s.id());
        if (path.equals("repeater")) {
            return Math.max(0, parse(s.property("delay"), 1) - 1);
        }
        if (path.equals("comparator")) {
            return "subtract".equals(s.property("mode")) ? 1 : 0;
        }
        if (path.equals("note_block")) {
            return parse(s.property("note"), 0);
        }
        if (path.endsWith("_door") || path.endsWith("_trapdoor") || path.endsWith("_fence_gate")) {
            return "true".equals(s.property("open")) && !path.startsWith("iron_") ? 1 : 0;
        }
        if (path.equals("lever")) {
            return "true".equals(s.property("powered")) ? 1 : 0;
        }
        if (path.equals("daylight_detector")) {
            return "true".equals(s.property("inverted")) ? 1 : 0;
        }
        return 0;
    }

    private static int parse(String v, int def) {
        if (v == null) return def;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static String path(String id) {
        int c = id.indexOf(':');
        return c < 0 ? id : id.substring(c + 1);
    }
}
