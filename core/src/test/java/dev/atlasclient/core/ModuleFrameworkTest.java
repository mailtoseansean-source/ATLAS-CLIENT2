package dev.atlasclient.core;

import dev.atlasclient.core.TestModules.Harness;
import dev.atlasclient.core.TestModules.SampleModule;
import dev.atlasclient.core.TestModules.ThrowingModule;
import dev.atlasclient.core.event.CoreEvents;
import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.module.KeybindDispatcher;
import dev.atlasclient.core.module.Module;
import dev.atlasclient.core.notify.Notification;
import dev.atlasclient.core.setting.Keybind;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/** Requirements 1 (registration), 2 (toggling) and 4 (keybind registration). */
public class ModuleFrameworkTest {

    @Test
    public void registersAndLooksUpModules() {
        Harness h = new Harness();
        SampleModule a = h.modules.register(new SampleModule("Base Finder", Category.WORLD));
        SampleModule b = h.modules.register(new SampleModule("Chunk Outlines", Category.RENDER));
        assertEquals(2, h.modules.size());
        assertSame(a, h.modules.get("base finder"));
        assertSame(b, h.modules.get("CHUNK OUTLINES"));
        assertEquals(List.of(a), h.modules.byCategory(Category.WORLD));
        assertNull(h.modules.get("missing"));
    }

    @Test(expected = IllegalStateException.class)
    public void rejectsDuplicateNames() {
        Harness h = new Harness();
        h.modules.register(new SampleModule("Flight", Category.MOVEMENT));
        h.modules.register(new SampleModule("flight", Category.MOVEMENT));
    }

    @Test
    public void searchRanksNameMatchesFirst() {
        Harness h = new Harness();
        Module debris = h.modules.register(new SampleModule("Ancient Debris Finder", Category.WORLD));
        Module base = h.modules.register(new SampleModule("Base Finder", Category.WORLD));
        Module other = h.modules.register(new SampleModule("Waypoints", Category.RENDER));
        List<Module> hits = h.modules.search("finder");
        assertTrue(hits.contains(debris));
        assertTrue(hits.contains(base));
        assertFalse(hits.contains(other));
        assertEquals(base, h.modules.search("base").get(0));
        // Setting names are searchable too.
        assertTrue(h.modules.search("range").contains(other));
        assertEquals(3, h.modules.search("").size());
    }

    @Test
    public void togglingCallsHooksAndPostsEvents() {
        Harness h = new Harness();
        SampleModule m = h.modules.register(new SampleModule("Test", Category.UTILITY));
        List<Boolean> events = new ArrayList<>();
        h.bus.subscribe(CoreEvents.ModuleToggled.class, e -> events.add(e.enabled()));
        assertTrue(m.toggle());
        assertTrue(m.isEnabled());
        assertEquals(1, m.enables);
        h.modules.tick();
        h.modules.tick();
        assertEquals(2, m.ticks);
        assertTrue(m.toggle());
        assertFalse(m.isEnabled());
        assertEquals(1, m.disables);
        h.modules.tick();
        assertEquals("disabled modules are not ticked", 2, m.ticks);
        assertEquals(List.of(true, false), events);
        // Setting the same state again is a no-op.
        m.setEnabled(false);
        assertEquals(1, m.disables);
    }

    @Test
    public void failingModulesAreIsolatedAndReported() {
        Harness h = new Harness();
        ThrowingModule enableFail = h.modules.register(new ThrowingModule("EnableFail", true));
        ThrowingModule tickFail = h.modules.register(new ThrowingModule("TickFail", false));
        SampleModule healthy = h.modules.register(new SampleModule("Healthy", Category.UTILITY));
        assertFalse(enableFail.setEnabled(true));
        assertFalse(enableFail.isEnabled());
        assertTrue(tickFail.setEnabled(true));
        healthy.setEnabled(true);
        h.modules.tick();
        assertFalse("a module throwing in tick is disabled", tickFail.isEnabled());
        assertEquals("other modules still tick", 1, healthy.ticks);
        long errors = h.notifications.history().stream().filter(n -> n.level() == Notification.Level.ERROR).count();
        assertEquals(2, errors);
    }

    @Test
    public void blockedEnableShowsReasonAndStaysOff() {
        Harness h = new Harness();
        Module blocked = h.modules.register(new SampleModule("Authorized Flight", Category.MOVEMENT) {
            @Override public String enableBlockedReason() { return "Flight not permitted here"; }
        });
        assertFalse(blocked.setEnabled(true));
        assertFalse(blocked.isEnabled());
        assertEquals("Flight not permitted here", h.notifications.history().get(0).message());
    }

    @Test
    public void keybindsToggleOnPressEdgeOnly() {
        Harness h = new Harness();
        SampleModule m = h.modules.register(new SampleModule("Flight", Category.MOVEMENT));
        m.keybind().set(70); // F
        KeybindDispatcher d = new KeybindDispatcher(h.modules);
        Set<Integer> down = new HashSet<>();
        assertEquals(0, d.poll(down::contains, false));
        down.add(70);
        assertEquals(1, d.poll(down::contains, false));
        assertTrue(m.isEnabled());
        assertEquals("holding does not retrigger", 0, d.poll(down::contains, false));
        down.remove(70);
        d.poll(down::contains, false);
        down.add(70);
        d.poll(down::contains, false);
        assertFalse(m.isEnabled());
    }

    @Test
    public void keybindsIgnoredWhileScreenOpen() {
        Harness h = new Harness();
        SampleModule m = h.modules.register(new SampleModule("Flight", Category.MOVEMENT));
        m.keybind().set(70);
        KeybindDispatcher d = new KeybindDispatcher(h.modules);
        assertEquals(0, d.poll(k -> k == 70, true));
        assertFalse(m.isEnabled());
        // After the screen closes, the still-held key must not fire until released and pressed again.
        assertEquals(0, d.poll(k -> k == 70, false));
        d.poll(k -> false, false);
        assertEquals(1, d.poll(k -> k == 70, false));
    }

    @Test
    public void globalBindAndConflictDetection() {
        Harness h = new Harness();
        SampleModule a = h.modules.register(new SampleModule("A", Category.WORLD));
        SampleModule b = h.modules.register(new SampleModule("B", Category.WORLD));
        a.keybind().set(Keybind.KEY_RIGHT_SHIFT);
        b.keybind().set(66);
        KeybindDispatcher d = new KeybindDispatcher(h.modules);
        int[] opened = {0};
        d.bindGlobal(Keybind.KEY_RIGHT_SHIFT, () -> opened[0]++);
        assertEquals(1, d.conflicts().size());
        assertTrue(d.conflicts().get(Keybind.KEY_RIGHT_SHIFT).contains("A"));
        d.poll(k -> k == Keybind.KEY_RIGHT_SHIFT, false);
        assertEquals(1, opened[0]);
        a.keybind().set(Keybind.NONE);
        assertTrue(d.conflicts().isEmpty());
        assertEquals("None", Keybind.name(Keybind.NONE));
        assertEquals("Right Shift", Keybind.name(Keybind.KEY_RIGHT_SHIFT));
    }
}
