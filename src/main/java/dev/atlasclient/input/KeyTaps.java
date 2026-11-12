package dev.atlasclient.input;

import java.util.HashSet;
import java.util.Set;

/**
 * Key presses reported by the game's keyboard handler (see {@code KeyboardMixin}). The keybind
 * poll runs once per tick, so a quick tap that goes down and up between two ticks would otherwise
 * be missed; recorded presses make every tap count. Client thread only.
 */
public final class KeyTaps {
    private static final Set<Integer> PRESSED = new HashSet<>();

    private KeyTaps() { }

    public static void press(int key) {
        if (key >= 0) PRESSED.add(key);
    }

    /** True once for every key pressed since the last call for that key. */
    public static boolean consume(int key) {
        return PRESSED.remove(key);
    }

    /** Drops presses of keys nobody asked about, so they cannot fire later. */
    public static void clear() {
        PRESSED.clear();
    }
}
