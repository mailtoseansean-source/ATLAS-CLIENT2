package dev.atlasclient.core.samples;

import dev.atlasclient.core.schematic.BlockStateString;
import dev.atlasclient.core.schematic.LitematicIO;
import dev.atlasclient.core.schematic.Schematic;
import dev.atlasclient.core.schematic.SchematicRegion;
import dev.atlasclient.core.schematic.SpongeSchematicIO;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Programmatic sample schematics used by unit tests, the in-game test suite and the shipped
 * {@code schematics/} folder. Built from code so every block state is explicit and reviewable.
 */
public final class SampleSchematics {
    /**
     * Data version written into sample files. 1.21.11 Pre-Release 5 is documented as 4667; the
     * release value 4671 is taken from memory and not independently confirmed here. Litematica and
     * the vanilla DataFixer upgrade older values, so a slightly low number would also be harmless.
     */
    public static final int DATA_VERSION = 4671;

    private SampleSchematics() { }

    /** Mutable helper for one box region. */
    public static final class Box {
        final String name;
        final int px, py, pz, sx, sy, sz;
        final Map<String, Integer> paletteIndex = new LinkedHashMap<>();
        final List<BlockStateString> palette = new ArrayList<>();
        final int[] blocks;

        public Box(String name, int px, int py, int pz, int sx, int sy, int sz) {
            this.name = name;
            this.px = px; this.py = py; this.pz = pz;
            this.sx = sx; this.sy = sy; this.sz = sz;
            this.blocks = new int[Math.abs(sx * sy * sz)];
            id("minecraft:air");
        }

        int id(String state) {
            return paletteIndex.computeIfAbsent(BlockStateString.parse(state).toString(), k -> {
                palette.add(BlockStateString.parse(k));
                return palette.size() - 1;
            });
        }

        /** Coordinates relative to the region's minimum corner. */
        public Box set(int x, int y, int z, String state) {
            int ax = Math.abs(sx), az = Math.abs(sz);
            blocks[(y * az + z) * ax + x] = id(state);
            return this;
        }

        public Box fill(int x0, int y0, int z0, int x1, int y1, int z1, String state) {
            for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) for (int x = x0; x <= x1; x++) set(x, y, z, state);
            return this;
        }

