package dev.atlasclient.core;

import dev.atlasclient.core.analysis.BlockHit;
import dev.atlasclient.core.build.AreaMining;
import dev.atlasclient.core.build.BlockTraits.PlacementClass;
import dev.atlasclient.core.build.BuildJob;
import dev.atlasclient.core.build.BuildSession;
import dev.atlasclient.core.build.DefaultBlockTraits;
import dev.atlasclient.core.build.ExcavationPlanner;
import dev.atlasclient.core.build.ExcavationSession;
import dev.atlasclient.core.build.MaterialList;
import dev.atlasclient.core.build.PlacementPlanner;
import dev.atlasclient.core.build.WorldProbe;
import dev.atlasclient.core.schematic.BlockStateString;
import dev.atlasclient.core.schematic.Placement;
import dev.atlasclient.core.schematic.Schematic;
import dev.atlasclient.core.schematic.SchematicPlacer;
import dev.atlasclient.core.schematic.SchematicPlacer.PlacedBlock;
import dev.atlasclient.core.schematic.SchematicRegion;
import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Requirements 9 (placement planning) and 10 (progress and cancellation), plus excavation and the
 * Amethyst (3x3) pickaxe safety logic. The simulated world below stands in for the game.
 */
public class BuilderTest {
    private static final DefaultBlockTraits TRAITS = new DefaultBlockTraits();

    /** In-memory world: position -> state. Missing = air. */
    static final class FakeWorld implements WorldProbe, ExcavationPlanner.Probe {
        final Map<Long, BlockStateString> blocks = new HashMap<>();
        final Set<Long> fluids = new HashSet<>();

        void set(int x, int y, int z, String state) { blocks.put(BlockHit.pack(x, y, z), BlockStateString.parse(state)); }
        BlockStateString get(int x, int y, int z) { return blocks.getOrDefault(BlockHit.pack(x, y, z), BlockStateString.AIR); }

        @Override public Status status(PlacedBlock t) {
            BlockStateString s = get(t.x(), t.y(), t.z());
            if (s.isAir()) return Status.EMPTY;
            if (s.equals(t.state())) return Status.MATCHES;
            if (s.id().equals(t.state().id())) return Status.NEEDS_ADJUSTMENT;
            return Status.WRONG_BLOCK;
        }

        @Override public boolean isSolid(int x, int y, int z) { return !get(x, y, z).isAir(); }

        @Override public Cell classify(PlacedBlock t) {
            if (fluids.contains(BlockHit.pack(t.x(), t.y(), t.z()))) return Cell.FLUID;
            BlockStateString s = get(t.x(), t.y(), t.z());
            if (s.isAir()) return Cell.EMPTY;
            if (s.equals(t.state())) return Cell.KEEP;
            if (s.id().equals("minecraft:bedrock")) return Cell.UNBREAKABLE;
            return Cell.REMOVE;
        }

        @Override public boolean neighbourHasFluid(int x, int y, int z) {
            int[][] d = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            for (int[] o : d) if (fluids.contains(BlockHit.pack(x + o[0], y + o[1], z + o[2]))) return true;
            return false;
        }
    }

    /** 3x3 floor of stone with a torch and a sand block on top, plus a door. */
    static Schematic smallHouse() {
        List<BlockStateString> p = List.of(BlockStateString.AIR, BlockStateString.of("minecraft:stone"),
                BlockStateString.parse("minecraft:wall_torch[facing=north]"), BlockStateString.of("minecraft:sand"),
                BlockStateString.parse("minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]"),
                BlockStateString.parse("minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]"),
                BlockStateString.parse("minecraft:repeater[delay=3,facing=north,locked=false,powered=false]"));
        int sx = 3, sy = 3, sz = 3;
        int[] b = new int[sx * sy * sz];
        for (int z = 0; z < 3; z++) for (int x = 0; x < 3; x++) b[(0 * sz + z) * sx + x] = 1; // floor y=0
        b[(1 * sz + 0) * sx + 0] = 2; // torch at y=1
        b[(1 * sz + 1) * sx + 1] = 3; // sand at y=1 on stone
        b[(1 * sz + 2) * sx + 2] = 4; // door lower
        b[(2 * sz + 2) * sx + 2] = 5; // door upper
        b[(1 * sz + 0) * sx + 2] = 6; // repeater
        return new Schematic("house", "", "", Schematic.Format.BUILT_IN, 0,
                List.of(new SchematicRegion("main", 0, 0, 0, sx, sy, sz, p, b)), "");
    }

