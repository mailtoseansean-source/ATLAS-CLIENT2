package dev.atlasclient.core;

import dev.atlasclient.core.analysis.IndicatorRegistry;
import dev.atlasclient.core.debris.DataAvailability;
import dev.atlasclient.core.debris.DebrisRecord;
import dev.atlasclient.core.debris.DebrisTracker;
import dev.atlasclient.core.debris.DebrisTracker.Found;
import dev.atlasclient.core.nbt.Nbt;
import dev.atlasclient.core.region.LocalWorldAnalyzer;
import dev.atlasclient.core.region.RegionScanner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DeflaterOutputStream;

import static org.junit.Assert.*;

/** Requirement 7: ancient debris detection, availability status, export and local save scanning. */
public class DebrisTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private static final String NETHER = "minecraft:the_nether";

    @Test
    public void deduplicatesAndRemovesMinedDebris() {
        DebrisTracker t = new DebrisTracker(1000);
        var r1 = t.observeChunk(NETHER, 0, 0, List.of(new Found(1, 15, 1, DebrisRecord.Exposure.ENCLOSED),
                new Found(2, 15, 1, DebrisRecord.Exposure.EXPOSED)), 100, 8, DebrisRecord.Source.CLIENT_CHUNK, 10);
        assertEquals(2, r1.added().size());
        var r2 = t.observeChunk(NETHER, 0, 0, List.of(new Found(1, 15, 1, DebrisRecord.Exposure.EXPOSED)), 100, 8,
                DebrisRecord.Source.CLIENT_CHUNK, 20);
        assertEquals("re-observation is not a new find", 0, r2.added().size());
        assertEquals("mined block disappears", 1, r2.removed());
        assertEquals(1, t.count(NETHER));
        DebrisRecord rec = t.all(NETHER).get(0);
        assertEquals(10, rec.firstSeenMillis());
        assertEquals(20, rec.lastSeenMillis());
        assertEquals(DebrisRecord.Exposure.EXPOSED, rec.exposure());
    }

    @Test
    public void fullTrackerDropsEnclosedRecordsBeforeExposedOnes() {
        DebrisTracker t = new DebrisTracker(100);
        t.observeChunk(NETHER, 0, 0, List.of(new Found(3, 20, 3, DebrisRecord.Exposure.EXPOSED)), 10, 8, DebrisRecord.Source.CLIENT_CHUNK, 0);
        // A fake-ore server floods the other chunks with enclosed "debris".
        for (int c = 1; c <= 20; c++) {
            List<Found> fakes = new java.util.ArrayList<>();
            for (int i = 0; i < 16; i++) fakes.add(new Found(c * 16 + i, 20, 5, DebrisRecord.Exposure.ENCLOSED));
            t.observeChunk(NETHER, c, 0, fakes, 10, 8, DebrisRecord.Source.CLIENT_CHUNK, c);
        }
        assertTrue("stays bounded: " + t.count(NETHER), t.count(NETHER) <= 100);
        assertEquals("the real exposed one is kept", 1, t.query(NETHER, 3, 20, 3, 4, true).size());
    }

    @Test
    public void distanceAndExposureFiltering() {
        DebrisTracker t = new DebrisTracker(1000);
        t.observeChunk(NETHER, 0, 0, List.of(new Found(0, 15, 0, DebrisRecord.Exposure.ENCLOSED)), 100, 8, DebrisRecord.Source.CLIENT_CHUNK, 0);
        t.observeChunk(NETHER, 3, 0, List.of(new Found(50, 15, 0, DebrisRecord.Exposure.EXPOSED)), 100, 8, DebrisRecord.Source.CLIENT_CHUNK, 0);
        assertEquals(2, t.query(NETHER, 0, 15, 0, 100, false).size());
        assertEquals(1, t.query(NETHER, 0, 15, 0, 10, false).size());
        assertEquals(1, t.query(NETHER, 0, 15, 0, 100, true).size());
        assertEquals(50, t.query(NETHER, 0, 15, 0, 100, true).get(0).x());
        assertEquals("nearest first", 0, t.query(NETHER, 0, 15, 0, 100, false).get(0).x());
    }

    @Test
    public void availabilityReflectsData() {
        DebrisTracker t = new DebrisTracker(10_000);
        assertEquals(DataAvailability.NOT_IN_NETHER, t.availability("minecraft:overworld"));
        assertEquals(DataAvailability.NO_DATA, t.availability(NETHER));
        // natural-looking data: ~2 debris and ~100 quartz per chunk
        for (int c = 0; c < 20; c++) {
            t.observeChunk(NETHER, c, 0, List.of(new Found(c * 16, 15, 0, DebrisRecord.Exposure.ENCLOSED),
                    new Found(c * 16 + 1, 15, 0, DebrisRecord.Exposure.ENCLOSED)), 100, 8, DebrisRecord.Source.CLIENT_CHUNK, 0);
        }
        assertEquals(DataAvailability.CONSISTENT, t.availability(NETHER));
        assertTrue(t.availability(NETHER).resultsTrustworthy());

        DebrisTracker hidden = new DebrisTracker(10_000);
        for (int c = 0; c < 20; c++) {
            hidden.observeChunk(NETHER, c, 0, List.of(), 0, 8, DebrisRecord.Source.CLIENT_CHUNK, 0);
        }
        assertEquals(DataAvailability.UNAVAILABLE_HIDDEN, hidden.availability(NETHER));
        assertFalse(hidden.availability(NETHER).resultsTrustworthy());

        DebrisTracker fake = new DebrisTracker(100_000);
        for (int c = 0; c < 5; c++) {
            List<Found> lots = new ArrayList<>();
            for (int i = 0; i < 60; i++) lots.add(new Found(c * 16 + (i % 16), 10 + i / 16, 3, DebrisRecord.Exposure.ENCLOSED));
            fake.observeChunk(NETHER, c, 0, lots, 300, 8, DebrisRecord.Source.CLIENT_CHUNK, 0);
        }
        assertEquals(DataAvailability.SUSPECT_FAKE_ORES, fake.availability(NETHER));

        DebrisTracker local = new DebrisTracker(100);
        local.setAuthoritative(true);
        assertEquals(DataAvailability.AUTHORITATIVE_LOCAL, local.availability(NETHER));
    }

    @Test
    public void boundedRecordCount() {
        DebrisTracker t = new DebrisTracker(50);
        for (int c = 0; c < 30; c++) {
            t.observeChunk(NETHER, c, 0, List.of(new Found(c * 16, 15, 0, DebrisRecord.Exposure.EXPOSED),
                    new Found(c * 16 + 1, 15, 0, DebrisRecord.Exposure.EXPOSED)), 0, 8, DebrisRecord.Source.CLIENT_CHUNK, 0);
        }
        assertTrue("bounded: " + t.count(NETHER), t.count(NETHER) <= 50 && t.count(NETHER) >= 45);
    }

    @Test
    public void exportsCsvAndJson() throws Exception {
        DebrisTracker t = new DebrisTracker(100);
        t.observeChunk(NETHER, 0, 0, List.of(new Found(5, 14, 6, DebrisRecord.Exposure.EXPOSED)), 50, 8, DebrisRecord.Source.CLIENT_CHUNK, 7);
        Path csv = tmp.getRoot().toPath().resolve("out/debris.csv");
        Path json = tmp.getRoot().toPath().resolve("out/debris.json");
        assertEquals(1, t.export(csv, NETHER, DebrisTracker.ExportFormat.CSV, 0, 0, 0));
        assertEquals(1, t.export(json, NETHER, DebrisTracker.ExportFormat.JSON, 0, 0, 0));
        List<String> lines = Files.readAllLines(csv);
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).startsWith("minecraft:the_nether,5,14,6,EXPOSED"));
        assertTrue(Files.readString(json).contains("\"x\": 5"));
    }

    // ------------------------------------------------------- region file (local save) scanning

    /** Builds a one-chunk region file with debris enclosed by netherrack and one exposed to air. */
    static Path writeRegion(Path dir) throws Exception {
        Files.createDirectories(dir);
        // palette: 0 netherrack, 1 ancient_debris, 2 air, 3 nether_quartz_ore -> 4 bits per entry
        Nbt.ListTag palette = new Nbt.ListTag(Nbt.COMPOUND);
        for (String n : new String[]{"minecraft:netherrack", "minecraft:ancient_debris", "minecraft:air", "minecraft:nether_quartz_ore"}) {
            palette.add(new Nbt.Compound().putString("Name", n));
        }
        int[] blocks = new int[4096];
        // y=1 (section 0): enclosed debris at (5,1,5); exposed debris at (10,1,10) with air at (10,2,10)
        blocks[(1 << 8) | (5 << 4) | 5] = 1;
        blocks[(1 << 8) | (10 << 4) | 10] = 1;
        blocks[(2 << 8) | (10 << 4) | 10] = 2;
        for (int i = 0; i < 7; i++) blocks[(5 << 8) | (i << 4)] = 3;
        long[] data = new long[4096 * 4 / 64];
        for (int i = 0; i < 4096; i++) {
            data[i / 16] |= ((long) blocks[i]) << ((i % 16) * 4);
        }
        Nbt.Compound states = new Nbt.Compound().put("palette", palette).put("data", data);
        Nbt.Compound section = new Nbt.Compound().putByte("Y", 0).put("block_states", states);
        Nbt.ListTag sections = new Nbt.ListTag(Nbt.COMPOUND);
        sections.add(section);
        Nbt.Compound chunk = new Nbt.Compound().putInt("DataVersion", 4671).putInt("xPos", 0).putInt("zPos", 0)
                .put("sections", sections);
        byte[] raw = Nbt.toUncompressedBytes("", chunk);
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        try (DeflaterOutputStream d = new DeflaterOutputStream(z)) {
            d.write(raw);
        }
        byte[] compressed = z.toByteArray();
        Path file = dir.resolve("r.0.0.mca");
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "rw")) {
            f.setLength(8192);
            int sectors = (compressed.length + 5 + 4095) / 4096;
            f.seek(0);
            f.writeInt((2 << 8) | sectors); // chunk (0,0) at sector 2
            f.seek(8192);
            f.writeInt(compressed.length + 1);
            f.writeByte(2); // zlib
            f.write(compressed);
            f.setLength(8192 + (long) sectors * 4096);
        }
        return dir;
    }

    @Test
    public void scansOwnedSaveWithExposure() throws Exception {
        Path world = tmp.newFolder("world").toPath();
        writeRegion(RegionScanner.regionDir(world, NETHER));
        LocalWorldAnalyzer analyzer = new LocalWorldAnalyzer(IndicatorRegistry.vanillaDefaults());
        List<LocalWorldAnalyzer.DebrisChunk> found = new ArrayList<>();
        RegionScanner.Stats stats = analyzer.scanDebris(RegionScanner.regionDir(world, NETHER), 0, 0, 4, found::add, () -> false);
        assertEquals(1, stats.regionsRead());
        assertEquals(1, stats.chunksDecoded());
        assertEquals(1, found.size());
        LocalWorldAnalyzer.DebrisChunk c = found.get(0);
        assertEquals(2, c.found().size());
        assertEquals(7, c.naturalOres());
        Found enclosed = c.found().stream().filter(f -> f.x() == 5).findFirst().orElseThrow();
        Found exposed = c.found().stream().filter(f -> f.x() == 10).findFirst().orElseThrow();
        assertEquals(DebrisRecord.Exposure.ENCLOSED, enclosed.exposure());
        assertEquals(DebrisRecord.Exposure.EXPOSED, exposed.exposure());
        assertEquals(1, enclosed.y());
    }

    @Test
    public void corruptRegionIsReportedNotThrown() throws Exception {
        Path dir = tmp.newFolder("bad").toPath();
        Path file = dir.resolve("r.0.0.mca");
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "rw")) {
            f.setLength(12288);
            f.writeInt((2 << 8) | 1);
            f.seek(8192);
            f.writeInt(100);
            f.writeByte(2);
            f.write(new byte[99]); // garbage zlib
        }
        RegionScanner.Stats s = new RegionScanner().scan(dir, 0, 0, 2, n -> true, c -> fail(), () -> false);
        assertEquals(1, s.chunksSkipped());
        assertFalse(s.problems().isEmpty());
    }
}
