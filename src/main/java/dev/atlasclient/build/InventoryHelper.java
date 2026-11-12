package dev.atlasclient.build;

import dev.atlasclient.core.build.AreaMining;
import dev.atlasclient.core.build.Restock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Inventory access for the builder using normal interactions only: selecting a hotbar slot, the
 * vanilla "swap with hotbar" inventory click (same as pressing a number key over a slot), and in
 * creative mode the vanilla creative-inventory stack action.
 */
public final class InventoryHelper {
    public static final int HOTBAR = 9;
    public static final int MAIN = 36;

    private InventoryHelper() { }

    public static Map<String, Integer> counts(ClientPlayerEntity p) {
        Map<String, Integer> m = new HashMap<>();
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < MAIN; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty()) {
                m.merge(Registries.ITEM.getId(s.getItem()).toString(), s.getCount(), Integer::sum);
            }
        }
        return m;
    }

    public static boolean creative(ClientPlayerEntity p) { return p.getAbilities().creativeMode; }

    /** Inventory index (0-35) holding the item, hotbar first; -1 if absent. */
    public static int find(ClientPlayerEntity p, Item item) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < MAIN; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && s.isOf(item)) return i;
        }
        return -1;
    }

    public static Item item(String id) {
        Identifier ident = Identifier.tryParse(id);
        if (ident == null) return null;
        Optional<Item> it = Registries.ITEM.getOptionalValue(ident);
        return it.orElse(null);
    }

    /**
     * Makes the item the selected hotbar stack. Returns true when it is selected now; false when
     * an inventory swap was issued (wait a tick) or the item is unavailable.
     */
    public static boolean select(MinecraftClient mc, Item item, int preferredHotbarSlot) {
        ClientPlayerEntity p = mc.player;
        PlayerInventory inv = p.getInventory();
        ItemStack held = inv.getStack(inv.getSelectedSlot());
        if (!held.isEmpty() && held.isOf(item)) return true;
        int idx = find(p, item);
        if (idx >= 0 && idx < HOTBAR) {
            inv.setSelectedSlot(idx);
            return true;
        }
        int target = Math.max(0, Math.min(8, preferredHotbarSlot));
        if (idx >= HOTBAR) {
            // PlayerScreenHandler slot indices: main inventory 9-35 map 1:1, hotbar 0-8 is button 0-8.
            mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, idx, target, SlotActionType.SWAP, p);
            inv.setSelectedSlot(target);
            return false;
        }
        if (creative(p)) {
            ItemStack stack = new ItemStack(item, Math.max(1, Math.min(64, item.getMaxCount())));
            inv.setStack(target, stack);
            mc.interactionManager.clickCreativeStack(stack, 36 + target);
            inv.setSelectedSlot(target);
            return false;
        }
        return false;
    }

    /** Candidate mining tools (hotbar and main inventory) for a block, with area-tool detection. */
    public static List<AreaMining.Tool> tools(ClientPlayerEntity p, BlockState state, List<String> areaKeywords, int manualAreaSlot) {
        List<AreaMining.Tool> out = new ArrayList<>();
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < MAIN; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            float speed = s.getMiningSpeedMultiplier(state);
            boolean suitable = !state.isToolRequired() || s.isSuitableFor(state);
            if (!suitable && speed <= 1.0f) continue;
            boolean area = (manualAreaSlot >= 0 && i == manualAreaSlot) || isAreaTool(s, areaKeywords);
            int durability = s.isDamageable() ? s.getMaxDamage() - s.getDamage() : -1;
            out.add(new AreaMining.Tool(i, area, suitable ? speed : speed * 0.3, durability));
        }
        // Bare hand as a last resort (only useful for blocks that do not need a tool).
        if (!state.isToolRequired()) out.add(new AreaMining.Tool(-1, false, 1.0, -1));
        return out;
    }

    public static boolean isAreaTool(ItemStack s, List<String> keywords) {
        String id = Registries.ITEM.getId(s.getItem()).toString();
        String name = s.getName().getString();
        List<String> lore = new ArrayList<>();
        LoreComponent lc = s.get(DataComponentTypes.LORE);
        if (lc != null) {
            for (Text t : lc.lines()) lore.add(t.getString());
        }
        List<String> ench = new ArrayList<>();
        for (RegistryEntry<?> e : s.getEnchantments().getEnchantments()) {
            ench.add(e.getIdAsString());
        }
        return AreaMining.looksLikeAreaTool(keywords, id, name, lore, ench);
    }

    // ------------------------------------------------------------------ shulker restock / dropping

    public static boolean isShulkerBox(ItemStack s) {
        return !s.isEmpty() && s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof ShulkerBoxBlock;
    }

    /** What a shulker box (or other container item) holds, from its data component. */
    public static Map<String, Integer> contents(ItemStack s) {
        Map<String, Integer> m = new HashMap<>();
        ContainerComponent c = s.get(DataComponentTypes.CONTAINER);
        if (c != null) {
            for (ItemStack in : c.iterateNonEmpty()) {
                m.merge(Registries.ITEM.getId(in.getItem()).toString(), in.getCount(), Integer::sum);
            }
        }
        return m;
    }

    /** Snapshot of the 36 main-inventory slots with drop protection flags. */
    public static List<Restock.Stack> stacks(ClientPlayerEntity p) {
        List<Restock.Stack> out = new ArrayList<>(MAIN);
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < MAIN; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) {
                out.add(new Restock.Stack(i, "", 0, false, false));
                continue;
            }
            boolean prot = isShulkerBox(s) || s.get(DataComponentTypes.CUSTOM_NAME) != null || s.hasEnchantments()
                    || !contents(s).isEmpty();
            out.add(new Restock.Stack(i, Registries.ITEM.getId(s.getItem()).toString(), s.getCount(), s.getItem() instanceof BlockItem, prot));
        }
        return out;
    }

    public static List<Restock.Shulker> shulkers(ClientPlayerEntity p) {
        List<Restock.Shulker> out = new ArrayList<>();
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < MAIN; i++) {
            ItemStack s = inv.getStack(i);
            if (isShulkerBox(s)) {
                out.add(new Restock.Shulker(i, Registries.ITEM.getId(s.getItem()).toString(), contents(s)));
            }
        }
        return out;
    }

    /** Items stored in all shulker boxes in the inventory, by item id. */
    public static Map<String, Integer> shulkerTotals(ClientPlayerEntity p) {
        Map<String, Integer> m = new HashMap<>();
        for (Restock.Shulker s : shulkers(p)) {
            for (Map.Entry<String, Integer> e : s.contents().entrySet()) m.merge(e.getKey(), e.getValue(), Integer::sum);
        }
        return m;
    }

    public static int shulkerCount(ClientPlayerEntity p) {
        int n = 0;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < MAIN; i++) if (isShulkerBox(inv.getStack(i))) n++;
        return n;
    }

    /** PlayerScreenHandler slot id of inventory index 0-35 (hotbar 0-8 is 36-44, main 9-35 is 9-35). */
    public static int playerHandlerSlot(int invIndex) { return invIndex < HOTBAR ? 36 + invIndex : invIndex; }

    /**
     * Throws the whole stack in an inventory slot, the vanilla Ctrl+Q action. Only valid while no
     * container screen is open (the player's own inventory handler is active).
     */
    public static void throwStack(MinecraftClient mc, int invIndex) {
        ClientPlayerEntity p = mc.player;
        mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, playerHandlerSlot(invIndex), 1, SlotActionType.THROW, p);
    }

    /** Selects a tool by inventory index (-1 = keep current / bare hand). Returns true if ready now. */
    public static boolean selectSlot(MinecraftClient mc, int invIndex, int preferredHotbarSlot) {
        if (invIndex < 0) return true;
        ClientPlayerEntity p = mc.player;
        PlayerInventory inv = p.getInventory();
        if (invIndex < HOTBAR) {
            inv.setSelectedSlot(invIndex);
            return true;
        }
        int target = Math.max(0, Math.min(8, preferredHotbarSlot));
        mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, invIndex, target, SlotActionType.SWAP, p);
        inv.setSelectedSlot(target);
        return false;
    }
}