    static PlacementPlanner.Plan plan(FakeWorld w) {
        return PlacementPlanner.plan(SchematicPlacer.place(smallHouse(), new Placement("h", "h", 0, 64, 0), false), TRAITS, w);
    }

    @Test
    public void classifiesBlocks() {
        assertEquals(PlacementClass.GRAVITY, TRAITS.classify(BlockStateString.of("minecraft:sand")));
        assertEquals(PlacementClass.GRAVITY, TRAITS.classify(BlockStateString.of("minecraft:red_concrete_powder")));
        assertEquals(PlacementClass.ATTACHED, TRAITS.classify(BlockStateString.of("minecraft:redstone_wire")));
        assertEquals(PlacementClass.SOLID, TRAITS.classify(BlockStateString.of("minecraft:crimson_stem")));
        assertEquals(PlacementClass.AUTO_PART, TRAITS.classify(BlockStateString.parse("minecraft:red_bed[part=head,facing=north,occupied=false]")));
        assertEquals(PlacementClass.UNPLACEABLE, TRAITS.classify(BlockStateString.of("minecraft:water")));
        assertEquals("minecraft:redstone", TRAITS.requiredItem(BlockStateString.of("minecraft:redstone_wire")));
        assertEquals("minecraft:oak_sign", TRAITS.requiredItem(BlockStateString.of("minecraft:oak_wall_sign")));
        assertEquals(2, TRAITS.itemCount(BlockStateString.parse("minecraft:oak_slab[type=double,waterlogged=false]")));
        assertEquals(2, TRAITS.extraInteractions(BlockStateString.parse("minecraft:repeater[delay=3,facing=north,locked=false,powered=false]")));
        assertEquals(1, TRAITS.extraInteractions(BlockStateString.parse("minecraft:comparator[facing=north,mode=subtract,powered=false]")));
    }

    @Test
    public void planOrdersSupportsFirst() {
        FakeWorld w = new FakeWorld();
        PlacementPlanner.Plan p = plan(w);
        assertEquals("door upper half is automatic", 1, p.autoParts());
        assertEquals(9 + 4, p.entries().size());
        int lastFloor = -1, firstAbove = Integer.MAX_VALUE, sandIdx = -1, torchIdx = -1;
        for (PlacementPlanner.Entry e : p.entries()) {
            if (e.target().y() == 64) lastFloor = Math.max(lastFloor, e.index());
            else firstAbove = Math.min(firstAbove, e.index());
            if (e.target().state().id().equals("minecraft:sand")) sandIdx = e.index();
            if (e.target().state().id().equals("minecraft:wall_torch")) torchIdx = e.index();
        }
        assertTrue("whole floor before anything above", lastFloor < firstAbove);
        assertTrue("gravity before attached in same layer", sandIdx < torchIdx);
        PlacementPlanner.Entry sand = p.entries().get(sandIdx);
        assertEquals(PlacementPlanner.Support.PLAN, sand.support());
        assertEquals(0, p.missingSupport());
    }

