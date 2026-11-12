package dev.atlasclient.core.analysis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Recognises vanilla generated structures from the blocks they are made of, so their chests,
 * corridors, workstations and redstone are not mistaken for a player base. Works on per-chunk
 * counts of a fixed block vocabulary (counted at every depth) plus the chunk's biomes.
 *
 * <p>Recognition is deliberately simple and conservative: each rule needs blocks that are typical of
 * that structure and rare in bases. A base built into a structure is still found when it has
 * blocks world generation cannot make (see {@link SuspicionScorer}).</p>
 */
public final class StructureSignatures {
    public enum Kind {
        VILLAGE("Village", true),
        MINESHAFT("Abandoned mineshaft", true),
        STRONGHOLD("Stronghold", true),
        DUNGEON("Monster room (dungeon)", false),
        ANCIENT_CITY("Ancient city", true),
        TRIAL_CHAMBERS("Trial chambers", true),
        DESERT_TEMPLE("Desert temple", false),
        JUNGLE_TEMPLE("Jungle temple", false),
        WOODLAND_MANSION("Woodland mansion", true),
        RUINED_PORTAL("Ruined portal", false),
        OCEAN_MONUMENT("Ocean monument", true),
        BASTION("Bastion remnant", true),
        NETHER_FORTRESS("Nether fortress", true),
        ARCHAEOLOGY("Trail ruins / ocean ruins / desert well", false),
        IGLOO("Igloo basement", false),
        END_CITY("End city", true);

        private final String label;
        /** Spans many chunks: neighbouring chunks of the same site are treated as part of it too. */
        private final boolean large;

        Kind(String label, boolean large) {
            this.label = label;
            this.large = large;
        }

        public String label() { return label; }
        public boolean large() { return large; }
    }

    /** A recognised structure with the reason shown to the user. */
    public record Match(Kind kind, String reason) { }

    /** Every block id (path only) whose count the rules use. */
    public static final Set<String> VOCABULARY = Set.of(
            "bell", "dirt_path", "hay_block", "composter", "lectern", "cauldron", "water_cauldron",
            "rail", "cobweb", "oak_planks", "oak_fence", "dark_oak_planks", "dark_oak_fence",
            "stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks", "chiseled_stone_bricks", "iron_bars",
            "end_portal_frame", "bookshelf", "oak_door", "infested_stone", "infested_stone_bricks",
            "infested_mossy_stone_bricks", "infested_cracked_stone_bricks", "infested_chiseled_stone_bricks",
            "infested_cobblestone", "infested_deepslate",
            "spawner", "mossy_cobblestone",
            "reinforced_deepslate", "sculk", "sculk_sensor", "sculk_shrieker", "sculk_catalyst", "deepslate_tiles",
            "deepslate_bricks", "soul_lantern", "candle",
            "trial_spawner", "vault", "chiseled_tuff", "chiseled_tuff_bricks", "tuff_bricks", "waxed_copper_grate",
            "waxed_oxidized_copper_grate", "waxed_weathered_copper_grate", "waxed_exposed_copper_grate", "waxed_copper_bulb",
            "chiseled_sandstone", "orange_terracotta", "blue_terracotta", "tnt", "stone_pressure_plate",
            "sticky_piston", "tripwire_hook", "tripwire", "dispenser",
            "white_wool", "white_carpet", "birch_planks",
            "crying_obsidian", "netherrack", "magma_block", "gold_block",
            "prismarine", "prismarine_bricks", "dark_prismarine", "sea_lantern", "wet_sponge",
            "gilded_blackstone", "polished_blackstone_bricks", "cracked_polished_blackstone_bricks",
            "nether_bricks", "nether_brick_fence", "nether_wart",
            "suspicious_sand", "suspicious_gravel",
            "brewing_stand", "snow_block",
            "purpur_block", "purpur_pillar", "end_stone_bricks");

    private StructureSignatures() { }

    public static boolean inVocabulary(String path) { return VOCABULARY.contains(path); }

    /**
     * @param sig       counts of {@link #VOCABULARY} blocks in the chunk (any depth)
     * @param biomes    biome ids (path only, e.g. "dark_forest") sampled in the chunk; may be empty
     * @param dimension dimension id
     */
    public static List<Match> recognize(Map<String, Integer> sig, Set<String> biomes, String dimension) {
        return recognize(sig, Map.of(), biomes, dimension);
    }

