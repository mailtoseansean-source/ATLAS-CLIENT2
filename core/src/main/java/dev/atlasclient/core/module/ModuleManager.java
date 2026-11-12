package dev.atlasclient.core.module;

import dev.atlasclient.core.event.CoreEvents;
import dev.atlasclient.core.event.EventBus;
import dev.atlasclient.core.notify.NotificationManager;
import dev.atlasclient.core.notify.Notification;
import dev.atlasclient.core.perf.PerfMonitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Owns every module, dispatches ticks and isolates module failures. Client thread only. */
public final class ModuleManager {
    private final Map<String, Module> modules = new LinkedHashMap<>();
    private final EventBus bus;
    private final NotificationManager notifications;
    private final PerfMonitor perf;
    private Consumer<String> errorLog = msg -> { };

    public ModuleManager(EventBus bus, NotificationManager notifications, PerfMonitor perf) {
        this.bus = bus;
        this.notifications = notifications;
        this.perf = perf;
    }

    public void setErrorLog(Consumer<String> log) { this.errorLog = log; }

    public <M extends Module> M register(M module) {
        String key = key(module.name());
        if (modules.containsKey(key)) {
            throw new IllegalStateException("Module already registered: " + module.name());
        }
        modules.put(key, module);
        module.attach(this);
        return module;
    }

    public List<Module> all() { return Collections.unmodifiableList(new ArrayList<>(modules.values())); }

    public int size() { return modules.size(); }

    public Module get(String name) { return modules.get(key(name)); }

    public <M extends Module> M get(Class<M> type) {
        for (Module m : modules.values()) {
            if (type.isInstance(m)) {
                return type.cast(m);
            }
        }
        return null;
    }

    public List<Module> byCategory(Category category) {
        List<Module> list = new ArrayList<>();
        for (Module m : modules.values()) {
            if (m.category() == category) {
                list.add(m);
            }
        }
        return list;
    }

    /**
     * Case-insensitive search over module names, descriptions and setting names. Name matches
     * rank before description matches.
     */
    public List<Module> search(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return all();
        }
        List<Module> hits = new ArrayList<>();
        for (Module m : modules.values()) {
            if (score(m, q) > 0) {
                hits.add(m);
            }
        }
        hits.sort(Comparator.comparingInt((Module m) -> -score(m, q)).thenComparing(Module::name));
        return hits;
    }

    private static int score(Module m, String q) {
        String name = m.name().toLowerCase(Locale.ROOT);
        if (name.equals(q)) return 100;
        if (name.startsWith(q)) return 80;
        if (name.contains(q)) return 60;
        if (m.description().toLowerCase(Locale.ROOT).contains(q)) return 30;
        if (m.category().displayName().toLowerCase(Locale.ROOT).contains(q)) return 20;
        for (var s : m.settings()) {
            if (s.name().toLowerCase(Locale.ROOT).contains(q)) return 10;
        }
        return 0;
    }

    public boolean setEnabled(Module module, boolean value) {
        if (value == module.isEnabled()) {
            return true;
        }
        if (value) {
            if (!module.canToggle()) {
                return false;
            }
            String blocked = module.enableBlockedReason();
            if (blocked != null) {
                notifications.push(Notification.Level.WARNING, module.name(), blocked);
                return false;
            }
        }
        try {
            module.setEnabledDirect(value);
        } catch (RuntimeException e) {
            fail(module, value ? "enable" : "disable", e);
            return false;
        }
        bus.post(new CoreEvents.ModuleToggled(module, module.isEnabled()));
        return true;
    }

    /** Runs onTick for every enabled module with per-module timing and failure isolation. */
    public void tick() {
        for (Module m : modules.values()) {
            if (!m.isEnabled()) {
                continue;
            }
            long start = System.nanoTime();
            try {
                m.onTick();
            } catch (RuntimeException e) {
                fail(m, "tick", e);
            } finally {
                perf.record("tick/" + m.name(), System.nanoTime() - start);
            }
        }
    }

    /** Called on disconnect/world change. Every module resets per-world state. */
    public void sessionReset() {
        for (Module m : modules.values()) {
            try {
                m.onSessionReset();
            } catch (RuntimeException e) {
                errorLog.accept("Session reset failed in " + m.name() + ": " + e);
            }
        }
    }

    public void disableAll() {
        for (Module m : modules.values()) {
            if (m.isEnabled() && m.canToggle()) {
                setEnabled(m, false);
            }
        }
    }

    private void fail(Module module, String phase, RuntimeException e) {
        errorLog.accept("Module '" + module.name() + "' failed during " + phase + ": " + e);
        notifications.push(Notification.Level.ERROR, "Module error",
                module.name() + " failed (" + phase + "): " + e.getClass().getSimpleName()
                        + (e.getMessage() != null ? " - " + e.getMessage() : ""));
        if (module.isEnabled()) {
            try {
                module.setEnabledDirect(false);
            } catch (RuntimeException ignored) {
                // Already failing; keep it disabled.
            }
            bus.post(new CoreEvents.ModuleToggled(module, false));
        }
    }

    private static String key(String name) { return name.toLowerCase(Locale.ROOT); }
}
