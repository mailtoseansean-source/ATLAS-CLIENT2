package dev.atlasclient.build;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.BlockItem;
import net.minecraft.item.HangingSignItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.VerticallyAttachableBlockItem;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Set;

/**
 * Finds a normal right-click (clicked face, hit point, and the player's facing) that makes vanilla
 * place exactly the wanted state. It asks the block's own {@code getPlacementState} what would be
 * placed for each candidate, so orientation rules of stairs, slabs, logs, doors, trapdoors,
 * buttons, torches, repeaters, observers, hoppers etc. are vanilla's, not re-implemented.
 *
 * <p>The player's rotation is only changed temporarily during the prediction and restored before
 * returning; the executor then turns the player for real and clicks on the next tick, after the
 * server has received the new rotation.</p>
 */
public final class PlacementSolver {
    /** A click that should produce the wanted state. */
    public record Solution(BlockHitResult hit, float yaw, float pitch, boolean needsRotation) { }

    private static final Set<String> INTERACTIVE = Set.of("_door", "_trapdoor", "_fence_gate", "_button", "lever", "repeater",
            "comparator", "note_block", "_bed", "crafting_table", "anvil", "cake", "daylight_detector", "bell", "lectern",
            "jukebox", "cauldron", "_sign", "loom", "grindstone", "stonecutter", "smithing_table", "cartography_table",
            "enchanting_table", "brewing_stand", "beacon", "respawn_anchor", "composter", "flower_pot", "crafter", "chiseled_bookshelf",
            "decorated_pot", "dragon_egg", "vault", "candle", "barrel", "hopper", "dispenser", "dropper", "furnace", "smoker", "chest",
            "shulker_box", "ender_chest", "command_block", "structure_block", "jigsaw", "redstone_wire");
    private static final float[] YAWS = {0, 90, 180, -90};
    private static final float[] PITCHES = {0, 60, -60, 89, -89};

    private PlacementSolver() { }

    /** Whether right-clicking this neighbour would use the block instead of placing against it. */
    public static boolean interactive(BlockState s) {
        if (s.hasBlockEntity()) return true;
        String id = net.minecraft.registry.Registries.BLOCK.getId(s.getBlock()).getPath();
        for (String k : INTERACTIVE) {
            if (id.endsWith(k) || id.equals(k)) return true;
        }
        return false;
    }

    public static Solution solve(ClientWorld world, ClientPlayerEntity player, BlockPos target, BlockState wanted,
                                 ItemStack stack, double reach) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return null;
        }
        Block block = blockItem.getBlock();
        // Torches, signs, banners, heads and coral fans: one item places either the standing block or its
        // wall variant (wall_torch.asItem() == torch). Anything else must be the item's own block.
        boolean attachable = blockItem instanceof VerticallyAttachableBlockItem;
        if (block != wanted.getBlock() && !(attachable && wanted.getBlock().asItem() == blockItem)) {
            return null;
        }
        float origYaw = player.getYaw(), origPitch = player.getPitch();
        Vec3d eye = player.getEyePos();
        Solution best = null;
        double bestCost = Double.MAX_VALUE;
        try {
            BlockState current = world.getBlockState(target);
            boolean secondSlab = StateResolver.isMissingSecondSlab(current, wanted);
            for (Direction d : Direction.values()) {
                BlockPos clickPos;
                Direction side;
                if (secondSlab) {
                    if (d.getAxis() != Direction.Axis.Y) continue;
                    clickPos = target;          // click the existing half slab itself (top or bottom face)
                    side = d;
                } else if (current.isReplaceable() && !current.isAir() && d == Direction.UP) {
                    clickPos = target;          // e.g. placing into tall grass / water
                    side = Direction.UP;
                } else {
                    clickPos = target.offset(d);
                    side = d.getOpposite();
                    BlockState n = world.getBlockState(clickPos);
                    if (n.isAir() || n.isReplaceable() || interactive(n)) {
                        continue;
                    }
                }
                double[] yOffsets = side.getAxis() == Direction.Axis.Y ? new double[]{0} : new double[]{0.25, -0.25};
                for (double yo : yOffsets) {
                    Vec3d hitPos = Vec3d.ofCenter(clickPos).add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5 + yo, side.getOffsetZ() * 0.5);
                    if (eye.squaredDistanceTo(hitPos) > reach * reach) {
                        continue;
                    }
                    BlockHitResult hit = new BlockHitResult(hitPos, side, clickPos, false);
                    // Try the current rotation first: no turning needed if it already works.
                    float[][] rotations = new float[1 + YAWS.length * PITCHES.length][];
                    rotations[0] = new float[]{origYaw, origPitch};
                    int k = 1;
                    for (float yaw : YAWS) for (float pitch : PITCHES) rotations[k++] = new float[]{yaw, pitch};
                    for (float[] rot : rotations) {
                        player.setYaw(rot[0]);
                        player.setPitch(rot[1]);
                        ItemPlacementContext ctx = new ItemPlacementContext(player, Hand.MAIN_HAND, stack, hit);
                        if (!ctx.getBlockPos().equals(target) || !ctx.canPlace()) {
                            break; // position does not depend on rotation
                        }
                        BlockState predicted = attachable ? predictAttachable(blockItem, wanted.getBlock(), ctx, side)
                                : block.getPlacementState(ctx);
                        if (predicted == null || !predicted.canPlaceAt(world, target)) {
                            continue;
                        }
                        if (StateResolver.placementEquivalent(predicted, wanted)) {
                            boolean turn = rot != rotations[0];
                            double cost = (turn ? angle(origYaw, rot[0]) + Math.abs(origPitch - rot[1]) : 0) + eye.squaredDistanceTo(hitPos) * 0.1;
                            if (cost < bestCost) {
                                bestCost = cost;
                                best = new Solution(hit, rot[0], rot[1], turn);
                            }
                            break;
                        }
                    }
                }
            }
        } finally {
            player.setYaw(origYaw);
            player.setPitch(origPitch);
        }
        return best;
    }

    /**
     * Mirrors VerticallyAttachableBlockItem: the first placement direction decides the variant. The
     * standing block is used when it is the attachment direction (clicking a top face for torches,
     * signs, banners, heads and coral fans, a bottom face for hanging signs); a side face gives the
     * wall variant, whose own placement rules then pick the facing.
     */
    private static BlockState predictAttachable(BlockItem item, Block wantedBlock, ItemPlacementContext ctx, Direction side) {
        Direction attach = item instanceof HangingSignItem ? Direction.UP : Direction.DOWN;
        boolean standingWanted = wantedBlock == item.getBlock();
        if (standingWanted) {
            return side == attach.getOpposite() ? item.getBlock().getPlacementState(ctx) : null;
        }
        return side.getAxis() != Direction.Axis.Y ? wantedBlock.getPlacementState(ctx) : null;
    }

    private static double angle(float a, float b) {
        double d = Math.abs(((b - a) % 360 + 540) % 360 - 180);
        return d;
    }
}
