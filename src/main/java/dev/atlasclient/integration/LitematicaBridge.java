package dev.atlasclient.integration;

import dev.atlasclient.Atlas;
import dev.atlasclient.core.schematic.LitematicaPlacementImporter;
import dev.atlasclient.core.schematic.Placement;
import dev.atlasclient.core.schematic.transform.Mirror;
import dev.atlasclient.core.schematic.transform.Rotation;
import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Litematica interoperability without a compile-time dependency.
 *
 * <ul>
 *   <li><b>File import</b> (always available, tested): reads placements Litematica saved to
 *       {@code config/litematica/litematica_*.json}. Litematica writes that file when you leave a
 *       world, so placements made in the current session appear after re-joining.</li>
 *   <li><b>Live read</b> (only when Litematica is installed; EXPERIMENTAL, not tested in this
 *       project's build environment): calls Litematica's public placement API by reflection -
 *       {@code DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()} - and
 *       copies origin/rotation/mirror/sub-regions. Any failure disables it with a message.</li>
 * </ul>
 * Litematica's files and state are never modified.
 */
public final class LitematicaBridge {
    private static String liveFailure = "";

    private LitematicaBridge() { }

    public static boolean installed() { return FabricLoader.getInstance().isModLoaded("litematica"); }

    public static Path configDir() { return FabricLoader.getInstance().getConfigDir().resolve("litematica"); }

    public static List<LitematicaPlacementImporter.Imported> fromFiles() {
        List<LitematicaPlacementImporter.Imported> out = new ArrayList<>();
        for (Path f : LitematicaPlacementImporter.findDataFiles(configDir())) {
            try {
                out.addAll(LitematicaPlacementImporter.read(f));
            } catch (Exception e) {
                Atlas.LOG.warn("Could not read Litematica data file {}", f, e);
            }
        }
        return out;
    }

    public static String liveFailure() { return liveFailure; }

    /** Reads placements from the running Litematica instance. Returns an empty list on any failure. */
    public static List<Placement> live() {
        List<Placement> out = new ArrayList<>();
        if (!installed()) {
            liveFailure = "Litematica is not installed";
            return out;
        }
        try {
            Class<?> dm = Class.forName("fi.dy.masa.litematica.data.DataManager");
            Object manager = dm.getMethod("getSchematicPlacementManager").invoke(null);
            Collection<?> placements = (Collection<?>) manager.getClass().getMethod("getAllSchematicsPlacements").invoke(manager);
            for (Object sp : placements) {
                Object file = call(sp, "getSchematicFile");
                if (file == null) continue; // in-memory schematic without a file
                Object origin = call(sp, "getOrigin");
                int[] o = xyz(origin);
                Placement p = new Placement(file.toString(), String.valueOf(call(sp, "getName")), o[0], o[1], o[2]);
                p.setRotation(rotation(call(sp, "getRotation")));
                p.setMirror(mirror(call(sp, "getMirror")));
                Collection<?> subs = (Collection<?>) call(sp, "getAllSubRegionsPlacements");
                if (subs != null) {
                    for (Object sub : subs) {
                        int[] sp2 = xyz(call(sub, "getPos"));
                        p.putRegion(new Placement.RegionPlacement(String.valueOf(call(sub, "getName")), sp2[0], sp2[1], sp2[2],
                                rotation(call(sub, "getRotation")),
                                mirror(call(sub, "getMirror")),
                                Boolean.TRUE.equals(call(sub, "isEnabled"))));
                    }
                }
                out.add(p);
            }
            liveFailure = "";
        } catch (Throwable t) {
            liveFailure = "Live Litematica read failed (" + t.getClass().getSimpleName() + "); use file import instead";
            Atlas.LOG.warn("Litematica live bridge failed", t);
            out.clear();
        }
        return out;
    }

    /** Litematica returns vanilla BlockRotation/BlockMirror; compare constants, never enum names. */
    private static Rotation rotation(Object o) throws ReflectiveOperationException {
        if (o instanceof net.minecraft.util.BlockRotation r) return dev.atlasclient.build.StateResolver.fromMc(r);
        throw new ReflectiveOperationException("unexpected rotation type " + (o == null ? "null" : o.getClass()));
    }

    private static Mirror mirror(Object o) throws ReflectiveOperationException {
        if (o instanceof net.minecraft.util.BlockMirror m) return dev.atlasclient.build.StateResolver.fromMc(m);
        throw new ReflectiveOperationException("unexpected mirror type " + (o == null ? "null" : o.getClass()));
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        Method m = target.getClass().getMethod(name);
        return m.invoke(target);
    }

    /** BlockPos at runtime is a Vec3i; read coordinates through its public getters. */
    private static int[] xyz(Object pos) throws ReflectiveOperationException {
        if (pos instanceof net.minecraft.util.math.Vec3i v) {
            return new int[]{v.getX(), v.getY(), v.getZ()};
        }
        throw new ReflectiveOperationException("unexpected position type " + (pos == null ? "null" : pos.getClass()));
    }
}
