package dev.atlasclient.build;

import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.FallingBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Set;

/**
 * Keeps Auto Mine and the AutoBuilder going on their own, with normal clicks and key presses:
 * <ul>
 *   <li><b>Eat</b> when hunger drops: the best food (steak first) from the hotbar, else from the
 *       inventory. Never rotten flesh, spider eyes, poisonous potatoes, pufferfish, chorus fruit,
 *       suspicious stew or golden apples.</li>
 *   <li><b>Fill a floor gap</b> ahead: sneak to the edge (so you cannot fall) and place a plain
 *       block (cobblestone, deepslate, dirt...) against the side of the block you stand on.</li>
 *   <li><b>Climb back</b> after falling below the working level: pillar up (jump and place a block
 *       under you), mining anything above your head, then go back toward the work area, digging
 *       through walls and bridging gaps the same way.</li>
 * </ul>
 * The caller asks every tick; while this returns true it must not mine, build or walk itself.
 */
public final class SurvivalAssist {
    public enum Phase { IDLE, EATING, FILLING, PILLARING, RETURNING, FAILED }

    private static final Set<String> BAD_FOOD = Set.of("rotten_flesh", "spider_eye", "poisonous_potato", "pufferfish",
            "chorus_fruit", "suspicious_stew", "golden_apple", "enchanted_golden_apple", "chicken");

    private Phase phase = Phase.IDLE;
    private String failure = "";
    private int eatTicks;
    private int eatFoodBefore;
    private int slotBefore = -1;
    private int phaseTicks;
    private BlockPos fillTarget;
    private BlockPos fillSupport;
    private Direction fillDir;
    private BlockPos breaking;
    private BlockPos pillarFrom;
    private int floorY;
    private double homeX, homeZ;

    public Phase phase() { return phase; }
    public String failure() { return failure; }
    public boolean busy() { return phase != Phase.IDLE && phase != Phase.FAILED; }
    /** Carries plain blocks it could place (to bridge or pillar). */
    public boolean hasBuildingBlocks(ClientPlayerEntity p) { return findBlockSlot(p) >= 0; }

    /** Eat when food is at or below this (20 = full). */
    public int eatAt = 14;
    /** A fall counts once the feet are this many blocks below the work level. */
    public int fallMargin = 0;
    /** After climbing back up, walk straight back toward home (tunnels). Area mode finds its own way instead. */
    public boolean returnAfterClimb = true;

    public void reset(MinecraftClient mc) {
        releaseKeys(mc);
        phase = Phase.IDLE;
        failure = "";
        fillTarget = null;
        breaking = null;
    }

    /**
     * Fill the floor block at {@code target} (air) from the neighbouring floor block {@code support}
     * that the player can stand on. Starts a FILLING phase; returns false if no plain block is carried.
     */
    public boolean requestFill(MinecraftClient mc, BlockPos target, BlockPos support) {
        return requestFill(mc, target, support, false);
    }

    private boolean softFill;
    private boolean fillFailed;

    /** @param soft when it can't be placed, just give up (see {@link #takeFillFailed}) instead of failing */
    public boolean requestFill(MinecraftClient mc, BlockPos target, BlockPos support, boolean soft) {
        if (findBlockSlot(mc.player) < 0) return false;
        softFill = soft;
        fillTarget = target;
        fillSupport = support;
        int ddx = target.getX() - support.getX(), ddz = target.getZ() - support.getZ();
        fillDir = Math.abs(ddx) >= Math.abs(ddz) ? (ddx > 0 ? Direction.EAST : Direction.WEST) : (ddz > 0 ? Direction.SOUTH : Direction.NORTH);
        phase = Phase.FILLING;
        phaseTicks = 0;
        return true;
    }

