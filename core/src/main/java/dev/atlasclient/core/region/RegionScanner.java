package dev.atlasclient.core.region;

import dev.atlasclient.core.nbt.Nbt;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Read-only reader for Anvil region files (.mca) of a world save the user owns. Used for "local
 * world analysis" in single-player: it reads what the save file contains, which is exactly the
 * server's own data, so no information is hidden or reconstructed.
 *
 * <p>Supports the 1.18+ chunk format ({@code sections[].block_states.palette/data}). Chunks stored
 * with LZ4 compression or in external .mcc files are skipped and counted, never guessed.</p>
 */
public final class RegionScanner {
    private static final int SECTOR = 4096;

    public record Stats(int regionsRead, int chunksDecoded, int chunksSkipped, List<String> problems) { }

    @FunctionalInterface
    public interface ChunkVisitor {
        void visit(DecodedChunk chunk);
    }

    /** A decoded chunk with lazily unpacked sections. */
    public static final class DecodedChunk {
        private final int chunkX;
        private final int chunkZ;
        private final Map<Integer, Section> sections;

        DecodedChunk(int chunkX, int chunkZ, Map<Integer, Section> sections) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.sections = sections;
        }

        public int chunkX() { return chunkX; }
        public int chunkZ() { return chunkZ; }
        public int sectionCount() { return sections.size(); }
        public Iterable<Section> sections() { return sections.values(); }

