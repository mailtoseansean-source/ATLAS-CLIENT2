package dev.atlasclient.core;

import dev.atlasclient.core.TestModules.Harness;
import dev.atlasclient.core.TestModules.SampleModule;
import dev.atlasclient.core.config.ConfigManager;
import dev.atlasclient.core.module.Category;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** Requirement 3: configuration persistence, profiles and corruption handling. */
public class ConfigPersistenceTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static Harness harness() {
        Harness h = new Harness();
        h.modules.register(new SampleModule("Base Finder", Category.WORLD));
        h.modules.register(new SampleModule("Flight", Category.MOVEMENT));
        return h;
    }

    @Test
    public void valuesSurviveRestart() throws Exception {
        Path root = tmp.newFolder("atlas").toPath();
        Harness first = harness();
        ConfigManager cfg = new ConfigManager(root, first.modules);
        cfg.loadAll();
        SampleModule bf = (SampleModule) first.modules.get("Base Finder");
        bf.setEnabled(true);
        bf.flag.set(false);
        bf.range.set(20.0);
        bf.color.set(0xFF00FF00);
        bf.cat.set(Category.RENDER);
        bf.keybind().set(66);
        assertTrue(cfg.saveProfile("default"));

        // "Restart": brand new module instances read the same directory.
        Harness second = harness();
        ConfigManager cfg2 = new ConfigManager(root, second.modules);
        cfg2.loadAll();
        SampleModule bf2 = (SampleModule) second.modules.get("Base Finder");
        assertTrue(bf2.isEnabled());
        assertFalse(bf2.flag.get());
        assertEquals(20.0, bf2.range.get(), 1e-9);
        assertEquals(0xFF00FF00, (int) bf2.color.get());
        assertEquals(Category.RENDER, bf2.cat.get());
        assertEquals(66, bf2.keybind().key());
        assertFalse(second.modules.get("Flight").isEnabled());
    }

    @Test
    public void multipleProfilesAreIndependent() {
        Path root = tmp.getRoot().toPath().resolve("p");
        Harness h = harness();
        ConfigManager cfg = new ConfigManager(root, h.modules);
        cfg.loadAll();
        SampleModule bf = (SampleModule) h.modules.get("Base Finder");
        bf.range.set(5.0);
        cfg.saveProfile("mining");
        bf.range.set(30.0);
        cfg.saveProfile("building");
        assertEquals(List.of("building", "default", "mining"), cfg.listProfiles());
        assertTrue(cfg.loadProfile("mining"));
        assertEquals(5.0, bf.range.get(), 1e-9);
        assertEquals("mining", cfg.activeProfile());
        assertTrue(cfg.loadProfile("building"));
        assertEquals(30.0, bf.range.get(), 1e-9);
        assertFalse("cannot delete active profile", cfg.deleteProfile("building"));
        assertTrue(cfg.deleteProfile("mining"));
        assertFalse(cfg.saveProfile("../evil"));
        assertFalse(ConfigManager.isValidProfileName("a b"));
    }

    @Test
    public void corruptedFilesFallBackToDefaults() throws Exception {
        Path root = tmp.newFolder("c").toPath();
        Files.createDirectories(root.resolve("profiles"));
        Files.writeString(root.resolve("profiles/default.json"), "{ this is not json");
        Harness h = harness();
        ConfigManager cfg = new ConfigManager(root, h.modules);
        List<String> warnings = new ArrayList<>();
        cfg.setWarningSink(warnings::add);
        cfg.loadAll();
        assertFalse(warnings.isEmpty());
        SampleModule bf = (SampleModule) h.modules.get("Base Finder");
        assertEquals(8.0, bf.range.get(), 1e-9);
        // The corrupted file was replaced by a valid one.
        assertTrue(Files.readString(root.resolve("profiles/default.json")).contains("\"modules\""));
    }

    @Test
    public void invalidValuesAreClampedOrIgnored() throws Exception {
        Path root = tmp.newFolder("v").toPath();
        Files.createDirectories(root.resolve("profiles"));
        Files.writeString(root.resolve("profiles/default.json"),
                "{\"modules\":{\"Base Finder\":{\"enabled\":true,\"settings\":{\"Range\":999,\"Flag\":\"yes\",\"Mode\":\"NOPE\",\"Color\":\"#12345678\",\"Unknown\":1}},\"Ghost\":{}}}");
        Harness h = harness();
        ConfigManager cfg = new ConfigManager(root, h.modules);
        List<String> warnings = new ArrayList<>();
        cfg.setWarningSink(warnings::add);
        cfg.loadAll();
        SampleModule bf = (SampleModule) h.modules.get("Base Finder");
        assertEquals("clamped to max", 32.0, bf.range.get(), 1e-9);
        assertTrue("wrong type keeps default", bf.flag.get());
        assertEquals(Category.WORLD, bf.cat.get());
        assertEquals(0x12345678, (int) bf.color.get());
        assertTrue(bf.isEnabled());
        assertEquals(2, warnings.size());
    }

    @Test
    public void uiPreferencesPersist() {
        Path root = tmp.getRoot().toPath().resolve("ui");
        Harness h = harness();
        ConfigManager cfg = new ConfigManager(root, h.modules);
        cfg.loadAll();
        cfg.uiPrefs().addProperty("category", "BUILDING");
        cfg.saveClientFile();
        ConfigManager cfg2 = new ConfigManager(root, harness().modules);
        cfg2.loadAll();
        assertEquals("BUILDING", cfg2.uiPrefs().get("category").getAsString());
    }

    @Test
    public void dirtyFlagSavesOnlyWhenChanged() throws Exception {
        Path root = tmp.newFolder("d").toPath();
        Harness h = harness();
        ConfigManager cfg = new ConfigManager(root, h.modules);
        cfg.loadAll();
        Path file = root.resolve("profiles/default.json");
        long before = Files.getLastModifiedTime(file).toMillis();
        cfg.flushIfDirty();
        assertEquals(before, Files.getLastModifiedTime(file).toMillis());
        ((SampleModule) h.modules.get("Flight")).range.set(3.0);
        cfg.markDirty();
        cfg.flushIfDirty();
        assertFalse(cfg.isDirty());
        assertTrue(Files.readString(file).contains("3.0"));
    }
}
