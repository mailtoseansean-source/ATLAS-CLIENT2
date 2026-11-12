package dev.atlasclient.module.building;

import dev.atlasclient.build.InventoryHelper;
import dev.atlasclient.build.McBlockTraits;
import dev.atlasclient.build.McWorld;
import dev.atlasclient.build.SchematicSession;
import dev.atlasclient.core.build.BuildSession;
import dev.atlasclient.core.build.MaterialList;
import dev.atlasclient.core.build.PlacementPlanner;
import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.setting.Settings.ActionSetting;
import dev.atlasclient.core.setting.Settings.BooleanSetting;
import dev.atlasclient.core.setting.Settings.NumberSetting;
import dev.atlasclient.module.AtlasModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Required vs available materials for the loaded schematic (remaining blocks only while building). */
public final class MaterialChecklistModule extends AtlasModule {
    final NumberSetting lines = add(new NumberSetting("Lines", "Material lines shown on the HUD", 8, 1, 30, 1));
    final BooleanSetting missingOnly = add(new BooleanSetting("Missing only", "Show only items you lack", false));
    final ActionSetting print = add(new ActionSetting("Print to chat", "Print the full material list (client-side only)", this::printAll));
    private MaterialList list;
    private java.util.Map<String, Integer> inBoxes = java.util.Map.of();
    private int ticks;

    public MaterialChecklistModule() {
        super("Material Checklist", "Materials needed for the loaded schematic vs. your inventory", Category.BUILDING);
    }

    @Override
    public void onTick() {
        if (++ticks % 20 != 0) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        AutoBuilderModule ab = atlas().modules.get(AutoBuilderModule.class);
        SchematicSession s = ab.session;
        if (!s.loaded() || mc.player == null || mc.world == null) {
            list = null;
            setStatus("No schematic loaded");
            return;
        }
        List<PlacementPlanner.Entry> entries;
        if (ab.job() != null) {
            entries = new ArrayList<>();
            BuildSession bs = ab.job().build();
            for (int i = 0; i < bs.entries().size(); i++) {
                BuildSession.EntryState st = bs.entryState(i);
                if (st == BuildSession.EntryState.PENDING || st == BuildSession.EntryState.SKIPPED || st == BuildSession.EntryState.FAILED) {
                    entries.add(bs.entries().get(i));
                }
            }
        } else {
            PlacementPlanner.Plan plan = PlacementPlanner.plan(s.blocks(), new McBlockTraits(ab.resolver()), new McWorld(mc.world, ab.resolver()));
            entries = new ArrayList<>();
            for (PlacementPlanner.Entry e : plan.entries()) {
                if (e.initialStatus() != dev.atlasclient.core.build.WorldProbe.Status.MATCHES) entries.add(e);
            }
        }
        list = MaterialList.compute(entries, InventoryHelper.counts(mc.player), InventoryHelper.creative(mc.player));
        inBoxes = InventoryHelper.shulkerTotals(mc.player);
        int coveredByBoxes = 0;
        for (MaterialList.Line l : list.missingLines()) coveredByBoxes += Math.min(l.missing(), inBoxes.getOrDefault(l.item(), 0));
        int stillMissing = list.totalMissing() - coveredByBoxes;
        setStatus(list.complete() ? "All materials available"
                : (stillMissing > 0 ? stillMissing + " items missing" : "Everything is available")
                + (coveredByBoxes > 0 ? ", " + coveredByBoxes + " in shulker boxes" : ""));
    }

    @Override
    public int renderHud(DrawContext ctx, int x, int y, float tickDelta) {
        if (list == null) return 0;
        var tr = MinecraftClient.getInstance().textRenderer;
        int start = y;
        ctx.drawTextWithShadow(tr, "Materials (" + (list.complete() ? "complete" : list.totalMissing() + " missing") + ")", x, y, 0xFF8B5CF6);
        y += 10;
        int n = 0;
        for (MaterialList.Line l : missingOnly.get() ? list.missingLines() : list.lines()) {
            if (n++ >= lines.intValue()) break;
            String item = l.item().startsWith("minecraft:") ? l.item().substring(10) : l.item();
            int boxed = inBoxes.getOrDefault(l.item(), 0);
            boolean coveredByBoxes = !l.satisfied() && boxed >= l.missing();
            ctx.drawTextWithShadow(tr, String.format(Locale.ROOT, "%s %d/%d%s", item, Math.min(l.available(), l.required()), l.required(),
                            boxed > 0 && !l.satisfied() ? " (+" + boxed + " in boxes)" : ""),
                    x + 4, y, l.satisfied() ? 0xFF81C784 : coveredByBoxes ? 0xFFFFD54F : 0xFFE57373);
            y += 9;
        }
        return y - start + 2;
    }

    private void printAll() {
        var p = MinecraftClient.getInstance().player;
        if (p == null || list == null) return;
        for (MaterialList.Line l : list.lines()) {
            p.sendMessage(Text.literal(String.format(Locale.ROOT, "[Atlas] %s: need %d, have %d%s", l.item(), l.required(), l.available(),
                    l.satisfied() ? "" : " (missing " + l.missing() + ")")), false);
        }
    }
}
