package dev.atlasclient.core;

import dev.atlasclient.core.event.EventBus;
import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.module.Module;
import dev.atlasclient.core.module.ModuleManager;
import dev.atlasclient.core.notify.NotificationManager;
import dev.atlasclient.core.perf.PerfMonitor;
import dev.atlasclient.core.setting.Settings;

/** Shared fixtures for core tests. */
public final class TestModules {
    private TestModules() { }

    public static final class Harness {
        public final EventBus bus = new EventBus();
        public final NotificationManager notifications = new NotificationManager();
        public final PerfMonitor perf = new PerfMonitor();
        public final ModuleManager modules = new ModuleManager(bus, notifications, perf);
    }

    public static class SampleModule extends Module {
        public int enables;
        public int disables;
        public int ticks;
        public final Settings.BooleanSetting flag = add(new Settings.BooleanSetting("Flag", "test flag", true));
        public final Settings.NumberSetting range = add(new Settings.NumberSetting("Range", "test range", 8, 1, 32, 1));
        public final Settings.ColorSetting color = add(new Settings.ColorSetting("Color", "test color", 0x80FF0000));
        public final Settings.EnumSetting<Category> cat = add(new Settings.EnumSetting<>("Mode", "test enum", Category.WORLD));

        public SampleModule(String name, Category category) {
            super(name, "Sample module " + name, category);
        }

        @Override protected void onEnable() { enables++; }
        @Override protected void onDisable() { disables++; }
        @Override public void onTick() { ticks++; }
    }

    public static final class ThrowingModule extends Module {
        public final boolean throwOnEnable;

        public ThrowingModule(String name, boolean throwOnEnable) {
            super(name, "Throws", Category.UTILITY);
            this.throwOnEnable = throwOnEnable;
        }

        @Override protected void onEnable() {
            if (throwOnEnable) throw new IllegalStateException("boom on enable");
        }

        @Override public void onTick() { throw new IllegalStateException("boom on tick"); }
    }
}
