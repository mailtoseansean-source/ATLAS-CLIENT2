package dev.atlasclient.core;

import dev.atlasclient.core.camera.CameraMotion;
import dev.atlasclient.core.gui.GuiModel;
import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.module.Module;
import dev.atlasclient.core.module.ModuleManager;
import dev.atlasclient.core.notify.NotificationManager;
import dev.atlasclient.core.event.EventBus;
import dev.atlasclient.core.perf.PerfMonitor;
import dev.atlasclient.core.xray.XrayIndex;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** X-Ray index, free camera motion and the GUI "Active" view. */
public class RenderCameraTest {

    private static List<XrayIndex.Hit> hits(int cx, int cz, int diamonds, int iron, boolean exposed) {
        List<XrayIndex.Hit> l = new ArrayList<>();
        for (int i = 0; i < diamonds; i++) l.add(new XrayIndex.Hit(cx * 16 + (i % 16), -50 + i / 16, cz * 16, "minecraft:deepslate_diamond_ore", exposed || i % 3 == 0));
        for (int i = 0; i < iron; i++) l.add(new XrayIndex.Hit(cx * 16 + (i % 16), 20 + i / 16, cz * 16 + 1, "minecraft:iron_ore", exposed || i % 2 == 0));
        return l;
    }

    @Test
    public void xrayNearestIsSortedFilteredAndBounded() {
        XrayIndex idx = new XrayIndex();
        idx.put("minecraft:overworld", 0, 0, hits(0, 0, 5, 10, false));
        idx.put("minecraft:overworld", 10, 0, hits(10, 0, 5, 0, false)); // 160+ blocks away
        List<XrayIndex.Hit> near = idx.nearest(0, -50, 0, 64, 100, false, b -> b.contains("diamond"));
        assertEquals(5, near.size());
        for (int i = 1; i < near.size(); i++) {
            assertTrue(Math.abs(near.get(i).x()) >= Math.abs(near.get(i - 1).x()));
        }
        assertEquals(3, idx.nearest(0, -50, 0, 64, 3, false, b -> true).size());
        assertTrue(idx.nearest(0, -50, 0, 64, 100, true, b -> true).stream().allMatch(XrayIndex.Hit::exposed));
        idx.removeBlock(0, -50, 0);
        assertEquals(4, idx.nearest(0, -50, 0, 64, 100, false, b -> b.contains("diamond")).size());
        // Dimension change clears the old dimension.
        idx.put("minecraft:the_nether", 0, 0, List.of(new XrayIndex.Hit(1, 20, 1, "minecraft:ancient_debris", false)));
        assertEquals(1, idx.size());
    }

    @Test
    public void xrayAvailabilityDetectsBothAntiXrayStyles() {
        XrayIndex natural = new XrayIndex();
        for (int c = 0; c < 10; c++) natural.put("o", c, 0, hits(c, 0, 4, 20, false));
        assertEquals(XrayIndex.Availability.NORMAL, natural.availability(false));
        assertEquals(XrayIndex.Availability.AUTHORITATIVE_LOCAL, natural.availability(true));

        XrayIndex fake = new XrayIndex();
        for (int c = 0; c < 10; c++) fake.put("o", c, 0, hits(c, 0, 60, 80, false));
        assertEquals(XrayIndex.Availability.SUSPECT_FAKE, fake.availability(false));

        XrayIndex hidden = new XrayIndex();
        for (int c = 0; c < 10; c++) hidden.put("o", c, 0, hits(c, 0, 3, 10, true));
        assertEquals(XrayIndex.Availability.LIKELY_HIDDEN, hidden.availability(false));

        XrayIndex few = new XrayIndex();
        few.put("o", 0, 0, hits(0, 0, 1, 1, false));
        assertEquals(XrayIndex.Availability.UNKNOWN, few.availability(false));
    }

    @Test
    public void xrayIndexIsBounded() {
        XrayIndex idx = new XrayIndex();
        for (int c = 0; c < XrayIndex.MAX_CHUNKS + 50; c++) idx.put("o", c, 0, List.of(new XrayIndex.Hit(c * 16, 0, 0, "minecraft:diamond_ore", true)));
        assertEquals(XrayIndex.MAX_CHUNKS, idx.chunkCount());
    }

