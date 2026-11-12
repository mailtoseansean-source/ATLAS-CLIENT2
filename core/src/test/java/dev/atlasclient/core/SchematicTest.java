package dev.atlasclient.core;

import dev.atlasclient.core.nbt.Nbt;
import dev.atlasclient.core.schematic.BlockStateString;
import dev.atlasclient.core.schematic.LitematicIO;
import dev.atlasclient.core.schematic.LitematicaPlacementImporter;
import dev.atlasclient.core.schematic.Placement;
import dev.atlasclient.core.schematic.Schematic;
import dev.atlasclient.core.schematic.SchematicLoader;
import dev.atlasclient.core.schematic.SchematicPlacer;
import dev.atlasclient.core.schematic.SchematicPlacer.PlacedBlock;
import dev.atlasclient.core.schematic.SchematicRegion;
import dev.atlasclient.core.schematic.SpongeSchematicIO;
import dev.atlasclient.core.schematic.transform.Mirror;
import dev.atlasclient.core.schematic.transform.Rotation;
import dev.atlasclient.core.schematic.transform.Transforms;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.Assert.*;

/** Requirement 8 (schematic parsing) plus Litematica format and coordinate compatibility. */
public class SchematicTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    static Schematic randomSchematic(int paletteSize, int sx, int sy, int sz, long seed) {
        List<BlockStateString> palette = new ArrayList<>();
        palette.add(BlockStateString.AIR);
        for (int i = 1; i < paletteSize; i++) {
            palette.add(BlockStateString.parse("minecraft:oak_stairs[facing=" + new String[]{"north", "east", "south", "west"}[i % 4]
                    + ",half=bottom,shape=straight,waterlogged=false,variant=" + i + "]"));
        }
        Random r = new Random(seed);
        int[] blocks = new int[Math.abs(sx * sy * sz)];
        for (int i = 0; i < blocks.length; i++) blocks[i] = r.nextInt(paletteSize);
        return new Schematic("test", "me", "", Schematic.Format.BUILT_IN, 4671,
                List.of(new SchematicRegion("main", 0, 0, 0, sx, sy, sz, palette, blocks)), "");
    }

    @Test
    public void blockStateStringParsing() {
        BlockStateString s = BlockStateString.parse("oak_stairs[half=bottom,facing=north]");
        assertEquals("minecraft:oak_stairs", s.id());
        assertEquals("north", s.property("facing"));
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom]", s.toString());
        assertEquals(s, BlockStateString.parse("minecraft:oak_stairs[facing=north,half=bottom]"));
        assertTrue(BlockStateString.parse("minecraft:cave_air").isAir());
        assertThrows(IllegalArgumentException.class, () -> BlockStateString.parse("stone[facing"));
        assertThrows(IllegalArgumentException.class, () -> BlockStateString.parse("stone[facing=]"));
    }

    @Test
    public void spongeV2AndV3RoundTrip() throws Exception {
        // 200 palette entries forces two-byte varints
        Schematic s = randomSchematic(200, 7, 5, 6, 1);
        for (int version : new int[]{2, 3}) {
            Path f = tmp.getRoot().toPath().resolve("t" + version + ".schem");
            SpongeSchematicIO.write(f, s, version, 4671);
            Schematic back = SchematicLoader.load(f);
            assertEquals(version == 2 ? Schematic.Format.SPONGE_V2 : Schematic.Format.SPONGE_V3, back.format());
            SchematicRegion a = s.regions().get(0), b = back.regions().get(0);
            assertEquals(a.absX(), b.absX());
            assertEquals(a.absY(), b.absY());
            assertEquals(a.absZ(), b.absZ());
            for (int y = 0; y < 5; y++) for (int z = 0; z < 6; z++) for (int x = 0; x < 7; x++) {
                assertEquals(a.stateAt(x, y, z), b.stateAt(x, y, z));
            }
            assertEquals(4671, back.dataVersion());
        }
    }

    @Test
    public void litematicBitArraySpansLongsLikeLitematica() throws Exception {
        // Palette of 5 -> 3 bits per entry; 64 is not a multiple of 3 so entries straddle longs.
        Schematic s = randomSchematic(5, 3, 4, 5, 7);
        Nbt.Compound root = LitematicIO.toNbt(s, 4671, 0);
        long[] packed = root.getCompound("Regions").getCompound("main").getLongArray("BlockStates");
        assertEquals((60 * 3 + 63) / 64, packed.length);
        // Independent check: treat the long array as one little-endian bit string.
        BigInteger bits = BigInteger.ZERO;
        for (int i = packed.length - 1; i >= 0; i--) {
            bits = bits.shiftLeft(64).or(new BigInteger(Long.toUnsignedString(packed[i])));
        }
        int[] raw = s.regions().get(0).rawBlocks();
        for (int i = 0; i < raw.length; i++) {
            int v = bits.shiftRight(i * 3).and(BigInteger.valueOf(7)).intValue();
            assertEquals("entry " + i, raw[i], v);
        }
        assertEquals(3, LitematicIO.bitsFor(5));
        assertEquals(2, LitematicIO.bitsFor(1));
        assertEquals(2, LitematicIO.bitsFor(4));
        assertEquals(9, LitematicIO.bitsFor(300));
    }

    @Test
    public void litematicMultiRegionRoundTripWithNegativeSize() throws Exception {
        List<BlockStateString> p1 = List.of(BlockStateString.AIR, BlockStateString.of("minecraft:stone"),
                BlockStateString.parse("minecraft:oak_slab[type=top,waterlogged=false]"));
        int[] b1 = new int[3 * 2 * 2];
        for (int i = 0; i < b1.length; i++) b1[i] = i % 3;
        List<BlockStateString> p2 = List.of(BlockStateString.of("minecraft:glass"));
        int[] b2 = new int[2];
        Schematic s = new Schematic("multi", "atlas", "two regions", Schematic.Format.BUILT_IN, 4671, List.of(
                new SchematicRegion("base", 0, 0, 0, 3, 2, 2, p1, b1),
                new SchematicRegion("tower", 5, 0, 0, -2, 1, 1, p2, b2)), "");
        Path f = tmp.getRoot().toPath().resolve("multi.litematic");
        LitematicIO.write(f, s, 4671);
        Schematic back = SchematicLoader.load(f);
        assertEquals(Schematic.Format.LITEMATIC, back.format());
        assertEquals(2, back.regions().size());
        SchematicRegion tower = back.region("tower");
        assertEquals(-2, tower.sizeX());
        assertEquals(4, tower.minX());
        assertEquals("top", back.region("base").stateAt(2, 0, 0).property("type"));
        assertEquals("multi", back.name());
        assertEquals(14, back.totalVolume()); // 3*2*2 + 2*1*1
        int[] enc = back.enclosingSize();
        assertArrayEquals(new int[]{6, 2, 2}, enc);
    }

    @Test
    public void rejectsCorruptAndUnsupportedFiles() throws Exception {
        Path garbage = tmp.getRoot().toPath().resolve("bad.schem");
        Files.write(garbage, new byte[]{0x1F, (byte) 0x8B, 1, 2, 3});
        assertThrows(IOException.class, () -> SchematicLoader.load(garbage));
        Path legacy = tmp.getRoot().toPath().resolve("old.litematic");
        Nbt.Compound root = new Nbt.Compound().putInt("Version", 4).putInt("MinecraftDataVersion", 1139)
                .put("Regions", new Nbt.Compound());
        Nbt.writeGzipFile(legacy, "", root);
        IOException e = assertThrows(IOException.class, () -> SchematicLoader.load(legacy));
        assertTrue(e.getMessage().contains("legacy"));
        // Region with palette index out of range
        Nbt.Compound r = new Nbt.Compound();
        Nbt.ListTag pal = new Nbt.ListTag(Nbt.COMPOUND);
        pal.add(new Nbt.Compound().putString("Name", "minecraft:stone"));
        r.put("BlockStatePalette", pal).put("BlockStates", new long[]{-1L})
                .put("Position", new Nbt.Compound().putInt("x", 0).putInt("y", 0).putInt("z", 0))
                .put("Size", new Nbt.Compound().putInt("x", 2).putInt("y", 1).putInt("z", 1));
        Path bad = tmp.getRoot().toPath().resolve("badidx.litematic");
        Nbt.writeGzipFile(bad, "", new Nbt.Compound().putInt("Version", 7).putInt("MinecraftDataVersion", 4671)
                .put("Regions", new Nbt.Compound().put("r", r)));
        assertThrows(IOException.class, () -> SchematicLoader.load(bad));
    }

    @Test
    public void listsSchematicFilesRecursively() throws Exception {
        Path dir = tmp.newFolder("schematics").toPath();
        Files.createDirectories(dir.resolve("farms"));
        Files.writeString(dir.resolve("a.schem"), "");
        Files.writeString(dir.resolve("farms/b.litematic"), "");
        Files.writeString(dir.resolve("notes.txt"), "");
        assertEquals(List.of("a.schem", "farms/b.litematic"), SchematicLoader.list(dir));
    }

    // ------------------------------------------------------- transforms (Litematica-compatible)

    static Schematic line() {
        List<BlockStateString> p = List.of(BlockStateString.of("minecraft:stone"), BlockStateString.of("minecraft:dirt"));
        return new Schematic("line", "", "", Schematic.Format.BUILT_IN, 0,
                List.of(new SchematicRegion("main", 0, 0, 0, 2, 1, 1, p, new int[]{0, 1})), "");
    }

    static PlacedBlock find(List<PlacedBlock> list, String id) {
        return list.stream().filter(b -> b.state().id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    public void rotationMatchesLitematica() {
        Placement p = new Placement("line", "p", 100, 64, 100).setRotation(Rotation.CLOCKWISE_90);
        List<PlacedBlock> out = SchematicPlacer.place(line(), p, false);
        PlacedBlock stone = find(out, "minecraft:stone"), dirt = find(out, "minecraft:dirt");
        assertArrayEquals(new int[]{100, 64, 100}, new int[]{stone.x(), stone.y(), stone.z()});
        assertArrayEquals("(1,0,0) rotated CW90 -> (0,0,1)", new int[]{100, 64, 101}, new int[]{dirt.x(), dirt.y(), dirt.z()});
        assertEquals(Rotation.CLOCKWISE_90, dirt.transform().rotation());
        Placement p180 = new Placement("line", "p", 0, 0, 0).setRotation(Rotation.CLOCKWISE_180);
        assertEquals(-1, find(SchematicPlacer.place(line(), p180, false), "minecraft:dirt").x());
        Placement pccw = new Placement("line", "p", 0, 0, 0).setRotation(Rotation.COUNTERCLOCKWISE_90);
        assertEquals(-1, find(SchematicPlacer.place(line(), pccw, false), "minecraft:dirt").z());
    }

    @Test
    public void mirrorMatchesLitematica() {
        Placement p = new Placement("line", "p", 0, 0, 0).setMirror(Mirror.FRONT_BACK);
        PlacedBlock dirt = find(SchematicPlacer.place(line(), p, false), "minecraft:dirt");
        assertEquals("FRONT_BACK flips X", -1, dirt.x());
        Placement lr = new Placement("line", "p", 0, 0, 0).setMirror(Mirror.LEFT_RIGHT);
        assertEquals("LEFT_RIGHT flips Z only", 1, find(SchematicPlacer.place(line(), lr, false), "minecraft:dirt").x());
        int[] t = Transforms.transform(3, 4, 5, Mirror.FRONT_BACK, Rotation.CLOCKWISE_90);
        assertArrayEquals(new int[]{-5, 4, -3}, t);
        assertArrayEquals(new int[]{3, 4, 5}, Transforms.reverse(t[0], t[1], t[2], Mirror.FRONT_BACK, Rotation.CLOCKWISE_90));
    }

    @Test
    public void subRegionPlacementAndNegativeSizeMath() {
        List<BlockStateString> p = List.of(BlockStateString.of("minecraft:stone"), BlockStateString.of("minecraft:dirt"));
        // Region at pos (5,0,0) with size -2: covers x=4..5, index 0 at x=4.
        Schematic s = new Schematic("neg", "", "", Schematic.Format.BUILT_IN, 0,
                List.of(new SchematicRegion("r", 5, 0, 0, -2, 1, 1, p, new int[]{0, 1})), "");
        List<PlacedBlock> out = SchematicPlacer.place(s, new Placement("neg", "p", 10, 0, 0), false);
        assertEquals(14, find(out, "minecraft:stone").x());
        assertEquals(15, find(out, "minecraft:dirt").x());

        // Main rotation CW90 with region offset (10,0,0): regionPosT = (0,0,10).
        Schematic offset = new Schematic("off", "", "", Schematic.Format.BUILT_IN, 0,
                List.of(new SchematicRegion("r", 10, 0, 0, 2, 1, 1, p, new int[]{0, 1})), "");
        List<PlacedBlock> rot = SchematicPlacer.place(offset, new Placement("off", "p", 0, 0, 0).setRotation(Rotation.CLOCKWISE_90), false);
        PlacedBlock d = find(rot, "minecraft:dirt");
        assertArrayEquals(new int[]{0, 0, 11}, new int[]{d.x(), d.y(), d.z()});

        // Sub-region override: moved to (20,0,0) and rotated CW90 on its own.
        Placement withSub = new Placement("off", "p", 0, 0, 0)
                .putRegion(new Placement.RegionPlacement("r", 20, 0, 0, Rotation.CLOCKWISE_90, Mirror.NONE, true));
        PlacedBlock ds = find(SchematicPlacer.place(offset, withSub, false), "minecraft:dirt");
        assertArrayEquals(new int[]{20, 0, 1}, new int[]{ds.x(), ds.y(), ds.z()});

        // Disabled region is skipped.
        Placement disabled = new Placement("off", "p", 0, 0, 0)
                .putRegion(new Placement.RegionPlacement("r", 10, 0, 0, Rotation.NONE, Mirror.NONE, false));
        assertTrue(SchematicPlacer.place(offset, disabled, false).isEmpty());

        int[] b = SchematicPlacer.bounds(offset, new Placement("off", "p", 0, 0, 0).setRotation(Rotation.CLOCKWISE_90));
        assertArrayEquals(new int[]{0, 0, 10, 0, 0, 11}, b);
    }

    @Test
    public void stateTransformFollowsLitematicaRules() {
        Transforms.StateTransform st = Transforms.StateTransform.of(Mirror.NONE, Rotation.CLOCKWISE_90, Mirror.FRONT_BACK, Rotation.CLOCKWISE_90);
        assertEquals("sub mirror axis swaps under 90 degree main rotation", Mirror.LEFT_RIGHT, st.subMirror());
        assertEquals(Rotation.CLOCKWISE_180, st.rotation());
        assertTrue(Transforms.StateTransform.of(Mirror.NONE, Rotation.NONE, Mirror.NONE, Rotation.NONE).isIdentity());
    }

    @Test
    public void structureVoidAndAirHandling() {
        List<BlockStateString> p = List.of(BlockStateString.AIR, BlockStateString.of("minecraft:structure_void"), BlockStateString.of("minecraft:stone"));
        Schematic s = new Schematic("v", "", "", Schematic.Format.BUILT_IN, 0,
                List.of(new SchematicRegion("r", 0, 0, 0, 3, 1, 1, p, new int[]{0, 1, 2})), "");
        assertEquals(1, SchematicPlacer.place(s, new Placement("v", "p", 0, 0, 0), false).size());
        List<PlacedBlock> withAir = SchematicPlacer.place(s, new Placement("v", "p", 0, 0, 0), true);
        assertEquals("structure void is never a target, air is when requested", 2, withAir.size());
        assertEquals(1, s.totalNonAir());
    }

    @Test
    public void importsLitematicaPlacementJson() throws Exception {
        Path cfg = tmp.newFolder("config", "litematica").toPath();
        Path schem = tmp.getRoot().toPath().resolve("house.litematic");
        LitematicIO.write(schem, line(), 4671);
        String json = "{\"placements\":{\"placements\":[{\"schematic\":" + new com.google.gson.JsonPrimitive(schem.toString())
                + ",\"name\":\"House\",\"origin\":[10,64,-3],\"rotation\":\"CLOCKWISE_90\",\"mirror\":\"LEFT_RIGHT\","
                + "\"enabled\":true,\"placements\":[{\"name\":\"main\",\"placement\":{\"pos\":[0,0,0],\"name\":\"main\","
                + "\"rotation\":\"NONE\",\"mirror\":\"FRONT_BACK\",\"enabled\":true}}]},{\"schematic\":\"/missing.litematic\","
                + "\"name\":\"Gone\",\"origin\":[0,0,0],\"rotation\":\"NONE\",\"mirror\":\"NONE\"},{\"broken\":true}]},"
                + "\"selections\":{}}";
        Files.writeString(cfg.resolve("litematica_MyWorld_dim_minecraft-overworld.json"), json);
        List<Path> files = LitematicaPlacementImporter.findDataFiles(cfg);
        assertEquals(1, files.size());
        List<LitematicaPlacementImporter.Imported> imported = LitematicaPlacementImporter.read(files.get(0));
        assertEquals("malformed entry skipped", 2, imported.size());
        Placement house = imported.get(0).placement();
        assertTrue(imported.get(0).schematicExists());
        assertFalse(imported.get(1).schematicExists());
        assertEquals(10, house.originX());
        assertEquals(-3, house.originZ());
        assertEquals(Rotation.CLOCKWISE_90, house.rotation());
        assertEquals(Mirror.LEFT_RIGHT, house.mirror());
        assertEquals(Mirror.FRONT_BACK, house.regionOverrides().get(0).mirror());
        // Same file + same placement => same world coordinates as computed from Litematica's formula.
        Schematic loaded = SchematicLoader.load(schem);
        List<PlacedBlock> out = SchematicPlacer.place(loaded, house, false);
        PlacedBlock dirt = find(out, "minecraft:dirt");
        // rel (1,0,0): main mirror LEFT_RIGHT (z) -> (1,0,0); main CW90 -> (0,0,1);
        // sub mirror FRONT_BACK (x) -> (0,0,1); sub NONE -> (0,0,1); + origin
        assertArrayEquals(new int[]{10, 64, -2}, new int[]{dirt.x(), dirt.y(), dirt.z()});
        // Our own placement JSON round-trips.
        Placement again = Placement.fromJson(house.toJson());
        assertEquals(house.toJson(), again.toJson());
    }

    @Test
    public void placementJsonPersistsRegionOverrides() {
        Placement p = new Placement("f.litematic", "x", 1, 2, 3).setRotation(Rotation.COUNTERCLOCKWISE_90).setMirror(Mirror.FRONT_BACK)
                .setDimension("minecraft:the_nether")
                .putRegion(new Placement.RegionPlacement("a", 4, 5, 6, Rotation.CLOCKWISE_180, Mirror.NONE, false));
        Placement q = Placement.fromJson(p.toJson());
        assertEquals("minecraft:the_nether", q.dimension());
        assertFalse(q.regionOverrides().get(0).enabled());
        assertEquals(Map.of(), Map.of());
    }
}