    /** @param counts indicator counts of the same chunk (used to tell bases apart from look-alikes) */
    public static List<Match> recognize(Map<String, Integer> sig, Map<Indicator, Integer> counts, Set<String> biomes, String dimension) {
        List<Match> out = new ArrayList<>();
        boolean nether = dimension.endsWith("the_nether");
        boolean end = dimension.endsWith("the_end");
        if (sig == null || sig.isEmpty()) return out;
        Set<String> b = biomes == null ? Set.of() : biomes;

        int bell = n(sig, "bell"), path = n(sig, "dirt_path"), hay = n(sig, "hay_block");
        boolean villageBiome = any(b, "plains", "desert", "savanna", "taiga", "snowy_plains", "meadow", "sunflower_plains", "snowy_taiga");
        if (bell > 0 || path >= 6 || (hay >= 3 && (path > 0 || villageBiome))) {
            out.add(new Match(Kind.VILLAGE, bell > 0 ? "a bell" : path >= 6 ? path + " dirt path blocks" : hay + " hay bales"));
        }
        int shaftSigns = n(sig, "rail") + n(sig, "cobweb");
        int shaftWood = n(sig, "oak_planks") + n(sig, "oak_fence") + n(sig, "dark_oak_planks") + n(sig, "dark_oak_fence");
        int fences = n(sig, "oak_fence") + n(sig, "dark_oak_fence");
        int planks = n(sig, "oak_planks") + n(sig, "dark_oak_planks");
        boolean baseStuff = counts.getOrDefault(Indicator.STORAGE, 0) + counts.getOrDefault(Indicator.WORKSTATION, 0)
                + counts.getOrDefault(Indicator.BED, 0) > 0;
        if (shaftSigns > 0 && shaftWood >= 4) {
            out.add(new Match(Kind.MINESHAFT, shaftSigns + " rails/cobwebs with " + shaftWood + " oak planks/fences"));
        } else if (fences >= 4 && planks >= 3 && !baseStuff) {
            // A mineshaft stretch without rails or cobwebs: just its oak supports (fence posts under planks).
            out.add(new Match(Kind.MINESHAFT, fences + " oak fence posts under " + planks + " planks (mineshaft supports)"));
        }
        int infested = n(sig, "infested_stone") + n(sig, "infested_stone_bricks") + n(sig, "infested_mossy_stone_bricks")
                + n(sig, "infested_cracked_stone_bricks") + n(sig, "infested_chiseled_stone_bricks") + n(sig, "infested_cobblestone")
                + n(sig, "infested_deepslate");
        int bricks = n(sig, "stone_bricks") + n(sig, "mossy_stone_bricks") + n(sig, "cracked_stone_bricks");
        boolean worn = n(sig, "mossy_stone_bricks") >= 3 || n(sig, "cracked_stone_bricks") >= 3;
        if (n(sig, "end_portal_frame") > 0 || (infested > 0 && bricks > 0)
                || (bricks >= 30 && worn && (n(sig, "iron_bars") > 0 || n(sig, "oak_door") > 0 || n(sig, "bookshelf") >= 4))) {
            out.add(new Match(Kind.STRONGHOLD, n(sig, "end_portal_frame") > 0 ? "end portal frames"
                    : infested > 0 ? "infested stone bricks" : bricks + " worn stone bricks"));
        } else if (infested > 0) {
            // Infested stone also generates loose in mountains: harmless, but those blocks are not a base.
            out.add(new Match(Kind.STRONGHOLD, infested + " infested blocks (stronghold or mountain ore pocket)"));
        }
        if (n(sig, "spawner") > 0 && n(sig, "mossy_cobblestone") >= 4) {
            out.add(new Match(Kind.DUNGEON, "a spawner in mossy cobblestone"));
        }
        if (n(sig, "reinforced_deepslate") > 0 || n(sig, "sculk_shrieker") > 0
                || (n(sig, "deepslate_tiles") + n(sig, "deepslate_bricks") >= 30 && n(sig, "sculk") + n(sig, "sculk_sensor") >= 5)) {
            out.add(new Match(Kind.ANCIENT_CITY, n(sig, "reinforced_deepslate") > 0 ? "reinforced deepslate" : "sculk around deepslate bricks"));
        }
        int copper = n(sig, "waxed_copper_grate") + n(sig, "waxed_oxidized_copper_grate") + n(sig, "waxed_weathered_copper_grate")
                + n(sig, "waxed_exposed_copper_grate") + n(sig, "waxed_copper_bulb");
        if (n(sig, "trial_spawner") > 0 || n(sig, "vault") > 0 || n(sig, "chiseled_tuff") > 0 || n(sig, "chiseled_tuff_bricks") > 0
                || (n(sig, "tuff_bricks") >= 20 && copper >= 4)) {
            out.add(new Match(Kind.TRIAL_CHAMBERS, n(sig, "trial_spawner") > 0 ? "a trial spawner" : "tuff bricks and waxed copper"));
        }
        if (n(sig, "chiseled_sandstone") >= 4 && (n(sig, "orange_terracotta") >= 2 || n(sig, "tnt") >= 4)) {
            out.add(new Match(Kind.DESERT_TEMPLE, "chiseled sandstone with orange terracotta / TNT"));
        }
        if (n(sig, "mossy_cobblestone") >= 20 && n(sig, "chiseled_stone_bricks") > 0
                && (n(sig, "sticky_piston") > 0 || n(sig, "tripwire_hook") > 0 || n(sig, "dispenser") > 0)) {
            out.add(new Match(Kind.JUNGLE_TEMPLE, "mossy cobblestone with a piston/tripwire trap"));
        }
        if (n(sig, "dark_oak_planks") >= 100 && n(sig, "white_wool") + n(sig, "white_carpet") + n(sig, "birch_planks") >= 20
                && (b.isEmpty() || any(b, "dark_forest", "pale_garden"))) {
            out.add(new Match(Kind.WOODLAND_MANSION, "lots of dark oak with white wool / birch"));
        }
        if (!nether && n(sig, "crying_obsidian") > 0 && (n(sig, "netherrack") >= 3 || n(sig, "magma_block") > 0 || n(sig, "gold_block") > 0)) {
            out.add(new Match(Kind.RUINED_PORTAL, "crying obsidian with netherrack"));
        }
        if (n(sig, "prismarine") + n(sig, "prismarine_bricks") + n(sig, "dark_prismarine") >= 30 || n(sig, "wet_sponge") > 0) {
            out.add(new Match(Kind.OCEAN_MONUMENT, "prismarine walls"));
        }
        if (n(sig, "gilded_blackstone") > 0 || (nether && n(sig, "polished_blackstone_bricks") + n(sig, "cracked_polished_blackstone_bricks") >= 30)) {
            out.add(new Match(Kind.BASTION, "blackstone bricks / gilded blackstone"));
        }
        if (nether && n(sig, "nether_bricks") >= 40 && n(sig, "nether_brick_fence") >= 4) {
            out.add(new Match(Kind.NETHER_FORTRESS, "nether brick walls and fences"));
        }
        if (n(sig, "suspicious_sand") + n(sig, "suspicious_gravel") > 0) {
            out.add(new Match(Kind.ARCHAEOLOGY, "suspicious sand/gravel"));
        }
        if (n(sig, "brewing_stand") > 0 && (n(sig, "cauldron") + n(sig, "water_cauldron")) > 0 && bricks >= 10
                && (b.isEmpty() ? n(sig, "snow_block") > 0 : any(b, "snowy_plains", "snowy_taiga", "snowy_slopes", "ice_spikes", "frozen_peaks", "grove"))) {
            out.add(new Match(Kind.IGLOO, "brewing stand and cauldron in stone bricks under snow"));
        }
        if (end && n(sig, "purpur_block") + n(sig, "purpur_pillar") >= 20) {
            out.add(new Match(Kind.END_CITY, "purpur"));
        }
        return out;
    }

    public static String describe(List<Match> matches) {
        if (matches.isEmpty()) return "";
        Set<String> seen = new HashSet<>();
        StringBuilder sb = new StringBuilder();
        for (Match m : matches) {
            if (!seen.add(m.kind().label())) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(m.kind().label().toLowerCase(Locale.ROOT)).append(" (").append(m.reason()).append(')');
        }
        return sb.toString();
    }

    private static int n(Map<String, Integer> m, String k) { return m.getOrDefault(k, 0); }

    private static boolean any(Set<String> biomes, String... names) {
        for (String n : names) if (biomes.contains(n)) return true;
        return false;
    }
}
