package dev.atlasclient.core.schematic;

import dev.atlasclient.core.nbt.Nbt;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reader and writer for Sponge schematics (.schem), versions 1, 2 and 3 as written by WorldEdit,
 * FAWE and Litematica's exporter.
 *
 * <ul>
 *   <li>v1/v2: root compound named "Schematic" with Width/Height/Length, Palette, BlockData</li>
 *   <li>v3: unnamed root containing a "Schematic" compound whose Blocks compound holds Palette/Data</li>
 * </ul>
 * BlockData is a sequence of unsigned LEB128 varints indexed {@code (y * Length + z) * Width + x}.
 */
public final class SpongeSchematicIO {
    private SpongeSchematicIO() { }

    public static Schematic read(Path file) throws IOException {
        Nbt.Named named = Nbt.readFile(file);
        String base = file.getFileName().toString();
        int dot = base.lastIndexOf('.');
        return read(named, dot > 0 ? base.substring(0, dot) : base, file.toString());
    }

    public static Schematic read(Nbt.Named named, String fallbackName, String source) throws IOException {
        Nbt.Compound root = named.tag();
        Nbt.Compound inner = root.getCompound("Schematic");
        Nbt.Compound s = inner != null ? inner : root;
        int version = s.getInt("Version", -1);
        if (version < 1 || version > 3) {
            throw new IOException("Not a Sponge schematic (Version=" + version + ")");
        }
        int width = s.getInt("Width", 0) & 0xFFFF;
        int height = s.getInt("Height", 0) & 0xFFFF;
        int length = s.getInt("Length", 0) & 0xFFFF;
        if (width == 0 || height == 0 || length == 0) {
            throw new IOException("Schematic has zero size");
        }
        Nbt.Compound paletteTag;
        byte[] data;
        if (version == 3) {
            Nbt.Compound blocks = s.getCompound("Blocks");
            if (blocks == null) {
                throw new IOException("Sponge v3 schematic without Blocks");
            }
            paletteTag = blocks.getCompound("Palette");
            data = blocks.getByteArray("Data");
        } else {
            paletteTag = s.getCompound("Palette");
            data = s.getByteArray("BlockData");
        }
        if (paletteTag == null || data == null) {
            throw new IOException("Schematic is missing its palette or block data");
        }
        int paletteSize = 0;
        Map<Integer, BlockStateString> byIndex = new HashMap<>();
        for (String key : paletteTag.keys()) {
            int idx = paletteTag.getInt(key, -1);
            if (idx < 0) {
                throw new IOException("Bad palette index for " + key);
            }
            byIndex.put(idx, BlockStateString.parse(key));
            paletteSize = Math.max(paletteSize, idx + 1);
        }
        List<BlockStateString> palette = new ArrayList<>(paletteSize);
        for (int i = 0; i < paletteSize; i++) {
            palette.add(byIndex.getOrDefault(i, BlockStateString.AIR));
        }
        int volume = width * height * length;
        int[] blocks = decodeVarints(data, volume);
        // Sponge and our region use the same (y * L + z) * W + x ordering.
        Nbt.Compound meta = s.getCompound("Metadata");
        String name = meta != null ? meta.getString("Name", fallbackName) : fallbackName;
        String author = meta != null ? meta.getString("Author", "") : "";
        Schematic.Format format = switch (version) {
            case 1 -> Schematic.Format.SPONGE_V1;
            case 2 -> Schematic.Format.SPONGE_V2;
            default -> Schematic.Format.SPONGE_V3;
        };
        SchematicRegion region = new SchematicRegion("main", 0, 0, 0, width, height, length, palette, blocks);
        return new Schematic(name, author, "", format, s.getInt("DataVersion", 0), List.of(region), source);
    }

    static int[] decodeVarints(byte[] data, int expected) throws IOException {
        int[] out = new int[expected];
        int i = 0;
        int pos = 0;
        while (i < expected) {
            int value = 0;
            int shift = 0;
            while (true) {
                if (pos >= data.length) {
                    throw new IOException("BlockData ended early at block " + i + " of " + expected);
                }
                int b = data[pos++] & 0xFF;
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
                shift += 7;
                if (shift > 28) {
                    throw new IOException("Varint too long in BlockData");
                }
            }
            out[i++] = value;
        }
        return out;
    }

    static byte[] encodeVarints(int[] values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(values.length);
        for (int v : values) {
            int value = v;
            while ((value & ~0x7F) != 0) {
                out.write((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            out.write(value);
        }
        return out.toByteArray();
    }

    /**
     * Writes a single-region schematic ({@code version} 2 or 3). Multi-region schematics should be
     * saved as .litematic instead.
     */
    public static void write(Path file, Schematic schematic, int version, int dataVersion) throws IOException {
        if (schematic.regions().size() != 1) {
            throw new IOException("Sponge format holds one region; flatten the schematic first");
        }
        SchematicRegion r = schematic.regions().get(0);
        Nbt.Compound s = new Nbt.Compound();
        s.putInt("Version", version);
        s.putInt("DataVersion", dataVersion);
        s.putShort("Width", r.absX());
        s.putShort("Height", r.absY());
        s.putShort("Length", r.absZ());
        s.put("Offset", new int[]{0, 0, 0});
        Nbt.Compound meta = new Nbt.Compound();
        meta.putString("Name", schematic.name());
        meta.putString("Author", schematic.author());
        s.put("Metadata", meta);
        Nbt.Compound palette = new Nbt.Compound();
        for (int i = 0; i < r.palette().size(); i++) {
            palette.putInt(r.palette().get(i).toString(), i);
        }
        byte[] data = encodeVarints(r.rawBlocks());
        if (version == 3) {
            Nbt.Compound blocks = new Nbt.Compound();
            blocks.put("Palette", palette);
            blocks.put("Data", data);
            blocks.put("BlockEntities", new Nbt.ListTag(Nbt.COMPOUND));
            s.put("Blocks", blocks);
            Nbt.Compound root = new Nbt.Compound();
            root.put("Schematic", s);
            Nbt.writeGzipFile(file, "", root);
        } else {
            s.putInt("PaletteMax", r.palette().size());
            s.put("Palette", palette);
            s.put("BlockData", data);
            s.put("BlockEntities", new Nbt.ListTag(Nbt.COMPOUND));
            Nbt.writeGzipFile(file, "Schematic", s);
        }
    }
}
