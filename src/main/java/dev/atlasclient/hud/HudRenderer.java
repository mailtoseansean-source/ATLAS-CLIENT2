package dev.atlasclient.hud;

import dev.atlasclient.Atlas;
import dev.atlasclient.core.gui.Animator;
import dev.atlasclient.core.module.Module;
import dev.atlasclient.core.notify.Notification;
import dev.atlasclient.gui.UiKit;
import dev.atlasclient.module.AtlasModule;
import dev.atlasclient.module.client.GuiSettingsModule;
import dev.atlasclient.module.utility.NotificationsModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** HUD layer: toast notifications (top right), module info column and active module list. */
public final class HudRenderer {
    private final Animator anim = new Animator();
    private final java.util.Set<Long> seen = new java.util.HashSet<>();

    public void render(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!Atlas.ready() || mc.options.hudHidden) {
            return;
        }
        long start = System.nanoTime();
        Atlas atlas = Atlas.get();
        anim.frame(System.nanoTime());
        int sw = ctx.getScaledWindowWidth();
        float tickDelta = 0f;

        // Info column (left, below the debug area): each enabled module may contribute lines.
        int y = 4;
        GuiSettingsModule gs = atlas.modules.get(GuiSettingsModule.class);
        if (gs != null && gs.moduleList.get()) {
            List<Module> active = new ArrayList<>();
            for (Module m : atlas.modules.all()) {
                if (m.isEnabled() && m.canToggle() && GuiSettingsModule.isMainFeature(m)) active.add(m);
            }
            active.sort(Comparator.comparingInt((Module m) -> -UiKit.font().getWidth(m.name())));
            for (Module m : active) {
                int w = UiKit.font().getWidth(m.name());
                ctx.fill(sw - w - 6, y - 1, sw, y + 9, 0x90000000);
                ctx.fill(sw - 2, y - 1, sw, y + 9, UiKit.Palette.current().accent());
                ctx.drawTextWithShadow(mc.textRenderer, m.name(), sw - w - 4, y, 0xFFFFFFFF);
                y += 10;
            }
        }
        int infoX = gs == null ? 4 : gs.hudX.intValue();
        int infoY = gs == null ? 4 : gs.hudY.intValue();
        for (Module m : atlas.modules.all()) {
            if (m.isEnabled() && m instanceof AtlasModule am) {
                try {
                    infoY += am.renderHud(ctx, infoX, infoY, tickDelta);
                } catch (RuntimeException e) {
                    Atlas.LOG.error("HUD of {} failed", m.name(), e);
                }
            }
        }

        // Notifications (top right, under the module list)
        NotificationsModule nm = atlas.modules.get(NotificationsModule.class);
        if (nm == null || nm.isEnabled()) {
            int ny = Math.max(y + 4, 4);
            long now = atlas.notifications.nowMillis();
            for (Notification n : atlas.notifications.active()) {
                List<String> lines = UiKit.wrap(n.message(), 180);
                int w = 200, h = 14 + lines.size() * 10;
                if (seen.add(n.id())) {
                    anim.snap("n" + n.id(), 0); // slide in from the right
                }
                double target = n.progress(now) > 0.92 ? 0 : 1;
                double t = anim.approach("n" + n.id(), target);
                int x = (int) (sw - 6 - w * t);
                UiKit.roundRect(ctx, x, ny, w, h, 5, 0xE0141418);
                ctx.fill(x, ny + 3, x + 2, ny + h - 3, levelColor(n.level()));
                ctx.drawTextWithShadow(mc.textRenderer, n.title(), x + 7, ny + 3, levelColor(n.level()));
                for (int i = 0; i < lines.size(); i++) {
                    ctx.drawText(mc.textRenderer, lines.get(i), x + 7, ny + 14 + i * 10, 0xFFD8D8E0, false);
                }
                int barW = (int) ((w - 4) * (1 - n.progress(now)));
                ctx.fill(x + 2, ny + h - 1, x + 2 + barW, ny + h, levelColor(n.level()));
                ny += h + 4;
            }
        }
        if (seen.size() > 512) {
            seen.clear();
        }
        atlas.perf.record("render/hud", System.nanoTime() - start);
    }

    public static int levelColor(Notification.Level level) {
        return switch (level) {
            case INFO -> 0xFF64B5F6;
            case SUCCESS -> 0xFF81C784;
            case WARNING -> 0xFFFFB74D;
            case ERROR -> 0xFFE57373;
            case DISCOVERY -> 0xFFBA68C8;
        };
    }
}