    @Test
    public void detectsMissingSupportAndExistingBlocks() {
        FakeWorld w = new FakeWorld();
        w.set(0, 64, 0, "minecraft:stone");      // already correct
        w.set(1, 64, 0, "minecraft:dirt");       // wrong block
        List<PlacedBlock> floating = List.of(new PlacedBlock(50, 100, 50, BlockStateString.of("minecraft:sand"),
                null, "main"));
        PlacementPlanner.Plan f = PlacementPlanner.plan(floating, TRAITS, w);
        assertEquals(PlacementPlanner.Support.MISSING, f.entries().get(0).support());
        assertEquals(1, f.missingSupport());
        PlacementPlanner.Plan p = plan(w);
        assertEquals(1, p.alreadyCorrect());
        assertEquals(1, p.wrongBlocks());
    }

    @Test
    public void materialListComparesInventory() {
        PlacementPlanner.Plan p = plan(new FakeWorld());
        MaterialList m = MaterialList.compute(p.entries(), Map.of("minecraft:stone", 5, "minecraft:torch", 64), false);
        MaterialList.Line stone = m.lines().stream().filter(l -> l.item().equals("minecraft:stone")).findFirst().orElseThrow();
        assertEquals(9, stone.required());
        assertEquals(4, stone.missing());
        assertTrue(m.lines().stream().anyMatch(l -> l.item().equals("minecraft:torch") && l.satisfied()));
        assertEquals("missing sorted first", "minecraft:stone", m.lines().get(0).item());
        assertFalse(m.complete());
        assertTrue(MaterialList.compute(p.entries(), Map.of(), true).complete());
    }

    /** Simulates the executor: place everything in reach, then verify against the fake world. */
    static void simulate(BuildSession s, FakeWorld w, Set<String> materials, int ticks) {
        for (long tick = 0; tick < ticks && s.state() == BuildSession.State.RUNNING; tick++) {
            for (int i : s.nextTargets(1, 65, 1, 6, 4, e -> e.item() == null || materials.contains(e.item()), e -> true)) {
                PlacementPlanner.Entry e = s.entries().get(i);
                if (s.entryState(i) == BuildSession.EntryState.NEEDS_ADJUST) {
                    w.set(e.target().x(), e.target().y(), e.target().z(), e.target().state().toString());
                    s.markAdjustmentSent(i, tick);
                } else {
                    // Repeaters arrive with delay=1 and need adjusting, like in game.
                    String placed = e.target().state().id().equals("minecraft:repeater")
                            ? e.target().state().with("delay", "1").toString() : e.target().state().toString();
                    w.set(e.target().x(), e.target().y(), e.target().z(), placed);
                    s.markSent(i, tick);
                }
            }
            for (int i : s.awaitingVerification()) {
                s.verify(i, w.status(s.entries().get(i).target()), tick);
            }
        }
    }

    @Test
    public void buildRunsToVerifiedCompletion() {
        FakeWorld w = new FakeWorld();
        BuildSession s = new BuildSession(plan(w));
        assertTrue(s.start());
        simulate(s, w, Set.of("minecraft:stone", "minecraft:torch", "minecraft:sand", "minecraft:oak_door", "minecraft:repeater"), 50);
        assertEquals(BuildSession.State.COMPLETED, s.state());
        BuildSession.Progress p = s.progress();
        assertEquals(p.total(), p.done());
        assertEquals(1.0, p.fraction(), 1e-9);
        assertEquals("delay=3", "3", w.get(2, 65, 0).property("delay"));
    }

    @Test
    public void missingMaterialsLeaveEntriesPending() {
        FakeWorld w = new FakeWorld();
        BuildSession s = new BuildSession(plan(w));
        s.start();
        simulate(s, w, Set.of("minecraft:stone"), 50);
        assertEquals(BuildSession.State.RUNNING, s.state());
        assertEquals(9, s.progress().done());
        assertEquals(4, s.progress().remaining());
    }

    @Test
    public void pauseResumeCancelRestart() {
        FakeWorld w = new FakeWorld();
        BuildSession s = new BuildSession(plan(w));
        assertFalse("cannot pause before start", s.pause());
        s.start();
        assertTrue(s.pause());
        assertTrue("paused sessions offer no targets", s.nextTargets(1, 65, 1, 6, 4, e -> true, e -> true).isEmpty());
        assertTrue(s.resume());
        assertFalse(s.nextTargets(1, 65, 1, 6, 4, e -> true, e -> true).isEmpty());
        assertTrue(s.cancel());
        assertEquals(BuildSession.State.CANCELLED, s.state());
        assertFalse(s.cancel());
        s.restart();
        assertEquals(BuildSession.State.READY, s.state());
        assertEquals(s.progress().total(), s.progress().remaining());
    }

