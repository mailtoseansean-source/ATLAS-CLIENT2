package dev.atlasclient.build;

import dev.atlasclient.module.building.AutoBuilderModule;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * The schematic placement tool, like Litematica's stick but a birch plank by default (the item is
 * the AutoBuilder setting "Placement tool"). Holding it while a schematic is loaded or selected:
 * <ul>
 *   <li>right-click a block: move the schematic there (loads the selected one if none is loaded)</li>
 *   <li>left-click a block: rotate 90 degrees; sneak + left-click: mirror</li>
 *   <li>sneak + right-click: start building, or pause / resume</li>
 *   <li>Ctrl + scroll: nudge it one block toward where you look (up/down when looking steeply)</li>
 * </ul>
 * Those clicks are cancelled before anything is sent to the server, so no plank is placed and no
 * block is hit. With no schematic loaded or selected the item works as a normal plank. Clicks
 * made by Atlas itself (AutoBuilder, Auto Mine) are never treated as tool clicks.
 */
public final class PlacementTool {
    /** True while Atlas performs its own interactions. */
    public static boolean automating;

    private static long lastAction;
    private static int hintTicks;

    private PlacementTool() { }

    public static void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClient() || automating || hand != Hand.MAIN_HAND || !holding(player)) return ActionResult.PASS;
            AutoBuilderModule b = AutoBuilderModule.get();
            if (!b.hasSchematic()) return ActionResult.PASS; // nothing to place: behave like a normal plank
            if (cooldown()) return ActionResult.FAIL;
            if (player.isSneaking()) {
                hint(b.toolBuildOrPause());
            } else {
                BlockPos at = hit.getBlockPos().offset(hit.getSide());
                hint(b.toolMoveTo(at.getX(), at.getY(), at.getZ()));
            }
            return ActionResult.FAIL;
        });
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!world.isClient() || automating || !holding(player)) return ActionResult.PASS;
            AutoBuilderModule b = AutoBuilderModule.get();
            if (!b.session.loaded()) return ActionResult.PASS;
            if (cooldown()) return ActionResult.FAIL; // holding the button: one action per click
            hint(player.isSneaking() ? b.toolMirror() : b.toolRotate());
            return ActionResult.FAIL;
        });
    }

    private static boolean cooldown() {
        long now = System.currentTimeMillis();
        if (now - lastAction < 250) return true;
        lastAction = now;
        return false;
    }

    /** Ctrl + scroll while holding the tool. Returns true when the scroll was used (not passed to the hotbar). */
    public static boolean onScroll(double vertical) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.currentScreen != null || vertical == 0 || !holding(mc.player)) return false;
        boolean ctrl = InputUtil.isKeyPressed(mc.getWindow(), 341) || InputUtil.isKeyPressed(mc.getWindow(), 345);
        AutoBuilderModule b = AutoBuilderModule.get();
        if (!ctrl || !b.session.loaded()) return false;
        int step = vertical > 0 ? 1 : -1;
        float pitch = mc.player.getPitch();
        Direction d = pitch < -60 ? Direction.UP : pitch > 60 ? Direction.DOWN : mc.player.getHorizontalFacing();
        hint(b.toolNudge(d.getOffsetX() * step, d.getOffsetY() * step, d.getOffsetZ() * step));
        return true;
    }

    /** Every tick: a reminder of the controls while the tool is in hand. */
    public static void tick(MinecraftClient mc) {
        if (mc.player == null || mc.currentScreen != null || !holding(mc.player) || !AutoBuilderModule.get().hasSchematic()) {
            hintTicks = 0;
            return;
        }
        if (hintTicks++ % 40 == 0 && System.currentTimeMillis() - lastAction > 1500) {
            mc.inGameHud.setOverlayMessage(Text.literal(
                    "Right-click: move here | Left-click: rotate | Sneak+Left: mirror | Sneak+Right: build/pause | Ctrl+Scroll: nudge"), false);
        }
    }

    private static void hint(String message) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (message != null && !message.isEmpty() && mc.inGameHud != null) {
            mc.inGameHud.setOverlayMessage(Text.literal(message), false);
        }
    }

    public static boolean holding(PlayerEntity player) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (player == null || player != mc.player) return false;
        String tool = AutoBuilderModule.get().toolItemId();
        if (tool.isEmpty()) return false;
        ItemStack stack = player.getMainHandStack();
        return !stack.isEmpty() && Registries.ITEM.getId(stack.getItem()).toString().equals(tool);
    }
}
