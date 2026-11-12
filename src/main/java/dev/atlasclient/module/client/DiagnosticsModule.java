package dev.atlasclient.module.client;

import dev.atlasclient.Atlas;
import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.perf.PerfMonitor;
import dev.atlasclient.core.setting.Settings.ActionSetting;
import dev.atlasclient.module.AtlasModule;
import dev.atlasclient.render.AtlasRenderLayers;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.Locale;
import java.util.Map;

/** HUD overlay with timings, queue sizes and render stats; also writes them to the log. */
public final class DiagnosticsModule extends AtlasModule {
    final ActionSetting dump = add(new ActionSetting("Log report", "Write a diagnostics report to latest.log", this::dump));
    final ActionSetting reset = add(new ActionSetting("Reset maxima", "Reset max timings", () -> Atlas.get().perf.resetMaxima()));

    public DiagnosticsModule() {
        super("Diagnostics", "Performance and debug information on the HUD", Category.CLIENT);
    }

    @Override
    public int renderHud(DrawContext ctx, int x, int y, float tickDelta) {
        var tr = MinecraftClient.getInstance().textRenderer;
        int start = y;
        y += 2;
        ctx.drawTextWithShadow(tr, "Atlas diagnostics", x, y, 0xFF8B5CF6);
        y += 10;
        for (PerfMonitor.Snapshot s : Atlas.get().perf.snapshot()) {
            if (s.name().startsWith("tick/") && s.avgMillis() < 0.05) continue;
            ctx.drawTextWithShadow(tr, String.format(Locale.ROOT, "%-22s %6.3f ms (max %6.2f)", s.name(), s.avgMillis(), s.maxMillis()), x, y, 0xFFD0D0D8);
            y += 9;
        }
        for (Map.Entry<String, Long> g : Atlas.get().perf.gauges().entrySet()) {
            ctx.drawTextWithShadow(tr, g.getKey() + ": " + g.getValue(), x, y, 0xFFA0A0B0);
            y += 9;
        }
        ctx.drawTextWithShadow(tr, "see-through layer: " + (AtlasRenderLayers.seeThroughAvailable() ? "ok" : "fallback"), x, y, 0xFFA0A0B0);
        y += 9;
        ctx.drawTextWithShadow(tr, "event listeners: " + Atlas.get().bus.totalListenerCount(), x, y, 0xFFA0A0B0);
        y += 9;
        return y - start;
    }

    private void dump() {
        StringBuilder sb = new StringBuilder("Atlas diagnostics report\n");
        for (PerfMonitor.Snapshot s : Atlas.get().perf.snapshot()) {
            sb.append(String.format(Locale.ROOT, "  %s avg=%.3fms max=%.3fms n=%d%n", s.name(), s.avgMillis(), s.maxMillis(), s.samples()));
        }
        Atlas.get().perf.gauges().forEach((k, v) -> sb.append("  ").append(k).append('=').append(v).append('\n'));
        sb.append("  modules=").append(Atlas.get().modules.size()).append(" seeThrough=").append(AtlasRenderLayers.seeThroughAvailable())
                .append(' ').append(AtlasRenderLayers.failure()).append('\n');
        Atlas.LOG.info(sb.toString());
        info("Diagnostics", "Report written to the game log");
    }
}
