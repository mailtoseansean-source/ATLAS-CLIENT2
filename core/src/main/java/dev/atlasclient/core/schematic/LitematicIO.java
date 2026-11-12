package dev.atlasclient.core.schematic;

import dev.atlasclient.core.nbt.Nbt;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Independent reader/writer for Litematica's .litematic format, implemented from Litematica's
 * published source (LitematicaSchematic, LitematicaBlockStateContainer, LitematicaBitArray) for the
 * 1.21.11 branch. No Litematica code is used or required.
 *
 * <p>Layout: gzip NBT, unnamed root with {@code Version}, {@code SubVersion},
 * {@code MinecraftDataVersion}, {@code Metadata} and {@code Regions}. Each region has
 * {@code Position{x,y,z}}, signed {@code Size{x,y,z}}, {@code BlockStatePalette} (list of
 * {Name, Properties}) and {@code BlockStates}: a long array where each entry uses
 * {@code max(2, ceil(log2(paletteSize)))} bits and entries MAY span two longs. Entry index is
 * {@code y * sizeX * sizeZ + z * sizeX + x} relative to the region's minimum corner.</p>
 */
public final class LitematicIO {
    public static final int MAX_SUPPORTED_VERSION = 7;
    public static final int WRITE_VERSION = 7;
    public static final int WRITE_SUB_VERSION = 1;

    /** Block ids that only existed before the 1.13 flattening (none of them is a valid modern block id). */
    static final java.util.Set<String> LEGACY_ONLY_IDS = java.util.Set.of(
            "minecraft:concrete", "minecraft:concrete_powder", "minecraft:stained_glass", "minecraft:stained_glass_pane",
            "minecraft:wool", "minecraft:carpet", "minecraft:planks", "minecraft:log", "minecraft:log2", "minecraft:leaves",
            "minecraft:leaves2", "minecraft:sapling", "minecraft:stone_slab2", "minecraft:double_stone_slab",
            "minecraft:double_stone_slab2", "minecraft:wooden_slab", "minecraft:double_wooden_slab",
            "minecraft:unpowered_repeater", "minecraft:powered_repeater", "minecraft:unpowered_comparator",
            "minecraft:powered_comparator", "minecraft:lit_pumpkin", "minecraft:lit_furnace", "minecraft:lit_redstone_lamp",
            "minecraft:lit_redstone_ore", "minecraft:standing_sign", "minecraft:standing_banner", "minecraft:stained_hardened_clay",
            "minecraft:hardened_clay", "minecraft:red_flower", "minecraft:yellow_flower", "minecraft:tallgrass",
            "minecraft:double_plant", "minecraft:brick_block", "minecraft:monster_egg", "minecraft:noteblock", "minecraft:web",
            "minecraft:deadbush", "minecraft:mob_spawner", "minecraft:melon_block", "minecraft:waterlily", "minecraft:snow_layer",
            "minecraft:reeds", "minecraft:fence_gate", "minecraft:trapdoor", "minecraft:wooden_door", "minecraft:wooden_pressure_plate",
            "minecraft:wooden_button", "minecraft:piston_extension", "minecraft:magma", "minecraft:red_nether_brick",
            "minecraft:end_bricks", "minecraft:silver_shulker_box", "minecraft:silver_glazed_terracotta", "minecraft:quartz_ore",
            "minecraft:portal", "minecraft:skull", "minecraft:unlit_redstone_torch", "minecraft:daylight_detector_inverted");

    private LitematicIO() { }

    public static Schematic read(Path file) throws IOException {
        Nbt.Named named = Nbt.readFile(file);
        String base = file.getFileName().toString();
        int dot = base.lastIndexOf('.');
        return read(named.tag(), dot > 0 ? base.substring(0, dot) : base, file.toString());
    }

