package dev.atlasclient.core.build;

import java.util.List;
import java.util.Map;

/**
 * One shulker-box restock as a small state machine:
 * place the box next to the player, open it, shift-click out what the build is missing, close it,
 * mine it and walk over its drop until it is back in the inventory.
 *
 * <p>Pure logic: each tick the game layer passes an {@link Observation} and performs the returned
 * {@link Action} with normal player interactions. Whatever happens after the box is placed, the
 * machine always tries to get the box back before it reports done or failed.</p>
 */
public final class RestockSession {
    public enum Phase { PLACE, OPEN, TAKE, CLOSE, BREAK, PICK_UP, DONE, FAILED }

    public enum Kind {
        /** Select inventory slot {@code slot} and right-click the top of the block below {@code spot}. */
        PLACE,
        /** Right-click the box at {@code spot}. */
        OPEN,
        /** Shift-click container slot {@code slot} of the open box. */
        TAKE,
        /** Close the box screen. */
        CLOSE,
        /** Keep mining the box at {@code spot} (never with a 3x3 tool). */
        BREAK,
        /** Walk onto {@code spot} to collect the dropped box. */
        WALK,
        /** Throw out one unneeded stack to make room (the game layer picks which). */
        MAKE_ROOM,
        /** Nothing to do this tick. */
        WAIT,
        DONE,
        FAIL
    }

    public record Action(Kind kind, int slot, int[] spot, String message) {
        static Action of(Kind k) { return new Action(k, -1, null, ""); }
    }

    /**
     * What the game layer sees this tick.
     *
     * @param boxAtSpot      a shulker box block is at the chosen spot
     * @param screenOpen     the shulker box screen opened by us is showing
     * @param container      contents of the open box (slots 0-26), empty list if not open
     * @param freeSlots      empty main-inventory slots (0-35)
     * @param boxItemsHeld   number of shulker box items in the inventory
     * @param canMakeRoom    at least one stack could be thrown out under the current drop settings
     */
    public record Observation(long tick, boolean boxAtSpot, boolean screenOpen, List<Restock.Stack> container,
                              int freeSlots, int boxItemsHeld, boolean canMakeRoom) { }

    private static final int PLACE_TIMEOUT = 20, OPEN_TIMEOUT = 30, BREAK_TIMEOUT = 300, PICKUP_TIMEOUT = 200, ATTEMPTS = 3;

    private final int boxSlot;
    private final int[] spot;
    private final int boxesBefore;
    private Phase phase = Phase.PLACE;
    private long phaseStart = -1;
    private long lastAction = -1;
    private int attempts;
    private int taken;
    private String problem = "";

    /**
     * @param boxSlot     inventory index (0-35) of the shulker box to use
     * @param spot        where it goes (from {@link Restock#chooseSpot})
     * @param boxesBefore shulker box items held before starting (the count to return to)
     */
    public RestockSession(int boxSlot, int[] spot, int boxesBefore) {
        this.boxSlot = boxSlot;
        this.spot = spot.clone();
        this.boxesBefore = boxesBefore;
    }

    public Phase phase() { return phase; }
    public int[] spot() { return spot.clone(); }
    public int stacksTaken() { return taken; }
    /** Non-empty when something went wrong (also set when the box was recovered after a problem). */
    public String problem() { return problem; }
    public boolean finished() { return phase == Phase.DONE || phase == Phase.FAILED; }

    private void enter(Phase p, long tick) {
        phase = p;
        phaseStart = tick;
        attempts = 0;
        lastAction = -1;
    }

    private Action fail(String why) {
        problem = why;
        phase = Phase.FAILED;
        return new Action(Kind.FAIL, -1, spot.clone(), why);
    }

    private Action at(Kind k) { return new Action(k, -1, spot.clone(), ""); }