    /**
     * One tick. {@code workFloorY} is the feet level the player should stay at or above (Integer.MIN_VALUE
     * = no fall check); {@code homeX/Z} is a point of the work area to return to after a fall.
     * Returns true while it is doing something (the caller then waits).
     */
    public boolean tick(MinecraftClient mc, int workFloorY, double homeX, double homeZ) {
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.interactionManager == null || mc.world == null) return false;
        Phase was = phase;
        if (phase == Phase.IDLE) idleSlot = p.getInventory().getSelectedSlot();
        boolean busy = tickInner(mc, p, workFloorY, homeX, homeZ);
        // Done pillaring, filling or walking back: put the pickaxe (whatever was held before) back in hand.
        if (was != Phase.IDLE && was != Phase.EATING && phase == Phase.IDLE && idleSlot >= 0 && idleSlot < 9) {
            p.getInventory().setSelectedSlot(idleSlot);
        }
        return busy;
    }

    private int idleSlot = -1;

    private boolean swimming;

    private double safeX = Double.NaN, safeZ;
    private boolean escaping;
    private int lavaEscapes;

    /** Times the player was pulled out of lava since the last reset of this counter. */
    public int takeLavaEscapes() { int n = lavaEscapes; lavaEscapes = 0; return n; }

    private boolean tickInner(MinecraftClient mc, ClientPlayerEntity p, int workFloorY, double homeX, double homeZ) {
        phaseTicks++;
        // In lava: everything else waits. Jump and walk back to the last spot that was solid ground.
        if (p.isInLava()) {
            if (!escaping) lavaEscapes++;
            escaping = true;
            mc.options.sneakKey.setPressed(false);
            mc.options.jumpKey.setPressed(true);
            mc.options.sprintKey.setPressed(true);
            if (!Double.isNaN(safeX)) walkToward(mc, safeX, safeZ);
            else mc.options.forwardKey.setPressed(true);
            return true;
        } else if (escaping) {
            escaping = false;
            mc.options.jumpKey.setPressed(false);
            mc.options.sprintKey.setPressed(false);
            mc.options.forwardKey.setPressed(false);
        }
        if (p.isOnGround() && !p.isOnFire() && !p.isTouchingWater()) {
            safeX = p.getX();
            safeZ = p.getZ();
        }
        // Under water and running out of air: swim up (hold jump) until the head is out.
        if (p.isSubmergedInWater() && p.getAir() < p.getMaxAir() * 2 / 3) {
            // Drop whatever it was doing: a held sneak key (filling, pillaring) sinks the player.
            if (phase == Phase.FILLING && softFill) { fillFailed = true; phase = Phase.IDLE; }
            mc.options.sneakKey.setPressed(false);
            mc.options.jumpKey.setPressed(true);
            mc.options.sprintKey.setPressed(false);
            // Swim toward the nearest spot with air for the head (straight up may be a flooded ceiling).
            BlockPos air = nearestAir(mc, p.getBlockPos());
            if (air != null && (Math.abs(air.getX() + 0.5 - p.getX()) > 0.3 || Math.abs(air.getZ() + 0.5 - p.getZ()) > 0.3)) {
                float[] look = MovementController.lookAt(p, air.getX() + 0.5, air.getY() + 0.5, air.getZ() + 0.5);
                p.setYaw(look[0]);
                p.setPitch(look[1]);
                mc.options.forwardKey.setPressed(true);
            } else {
                mc.options.forwardKey.setPressed(false);
            }
            swimming = true;
            return true;
        } else if (swimming) {
            mc.options.jumpKey.setPressed(false);
            swimming = false;
        }
        switch (phase) {
            case EATING -> { tickEat(mc); return true; }
            case FILLING -> { tickFill(mc); return phase == Phase.FILLING; }
            case PILLARING -> { tickPillar(mc); return true; }
            case RETURNING -> { tickReturn(mc); return true; }
            case FAILED -> { return false; }
            default -> { }
        }
        if (!p.getAbilities().creativeMode && p.getHungerManager().getFoodLevel() <= eatAt && startEating(mc)) {
            return true;
        }
        if (workFloorY != Integer.MIN_VALUE && p.isOnGround() && p.getBlockPos().getY() < workFloorY - fallMargin && !p.isTouchingWater()) {
            if (findBlockSlot(p) < 0) {
                fail("Fell below the work level and has no plain blocks (cobblestone, dirt...) to pillar up with");
                return false;
            }
            floorY = workFloorY;
            this.homeX = homeX;
            this.homeZ = homeZ;
            phase = Phase.PILLARING;
            phaseTicks = 0;
            return true;
        }
        return false;
    }

    /** A soft fill gave up since the last call. */
    public boolean takeFillFailed() {
        boolean f = fillFailed;
        fillFailed = false;
        return f;
    }

    private void fail(String why) {
        failure = why;
        phase = Phase.FAILED;
    }

    // ------------------------------------------------------------------ eating

    private static int foodScore(ItemStack s) {
        if (s.isEmpty()) return -1;
        FoodComponent food = s.get(DataComponentTypes.FOOD);
        if (food == null) return -1;
        String id = Registries.ITEM.getId(s.getItem()).getPath();
        if (BAD_FOOD.contains(id)) return -1;
        int score = food.nutrition() * 10 + Math.round(food.saturation());
        if (id.equals("cooked_beef") || id.equals("cooked_porkchop")) score += 100; // steak first
        return score;
    }

    private boolean startEating(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        int best = -1, bestScore = -1;
        for (int i = 0; i < 36; i++) {
            int sc = foodScore(p.getInventory().getStack(i));
            if (i < 9) sc += 1; // same food: prefer the hotbar copy
            if (sc > bestScore) {
                bestScore = sc;
                best = i;
            }
        }
        if (best < 0 || bestScore < 0) return false;
        slotBefore = p.getInventory().getSelectedSlot();
        InventoryHelper.selectSlot(mc, best, 8);
        eatFoodBefore = p.getHungerManager().getFoodLevel();
        eatTicks = 0;
        phase = Phase.EATING;
        return true;
    }

    private void tickEat(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        eatTicks++;
        if (eatTicks == 2 && foodScore(p.getMainHandStack()) >= 0) {
            mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
        }
        mc.options.useKey.setPressed(eatTicks >= 2 && eatTicks < 70 && p.getHungerManager().getFoodLevel() <= eatFoodBefore);
        boolean done = eatTicks > 5 && !p.isUsingItem();
        if (done || eatTicks >= 80) {
            mc.options.useKey.setPressed(false);
            if (slotBefore >= 0 && slotBefore < 9) p.getInventory().setSelectedSlot(slotBefore);
            phase = Phase.IDLE;
        }
    }

    // ------------------------------------------------------------------ placing plain blocks

    /** A plain full block that is safe to place under you: not falling, no block entity, not valuable. */
    static boolean plainBlock(ItemStack s) {
        if (s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) return false;
        var block = bi.getBlock();
        if (block instanceof FallingBlock || block instanceof BlockWithEntity) return false;
        String id = Registries.ITEM.getId(s.getItem()).getPath();
        if (id.endsWith("_ore") || id.contains("diamond") || id.contains("netherite") || id.contains("emerald")
                || id.contains("gold") || id.contains("tnt") || id.contains("shulker") || id.contains("ice")
                || id.contains("leaves") || id.contains("glass") || id.contains("slab") || id.contains("stairs")) {
            return false;
        }
        return block.getDefaultState().isOpaqueFullCube();
    }

    private static int blockPriority(ItemStack s) {
        String id = Registries.ITEM.getId(s.getItem()).getPath();
        return switch (id) {
            case "cobblestone", "cobbled_deepslate", "netherrack", "dirt", "stone", "deepslate", "andesite", "diorite",
                 "granite", "tuff", "blackstone", "basalt", "end_stone" -> 2;
            default -> 1;
        };
    }

    static int findBlockSlot(ClientPlayerEntity p) {
        int best = -1, bestPri = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (!plainBlock(s)) continue;
            int pri = blockPriority(s) * 2 + (i < 9 ? 1 : 0);
            if (pri > bestPri) {
                bestPri = pri;
                best = i;
            }
        }
        return best;
    }

    /** Places a plain block against {@code face} of {@code support}. Returns false if it had to swap first. */
    private boolean place(MinecraftClient mc, BlockPos support, Direction face) {
        ClientPlayerEntity p = mc.player;
        int slot = findBlockSlot(p);
        if (slot < 0) {
            fail("Out of plain blocks (cobblestone, dirt...) to place");
            return false;
        }
        if (!InventoryHelper.selectSlot(mc, slot, 8)) return false;
        Vec3d hit = Vec3d.ofCenter(support).add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
        float[] look = MovementController.lookAt(p, hit.x, hit.y, hit.z);
        p.setYaw(look[0]);
        p.setPitch(look[1]);
        PlacementTool.automating = true;
        try {
            mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, new BlockHitResult(hit, face, support, false));
        } finally {
            PlacementTool.automating = false;
        }
        p.swingHand(Hand.MAIN_HAND);
        return true;
    }

    private static boolean solid(MinecraftClient mc, BlockPos pos) {
        BlockState s = mc.world.getBlockState(pos);
        return !s.isAir() && !s.isReplaceable() && s.getFluidState().isEmpty();
    }

    private static boolean open(MinecraftClient mc, BlockPos pos) {
        BlockState s = mc.world.getBlockState(pos);
        return (s.isAir() || s.isReplaceable()) && s.getFluidState().isEmpty();
    }

    /**
     * Sneak toward the gap until hanging over the edge (sneaking never walks off it), then place
     * against the side of the block under you.
     */
    private void tickFill(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        if (solid(mc, fillTarget)) {
            releaseKeys(mc);
            phase = Phase.IDLE;
            return;
        }
        if (phaseTicks > 200) {
            releaseKeys(mc);
            if (softFill) {
                fillFailed = true;
                phase = Phase.IDLE;
            } else {
                fail("Could not fill the floor gap at " + fillTarget.toShortString());
            }
            return;
        }
        mc.options.sneakKey.setPressed(true); // sneaking: never walks off the edge
        // Get onto (or next to) the support block first. Hanging over its edge counts as on it.
        boolean near = p.getBlockPos().getY() == fillSupport.getY() + 1
                && Math.abs(p.getX() - (fillSupport.getX() + 0.5)) <= 0.85 && Math.abs(p.getZ() - (fillSupport.getZ() + 0.5)) <= 0.85;
        if (!near) {
            walkToward(mc, fillSupport.getX() + 0.5, fillSupport.getZ() + 0.5);
            return;
        }
        double edge = (fillDir.getOffsetX() != 0 ? (p.getX() - (fillSupport.getX() + 0.5)) * fillDir.getOffsetX()
                : (p.getZ() - (fillSupport.getZ() + 0.5)) * fillDir.getOffsetZ());
        if (edge < 0.6) {
            // creep toward the edge so the side of the block below is in view
            float[] look = MovementController.lookAt(p, fillTarget.getX() + 0.5, p.getEyeY(), fillTarget.getZ() + 0.5);
            p.setYaw(look[0]);
            mc.options.forwardKey.setPressed(!dangerAhead(mc, p, look[0]));
            if (phaseTicks % 30 != 29) return; // if it stops short, place from where it is anyway
        }
        mc.options.forwardKey.setPressed(false);
        if (phaseTicks % 4 == 0) place(mc, fillSupport, fillDir);
    }

    // ------------------------------------------------------------------ climbing back after a fall

    private void tickPillar(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        BlockPos feet = p.getBlockPos();
        if (feet.getY() >= floorY && p.isOnGround()) {
            releaseKeys(mc);
            phase = returnAfterClimb ? Phase.RETURNING : Phase.IDLE;
            phaseTicks = 0;
            return;
        }
        if (phaseTicks > 1200) {
            releaseKeys(mc);
            fail("Could not climb out of the hole");
            return;
        }
        if (p.isOnGround()) {
            // Stand in the middle of the block first: half over the next one, the jump hits that one's ceiling.
            double cx = feet.getX() + 0.5 - p.getX(), cz = feet.getZ() + 0.5 - p.getZ();
            if (Math.abs(cx) > 0.2 || Math.abs(cz) > 0.2) {
                mc.options.jumpKey.setPressed(false);
                mc.options.sneakKey.setPressed(true);
                walkToward(mc, feet.getX() + 0.5, feet.getZ() + 0.5);
                return;
            }
            mc.options.forwardKey.setPressed(false);
            mc.options.sneakKey.setPressed(false);
            // Head room: the block above the head must be open to jump into.
            // Two blocks of head room: with only one the jump is cut short and the block can't go under the feet.
            for (BlockPos above : new BlockPos[]{feet.up(2), feet.up(3)}) {
                if (!open(mc, above)) {
                    mc.options.jumpKey.setPressed(false);
                    if (fluidNext(mc, above)) {
                        fail("Can't climb out: the block above touches lava or water at " + above.toShortString());
                        return;
                    }
                    if (!mine(mc, above, Direction.DOWN)) fail("Something unbreakable above the hole at " + above.toShortString());
                    return;
                }
            }
            breaking = null;
            p.setPitch(90f);
            pillarFrom = feet;
            mc.options.jumpKey.setPressed(true);
            return;
        }
        // In the air: once the feet are above the cell we jumped from, put a block there.
        p.setPitch(90f);
        if (pillarFrom != null && p.getY() >= pillarFrom.getY() + 1.1 && open(mc, pillarFrom) && solid(mc, pillarFrom.down())) {
            place(mc, pillarFrom.down(), Direction.UP);
        }
    }

    /** Back toward the work area at the work level: walk, dig through walls, bridge gaps. */
    private void tickReturn(MinecraftClient mc) {
        ClientPlayerEntity p = mc.player;
        BlockPos feet = p.getBlockPos();
        double dx = homeX - p.getX(), dz = homeZ - p.getZ();
        if (Math.abs(dx) < 1.0 && Math.abs(dz) < 1.0 || phaseTicks > 2400) {
            releaseKeys(mc);
            phase = Phase.IDLE;
            return;
        }
        if (feet.getY() < floorY && p.isOnGround()) {
            releaseKeys(mc);
            phase = Phase.PILLARING;
            phaseTicks = 0;
            return;
        }
        Direction dir = Math.abs(dx) >= Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
        BlockPos next = feet.offset(dir);
        // Dig through: feet and head level of the next cell.
        for (BlockPos c : new BlockPos[]{next.up(), next}) {
            if (!open(mc, c)) {
                releaseKeys(mc);
                if (fluidNext(mc, c)) { fail("The way back is next to lava or water at " + c.toShortString()); return; }
                if (!mine(mc, c, dir.getOpposite())) fail("Something unbreakable on the way back at " + c.toShortString());
                return;
            }
        }
        breaking = null;
        // Bridge: no floor under the next cell.
        if (!solid(mc, next.down())) {
            if (!requestFill(mc, next.down(), feet.down())) {
                fail("No plain blocks left to bridge back");
                return;
            }
            phase = Phase.FILLING;
            phaseTicks = 0;
            // FILLING returns to IDLE when done; the next fall check / return continues from there.
            return;
        }
        mc.options.sneakKey.setPressed(true); // never walk off an edge on the way back
        walkToward(mc, next.getX() + 0.5, next.getZ() + 0.5);
    }

    // ------------------------------------------------------------------ helpers

    private static void walkToward(MinecraftClient mc, double x, double z) {
        ClientPlayerEntity p = mc.player;
        float[] look = MovementController.lookAt(p, x, p.getEyeY(), z);
        p.setYaw(look[0]);
        // Never step into lava or fire: sneaking stops falls, not walking into lava on the same level.
        boolean safe = p.isInLava() || !dangerAhead(mc, p, look[0]);
        mc.options.forwardKey.setPressed(safe);
    }

    /** A fluid (lava, water) touches this block: mining it would let the fluid in. */
    private static boolean fluidNext(MinecraftClient mc, BlockPos pos) {
        for (Direction d : Direction.values()) if (!mc.world.getFluidState(pos.offset(d)).isEmpty()) return true;
        return false;
    }

    /** Nearest open, dry cell (with a dry or open cell below or above it) within 6 blocks: somewhere to breathe. */
    private static BlockPos nearestAir(MinecraftClient mc, BlockPos from) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dy = 0; dy <= 6; dy++) for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) {
            BlockPos c = from.add(dx, dy, dz);
            BlockState s = mc.world.getBlockState(c);
            if (!s.isAir() || !s.getFluidState().isEmpty()) continue;
            if (!mc.world.getBlockState(c.down()).getFluidState().isEmpty() && !mc.world.getBlockState(c.down()).isAir()
                    && mc.world.getBlockState(c.down()).getFluidState().isIn(net.minecraft.registry.tag.FluidTags.LAVA)) continue;
            double d = dx * dx + dz * dz + dy * dy * 0.5;
            if (d < bestD) { bestD = d; best = c; }
        }
        return best;
    }

    /** Lava/fire at the feet or head 0.7 blocks ahead, or lava right under that spot. */
    private static boolean dangerAhead(MinecraftClient mc, ClientPlayerEntity p, float yaw) {
        double rad = Math.toRadians(yaw);
        double ax = p.getX() - Math.sin(rad) * 0.7, az = p.getZ() + Math.cos(rad) * 0.7;
        BlockPos at = BlockPos.ofFloored(ax, p.getY() + 0.1, az);
        if (dangerous(mc.world.getBlockState(at)) || dangerous(mc.world.getBlockState(at.up()))) return true;
        // ... and no drop that ends in lava: look down past air until something solid or a fluid.
        for (int d = 1; d <= 12; d++) {
            BlockState s = mc.world.getBlockState(at.down(d));
            if (dangerous(s)) return true;
            if (!s.isAir() && !s.isReplaceable()) return false;
            if (!s.getFluidState().isEmpty()) return false; // water breaks the fall
        }
        return false;
    }

    private static boolean dangerous(BlockState s) {
        return s.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.LAVA) || Registries.BLOCK.getId(s.getBlock()).getPath().endsWith("fire");
    }

    /** Breaks a block with the fastest tool carried. Returns false if it cannot be broken. */
    private boolean mine(MinecraftClient mc, BlockPos pos, Direction face) {
        ClientPlayerEntity p = mc.player;
        BlockState state = mc.world.getBlockState(pos);
        if (state.getHardness(mc.world, pos) < 0) return false;
        if (!pos.equals(breaking)) {
            int best = -1;
            float bestSpeed = 1f;
            for (int i = 0; i < 36; i++) {
                float sp = p.getInventory().getStack(i).getMiningSpeedMultiplier(state);
                if (sp > bestSpeed) {
                    bestSpeed = sp;
                    best = i;
                }
            }
            if (best >= 0 && !InventoryHelper.selectSlot(mc, best, 8)) return true;
            float[] look = MovementController.lookAt(p, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            p.setYaw(look[0]);
            p.setPitch(look[1]);
            PlacementTool.automating = true;
            try {
                mc.interactionManager.attackBlock(pos, face);
            } finally {
                PlacementTool.automating = false;
            }
            breaking = pos;
            return true;
        }
        float[] look = MovementController.lookAt(p, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        p.setYaw(look[0]);
        p.setPitch(look[1]);
        mc.interactionManager.updateBlockBreakingProgress(pos, face);
        p.swingHand(Hand.MAIN_HAND);
        return true;
    }

    public void releaseKeys(MinecraftClient mc) {
        mc.options.useKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
        mc.options.forwardKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
    }
}