    @Test
    public void unconfirmedPlacementsRetryThenFail() {
        FakeWorld w = new FakeWorld();
        BuildSession s = new BuildSession(plan(w));
        s.setMaxAttempts(2);
        s.setVerifyTimeoutTicks(5);
        s.start();
        int i = s.nextTargets(1, 65, 1, 6, 1, e -> true, e -> true).get(0);
        s.markSent(i, 0);
        assertEquals(BuildSession.EntryState.AWAITING_VERIFY, s.verify(i, WorldProbe.Status.EMPTY, 2));
        assertEquals(BuildSession.EntryState.PENDING, s.verify(i, WorldProbe.Status.EMPTY, 10));
        s.markSent(i, 10);
        assertEquals(BuildSession.EntryState.FAILED, s.verify(i, WorldProbe.Status.EMPTY, 20));
        assertTrue(s.lastFailure().contains("2 attempts"));
        assertEquals(1, s.retrySkipped());
    }

    @Test
    public void detectsIncorrectAndTamperedBlocks() {
        FakeWorld w = new FakeWorld();
        BuildSession s = new BuildSession(plan(w));
        s.start();
        int i = s.nextTargets(1, 65, 1, 6, 1, e -> true, e -> true).get(0);
        s.markSent(i, 0);
        assertEquals(BuildSession.EntryState.INCORRECT, s.verify(i, WorldProbe.Status.WRONG_BLOCK, 1));
        assertEquals(1, s.progress().incorrect());
        s.recheck(i, WorldProbe.Status.EMPTY);
        assertEquals("removed block re-opens the entry", BuildSession.EntryState.PENDING, s.entryState(i));
    }

    // ------------------------------------------------------------------ excavation

    @Test
    public void excavationPlansTopDownAndSkipsHazards() {
        FakeWorld w = new FakeWorld();
        // 3x3x3 underground room: everything is stone, schematic wants air inside and stone floor.
        for (int x = 0; x < 3; x++) for (int y = 0; y < 3; y++) for (int z = 0; z < 3; z++) w.set(x, 60 + y, z, "minecraft:stone");
        w.set(2, 62, 2, "minecraft:bedrock");
        w.fluids.add(BlockHit.pack(3, 61, 0)); // lava just outside the wall next to (2,61,0)
        List<BlockStateString> p = List.of(BlockStateString.AIR, BlockStateString.of("minecraft:stone"));
        int[] b = new int[27];
        for (int z = 0; z < 3; z++) for (int x = 0; x < 3; x++) b[z * 3 + x] = 1; // floor stays stone
        Schematic room = new Schematic("room", "", "", Schematic.Format.BUILT_IN, 0,
                List.of(new SchematicRegion("main", 0, 0, 0, 3, 3, 3, p, b)), "");
        List<PlacedBlock> all = SchematicPlacer.place(room, new Placement("room", "r", 0, 60, 0), true);
        ExcavationPlanner.Plan plan = ExcavationPlanner.plan(all, w, true);
        assertEquals(1, plan.unbreakable());
        assertEquals(1, plan.fluidNeighbours());
        assertEquals("18 cells to clear minus bedrock and lava-adjacent", 16, plan.toRemove());
        assertEquals("highest layer first", 62, plan.entries().get(0).y());
        assertTrue(plan.entries().stream().noneMatch(e -> e.y() == 60));

        ExcavationSession s = new ExcavationSession(plan);
        int guard = 0;
        // Player stands on the room's top; when only the block under them is left they step into the room.
        double px = 1.5, py = 63, pz = 1.5;
        while (!s.finished() && guard++ < 200) {
            int i = s.nextTarget(px, py, pz, 6);
            if (i < 0) {
                int pending = s.nearestPending(px, py, pz);
                assertTrue("only the standing block remains", pending >= 0);
                px = 0.5; py = 61; pz = 0.5;
                continue;
            }
            ExcavationPlanner.Entry e = s.entries().get(i);
            s.markStarted(i, guard);
            w.blocks.remove(BlockHit.pack(e.x(), e.y(), e.z()));
            s.update(i, true, false, guard);
        }
        assertTrue(s.finished());
        assertEquals(16, s.progress().done());
        assertEquals(2, s.progress().skipped());
        assertEquals("floor kept", "minecraft:stone", w.get(1, 60, 1).id());
    }