    private static CameraMotion.Input in(boolean f, boolean b, boolean l, boolean r, boolean up, boolean down, double yaw, double smoothing) {
        return new CameraMotion.Input(f, b, l, r, up, down, false, yaw, 20, 3, 1, smoothing);
    }

    @Test
    public void cameraMovesRelativeToYaw() {
        CameraMotion m = new CameraMotion();
        m.reset(0, 64, 0);
        m.tick(in(true, false, false, false, false, false, 0, 0), null);   // facing south (+Z)
        assertEquals(1.0, m.z(), 1e-9);                                     // 20 blocks/s = 1 block/tick
        assertEquals(0.0, m.x(), 1e-9);
        m.reset(0, 64, 0);
        m.tick(in(true, false, false, false, false, false, 90, 0), null);  // facing west (-X)
        assertEquals(-1.0, m.x(), 1e-9);
        m.reset(0, 64, 0);
        m.tick(in(false, false, false, true, false, false, 0, 0), null);   // right of south is west
        assertEquals(-1.0, m.x(), 1e-9);
        m.reset(0, 64, 0);
        m.tick(in(true, false, false, true, true, false, 0, 0), null);     // diagonal is not faster
        assertEquals(1.0, Math.hypot(m.x(), m.z()), 1e-9);
        assertEquals(65.0, m.y(), 1e-9);
    }

    @Test
    public void cameraAcceleratesGlidesAndRespectsBounds() {
        CameraMotion m = new CameraMotion();
        m.reset(0, 64, 0);
        m.tick(in(true, false, false, false, false, false, 0, 0.8), null);
        assertTrue("accelerates gradually", m.z() > 0 && m.z() < 0.5);
        for (int i = 0; i < 40; i++) m.tick(in(true, false, false, false, false, false, 0, 0.8), null);
        double cruising = m.speedBlocksPerTick();
        assertEquals(1.0, cruising, 0.01);
        m.tick(in(false, false, false, false, false, false, 0, 0.8), null);
        assertTrue("glides after release", m.speedBlocksPerTick() > 0.5 && m.speedBlocksPerTick() < cruising);
        // Bounds: cannot cross z = 10, but can still slide along x.
        m.reset(0, 64, 9.5);
        CameraMotion.Bounds wall = (x, y, z) -> z < 10;
        for (int i = 0; i < 10; i++) m.tick(new CameraMotion.Input(true, false, false, true, false, false, false, 0, 20, 3, 1, 0), wall);
        assertTrue(m.z() < 10);
        assertTrue("slides sideways along the wall", m.x() < -3);
        double[] mid = m.lerp(0.5);
        assertEquals((m.lastX() + m.x()) / 2, mid[0], 1e-9);
    }

    @Test
    public void guiActiveViewListsOnlySwitchedOnModules() {
        ModuleManager mm = new ModuleManager(new EventBus(), new NotificationManager(), new PerfMonitor());
        TestModules.SampleModule a = new TestModules.SampleModule("Alpha", Category.RENDER);
        TestModules.SampleModule b = new TestModules.SampleModule("Beta", Category.CAMERA);
        TestModules.SampleModule c = new TestModules.SampleModule("Gamma", Category.WORLD);
        mm.register(a);
        mm.register(b);
        mm.register(c);
        GuiModel gui = new GuiModel(mm);
        gui.selectActive();
        assertTrue(gui.visibleModules().isEmpty());
        mm.setEnabled(b, true);
        mm.setEnabled(c, true);
        List<Module> v = gui.visibleModules();
        assertEquals(2, v.size());
        assertTrue(v.contains(b) && v.contains(c));
        com.google.gson.JsonObject ui = new com.google.gson.JsonObject();
        gui.savePrefs(ui);
        GuiModel again = new GuiModel(mm);
        again.loadPrefs(ui);
        assertTrue(again.activeView());
        again.selectCategory(Category.CAMERA);
        assertFalse(again.activeView());
        assertEquals(List.of(b), again.visibleModules());
    }
}
