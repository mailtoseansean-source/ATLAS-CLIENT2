package dev.atlasclient.core.setting;

import java.util.Map;
import java.util.function.IntFunction;

/**
 * Key codes use GLFW numbering (the same numbers Minecraft uses), so the core can be tested without
 * LWJGL. The Fabric layer installs a name resolver that uses Minecraft's localized key names.
 */
public final class Keybind {
    public static final int NONE = -1;
    public static final int KEY_RIGHT_SHIFT = 344;
    public static final int KEY_ESCAPE = 256;
    public static final int KEY_BACKSPACE = 259;
    public static final int KEY_DELETE = 261;

    private static volatile IntFunction<String> resolver = Keybind::fallbackName;

    private static final Map<Integer, String> FALLBACK = Map.ofEntries(
            Map.entry(32, "Space"), Map.entry(256, "Escape"), Map.entry(257, "Enter"),
            Map.entry(258, "Tab"), Map.entry(259, "Backspace"), Map.entry(260, "Insert"),
            Map.entry(261, "Delete"), Map.entry(262, "Right"), Map.entry(263, "Left"),
            Map.entry(264, "Down"), Map.entry(265, "Up"), Map.entry(266, "Page Up"),
            Map.entry(267, "Page Down"), Map.entry(268, "Home"), Map.entry(269, "End"),
            Map.entry(340, "Left Shift"), Map.entry(341, "Left Ctrl"), Map.entry(342, "Left Alt"),
            Map.entry(344, "Right Shift"), Map.entry(345, "Right Ctrl"), Map.entry(346, "Right Alt"));

    private Keybind() { }

    public static void setNameResolver(IntFunction<String> nameResolver) {
        resolver = nameResolver == null ? Keybind::fallbackName : nameResolver;
    }

    public static String name(int key) {
        if (key == NONE) {
            return "None";
        }
        try {
            String n = resolver.apply(key);
            return n == null || n.isBlank() ? fallbackName(key) : n;
        } catch (RuntimeException e) {
            return fallbackName(key);
        }
    }

    static String fallbackName(int key) {
        if (key >= 65 && key <= 90) {
            return String.valueOf((char) key);
        }
        if (key >= 48 && key <= 57) {
            return String.valueOf((char) key);
        }
        if (key >= 290 && key <= 314) {
            return "F" + (key - 289);
        }
        String n = FALLBACK.get(key);
        return n != null ? n : "Key " + key;
    }
}