        public SchematicRegion build() { return new SchematicRegion(name, px, py, pz, sx, sy, sz, palette, blocks.clone()); }
    }

    private static Schematic of(String name, String description, Box... boxes) {
        List<SchematicRegion> regions = new ArrayList<>();
        for (Box b : boxes) regions.add(b.build());
        return new Schematic(name, "Atlas Client samples", description, Schematic.Format.BUILT_IN, DATA_VERSION, regions, "");
    }

    /** 7x1x7 stone platform. */
    public static Schematic stonePlatform() {
        return of("stone_platform", "Simplest test: 49 stone blocks", new Box("main", 0, 0, 0, 7, 1, 7).fill(0, 0, 0, 6, 0, 6, "minecraft:stone"));
    }

    /** 7x5x7 house: floor, walls with windows, door, stair/slab roof edge, wall torch, crafting table. */
    public static Schematic smallHouse() {
        Box b = new Box("main", 0, 0, 0, 7, 5, 7);
        b.fill(0, 0, 0, 6, 0, 6, "minecraft:cobblestone");
        for (int y = 1; y <= 3; y++) {
            for (int i = 0; i <= 6; i++) {
                b.set(i, y, 0, "minecraft:oak_planks").set(i, y, 6, "minecraft:oak_planks");
                b.set(0, y, i, "minecraft:oak_planks").set(6, y, i, "minecraft:oak_planks");
            }
        }
        b.set(2, 2, 0, "minecraft:glass_pane[east=false,north=false,south=false,waterlogged=false,west=false]");
        b.set(4, 2, 0, "minecraft:glass_pane[east=false,north=false,south=false,waterlogged=false,west=false]");
        b.set(3, 1, 6, "minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]");
        b.set(3, 2, 6, "minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]");
        b.fill(1, 4, 1, 5, 4, 5, "minecraft:oak_slab[type=bottom,waterlogged=false]");
        for (int i = 0; i <= 6; i++) {
            b.set(i, 4, 0, "minecraft:oak_stairs[facing=south,half=bottom,shape=straight,waterlogged=false]");
            b.set(i, 4, 6, "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]");
        }
        for (int i = 1; i <= 5; i++) {
            b.set(0, 4, i, "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]");
            b.set(6, 4, i, "minecraft:oak_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]");
        }
        b.set(1, 2, 3, "minecraft:wall_torch[facing=east]");
        b.set(5, 1, 1, "minecraft:crafting_table");
        return of("small_house", "Walls, windows, door, stair/slab roof, wall torch", b);
    }

    /** Stairs in four facings and both halves, slabs bottom/top/double. */
    public static Schematic stairsAndSlabs() {
        Box b = new Box("main", 0, 0, 0, 6, 3, 3);
        b.fill(0, 0, 0, 5, 0, 2, "minecraft:stone_bricks");
        String[] f = {"north", "east", "south", "west"};
        for (int i = 0; i < 4; i++) {
            b.set(i, 1, 0, "minecraft:stone_brick_stairs[facing=" + f[i] + ",half=bottom,shape=straight,waterlogged=false]");
            b.set(i, 2, 0, "minecraft:stone_brick_stairs[facing=" + f[i] + ",half=top,shape=straight,waterlogged=false]");
        }
        b.set(0, 1, 2, "minecraft:stone_brick_slab[type=bottom,waterlogged=false]");
        b.set(1, 2, 2, "minecraft:stone_brick_slab[type=top,waterlogged=false]");
        b.set(2, 1, 2, "minecraft:stone_brick_slab[type=double,waterlogged=false]");
        b.set(4, 1, 2, "minecraft:oak_log[axis=x]");
        b.set(5, 1, 2, "minecraft:oak_log[axis=z]");
        return of("stairs_and_slabs", "Orientation test: stairs, slabs, logs", b);
    }

    /** Lever -> wire -> repeater (delay 3) -> wire -> lamp, plus comparator in subtract mode. */
    public static Schematic redstoneMechanism() {
        Box b = new Box("main", 0, 0, 0, 7, 2, 3);
        b.fill(0, 0, 0, 6, 0, 2, "minecraft:smooth_stone");
        b.set(0, 1, 1, "minecraft:lever[face=floor,facing=east,powered=false]");
        b.set(1, 1, 1, "minecraft:redstone_wire[east=side,north=none,power=0,south=none,west=side]");
        b.set(2, 1, 1, "minecraft:repeater[delay=3,facing=west,locked=false,powered=false]");
        b.set(3, 1, 1, "minecraft:redstone_wire[east=side,north=none,power=0,south=none,west=side]");
        b.set(4, 1, 1, "minecraft:redstone_lamp[lit=false]");
        b.set(5, 1, 2, "minecraft:comparator[facing=north,mode=subtract,powered=false]");
        b.set(6, 1, 0, "minecraft:observer[facing=up,powered=false]");
        return of("redstone_mechanism", "Repeater delay, comparator mode and lever are set by right-clicking after placement", b);
    }

    /** Two-region .litematic: a base and a tower region with a negative Y size (grows downward from its corner). */
    public static Schematic multiRegion() {
        Box base = new Box("base", 0, 0, 0, 5, 1, 5).fill(0, 0, 0, 4, 0, 4, "minecraft:polished_andesite");
        Box tower = new Box("tower", 2, 4, 2, 1, -4, 1).fill(0, 0, 0, 0, 3, 0, "minecraft:glass");
        Box annex = new Box("annex", 8, 0, 4, -3, 1, -1).fill(0, 0, 0, 2, 0, 0, "minecraft:bricks");
        return of("multi_region", "Three regions incl. negative sizes", base, tower, annex);
    }

    /** Storage room: stone brick shell, chests, barrels, hopper, lantern. */
    public static Schematic storageRoom() {
        Box b = new Box("main", 0, 0, 0, 5, 4, 5);
        b.fill(0, 0, 0, 4, 0, 4, "minecraft:stone_bricks");
        for (int y = 1; y <= 3; y++) {
            for (int i = 0; i <= 4; i++) {
                b.set(i, y, 0, "minecraft:stone_bricks").set(i, y, 4, "minecraft:stone_bricks");
                b.set(0, y, i, "minecraft:stone_bricks").set(4, y, i, "minecraft:stone_bricks");
            }
        }
        b.set(2, 1, 4, "minecraft:air").set(2, 2, 4, "minecraft:air");
        b.set(1, 1, 1, "minecraft:chest[facing=south,type=single,waterlogged=false]");
        b.set(3, 1, 1, "minecraft:chest[facing=south,type=single,waterlogged=false]");
        b.set(1, 1, 3, "minecraft:barrel[facing=up,open=false]");
        b.set(3, 1, 3, "minecraft:barrel[facing=up,open=false]");
        b.set(2, 1, 1, "minecraft:hopper[enabled=true,facing=down]");
        b.set(2, 3, 2, "minecraft:lantern[hanging=false,waterlogged=false]");
        b.set(2, 2, 2, "minecraft:stone_bricks");
        return of("storage_room", "Containers and attached blocks", b);
    }

    /** Basic crop farm frame: tillable dirt rows, fences, torches, composter (till and plant manually). */
    public static Schematic cropFarm() {
        Box b = new Box("main", 0, 0, 0, 7, 2, 7);
        b.fill(1, 0, 1, 5, 0, 5, "minecraft:dirt");
        for (int i = 0; i <= 6; i++) {
            b.set(i, 0, 0, "minecraft:oak_planks").set(i, 0, 6, "minecraft:oak_planks");
            b.set(0, 0, i, "minecraft:oak_planks").set(6, 0, i, "minecraft:oak_planks");
            b.set(i, 1, 0, "minecraft:oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]");
            b.set(i, 1, 6, "minecraft:oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]");
        }
        b.set(0, 1, 3, "minecraft:torch").set(6, 1, 3, "minecraft:torch");
        b.set(3, 1, 3, "minecraft:composter[level=0]");
        return of("crop_farm", "Farm frame; farmland and crops are manual steps (hoe + seeds)", b);
    }

    /** Simple automated cactus farm: sand pillar, cactus, fence breaker, hoppers into a chest. */
    public static Schematic cactusFarm() {
        Box b = new Box("main", 0, 0, 0, 3, 5, 3);
        b.set(1, 0, 1, "minecraft:chest[facing=north,type=single,waterlogged=false]");
        b.set(1, 1, 0, "minecraft:hopper[enabled=true,facing=south]");
        b.set(0, 1, 1, "minecraft:hopper[enabled=true,facing=east]");
        b.set(2, 1, 1, "minecraft:hopper[enabled=true,facing=west]");
        b.set(1, 1, 2, "minecraft:hopper[enabled=true,facing=north]");
        b.set(1, 1, 1, "minecraft:stone");
        b.set(1, 2, 1, "minecraft:sand");
        b.set(1, 3, 1, "minecraft:cactus[age=0]");
        b.set(0, 4, 0, "minecraft:oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]");
        b.set(0, 3, 0, "minecraft:stone");
        b.set(0, 2, 0, "minecraft:stone");
        b.set(0, 1, 0, "minecraft:stone");
        b.set(0, 0, 0, "minecraft:stone");
        return of("cactus_farm", "Cactus grows into a fence corner, drops fall into hoppers feeding a chest", b);
    }

    /** Underground room for the excavation test: stone floor, everything above is air. */
    public static Schematic undergroundRoom() {
        Box b = new Box("main", 0, 0, 0, 5, 4, 5);
        b.fill(0, 0, 0, 4, 0, 4, "minecraft:stone_bricks");
        b.set(2, 1, 2, "minecraft:crafting_table");
        return of("underground_room", "Excavate first: clears a 5x3x5 room above a stone brick floor", b);
    }

    public record Sample(String file, Schematic schematic) { }

    public static List<Sample> all() {
        return List.of(
                new Sample("stone_platform.schem", stonePlatform()),
                new Sample("small_house.litematic", smallHouse()),
                new Sample("stairs_and_slabs.schem", stairsAndSlabs()),
                new Sample("redstone_mechanism.litematic", redstoneMechanism()),
                new Sample("multi_region.litematic", multiRegion()),
                new Sample("storage_room.schem", storageRoom()),
                new Sample("crop_farm.schem", cropFarm()),
                new Sample("cactus_farm.litematic", cactusFarm()),
                new Sample("underground_room.litematic", undergroundRoom()));
    }

    /** Writes every sample into a directory (.schem as Sponge v3, .litematic as version 7). */
    public static List<Path> writeAll(Path dir) throws IOException {
        Files.createDirectories(dir);
        List<Path> out = new ArrayList<>();
        for (Sample s : all()) {
            Path f = dir.resolve(s.file());
            if (s.file().endsWith(".schem")) {
                SpongeSchematicIO.write(f, s.schematic(), 3, DATA_VERSION);
            } else {
                LitematicIO.write(f, s.schematic(), DATA_VERSION);
            }
            out.add(f);
        }
        return out;
    }

    /** Generates the repository's sample folder: {@code java ... SampleSchematics <dir>}. */
    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args.length > 0 ? args[0] : "schematics");
        for (Path p : writeAll(dir)) {
            System.out.println("wrote " + p);
        }
    }
}
