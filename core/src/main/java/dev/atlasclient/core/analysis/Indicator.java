package dev.atlasclient.core.analysis;

/**
 * Observable block types that may indicate player activity. Each indicator has a default weight
 * and a "natural baseline": the count per chunk that world generation alone commonly produces, so
 * only the excess counts toward the score. All values are configurable at runtime through
 * {@link ScoringConfig}.
 */
public enum Indicator {
    // Amethyst geodes (analysed separately by AmethystAnalyzer; weights here only cover raw presence)
    BUDDING_AMETHYST(Group.AMETHYST, 0.0, 0, "Budding amethyst"),
    AMETHYST_CLUSTER(Group.AMETHYST, 0.0, 0, "Amethyst cluster (fully grown)"),
    LARGE_AMETHYST_BUD(Group.AMETHYST, 0.0, 0, "Large amethyst bud"),
    MEDIUM_AMETHYST_BUD(Group.AMETHYST, 0.0, 0, "Medium amethyst bud"),
    SMALL_AMETHYST_BUD(Group.AMETHYST, 0.0, 0, "Small amethyst bud"),
    AMETHYST_BLOCK(Group.AMETHYST, 0.0, 0, "Amethyst block"),
    CALCITE(Group.AMETHYST, 0.0, 0, "Calcite (geode shell)"),

    // Plants whose growth is driven by random ticks (analysed by PlantGrowthAnalyzer)
    VINE(Group.PLANT_GROWTH, 0.0, 0, "Vines"),
    KELP(Group.PLANT_GROWTH, 0.0, 0, "Kelp"),
    BAMBOO(Group.PLANT_GROWTH, 0.0, 0, "Bamboo"),
    COCOA(Group.PLANT_GROWTH, 0.0, 0, "Cocoa"),
    /** Unwaxed copper that has turned (exposed/weathered/oxidized): only ages through random ticks. */
    AGED_COPPER(Group.PLANT_GROWTH, 0.0, 0, "Aged (oxidised) unwaxed copper"),

    BEE_NEST(Group.BEES, 1.5, 1, "Bee nest (generates naturally)"),
    BEEHIVE(Group.BEES, 9.0, 0, "Beehive (crafted, not world-generated)"),

    CROP(Group.FARMING, 0.8, 6, "Crops"),
    FARMLAND(Group.FARMING, 0.9, 6, "Farmland"),

    REDSTONE(Group.REDSTONE, 1.4, 3, "Redstone component"),
    STORAGE(Group.STORAGE, 2.2, 2, "Storage container"),
    WORKSTATION(Group.UTILITY_BLOCKS, 1.6, 2, "Workstation / utility block"),
    BED(Group.UTILITY_BLOCKS, 2.5, 1, "Bed"),
    CRAFTED_BUILDING(Group.BUILDING, 0.6, 12, "Crafted building block"),
    PLAYER_ONLY(Group.BUILDING, 6.0, 0, "Block not produced by vanilla world generation"),
    LIGHT_SOURCE(Group.BUILDING, 0.7, 6, "Placed light source"),
    PORTAL(Group.UTILITY_BLOCKS, 4.0, 0, "Nether portal"),
    /** Sugar cane, cactus, melons, pumpkins, sweet berries: generate on the surface, never deep underground. */
    FARM_PLANT(Group.FARMING, 0.7, 10, "Farm plant (sugar cane, cactus, melon, pumpkin)"),
    /** Saplings are never placed by world generation. */
    SAPLING(Group.FARMING, 1.6, 1, "Sapling"),
    /** Leaves with persistent=true: only players place those (natural leaves are not persistent). */
    PLACED_LEAVES(Group.BUILDING, 2.4, 2, "Placed leaves (persistent)"),
    /** Signs, banners, flower pots, candles, lecterns, bookshelves. */
    DECORATION(Group.DECOR, 1.2, 2, "Sign / banner / pot / candle / bookshelf"),
    /** Doors, trapdoors, stairs, slabs, walls, fences, gates, buttons, pressure plates. */
    BUILDING_PART(Group.BUILDING, 0.45, 10, "Door / trapdoor / stairs / slab / fence / wall"),
    /** Terracotta: generates in huge amounts in badlands, rarely elsewhere. */
    TERRACOTTA(Group.BUILDING, 0.5, 6, "Terracotta"),
    FIRE(Group.DAMAGE, 0.0, 0, "Fire"),
    /** Oak and dark oak planks/fences: common in bases, but also what abandoned mineshafts are built from. */
    MINESHAFT_WOOD(Group.BUILDING, 0.6, 12, "Oak / dark oak planks or fences"),
    /** Rails and cobwebs: generated in abandoned mineshafts. Used only to recognise mineshafts. */
    MINESHAFT_SIGN(Group.CONFOUNDER, 0.0, 0, "Rail or cobweb (mineshaft sign)"),

    /** Generated-structure markers that reduce confidence (villages, dungeons, trial chambers...). */
    STRUCTURE_MARKER(Group.CONFOUNDER, 0.0, 0, "Generated structure marker");

    public enum Group {
        AMETHYST("Amethyst growth"), PLANT_GROWTH("Growth & aging"), BEES("Bees"), FARMING("Farms"), REDSTONE("Redstone"),
        STORAGE("Storage"), UTILITY_BLOCKS("Utility blocks"), BUILDING("Building"), DECOR("Decoration"),
        GEOMETRY("Artificial shapes"), DAMAGE("Damage"), CONFOUNDER("Generated structure");

        private final String label;

        Group(String label) { this.label = label; }

        public String label() { return label; }
    }

    private final Group group;
    private final double defaultWeight;
    private final int defaultBaseline;
    private final String label;

    Indicator(Group group, double defaultWeight, int defaultBaseline, String label) {
        this.group = group;
        this.defaultWeight = defaultWeight;
        this.defaultBaseline = defaultBaseline;
        this.label = label;
    }

    public Group group() { return group; }

    /**
     * Grows or ages only through random ticks, which happen only near players. Counted at any depth
     * (also with "Underground only"): kelp in the sea or vines on trees near a hidden base still tell
     * that someone stayed around.
     */
    public boolean growth() { return group == Group.AMETHYST || group == Group.PLANT_GROWTH; }
    public double defaultWeight() { return defaultWeight; }
    public int defaultBaseline() { return defaultBaseline; }
    public String label() { return label; }
}
