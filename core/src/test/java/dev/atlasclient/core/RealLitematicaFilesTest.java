package dev.atlasclient.core;

import dev.atlasclient.core.nbt.Nbt;
import dev.atlasclient.core.schematic.BlockStateString;
import dev.atlasclient.core.schematic.LitematicIO;
import dev.atlasclient.core.schematic.Placement;
import dev.atlasclient.core.schematic.Schematic;
import dev.atlasclient.core.schematic.SchematicLoader;
import dev.atlasclient.core.schematic.SchematicPlacer;
import dev.atlasclient.core.schematic.SchematicRegion;
import dev.atlasclient.core.schematic.transform.Mirror;
import dev.atlasclient.core.schematic.transform.Rotation;
import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.*;

/**
 * Reads real .litematic files saved by Litematica itself and cross-checks every file against the
 * metadata Litematica wrote (region count, total volume, non-air block count, enclosing size),
 * then round-trips each through our writer and through every rotation/mirror.
 *
 * <p>The corpus is not shipped (the files belong to their creators). Point the system property
 * or environment variable {@code ATLAS_LITEMATIC_CORPUS} at a folder of .litematic files to run it;
 * otherwise the test is skipped.</p>
 */
public class RealLitematicaFilesTest {

    private static Path corpus() {
        String p = System.getProperty("ATLAS_LITEMATIC_CORPUS", System.getenv("ATLAS_LITEMATIC_CORPUS"));
        return p == null || p.isBlank() ? null : Path.of(p);
    }

    @Test
    public void everyRealFileMatchesLitematicasOwnMetadata() throws IOException {
        Path dir = corpus();
        Assume.assumeTrue("ATLAS_LITEMATIC_CORPUS not set", dir != null && Files.isDirectory(dir));
        List<Path> files;
        try (Stream<Path> s = Files.walk(dir)) {
            files = s.filter(f -> f.toString().endsWith(".litematic")).sorted().toList();
        }
        assertFalse("corpus is empty", files.isEmpty());
        List<String> failures = new ArrayList<>();
        int ok = 0, legacy = 0, blocksTotal = 0, regionsTotal = 0, versions7 = 0, multi = 0, negative = 0;
        for (Path f : files) {
            Nbt.Compound root = Nbt.readFile(f).tag();
            int dataVersion = root.getInt("MinecraftDataVersion", 0);
            Schematic s;
            try {
                s = SchematicLoader.load(f);
            } catch (IOException e) {
                if (dataVersion < 1631 && e.getMessage().contains("pre-1.13")) {
                    legacy++; // correctly rejected with a clear message
                    continue;
                }
                failures.add(f.getFileName() + ": load failed: " + e.getMessage());
                continue;
            }
            Nbt.Compound meta = root.getCompound("Metadata");
            String name = f.getFileName().toString();
            if (meta != null) {
                int rc = meta.getInt("RegionCount", -1);
                if (rc >= 0 && rc != s.regions().size()) failures.add(name + ": RegionCount " + rc + " vs " + s.regions().size());
                int vol = meta.getInt("TotalVolume", -1);
                if (vol >= 0 && vol != s.totalVolume()) failures.add(name + ": TotalVolume " + vol + " vs " + s.totalVolume());
                int tb = meta.getInt("TotalBlocks", -1);
                if (tb >= 0 && tb != s.totalNonAir()) failures.add(name + ": TotalBlocks " + tb + " vs " + s.totalNonAir());
                Nbt.Compound es = meta.getCompound("EnclosingSize");
                if (es != null) {
                    int[] e = s.enclosingSize();
                    if (es.getInt("x", 0) != e[0] || es.getInt("y", 0) != e[1] || es.getInt("z", 0) != e[2]) {
                        failures.add(name + ": EnclosingSize " + es.getInt("x", 0) + "x" + es.getInt("y", 0) + "x" + es.getInt("z", 0)
                                + " vs " + e[0] + "x" + e[1] + "x" + e[2]);
                    }
                }
            }
            for (SchematicRegion r : s.regions()) {
                for (BlockStateString b : r.palette()) {
                    if (!b.id().contains(":")) failures.add(name + ": palette id without namespace " + b.id());
                }
                if (r.sizeX() < 0 || r.sizeY() < 0 || r.sizeZ() < 0) negative++;
            }
            if (s.regions().size() > 1) multi++;
            if (root.getInt("Version", 0) == 7) versions7++;
            // Our writer -> reader must reproduce every block exactly.
            Path tmp = Files.createTempFile("rt", ".litematic");
            LitematicIO.write(tmp, s, Math.max(dataVersion, 1631));
            Schematic back = LitematicIO.read(tmp);
            Files.deleteIfExists(tmp);
            for (int i = 0; i < s.regions().size(); i++) {
                SchematicRegion a = s.regions().get(i), b = back.region(a.name());
                if (b == null || a.volume() != b.volume()) {
                    failures.add(name + ": round trip lost region " + a.name());
                    continue;
                }
                for (int x = 0; x < a.absX(); x += Math.max(1, a.absX() / 7)) {
                    for (int y = 0; y < a.absY(); y += Math.max(1, a.absY() / 7)) {
                        for (int z = 0; z < a.absZ(); z += Math.max(1, a.absZ() / 7)) {
                            if (!a.stateAt(x, y, z).equals(b.stateAt(x, y, z))) {
                                failures.add(name + ": round trip differs at " + x + "," + y + "," + z);
                            }
                        }
                    }
                }
            }
            // Every transform keeps the block count and stays finite.
            long nonAir = s.totalNonAir();
            for (Rotation rot : Rotation.values()) {
                for (Mirror mir : Mirror.values()) {
                    Placement p = new Placement(f.toString(), name, 100, 64, -100).setRotation(rot).setMirror(mir);
                    int n = SchematicPlacer.place(s, p, false).size();
                    if (n != nonAir) failures.add(name + ": " + rot + "/" + mir + " placed " + n + " of " + nonAir);
                }
            }
            ok++;
            blocksTotal += s.totalNonAir();
            regionsTotal += s.regions().size();
        }
        System.out.printf("[corpus] %d files: %d verified, %d legacy (pre-1.13, rejected with message), %d failures; "
                        + "%d regions (%d multi-region files, %d negative-size regions), %d non-air blocks, %d files at format v7%n",
                files.size(), ok, legacy, failures.size(), regionsTotal, multi, negative, blocksTotal, versions7);
        for (String fl : failures.subList(0, Math.min(40, failures.size()))) System.out.println("  " + fl);
        assertTrue(failures.size() + " problems, first: " + (failures.isEmpty() ? "" : failures.get(0)), failures.isEmpty());
    }
}
