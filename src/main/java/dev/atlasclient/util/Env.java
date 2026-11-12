package dev.atlasclient.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.world.World;

import java.util.Locale;

/** Small helpers describing where the player currently is. Client thread only. */
public final class Env {
    private Env() { }

    public static MinecraftClient mc() { return MinecraftClient.getInstance(); }

    public static boolean inWorld() {
        MinecraftClient mc = mc();
        return mc.world != null && mc.player != null;
    }

    /** Dimension id such as "minecraft:the_nether", or "" when not in a world. */
    public static String dimension() {
        MinecraftClient mc = mc();
        return mc.world == null ? "" : mc.world.getRegistryKey().getValue().toString();
    }

    public static boolean inNether() {
        MinecraftClient mc = mc();
        return mc.world != null && mc.world.getRegistryKey() == World.NETHER;
    }

    /** True for single-player and LAN-hosting (the integrated server is ours). */
    public static boolean singleplayer() { return mc().isIntegratedServerRunning(); }

    /** Server address in lower case, or "" in single-player. */
    public static String serverAddress() {
        ServerInfo info = mc().getCurrentServerEntry();
        return info == null || info.address == null ? "" : info.address.trim().toLowerCase(Locale.ROOT);
    }

    /** Stable key for per-world storage: "sp_<level name>" or "mp_<address>". */
    public static String worldKey() {
        MinecraftClient mc = mc();
        IntegratedServer server = mc.getServer();
        if (server != null) {
            return "sp_" + server.getSaveProperties().getLevelName();
        }
        String addr = serverAddress();
        return addr.isEmpty() ? "" : "mp_" + addr;
    }
}
