package dev.atlasclient;

import dev.atlasclient.core.config.ConfigManager;
import dev.atlasclient.core.event.EventBus;
import dev.atlasclient.core.gui.GuiModel;
import dev.atlasclient.core.module.KeybindDispatcher;
import dev.atlasclient.core.module.ModuleManager;
import dev.atlasclient.core.notify.NotificationManager;
import dev.atlasclient.core.perf.PerfMonitor;
import dev.atlasclient.core.util.SessionLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Process-wide services. Created once in {@link AtlasClient#onInitializeClient()}; everything here
 * is either thread-safe or only touched on the client thread (documented per class).
 */
public final class Atlas {
    public static final String MOD_ID = "atlasclient";
    public static final Logger LOG = LoggerFactory.getLogger("Atlas Client");

    private static Atlas instance;

    public final EventBus bus = new EventBus();
    public final NotificationManager notifications = new NotificationManager();
    public final PerfMonitor perf = new PerfMonitor();
    public final ModuleManager modules;
    public final KeybindDispatcher keybinds;
    public final ConfigManager config;
    public final SessionLifecycle lifecycle = new SessionLifecycle();
    public final GuiModel gui;
    public final Path rootDir;

    Atlas(Path configDir) {
        this.rootDir = configDir.resolve(MOD_ID);
        this.modules = new ModuleManager(bus, notifications, perf);
        this.modules.setErrorLog(LOG::error);
        this.keybinds = new KeybindDispatcher(modules);
        this.config = new ConfigManager(rootDir, modules);
        this.config.setWarningSink(LOG::warn);
        this.gui = new GuiModel(modules);
        this.lifecycle.setErrorLog(LOG::error);
        this.bus.setErrorHandler((event, error) -> LOG.error("Event listener failed for {}", event.getClass().getSimpleName(), error));
        instance = this;
    }

    public static Atlas get() {
        if (instance == null) {
            throw new IllegalStateException("Atlas Client is not initialized yet");
        }
        return instance;
    }

    public static boolean ready() { return instance != null; }

    /** Per-world data folder: config/atlasclient/worlds/<world key>/ */
    public Path worldDir(String worldKey) {
        String safe = worldKey.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isEmpty()) {
            safe = "unknown";
        }
        return rootDir.resolve("worlds").resolve(safe);
    }

    public Path exportsDir() { return rootDir.resolve("exports"); }
}
