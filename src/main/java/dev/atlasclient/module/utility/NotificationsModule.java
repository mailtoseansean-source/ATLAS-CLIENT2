package dev.atlasclient.module.utility;

import dev.atlasclient.Atlas;
import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.notify.Notification;
import dev.atlasclient.core.setting.Settings.ActionSetting;
import dev.atlasclient.core.setting.Settings.BooleanSetting;
import dev.atlasclient.core.setting.Settings.NumberSetting;
import dev.atlasclient.module.AtlasModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Toast notifications: display duration, how many are visible, muted levels and history. */
public final class NotificationsModule extends AtlasModule {
    final NumberSetting duration = add(new NumberSetting("Duration seconds", "How long a toast stays visible", 4, 1, 30, 0.5));
    final NumberSetting maxVisible = add(new NumberSetting("Max visible", "Toasts shown at once", 5, 1, 12, 1));
    final BooleanSetting showInfo = add(new BooleanSetting("Info", "Show info toasts", true));
    final BooleanSetting showDiscovery = add(new BooleanSetting("Discoveries", "Show discovery toasts", true));
    final BooleanSetting chatEcho = add(new BooleanSetting("Echo errors to chat", "Also print errors in chat (client-side only)", false));
    final ActionSetting history = add(new ActionSetting("Print history", "Print the last 20 notifications to chat (client-side only)", this::printHistory));
    final ActionSetting clear = add(new ActionSetting("Clear history", "Forget notification history", () -> Atlas.get().notifications.clearHistory()));

    public NotificationsModule() {
        super("Notifications", "Toast notifications with history", Category.UTILITY);
        setEnabled(true);
        duration.onChange(v -> apply());
        maxVisible.onChange(v -> apply());
        showInfo.onChange(v -> apply());
        showDiscovery.onChange(v -> apply());
    }

    public void apply() {
        var n = Atlas.get().notifications;
        n.setDurationMillis((long) (duration.get() * 1000));
        n.setMaxVisible(maxVisible.intValue());
        Set<Notification.Level> muted = EnumSet.noneOf(Notification.Level.class);
        if (!showInfo.get()) muted.add(Notification.Level.INFO);
        if (!showDiscovery.get()) muted.add(Notification.Level.DISCOVERY);
        n.setMuted(muted);
    }

    private void printHistory() {
        var player = MinecraftClient.getInstance().player;
        if (player == null) return;
        List<Notification> h = Atlas.get().notifications.history();
        for (int i = Math.min(19, h.size() - 1); i >= 0; i--) {
            Notification n = h.get(i);
            player.sendMessage(Text.literal("[Atlas] " + n.level() + " " + n.title() + ": " + n.message()), false);
        }
    }

    public boolean chatEcho() { return chatEcho.get(); }
}
