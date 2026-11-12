package dev.atlasclient.module.client;

import dev.atlasclient.AtlasClient;
import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.setting.Settings.BooleanSetting;
import dev.atlasclient.core.setting.Settings.NumberSetting;
import dev.atlasclient.module.AtlasModule;

/** Global performance guards. Always on. */
public final class PerformanceModule extends AtlasModule {
    public final BooleanSetting pauseScanWhenLowFps = add(new BooleanSetting("Slow scans below FPS", "Scan more gently (1 chunk every other tick) when the frame rate is low", true));
    public final NumberSetting minFps = add(new NumberSetting("Min FPS", "Below this frame rate, scans slow down", 30, 5, 120, 5));
    public final NumberSetting autosaveSeconds = add(new NumberSetting("Autosave seconds", "How often changed settings are written", 10, 2, 120, 1));

    public PerformanceModule() {
        super("Performance", "Scan throttling and autosave interval", Category.CLIENT);
        setEnabled(true);
    }

    @Override public boolean canToggle() { return false; }

    @Override public void onTick() {
        setStatus(AtlasClient.scan().queuedWork() + " analysis jobs queued");
    }
}