        /** Block id at chunk-local x/z (0..15) and absolute y; "minecraft:air" if no section. */
        public String nameAt(int localX, int y, int localZ) {
            if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15) {
                return null; // outside this chunk: unknown
            }
            Section s = sections.get(Math.floorDiv(y, 16));
            if (s == null) {
                return "minecraft:air";
            }
            return s.nameAt(localX, Math.floorMod(y, 16), localZ);
        }
    }

    public static final class Section {
        private final int sectionY;
        private final List<String> names;
        private final List<Nbt.Compound> properties;
        private final long[] data;
        private short[] indices;

        Section(int sectionY, List<String> names, List<Nbt.Compound> properties, long[] data) {
            this.sectionY = sectionY;
            this.names = names;
            this.properties = properties;
            this.data = data;
        }

        public int sectionY() { return sectionY; }
        public List<String> palette() { return names; }

        public boolean paletteContains(Predicate<String> filter) {
            for (String n : names) {
                if (filter.test(n)) return true;
            }
            return false;
        }

        /** Palette index at local coordinates (0..15). */
        public int indexAt(int x, int y, int z) {
            if (names.size() == 1 || data == null) {
                return 0;
            }
            if (indices == null) {
                indices = unpack(data, names.size());
            }
            return indices[(y << 8) | (z << 4) | x];
        }

        public String nameAt(int x, int y, int z) { return names.get(indexAt(x, y, z)); }

        public Nbt.Compound propertiesAt(int x, int y, int z) { return properties.get(indexAt(x, y, z)); }

        static short[] unpack(long[] data, int paletteSize) {
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
            int perLong = 64 / bits;
            long mask = (1L << bits) - 1;
            short[] out = new short[4096];
            for (int i = 0; i < 4096; i++) {
                int li = i / perLong;
                if (li >= data.length) {
                    break;
                }
                int value = (int) ((data[li] >>> ((i % perLong) * bits)) & mask);
                out[i] = (short) (value < paletteSize ? value : 0);
            }
            return out;
        }
    }

    /**
     * Scans every chunk within {@code radius} chunks of the center that exists in the region
     * files of {@code regionDir}. Sections whose palette contains nothing interesting are not
     * unpacked.
     */
    public Stats scan(Path regionDir, int centerCx, int centerCz, int radius, Predicate<String> interesting,
                      ChunkVisitor visitor, BooleanSupplier cancelled) {
        List<String> problems = new ArrayList<>();
        int regions = 0, decoded = 0, skipped = 0;
        int minRx = Math.floorDiv(centerCx - radius, 32), maxRx = Math.floorDiv(centerCx + radius, 32);
        int minRz = Math.floorDiv(centerCz - radius, 32), maxRz = Math.floorDiv(centerCz + radius, 32);
        for (int rx = minRx; rx <= maxRx; rx++) {
            for (int rz = minRz; rz <= maxRz; rz++) {
                Path file = regionDir.resolve("r." + rx + "." + rz + ".mca");
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                regions++;
                try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                    if (raf.length() < SECTOR * 2L) {
                        continue;
                    }
                    int[] locations = new int[1024];
                    for (int i = 0; i < 1024; i++) {
                        locations[i] = raf.readInt();
                    }
                    for (int i = 0; i < 1024; i++) {
                        if (cancelled.getAsBoolean()) {
                            return new Stats(regions, decoded, skipped, problems);
                        }
                        int loc = locations[i];
                        if (loc == 0) {
                            continue;
                        }
                        int cx = rx * 32 + (i & 31);
                        int cz = rz * 32 + (i >> 5);
                        if (Math.abs(cx - centerCx) > radius || Math.abs(cz - centerCz) > radius) {
                            continue;
                        }
                        long offset = (long) (loc >>> 8) * SECTOR;
                        if (offset + 5 > raf.length()) {
                            skipped++;
                            continue;
                        }
                        raf.seek(offset);
                        int length = raf.readInt();
                        int compression = raf.readUnsignedByte();
                        if (length <= 1 || offset + 4 + length > raf.length() || length > 16 * 1024 * 1024) {
                            skipped++;
                            continue;
                        }
                        if ((compression & 0x80) != 0 || (compression != 1 && compression != 2 && compression != 3)) {
                            skipped++; // external .mcc or LZ4/custom compression: not supported
                            continue;
                        }
                        byte[] payload = new byte[length - 1];
                        raf.readFully(payload);
                        try {
                            DecodedChunk chunk = decode(cx, cz, payload, compression, interesting);
                            if (chunk != null) {
                                decoded++;
                                visitor.visit(chunk);
                            }
                        } catch (IOException | RuntimeException e) {
                            skipped++;
                            if (problems.size() < 20) {
                                problems.add("chunk " + cx + "," + cz + ": " + e.getMessage());
                            }
                        }
                    }
                } catch (IOException e) {
                    problems.add(file.getFileName() + ": " + e.getMessage());
                }
            }
        }
        return new Stats(regions, decoded, skipped, problems);
    }

    static DecodedChunk decode(int cx, int cz, byte[] payload, int compression, Predicate<String> interesting) throws IOException {
        InputStream in = new ByteArrayInputStream(payload);
        in = switch (compression) {
            case 1 -> new GZIPInputStream(in);
            case 2 -> new InflaterInputStream(in);
            default -> in;
        };
        Nbt.Named named = Nbt.read(new DataInputStream(in));
        Nbt.Compound root = named.tag();
        Nbt.ListTag sections = root.getList("sections");
        if (sections == null) {
            return null; // pre-1.18 format or proto-chunk without sections
        }
        Map<Integer, Section> out = new HashMap<>();
        for (int i = 0; i < sections.size(); i++) {
            Nbt.Compound sec = sections.compound(i);
            Object yObj = sec.get("Y");
            if (!(yObj instanceof Number yNum)) {
                continue;
            }
            Nbt.Compound states = sec.getCompound("block_states");
            if (states == null) {
                continue;
            }
            Nbt.ListTag palette = states.getList("palette");
            if (palette == null || palette.isEmpty()) {
                continue;
            }
            List<String> names = new ArrayList<>(palette.size());
            List<Nbt.Compound> props = new ArrayList<>(palette.size());
            for (int p = 0; p < palette.size(); p++) {
                Nbt.Compound entry = palette.compound(p);
                names.add(entry.getString("Name", "minecraft:air"));
                Nbt.Compound pr = entry.getCompound("Properties");
                props.add(pr == null ? new Nbt.Compound() : pr);
            }
            out.put(yNum.intValue(), new Section(yNum.intValue(), names, props, states.getLongArray("data")));
        }
        return new DecodedChunk(cx, cz, out);
    }

    /** Region directory for a dimension inside a world save folder. */
    public static Path regionDir(Path worldDir, String dimensionId) {
        return switch (dimensionId) {
            case "minecraft:the_nether" -> worldDir.resolve("DIM-1").resolve("region");
            case "minecraft:the_end" -> worldDir.resolve("DIM1").resolve("region");
            case "minecraft:overworld" -> worldDir.resolve("region");
            default -> {
                int colon = dimensionId.indexOf(':');
                String ns = colon < 0 ? "minecraft" : dimensionId.substring(0, colon);
                String path = colon < 0 ? dimensionId : dimensionId.substring(colon + 1);
                yield worldDir.resolve("dimensions").resolve(ns).resolve(path).resolve("region");
            }
        };
    }
}