    @Test
    public void excavationNeverSelectsBlockUnderPlayer() {
        FakeWorld w = new FakeWorld();
        w.set(0, 64, 0, "minecraft:dirt");
        List<PlacedBlock> t = List.of(new PlacedBlock(0, 64, 0, BlockStateString.AIR, null, "m"));
        ExcavationSession s = new ExcavationSession(ExcavationPlanner.plan(t, w, true));
        assertEquals(-1, s.nextTarget(0.5, 65.0, 0.5, 6));
        assertEquals(0, s.nextTarget(1.5, 65.0, 0.5, 6));
    }

    @Test
    public void buildJobRunsExcavationThenBuilding() {
        FakeWorld w = new FakeWorld();
        w.set(5, 64, 5, "minecraft:dirt");
        List<PlacedBlock> t = List.of(new PlacedBlock(5, 64, 5, BlockStateString.of("minecraft:stone"), null, "m"));
        ExcavationSession ex = new ExcavationSession(ExcavationPlanner.plan(t, w, true));
        BuildSession bs = new BuildSession(PlacementPlanner.plan(t, TRAITS, w));
        BuildJob job = new BuildJob(ex, bs, w);
        assertEquals(BuildJob.Phase.EXCAVATING, job.phase());
        job.start();
        job.pause();
        assertTrue(job.summary().startsWith("Paused"));
        job.resume();
        ex.markStarted(0, 1);
        w.blocks.remove(BlockHit.pack(5, 64, 5));
        ex.update(0, true, false, 2);
        job.advance();
        assertEquals(BuildJob.Phase.BUILDING, job.phase());
        assertEquals(BuildSession.State.RUNNING, bs.state());
        job.cancel();
        assertEquals("Cancelled", job.summary());
    }

    // ------------------------------------------------------------------ Amethyst pickaxe (3x3)

    @Test
    public void areaPlaneOrientationFollowsClickedFace() {
        List<int[]> top = AreaMining.plane(0, 10, 0, 1, 1);
        assertEquals(9, top.size());
        assertTrue("top face clears a horizontal layer", top.stream().allMatch(c -> c[1] == 10));
        List<int[]> north = AreaMining.plane(0, 10, 0, 2, 1);
        assertTrue("north face clears an X/Y wall", north.stream().allMatch(c -> c[2] == 0));
        List<int[]> east = AreaMining.plane(0, 10, 0, 5, 1);
        assertTrue("east face clears a Y/Z wall", east.stream().allMatch(c -> c[0] == 0));
    }