    public static Schematic read(Nbt.Compound root, String fallbackName, String source) throws IOException {
        int version = root.getInt("Version", -1);
        if (version < 1) {
            throw new IOException("Not a Litematica schematic (no Version)");
        }
        if (version > MAX_SUPPORTED_VERSION) {
            throw new IOException("Unsupported .litematic version " + version + " (max " + MAX_SUPPORTED_VERSION + ")");
        }
        int dataVersion = root.getInt("MinecraftDataVersion", 0);
        if (dataVersion > 0 && dataVersion < 1631) {
            // Litematica converts pre-1.13 block names with Mojang's DataFixer; we do not.
            throw new IOException("This .litematic was saved by Minecraft data version " + dataVersion
                    + " (pre-1.13.2) and uses legacy block names; re-save it with a modern Litematica first");
        }
        Nbt.Compound meta = root.getCompound("Metadata");
        String name = meta != null ? meta.getString("Name", fallbackName) : fallbackName;
        String author = meta != null ? meta.getString("Author", "") : "";
        String desc = meta != null ? meta.getString("Description", "") : "";
        Nbt.Compound regionsTag = root.getCompound("Regions");
        if (regionsTag == null || regionsTag.size() == 0) {
            throw new IOException("Schematic has no regions");
        }
        List<SchematicRegion> regions = new ArrayList<>();
        for (String regionName : regionsTag.keys()) {
            Nbt.Compound r = regionsTag.getCompound(regionName);
            if (r == null) {
                continue;
            }
            int[] pos = readPos(r.getCompound("Position"), "Position", regionName);
            int[] size = readPos(r.getCompound("Size"), "Size", regionName);
            Nbt.ListTag paletteTag = r.getList("BlockStatePalette");
            long[] states = r.getLongArray("BlockStates");
            if (paletteTag == null || states == null) {
                throw new IOException("Region '" + regionName + "' lacks BlockStatePalette or BlockStates");
            }
            List<BlockStateString> palette = new ArrayList<>(paletteTag.size());
            for (int i = 0; i < paletteTag.size(); i++) {
                Nbt.Compound e = paletteTag.compound(i);
                String id = e.getString("Name", null);
                if (id == null) {
                    throw new IOException("Palette entry " + i + " of region '" + regionName + "' has no Name");
                }
                Map<String, String> props = new TreeMap<>();
                Nbt.Compound p = e.getCompound("Properties");
                if (p != null) {
                    for (String k : p.keys()) {
                        props.put(k, p.getString(k, ""));
                    }
                }
                palette.add(new BlockStateString(id, props));
            }
            int ax = Math.abs(size[0]), ay = Math.abs(size[1]), az = Math.abs(size[2]);
            long volume = (long) ax * ay * az;
            if (volume == 0) {
                throw new IOException("Region '" + regionName + "' has zero size");
            }
            int bits = bitsFor(palette.size());
            long neededLongs = (volume * bits + 63) / 64;
            if (states.length < neededLongs) {
                throw new IOException("Region '" + regionName + "' BlockStates too short: " + states.length + " < " + neededLongs);
            }
            int[] blocks = new int[(int) volume];
            for (int i = 0; i < volume; i++) {
                int id = getAt(states, bits, i);
                if (id >= palette.size()) {
                    throw new IOException("Region '" + regionName + "' references palette id " + id + " of " + palette.size());
                }
                blocks[i] = id;
            }
            if (dataVersion < 1631) {
                for (BlockStateString b : palette) {
                    if (LEGACY_ONLY_IDS.contains(b.id())) {
                        // Some converters write MinecraftDataVersion 0 for 1.12 content; the palette gives it away.
                        throw new IOException("This .litematic uses pre-1.13 block names (e.g. " + b.id()
                                + ") without a usable data version; open and re-save it with Litematica's datafixer first");
                    }
                }
            }
            regions.add(new SchematicRegion(regionName, pos[0], pos[1], pos[2], size[0], size[1], size[2], palette, blocks));
        }
        return new Schematic(name, author, desc, Schematic.Format.LITEMATIC,
                root.getInt("MinecraftDataVersion", 0), regions, source);
    }

    private static int[] readPos(Nbt.Compound c, String what, String region) throws IOException {
        if (c == null || !c.contains("x") || !c.contains("y") || !c.contains("z")) {
            throw new IOException("Region '" + region + "' has no valid " + what);
        }
        return new int[]{c.getInt("x", 0), c.getInt("y", 0), c.getInt("z", 0)};
    }

