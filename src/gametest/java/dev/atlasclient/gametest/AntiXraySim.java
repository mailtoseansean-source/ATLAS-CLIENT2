package dev.atlasclient.gametest;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Test-only stand-in for a server's anti-xray (like Paper's), applied to the block data the client
 * received, so X-Ray, Debris Finder and Base Finder can be tested against it:
 * <ul>
 *   <li>{@link Mode#HIDE} (Paper engine-mode 1): ores and debris not touching air are sent as stone
 *   (deepslate below Y 0, netherrack in the Nether);</li>
 *   <li>{@link Mode#FAKE} (Paper engine-mode 2): every enclosed stone/ore block is sent as a random
 *   ore (diamond, gold, iron, redstone, ancient debris...), so real and fake look the same.</li>
 * </ul>
 * Like the real thing, the true blocks around a block you break are sent (radius 2). Only chunks
 * within {@code radius} of the centre are touched; {@link #stop} restores them from the server.
 */
final class AntiXraySim {
    enum Mode { OFF, HIDE, FAKE }

    private static Mode mode = Mode.OFF;
    private static MinecraftServer server;
    private static int centerCx, centerCz, radius;
    private static final Set<Long> touched = new HashSet<>();
    /** The true block (as the server sent it) for every position the simulation changed. */
    private static final java.util.Map<Long, BlockState> originals = new java.util.HashMap<>();
    private static final Random rnd = new Random(42);
    private static boolean registered;
    private static long clientTicks;
    private static final List<long[]> pendingReveals = new ArrayList<>();
    private static final List<Block> FAKES = new ArrayList<>();
    private static final Set<Block> HIDDEN = new HashSet<>();

    private AntiXraySim() { }

    private static Block block(String id) { return Registries.BLOCK.get(Identifier.of("minecraft", id)); }

    static void register() {
        if (registered) return;
        registered = true;
        for (String id : new String[]{"diamond_ore", "deepslate_diamond_ore", "gold_ore", "deepslate_gold_ore", "iron_ore",
                "deepslate_iron_ore", "redstone_ore", "deepslate_redstone_ore", "emerald_ore", "lapis_ore", "copper_ore",
                "coal_ore", "ancient_debris", "nether_gold_ore", "nether_quartz_ore"}) {
            HIDDEN.add(block(id));
        }
        for (String id : new String[]{"diamond_ore", "gold_ore", "iron_ore", "redstone_ore", "lapis_ore", "ancient_debris",
                "deepslate_diamond_ore", "nether_gold_ore"}) {
            FAKES.add(block(id));
        }
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            if (mode != Mode.OFF && near(chunk.getPos().x, chunk.getPos().z)) obfuscate(world, chunk);
        });
        // The server re-sends the real blocks around every block a player breaks. Done a few ticks later,
        // like real packets: changes made during the client's own break prediction are rolled back.
        ClientPlayerBlockBreakEvents.AFTER.register((world, player, pos, state) -> {
            if (mode != Mode.OFF) pendingReveals.add(new long[]{pos.asLong(), clientTicks + 5});
        });
        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            clientTicks++;
            if (mc.world == null) return;
            for (var it = pendingReveals.iterator(); it.hasNext(); ) {
                long[] r = it.next();
                if (clientTicks < r[1]) continue;
                reveal(mc.world, BlockPos.fromLong(r[0]), 2);
                it.remove();
            }
        });
    }

    /** Called on the client thread. */
    static void start(Mode m, MinecraftServer srv, int cx, int cz, int r) {
        mode = m;
        server = srv;
        centerCx = cx;
        centerCz = cz;
        radius = r;
        MinecraftClient mc = MinecraftClient.getInstance();
        for (int x = cx - r; x <= cx + r; x++) {
            for (int z = cz - r; z <= cz + r; z++) {
                WorldChunk c = mc.world.getChunkManager().getWorldChunk(x, z, false);
                if (c != null) obfuscate(mc.world, c);
            }
        }
    }

    /** Puts the real blocks back (client thread; uses what the server had sent, never the server world). */
    static void stop() {
        Mode was = mode;
        mode = Mode.OFF;
        if (was == Mode.OFF) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        for (java.util.Map.Entry<Long, BlockState> e : originals.entrySet()) {
            BlockPos p = BlockPos.fromLong(e.getKey());
            WorldChunk c = mc.world.getChunkManager().getWorldChunk(p.getX() >> 4, p.getZ() >> 4, false);
            if (c == null) continue;
            int si = c.getSectionIndex(p.getY());
            c.getSectionArray()[si].setBlockState(p.getX() & 15, p.getY() & 15, p.getZ() & 15, e.getValue(), false);
        }
        originals.clear();
        touched.clear();
    }

    static Mode mode() { return mode; }

    private static boolean near(int cx, int cz) {
        return Math.abs(cx - centerCx) <= radius && Math.abs(cz - centerCz) <= radius;
    }

    private static ServerWorld serverWorld(ClientWorld w) {
        return server == null || w == null ? null : server.getWorld(w.getRegistryKey());
    }

    private static boolean exposed(ClientWorld w, BlockPos p) {
        for (Direction d : Direction.values()) {
            BlockPos n = p.offset(d);
            BlockState s = w.getBlockState(n);
            if (!s.isOpaqueFullCube()) return true;
        }
        return false;
    }

    private static void obfuscate(ClientWorld w, WorldChunk c) {
        touched.add(((long) c.getPos().x << 32) | (c.getPos().z & 0xFFFFFFFFL));
        boolean nether = w.getRegistryKey().getValue().getPath().equals("the_nether");
        Block stone = block("stone"), deepslate = block("deepslate"), netherrack = block("netherrack");
        List<int[]> changes = new ArrayList<>();
        List<BlockState> to = new ArrayList<>();
        BlockPos.Mutable p = new BlockPos.Mutable();
        for (int i = 0; i < c.getSectionArray().length; i++) {
            ChunkSection s = c.getSectionArray()[i];
            if (s == null || s.isEmpty()) continue;
            int baseY = (c.getBottomSectionCoord() + i) << 4;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                BlockState st = s.getBlockState(x, y, z);
                Block b = st.getBlock();
                boolean ground = b == stone || b == deepslate || b == netherrack || HIDDEN.contains(b);
                if (!ground) continue;
                p.set((c.getPos().x << 4) + x, baseY + y, (c.getPos().z << 4) + z);
                if (exposed(w, p)) continue;
                BlockState replacement = null;
                if (mode == Mode.HIDE) {
                    if (HIDDEN.contains(b)) {
                        replacement = (nether ? netherrack : baseY + y < 0 ? deepslate : stone).getDefaultState();
                    }
                } else if (rnd.nextInt(4) == 0) {
                    List<Block> pool = nether ? List.of(block("ancient_debris"), block("nether_gold_ore"), block("nether_quartz_ore")) : FAKES;
                    replacement = pool.get(rnd.nextInt(pool.size())).getDefaultState();
                } else if (HIDDEN.contains(b)) {
                    replacement = (nether ? netherrack : baseY + y < 0 ? deepslate : stone).getDefaultState();
                }
                if (replacement != null && replacement != st) {
                    changes.add(new int[]{i, x, y, z});
                    to.add(replacement);
                }
            }
        }
        for (int j = 0; j < changes.size(); j++) {
            int[] ch = changes.get(j);
            ChunkSection sec = c.getSectionArray()[ch[0]];
            long key = BlockPos.asLong((c.getPos().x << 4) + ch[1], ((c.getBottomSectionCoord() + ch[0]) << 4) + ch[2], (c.getPos().z << 4) + ch[3]);
            originals.putIfAbsent(key, sec.getBlockState(ch[1], ch[2], ch[3]));
            sec.setBlockState(ch[1], ch[2], ch[3], to.get(j), false);
        }
    }

    /** What a server does after a block is broken: send the real blocks around it. */
    static void reveal(ClientWorld w, BlockPos center, int r) {
        for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
            BlockPos p = center.add(dx, dy, dz);
            BlockState real = originals.remove(p.asLong());
            if (real != null) w.setBlockState(p, real, Block.NOTIFY_ALL);
        }
    }
}
