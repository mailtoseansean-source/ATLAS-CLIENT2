package dev.atlasclient.core.module;

import dev.atlasclient.core.setting.Keybind;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * Turns raw key state into module toggles using edge detection (press, not hold), and reports
 * keybind conflicts. Client thread only.
 */
public final class KeybindDispatcher {
    private final ModuleManager modules;
    private final Set<Integer> down = new HashSet<>();
    private final Map<Integer, Runnable> globalBinds = new HashMap<>();

    public KeybindDispatcher(ModuleManager modules) {
        this.modules = modules;
    }

    /** Registers a non-module action such as "open GUI". */
    public void bindGlobal(int key, Runnable action) {
        globalBinds.put(key, action);
    }

    public void unbindGlobal(int key) { globalBinds.remove(key); }

    /**
     * Polls every bound key. {@code inputBlocked} should be true while a screen or chat is open so
     * typing never toggles modules.
     *
     * @return number of actions triggered
     */
    public int poll(IntPredicate isKeyDown, boolean inputBlocked) {
        Set<Integer> keys = new HashSet<>(globalBinds.keySet());
        for (Module m : modules.all()) {
            if (m.keybind().isBound()) {
                keys.add(m.keybind().key());
            }
        }
        int triggered = 0;
        for (int key : keys) {
            boolean pressed = isKeyDown.test(key);
            boolean wasDown = down.contains(key);
            if (pressed && !wasDown) {
                down.add(key);
                if (!inputBlocked) {
                    triggered += fire(key);
                }
            } else if (!pressed && wasDown) {
                down.remove(key);
            }
        }
        down.retainAll(keys);
        return triggered;
    }

    private int fire(int key) {
        int count = 0;
        Runnable global = globalBinds.get(key);
        if (global != null) {
            global.run();
            count++;
        }
        for (Module m : modules.all()) {
            if (m.keybind().key() == key && m.canToggle()) {
                m.toggle();
                count++;
            }
        }
        return count;
    }

    /** Groups of modules/actions sharing the same key. */
    public Map<Integer, List<String>> conflicts() {
        Map<Integer, List<String>> byKey = new HashMap<>();
        for (Map.Entry<Integer, Runnable> e : globalBinds.entrySet()) {
            byKey.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add("[global]");
        }
        for (Module m : modules.all()) {
            int k = m.keybind().key();
            if (k != Keybind.NONE) {
                byKey.computeIfAbsent(k, x -> new ArrayList<>()).add(m.name());
            }
        }
        byKey.values().removeIf(list -> list.size() < 2);
        return byKey;
    }

    public void releaseAll() { down.clear(); }
}