    /** Litematica: max(2, Integer.SIZE - numberOfLeadingZeros(paletteSize - 1)). */
    public static int bitsFor(int paletteSize) {
        return Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(Math.max(0, paletteSize - 1)));
    }

    /** Port of LitematicaBitArray.getAt: entries may span two longs. */
    static int getAt(long[] array, int bits, long index) {
        long maxEntry = (1L << bits) - 1L;
        long startOffset = index * bits;
        int startArr = (int) (startOffset >> 6);
        int endArr = (int) (((index + 1L) * bits - 1L) >> 6);
        int startBit = (int) (startOffset & 0x3F);
        if (startArr == endArr) {
            return (int) (array[startArr] >>> startBit & maxEntry);
        }
        int endOffset = 64 - startBit;
        return (int) ((array[startArr] >>> startBit | array[endArr] << endOffset) & maxEntry);
    }

    /** Port of LitematicaBitArray.setAt. */
    static void setAt(long[] array, int bits, long index, int value) {
        long maxEntry = (1L << bits) - 1L;
        long startOffset = index * bits;
        int startArr = (int) (startOffset >> 6);
        int endArr = (int) (((index + 1L) * bits - 1L) >> 6);
        int startBit = (int) (startOffset & 0x3F);
        array[startArr] = array[startArr] & ~(maxEntry << startBit) | ((long) value & maxEntry) << startBit;
        if (startArr != endArr) {
            int endOffset = 64 - startBit;
            int j1 = bits - endOffset;
            array[endArr] = array[endArr] >>> j1 << j1 | ((long) value & maxEntry) >> endOffset;
        }
    }

    public static Nbt.Compound toNbt(Schematic s, int minecraftDataVersion, long timeMillis) {
        Nbt.Compound root = new Nbt.Compound();
        root.putInt("MinecraftDataVersion", minecraftDataVersion);
        root.putInt("Version", WRITE_VERSION);
        root.putInt("SubVersion", WRITE_SUB_VERSION);
        Nbt.Compound meta = new Nbt.Compound();
        meta.putString("Name", s.name());
        meta.putString("Author", s.author());
        meta.putString("Description", s.description());
        meta.putInt("RegionCount", s.regions().size());
        meta.putInt("TotalVolume", (int) Math.min(Integer.MAX_VALUE, s.totalVolume()));
        meta.putInt("TotalBlocks", (int) Math.min(Integer.MAX_VALUE, s.totalNonAir()));
        meta.putLong("TimeCreated", timeMillis);
        meta.putLong("TimeModified", timeMillis);
        int[] enc = s.enclosingSize();
        meta.put("EnclosingSize", pos(enc[0], enc[1], enc[2]));
        root.put("Metadata", meta);
        Nbt.Compound regions = new Nbt.Compound();
        for (SchematicRegion r : s.regions()) {
            Nbt.Compound tag = new Nbt.Compound();
            Nbt.ListTag palette = new Nbt.ListTag(Nbt.COMPOUND);
            for (BlockStateString st : r.palette()) {
                Nbt.Compound e = new Nbt.Compound();
                e.putString("Name", st.id());
                if (!st.properties().isEmpty()) {
                    Nbt.Compound p = new Nbt.Compound();
                    st.properties().forEach(p::putString);
                    e.put("Properties", p);
                }
                palette.add(e);
            }
            int bits = bitsFor(r.palette().size());
            long volume = r.volume();
            long[] arr = new long[(int) ((volume * bits + 63) / 64)];
            int[] raw = r.rawBlocks();
            for (int i = 0; i < raw.length; i++) {
                setAt(arr, bits, i, raw[i]);
            }
            tag.put("BlockStatePalette", palette);
            tag.put("BlockStates", arr);
            tag.put("TileEntities", new Nbt.ListTag(Nbt.COMPOUND));
            tag.put("Entities", new Nbt.ListTag(Nbt.COMPOUND));
            tag.put("PendingBlockTicks", new Nbt.ListTag(Nbt.COMPOUND));
            tag.put("PendingFluidTicks", new Nbt.ListTag(Nbt.COMPOUND));
            tag.put("Position", pos(r.posX(), r.posY(), r.posZ()));
            tag.put("Size", pos(r.sizeX(), r.sizeY(), r.sizeZ()));
            regions.put(r.name(), tag);
        }
        root.put("Regions", regions);
        return root;
    }

    public static void write(Path file, Schematic s, int minecraftDataVersion) throws IOException {
        Nbt.writeGzipFile(file, "", toNbt(s, minecraftDataVersion, System.currentTimeMillis()));
    }

    private static Nbt.Compound pos(int x, int y, int z) {
        return new Nbt.Compound().putInt("x", x).putInt("y", y).putInt("z", z);
    }
}
