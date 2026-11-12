package dev.atlasclient.core;

import dev.atlasclient.core.build.DefaultBlockTraits;
import dev.atlasclient.core.samples.SampleSchematics;
import dev.atlasclient.core.schematic.BlockStateString;
import dev.atlasclient.core.schematic.Placement;
import dev.atlasclient.core.schematic.Schematic;
import dev.atlasclient.core.schematic.SchematicLoader;
import dev.atlasclient.core.schematic.SchematicPlacer;
import dev.atlasclient.core.schematic.SchematicRegion;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/** Writes every shipped sample to disk, reloads it with the real loader and compares block for block. */
public class SampleSchematicsTest {

    private static String key(int x, int y, int z) { return x + "," + y + "," + z; }

    @Test
    public void everySampleRoundTripsThroughItsFileFormat() throws Exception {
        Path dir = Files.createTempDirectory("atlas-samples");
        List<Path> files = SampleSchematics.writeAll(dir);
        assertEquals(SampleSchematics.all().size(), files.size());
        for (int i = 0; i < files.size(); i++) {
            Schematic original = SampleSchematics.all().get(i).schematic();
            Schematic loaded = SchematicLoader.load(files.get(i));
            assertEquals(files.get(i).toString(), original.regions().size(), loaded.regions().size());
            assertEquals(original.totalNonAir(), loaded.totalNonAir());
            // Compare world positions + states after placement at the origin (covers all regions).
            Placement p = new Placement("sample", "sample", 0, 0, 0);
            Set<String> a = new HashSet<>(), b = new HashSet<>();
            for (SchematicPlacer.PlacedBlock pb : SchematicPlacer.place(original, p, false)) a.add(key(pb.x(), pb.y(), pb.z()) + pb.state());
            for (SchematicPlacer.PlacedBlock pb : SchematicPlacer.place(loaded, p, false)) b.add(key(pb.x(), pb.y(), pb.z()) + pb.state());
            assertEquals(files.get(i).getFileName().toString(), a, b);
        }
    }

    @Test
    public void samplesUseOnlyPlaceableStatesAndHaveMaterials() {
        DefaultBlockTraits traits = new DefaultBlockTraits();
        for (SampleSchematics.Sample s : SampleSchematics.all()) {
            for (SchematicRegion r : s.schematic().regions()) {
                for (BlockStateString st : r.palette()) {
                    if (st.isAir()) continue;
                    assertNotEquals(s.file() + " uses unplaceable " + st,
                            dev.atlasclient.core.build.BlockTraits.PlacementClass.UNPLACEABLE, traits.classify(st));
                    assertNotNull(s.file() + " has no item for " + st, traits.requiredItem(st));
                }
            }
        }
    }

    @Test
    public void multiRegionSampleHasNegativeSizesThatPlaceCorrectly() {
        Schematic s = SampleSchematics.multiRegion();
        SchematicRegion tower = s.region("tower");
        assertTrue(tower.sizeY() < 0);
        // Tower corner at y=4 with size -4 covers y=1..4.
        Set<Integer> ys = new HashSet<>();
        for (SchematicPlacer.PlacedBlock pb : SchematicPlacer.place(s, new Placement("multi", "multi", 0, 0, 0), false)) {
            if (pb.state().toString().equals("minecraft:glass")) ys.add(pb.y());
        }
        assertEquals(Set.of(1, 2, 3, 4), ys);
    }
}