    public Action tick(Observation o, Map<String, Integer> missing) {
        if (phaseStart < 0) phaseStart = o.tick();
        long inPhase = o.tick() - phaseStart;
        switch (phase) {
            case PLACE -> {
                if (o.boxAtSpot()) {
                    enter(Phase.OPEN, o.tick());
                    return Action.of(Kind.WAIT);
                }
                if (lastAction < 0 || o.tick() - lastAction >= PLACE_TIMEOUT) {
                    if (attempts >= ATTEMPTS) {
                        return fail("Could not place the shulker box at " + pos() + " (protected area or blocked?)");
                    }
                    attempts++;
                    lastAction = o.tick();
                    return new Action(Kind.PLACE, boxSlot, spot.clone(), "");
                }
                return Action.of(Kind.WAIT);
            }
            case OPEN -> {
                if (!o.boxAtSpot()) {
                    return fail("The shulker box at " + pos() + " disappeared before it could be opened");
                }
                if (o.screenOpen()) {
                    enter(Phase.TAKE, o.tick());
                    return Action.of(Kind.WAIT);
                }
                if (lastAction < 0 || o.tick() - lastAction >= OPEN_TIMEOUT) {
                    if (attempts >= ATTEMPTS) {
                        problem = "The shulker box would not open (lid blocked or interaction denied)";
                        enter(Phase.BREAK, o.tick());
                        return at(Kind.BREAK);
                    }
                    attempts++;
                    lastAction = o.tick();
                    return at(Kind.OPEN);
                }
                return Action.of(Kind.WAIT);
            }
            case TAKE -> {
                if (!o.screenOpen()) {
                    // Closed by the server or the player: stop taking, recover the box.
                    enter(Phase.BREAK, o.tick());
                    return at(Kind.BREAK);
                }
                // Keep one empty slot so the box itself can be picked up again.
                List<Integer> next = Restock.withdrawals(o.container(), missing, Math.max(0, o.freeSlots() - 1));
                if (next.isEmpty() || inPhase > 200) {
                    enter(Phase.CLOSE, o.tick());
                    return Action.of(Kind.CLOSE);
                }
                if (lastAction >= 0 && o.tick() - lastAction < 2) {
                    return Action.of(Kind.WAIT); // one click every other tick
                }
                lastAction = o.tick();
                taken++;
                return new Action(Kind.TAKE, next.get(0), spot.clone(), "");
            }
            case CLOSE -> {
                if (o.screenOpen()) {
                    if (inPhase > 10) {
                        return Action.of(Kind.CLOSE);
                    }
                    return Action.of(Kind.WAIT);
                }
                enter(Phase.BREAK, o.tick());
                return Action.of(Kind.WAIT);
            }
            case BREAK -> {
                if (o.screenOpen()) {
                    return Action.of(Kind.CLOSE);
                }
                if (!o.boxAtSpot()) {
                    enter(Phase.PICK_UP, o.tick());
                    return at(Kind.WALK);
                }
                if (o.freeSlots() == 0) {
                    // Never break the box without a slot to catch it.
                    if (o.canMakeRoom()) return Action.of(Kind.MAKE_ROOM);
                    return new Action(Kind.WAIT, -1, spot.clone(), "Free one inventory slot so the shulker box can be picked up");
                }
                if (inPhase > BREAK_TIMEOUT) {
                    return fail("Could not break the shulker box at " + pos() + "; mine it by hand");
                }
                return at(Kind.BREAK);
            }
            case PICK_UP -> {
                if (o.boxItemsHeld() >= boxesBefore) {
                    phase = Phase.DONE;
                    return new Action(Kind.DONE, -1, spot.clone(), problem);
                }
                if (o.freeSlots() == 0 && o.canMakeRoom()) {
                    return Action.of(Kind.MAKE_ROOM);
                }
                if (inPhase > PICKUP_TIMEOUT) {
                    return fail("The shulker box dropped at " + pos() + " was not picked up; collect it by hand");
                }
                return at(Kind.WALK);
            }
            default -> {
                return Action.of(phase == Phase.DONE ? Kind.DONE : Kind.FAIL);
            }
        }
    }

    private String pos() { return spot[0] + " " + spot[1] + " " + spot[2]; }
}
