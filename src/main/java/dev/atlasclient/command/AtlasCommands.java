package dev.atlasclient.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.atlasclient.Atlas;
import dev.atlasclient.AtlasClient;
import dev.atlasclient.build.SchematicSession;
import dev.atlasclient.core.build.AreaMining;
import dev.atlasclient.core.debris.DataAvailability;
import dev.atlasclient.core.module.Module;
import dev.atlasclient.core.notify.Notification;
import dev.atlasclient.core.schematic.transform.Mirror;
import dev.atlasclient.core.schematic.transform.Rotation;
import dev.atlasclient.module.building.AutoBuilderModule;
import dev.atlasclient.module.render.WaypointsModule;
import dev.atlasclient.util.Env;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Client-side commands (never sent to the server): {@code /atlas ...}. Everything here is also
 * reachable from the ClickGUI; commands are convenient for typing coordinates and file names.
 */
public final class AtlasCommands {
    private AtlasCommands() { }

    public static void register(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(ClientCommandManager.literal("atlas")
                .then(ClientCommandManager.literal("modules").executes(c -> modules(c)))
                .then(ClientCommandManager.literal("toggle")
                        .then(ClientCommandManager.argument("module", StringArgumentType.greedyString()).executes(c -> toggle(c))))
                .then(ClientCommandManager.literal("schem")
                        .then(ClientCommandManager.literal("list").executes(c -> schemList(c)))
                        .then(ClientCommandManager.literal("load")
                                .then(ClientCommandManager.argument("file", StringArgumentType.greedyString()).executes(c -> schemLoad(c))))
                        .then(ClientCommandManager.literal("origin")
                                .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                                        .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                                                .then(ClientCommandManager.argument("z", IntegerArgumentType.integer()).executes(c -> origin(c))))))
                        .then(ClientCommandManager.literal("rotate")
                                .then(ClientCommandManager.argument("rotation", StringArgumentType.word()).executes(c -> rotate(c))))
                        .then(ClientCommandManager.literal("mirror")
                                .then(ClientCommandManager.argument("mirror", StringArgumentType.word()).executes(c -> mirror(c))))
                        .then(ClientCommandManager.literal("unload").executes(c -> { builder().session.unload(); return ok(c, "Schematic unloaded"); })))
                .then(ClientCommandManager.literal("build")
                        .then(ClientCommandManager.literal("start").executes(c -> { builder().start(); return 1; }))
                        .then(ClientCommandManager.literal("pause").executes(c -> { builder().togglePause(); return 1; }))
                        .then(ClientCommandManager.literal("resume").executes(c -> { builder().togglePause(); return 1; }))
                        .then(ClientCommandManager.literal("cancel").executes(c -> { builder().cancel(); return 1; }))
                        .then(ClientCommandManager.literal("restart").executes(c -> { builder().restart(); return 1; }))
                        .then(ClientCommandManager.literal("retry").executes(c -> { builder().retrySkipped(); return 1; }))
                        .then(ClientCommandManager.literal("excavate")
                                .then(ClientCommandManager.argument("on", StringArgumentType.word()).executes(c -> excavate(c))))
                        .then(ClientCommandManager.literal("pickaxe")
                                .then(ClientCommandManager.argument("mode", StringArgumentType.word()).executes(c -> pickaxe(c)))))
                .then(ClientCommandManager.literal("debris")
                        .then(ClientCommandManager.literal("status").executes(c -> debrisStatus(c))))
                .then(ClientCommandManager.literal("waypoint")
                        .then(ClientCommandManager.literal("add")
                                .then(ClientCommandManager.argument("name", StringArgumentType.greedyString()).executes(c -> waypointAdd(c))))
                        .then(ClientCommandManager.literal("remove")
                                .then(ClientCommandManager.argument("name", StringArgumentType.greedyString()).executes(c -> waypointRemove(c))))
                        .then(ClientCommandManager.literal("list").executes(c -> waypointList(c))))
                .then(ClientCommandManager.literal("notifications").executes(c -> notifications(c)))
                .then(ClientCommandManager.literal("diag").executes(c -> diag(c))));
    }

    private static AutoBuilderModule builder() { return Atlas.get().modules.get(AutoBuilderModule.class); }

    private static int ok(CommandContext<FabricClientCommandSource> c, String msg) {
        c.getSource().sendFeedback(Text.literal("[Atlas] " + msg));
        return 1;
    }

    private static int fail(CommandContext<FabricClientCommandSource> c, String msg) {
        c.getSource().sendError(Text.literal("[Atlas] " + msg));
        return 0;
    }

    private static int modules(CommandContext<FabricClientCommandSource> c) {
        StringBuilder sb = new StringBuilder();
        for (Module m : Atlas.get().modules.all()) {
            sb.append(m.isEnabled() ? "+" : "-").append(m.name()).append("  ");
        }
        return ok(c, Atlas.get().modules.size() + " modules: " + sb);
    }

    private static int toggle(CommandContext<FabricClientCommandSource> c) {
        String name = StringArgumentType.getString(c, "module");
        Module m = Atlas.get().modules.get(name);
        if (m == null) return fail(c, "Unknown module: " + name);
        m.toggle();
        return ok(c, m.name() + " " + (m.isEnabled() ? "enabled" : "disabled"));
    }

    private static int schemList(CommandContext<FabricClientCommandSource> c) {
        List<String> files = SchematicSession.available();
        if (files.isEmpty()) return fail(c, "No .schem/.litematic files in " + SchematicSession.folders());
        return ok(c, String.join(", ", files));
    }

    private static int schemLoad(CommandContext<FabricClientCommandSource> c) {
        String name = StringArgumentType.getString(c, "file");
        Path f = SchematicSession.locate(name);
        if (f == null) return fail(c, "Not found: " + name);
        var p = c.getSource().getPlayer();
        try {
            builder().session.load(f, p.getBlockPos().getX(), p.getBlockPos().getY(), p.getBlockPos().getZ());
            builder().session.save();
            Atlas.get().notifications.info("Schematic loaded", builder().session.schematic().name());
            return ok(c, "Loaded " + f.getFileName() + " at your position");
        } catch (Exception e) {
            return fail(c, "Load failed: " + e.getMessage());
        }
    }

    private static int origin(CommandContext<FabricClientCommandSource> c) {
        if (!builder().session.loaded()) return fail(c, "Load a schematic first");
        builder().session.setOrigin(IntegerArgumentType.getInteger(c, "x"), IntegerArgumentType.getInteger(c, "y"), IntegerArgumentType.getInteger(c, "z"));
        builder().session.save();
        return ok(c, "Origin set");
    }

    private static int rotate(CommandContext<FabricClientCommandSource> c) {
        if (!builder().session.loaded()) return fail(c, "Load a schematic first");
        try {
            builder().session.setRotation(Rotation.parse(StringArgumentType.getString(c, "rotation")));
            builder().session.save();
            return ok(c, "Rotation " + builder().session.placement().rotation());
        } catch (IllegalArgumentException e) {
            return fail(c, "Use 0, 90, 180, 270 or NONE/CLOCKWISE_90/CLOCKWISE_180/COUNTERCLOCKWISE_90");
        }
    }

    private static int mirror(CommandContext<FabricClientCommandSource> c) {
        if (!builder().session.loaded()) return fail(c, "Load a schematic first");
        try {
            builder().session.setMirror(Mirror.parse(StringArgumentType.getString(c, "mirror")));
            builder().session.save();
            return ok(c, "Mirror " + builder().session.placement().mirror());
        } catch (IllegalArgumentException e) {
            return fail(c, "Use none, x (FRONT_BACK) or z (LEFT_RIGHT)");
        }
    }

    private static int excavate(CommandContext<FabricClientCommandSource> c) {
        String v = StringArgumentType.getString(c, "on").toLowerCase(Locale.ROOT);
        builder().excavate.set(v.equals("on") || v.equals("true") || v.equals("yes"));
        Atlas.get().config.markDirty();
        return ok(c, "Excavate first: " + builder().excavate.get());
    }

    private static int pickaxe(CommandContext<FabricClientCommandSource> c) {
        String v = StringArgumentType.getString(c, "mode").toLowerCase(Locale.ROOT);
        AreaMining.PickaxeMode mode = switch (v) {
            case "amethyst", "3x3", "area" -> AreaMining.PickaxeMode.AMETHYST;
            case "regular", "normal" -> AreaMining.PickaxeMode.REGULAR;
            default -> null;
        };
        if (mode == null) return fail(c, "Use regular or amethyst");
        builder().setPickaxeMode(mode);
        return ok(c, "Pickaxe mode " + mode);
    }

    private static int debrisStatus(CommandContext<FabricClientCommandSource> c) {
        DataAvailability av = AtlasClient.scan().debris().availability(Env.dimension());
        return ok(c, av.message() + " (" + AtlasClient.scan().debris().count(Env.dimension()) + " tracked, "
                + AtlasClient.scan().debris().scannedChunks(Env.dimension()) + " chunks scanned)");
    }

    private static int waypointAdd(CommandContext<FabricClientCommandSource> c) {
        var p = c.getSource().getPlayer();
        Atlas.get().modules.get(WaypointsModule.class).add(StringArgumentType.getString(c, "name"),
                p.getBlockPos().getX(), p.getBlockPos().getY(), p.getBlockPos().getZ(), 0, "manual");
        return 1;
    }

    private static int waypointRemove(CommandContext<FabricClientCommandSource> c) {
        String n = StringArgumentType.getString(c, "name");
        return Atlas.get().modules.get(WaypointsModule.class).remove(n) ? ok(c, "Removed " + n) : fail(c, "No waypoint " + n);
    }

    private static int waypointList(CommandContext<FabricClientCommandSource> c) {
        StringBuilder sb = new StringBuilder();
        for (WaypointsModule.Waypoint w : Atlas.get().modules.get(WaypointsModule.class).all()) {
            sb.append(w.name()).append(" (").append(w.x()).append(' ').append(w.y()).append(' ').append(w.z()).append(", ")
                    .append(w.dimension()).append(")  ");
        }
        return ok(c, sb.isEmpty() ? "No waypoints" : sb.toString());
    }

    private static int notifications(CommandContext<FabricClientCommandSource> c) {
        List<Notification> h = Atlas.get().notifications.history();
        for (int i = Math.min(9, h.size() - 1); i >= 0; i--) {
            Notification n = h.get(i);
            c.getSource().sendFeedback(Text.literal("[" + n.level() + "] " + n.title() + ": " + n.message()));
        }
        return 1;
    }

    private static int diag(CommandContext<FabricClientCommandSource> c) {
        return ok(c, String.format(Locale.ROOT, "%d modules, %d listeners, scan queue %d, analysed %d chunks, %d discoveries cached",
                Atlas.get().modules.size(), Atlas.get().bus.totalListenerCount(), AtlasClient.scan().queue().pendingCount(),
                AtlasClient.scan().scannedCount(), AtlasClient.scan().discoveries().size()));
    }
}