    @Test
    public void amethystSwingOnlyWhenWholePlaneIsSafe() {
        Set<Long> dig = new HashSet<>();
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) dig.add(BlockHit.pack(x, 10, z));
        java.util.function.Predicate<int[]> allowed = c -> dig.contains(BlockHit.pack(c[0], c[1], c[2]));
        java.util.function.Predicate<int[]> noFluid = c -> false;
        assertTrue("center of the area", AreaMining.planeIsSafe(2, 10, 2, 1, 1, allowed, noFluid));
        assertFalse("edge would break outside the area", AreaMining.planeIsSafe(0, 10, 2, 1, 1, allowed, noFluid));
        assertFalse("fluid next to a plane cell", AreaMining.planeIsSafe(2, 10, 2, 1, 1, allowed, c -> c[0] == 3));
        int face = AreaMining.chooseFace(2, 10, 2, 2.5, 12.6, 2.5, true, 1, f -> f == 1, allowed, noFluid);
        assertEquals(1, face);
        assertEquals(-1, AreaMining.chooseFace(0, 10, 2, 0.5, 12.6, 2.5, true, 1, f -> f == 1, allowed, noFluid));
        assertEquals("regular tool may use the same face", 1,
                AreaMining.chooseFace(0, 10, 2, 0.5, 12.6, 2.5, false, 1, f -> f == 1, allowed, noFluid));
    }

    @Test
    public void pickaxeModeToggleSelectsTools() {
        AreaMining.Tool amethyst = new AreaMining.Tool(0, true, 9, 1500);
        AreaMining.Tool diamond = new AreaMining.Tool(1, false, 8, 1500);
        AreaMining.Tool worn = new AreaMining.Tool(2, false, 9, 3);
        List<AreaMining.Tool> tools = List.of(amethyst, diamond, worn);
        assertSame(diamond, AreaMining.chooseTool(AreaMining.PickaxeMode.REGULAR, tools, true, true, 10));
        assertSame(amethyst, AreaMining.chooseTool(AreaMining.PickaxeMode.AMETHYST, tools, true, true, 10));
        assertSame("edge falls back to regular", diamond, AreaMining.chooseTool(AreaMining.PickaxeMode.AMETHYST, tools, false, true, 10));
        assertNull("fallback disabled", AreaMining.chooseTool(AreaMining.PickaxeMode.AMETHYST, tools, false, false, 10));
        assertSame("durability floor disabled", worn, AreaMining.chooseTool(AreaMining.PickaxeMode.REGULAR, tools, true, true, 0));
        // Gravel: pickaxes are slow (speed 1), a shovel is fast: the shovel wins even in Amethyst mode.
        AreaMining.Tool amethystOnGravel = new AreaMining.Tool(0, true, 1, 1500);
        AreaMining.Tool pickOnGravel = new AreaMining.Tool(1, false, 1, 1500);
        AreaMining.Tool shovel = new AreaMining.Tool(3, false, 8, 250);
        java.util.List<AreaMining.Tool> gravelTools = java.util.List.of(amethystOnGravel, pickOnGravel, shovel);
        assertSame("shovel on gravel (amethyst mode)", shovel, AreaMining.chooseTool(AreaMining.PickaxeMode.AMETHYST, gravelTools, true, true, 10));
        assertSame("shovel on gravel (regular mode)", shovel, AreaMining.chooseTool(AreaMining.PickaxeMode.REGULAR, gravelTools, true, true, 10));
    }

    @Test
    public void detectsDonutStyleAmethystPickaxe() {
        List<String> kw = List.of("amethyst");
        assertTrue(AreaMining.looksLikeAreaTool(kw, "minecraft:netherite_pickaxe", "Amethyst Pickaxe", List.of(), List.of()));
        assertTrue(AreaMining.looksLikeAreaTool(kw, "minecraft:diamond_pickaxe", "Pickaxe", List.of("Mines a 3x3 area", "AMETHYST"), List.of()));
        assertTrue(AreaMining.looksLikeAreaTool(kw, "minecraft:iron_pickaxe", "Pick", List.of(), List.of("amethystpickaxes:amethyst")));
        assertTrue(AreaMining.looksLikeAreaTool(kw, "amethyst_opickaxe:amethyst_opickaxe", null, null, null));
        assertFalse(AreaMining.looksLikeAreaTool(kw, "minecraft:netherite_pickaxe", "My Pick", List.of(), List.of("minecraft:efficiency")));
        assertFalse("only pickaxes qualify", AreaMining.looksLikeAreaTool(kw, "minecraft:amethyst_shard", "Amethyst", null, null));
    }
}
