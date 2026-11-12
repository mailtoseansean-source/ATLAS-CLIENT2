package dev.atlasclient.core;

import dev.atlasclient.core.build.Restock;
import dev.atlasclient.core.build.Restock.DropMode;
import dev.atlasclient.core.build.Restock.DropScope;
import dev.atlasclient.core.build.Restock.Stack;
import dev.atlasclient.core.build.RestockSession;
import dev.atlasclient.core.build.RestockSession.Kind;
import dev.atlasclient.core.build.RestockSession.Observation;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class RestockTest {
    private static final Set<String> JUNK = new HashSet<>(Restock.DEFAULT_JUNK);
    private static final Set<String> NEVER = new HashSet<>(Restock.DEFAULT_NEVER_DROP);

    private static List<Stack> inv(Object... spec) {
        List<Stack> out = new ArrayList<>();
        for (int i = 0; i < 36; i++) out.add(new Stack(i, "", 0, false, false));
        for (int i = 0; i < spec.length; i += 3) {
            int slot = (Integer) spec[i];
            String id = (String) spec[i + 1];
            int count = (Integer) spec[i + 2];
            boolean prot = id.contains("shulker") || id.contains("named");
            out.set(slot, new Stack(slot, id, count, !id.contains("pickaxe"), prot));
        }
        return out;
    }

    @Test
    public void choosesTheShulkerThatCoversMostMissingItems() {
        Map<String, Integer> missing = Map.of("minecraft:stone", 100, "minecraft:glass", 10);
        List<Restock.Shulker> boxes = List.of(
                new Restock.Shulker(5, "minecraft:shulker_box", Map.of("minecraft:dirt", 1728)),
                new Restock.Shulker(7, "minecraft:red_shulker_box", Map.of("minecraft:stone", 64, "minecraft:glass", 64)),
                new Restock.Shulker(9, "minecraft:blue_shulker_box", Map.of("minecraft:stone", 1728)));
        assertEquals(9, Restock.chooseShulker(boxes, missing).slot()); // 100 useful vs 74
        assertNull(Restock.chooseShulker(boxes, Map.of("minecraft:obsidian", 4)));
    }

    @Test
    public void withdrawsOnlyWhatIsMissingAndRespectsFreeSlots() {
        List<Stack> box = List.of(new Stack(0, "minecraft:stone", 64, true, false), new Stack(1, "minecraft:stone", 64, true, false),
                new Stack(2, "minecraft:stone", 32, true, false), new Stack(3, "minecraft:dirt", 64, true, false),
                new Stack(4, "minecraft:glass", 10, true, false));
        List<Integer> take = Restock.withdrawals(box, Map.of("minecraft:stone", 70, "minecraft:glass", 5), 10);
        assertEquals(List.of(0, 1, 4), take); // two stone stacks cover 70, one glass; never dirt
        assertEquals(List.of(0), Restock.withdrawals(box, Map.of("minecraft:stone", 70, "minecraft:glass", 5), 1));
        assertTrue(Restock.withdrawals(box, Map.of("minecraft:stone", 70), 0).isEmpty());
    }

    @Test
    public void dropsOnlyUnneededJunkAndNeverProtectedItems() {
        List<Stack> inventory = inv(0, "minecraft:diamond_pickaxe", 1, 1, "minecraft:cobblestone", 64, 2, "minecraft:stone", 40,
                3, "minecraft:shulker_box", 1, 4, "minecraft:diamond_block", 9, 5, "minecraft:oak_planks", 20,
                6, "minecraft:dirt", 12, 7, "minecraft:named_cobblestone", 3);
        Map<String, Integer> needed = Map.of("minecraft:stone", 30); // the build needs stone, not cobblestone
        List<Integer> always = Restock.drops(inventory, needed, DropMode.ALWAYS, DropScope.JUNK_ONLY, JUNK, NEVER, 3);
        assertEquals(List.of(1, 6), always); // cobblestone and dirt; stone is needed; planks are not junk
        List<Integer> any = Restock.drops(inventory, needed, DropMode.ALWAYS, DropScope.ANY_UNNEEDED_BLOCK, JUNK, NEVER, 3);
        assertTrue(any.contains(5));
        assertFalse("never the pickaxe, shulker, diamond block or renamed items", any.contains(0) || any.contains(3)
                || any.contains(4) || any.contains(7));
        assertTrue(Restock.drops(inventory, needed, DropMode.OFF, DropScope.ANY_UNNEEDED_BLOCK, JUNK, NEVER, 3).isEmpty());
    }

    @Test
    public void whenFullDropsJustEnoughToReachTheFreeSlotTarget() {
        List<Stack> full = new ArrayList<>();
        for (int i = 0; i < 36; i++) full.add(new Stack(i, i < 10 ? "minecraft:cobblestone" : "minecraft:stone", 64, true, false));
        Map<String, Integer> needed = Map.of("minecraft:stone", 5000);
        List<Integer> d = Restock.drops(full, needed, DropMode.WHEN_FULL, DropScope.JUNK_ONLY, JUNK, NEVER, 3);
        assertEquals(3, d.size());
        // Already enough room: nothing thrown.
        List<Stack> roomy = inv(0, "minecraft:cobblestone", 64);
        assertTrue(Restock.drops(roomy, needed, DropMode.WHEN_FULL, DropScope.JUNK_ONLY, JUNK, NEVER, 3).isEmpty());
    }

    @Test
    public void spotIsOutsideTheBuildOnSolidGroundWithRoomForTheLid() {
        // Flat ground at y=63 (solid), air above; build occupies x 0..4.
        Restock.SpotProbe probe = new Restock.SpotProbe() {
            public boolean empty(int x, int y, int z) { return y >= 64 && !(x == -1 && z == 0 && y == 65); }
            public boolean solid(int x, int y, int z) { return y == 63; }
            public boolean inBuild(int x, int y, int z) { return x >= 0 && x <= 4; }
            public boolean fluidNear(int x, int y, int z) { return x == -2 && z == 1; }
        };
        int[] spot = Restock.chooseSpot(-1, 64, 1, -0.5, 65.62, 1.5, 4.5, probe);
        assertNotNull(spot);
        assertTrue("outside the build", spot[0] < 0);
        assertEquals(64, spot[1]);
        assertFalse("not the player's own column", spot[0] == -1 && spot[2] == 1);
        assertFalse("lid needs air above", spot[0] == -1 && spot[2] == 0);
        assertFalse("away from fluid", spot[0] == -2 && spot[2] == 1);
    }

    /** Simulates the world around the session tick by tick. */
    private static final class Sim {
        boolean box, open, boxOnGround;
        int free = 5, held = 2;
        List<Stack> contents = new ArrayList<>(List.of(new Stack(0, "minecraft:stone", 64, true, false),
                new Stack(1, "minecraft:stone", 64, true, false), new Stack(2, "minecraft:glass", 32, true, false)));
        Map<String, Integer> missing = new HashMap<>(Map.of("minecraft:stone", 100, "minecraft:glass", 8));
        boolean refuseOpen;
        boolean pickupWorks = true;

        Observation obs(long t) { return new Observation(t, box, open, open ? contents : List.of(), free, held, true); }

        void apply(RestockSession.Action a) {
            switch (a.kind()) {
                case PLACE -> { box = true; held--; free++; }
                case OPEN -> { if (!refuseOpen) open = true; }
                case TAKE -> {
                    for (int i = 0; i < contents.size(); i++) {
                        Stack s = contents.get(i);
                        if (s.slot() == a.slot() && s.count() > 0) {
                            missing.merge(s.item(), -s.count(), Integer::sum);
                            contents.set(i, new Stack(s.slot(), "", 0, false, false));
                            free--;
                        }
                    }
                }
                case CLOSE -> open = false;
                case BREAK -> { if (box) { box = false; boxOnGround = true; } }
                case WALK -> { if (boxOnGround && pickupWorks && free > 0) { boxOnGround = false; held++; free--; } }
                default -> { }
            }
        }
    }

    @Test
    public void fullRestockTakesWhatIsMissingAndBringsTheBoxBack() {
        Sim sim = new Sim();
        RestockSession s = new RestockSession(12, new int[]{3, 64, 3}, sim.held);
        RestockSession.Action a = null;
        for (long t = 0; t < 400 && !s.finished(); t++) {
            a = s.tick(sim.obs(t), sim.missing);
            sim.apply(a);
        }
        assertEquals(RestockSession.Phase.DONE, s.phase());
        assertEquals(Kind.DONE, a.kind());
        assertEquals("box is back", 2, sim.held);
        assertFalse(sim.box || sim.boxOnGround || sim.open);
        assertEquals(3, s.stacksTaken()); // 2 stone (covers 100) + 1 glass
        assertTrue(sim.missing.get("minecraft:stone") <= 0 && sim.missing.get("minecraft:glass") <= 0);
    }

    @Test
    public void boxThatWillNotOpenIsStillRecovered() {
        Sim sim = new Sim();
        sim.refuseOpen = true;
        RestockSession s = new RestockSession(12, new int[]{3, 64, 3}, sim.held);
        for (long t = 0; t < 600 && !s.finished(); t++) sim.apply(s.tick(sim.obs(t), sim.missing));
        assertEquals(RestockSession.Phase.DONE, s.phase());
        assertEquals(2, sim.held);
        assertTrue(s.problem().contains("would not open"));
    }

    @Test
    public void neverBreaksTheBoxWithoutAFreeSlotAndReportsALostDrop() {
        Sim sim = new Sim();
        RestockSession s = new RestockSession(12, new int[]{3, 64, 3}, sim.held);
        // Run until the box is closed and about to be mined, then fill the inventory.
        long t = 0;
        while (s.phase() != RestockSession.Phase.BREAK && t < 400) sim.apply(s.tick(sim.obs(t++), sim.missing));
        sim.free = 0;
        RestockSession.Action a = s.tick(new Observation(t++, sim.box, sim.open, List.of(), 0, sim.held, false), sim.missing);
        assertEquals(Kind.WAIT, a.kind());
        assertTrue(a.message().contains("Free one inventory slot"));
        assertTrue(sim.box);
        // With room again but pickup failing, it eventually reports where the box is.
        sim.free = 2;
        sim.pickupWorks = false;
        while (!s.finished() && t < 2000) sim.apply(s.tick(sim.obs(t++), sim.missing));
        assertEquals(RestockSession.Phase.FAILED, s.phase());
        assertTrue(s.problem().contains("was not picked up"));
    }

    @Test
    public void placementThatNeverAppearsFailsWithoutLosingAnything() {
        RestockSession s = new RestockSession(12, new int[]{3, 64, 3}, 1);
        int places = 0;
        for (long t = 0; t < 200 && !s.finished(); t++) {
            RestockSession.Action a = s.tick(new Observation(t, false, false, List.of(), 5, 1, true), Map.of("minecraft:stone", 5));
            if (a.kind() == Kind.PLACE) places++;
        }
        assertEquals(RestockSession.Phase.FAILED, s.phase());
        assertEquals(3, places);
    }

    @Test
    public void parsesIdLists() {
        assertEquals(Set.of("minecraft:cobblestone", "mymod:thing"), Restock.parseIds(" cobblestone, mymod:thing ,"));
        assertEquals("cobblestone, dirt", Restock.joinIds(List.of("minecraft:cobblestone", "minecraft:dirt")));
    }
}
