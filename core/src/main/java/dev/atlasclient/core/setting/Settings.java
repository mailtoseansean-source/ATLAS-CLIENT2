package dev.atlasclient.core.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/** Concrete setting types. Kept in one file because each is small. */
public final class Settings {
    private Settings() { }

    public static final class BooleanSetting extends Setting<Boolean> {
        public BooleanSetting(String name, String description, boolean defaultValue) {
            super(name, description, defaultValue);
        }

        public boolean enabled() { return get(); }

        public void toggle() { set(!get()); }

        @Override public JsonElement toJson() { return new JsonPrimitive(get()); }

        @Override public boolean fromJson(JsonElement json) {
            if (json != null && json.isJsonPrimitive() && json.getAsJsonPrimitive().isBoolean()) {
                set(json.getAsBoolean());
                return true;
            }
            return false;
        }

        @Override public String displayValue() { return get() ? "On" : "Off"; }
    }

    public static final class NumberSetting extends Setting<Double> {
        private final double min;
        private final double max;
        private final double step;

        public NumberSetting(String name, String description, double defaultValue, double min, double max, double step) {
            super(name, description, defaultValue);
            if (!(min < max) || step < 0) {
                throw new IllegalArgumentException("invalid range for " + name);
            }
            this.min = min;
            this.max = max;
            this.step = step;
            set(defaultValue);
        }

        public double min() { return min; }
        public double max() { return max; }
        public double step() { return step; }
        public int intValue() { return (int) Math.round(get()); }
        public float floatValue() { return get().floatValue(); }

        /** Fraction 0..1 of the value inside its range (for sliders). */
        public double fraction() { return (get() - min) / (max - min); }

        public double setFraction(double fraction) {
            double f = Math.max(0, Math.min(1, fraction));
            return set(min + f * (max - min));
        }

        @Override protected Double sanitize(Double candidate) {
            double v = candidate;
            if (Double.isNaN(v)) {
                v = defaultValue();
            }
            if (step > 0) {
                v = min + Math.round((v - min) / step) * step;
            }
            v = Math.max(min, Math.min(max, v));
            // Remove floating noise such as 0.30000000000000004
            return Math.round(v * 1_000_000d) / 1_000_000d;
        }

        @Override public JsonElement toJson() { return new JsonPrimitive(get()); }

        @Override public boolean fromJson(JsonElement json) {
            if (json != null && json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) {
                set(json.getAsDouble());
                return true;
            }
            return false;
        }

        @Override public String displayValue() {
            double v = get();
            if (step >= 1 && Math.abs(v - Math.rint(v)) < 1e-9) {
                return Long.toString(Math.round(v));
            }
            return String.format(Locale.ROOT, "%.2f", v);
        }
    }

    public static final class EnumSetting<E extends Enum<E>> extends Setting<E> {
        private final E[] constants;

        public EnumSetting(String name, String description, E defaultValue) {
            super(name, description, defaultValue);
            this.constants = defaultValue.getDeclaringClass().getEnumConstants();
        }

        public List<E> options() { return List.of(constants); }

        public E cycle(int direction) {
            int idx = Math.floorMod(get().ordinal() + Integer.signum(direction == 0 ? 1 : direction), constants.length);
            return set(constants[idx]);
        }

        @Override public JsonElement toJson() { return new JsonPrimitive(get().name()); }

        @Override public boolean fromJson(JsonElement json) {
            if (json == null || !json.isJsonPrimitive()) {
                return false;
            }
            String s = json.getAsString();
            for (E c : constants) {
                if (c.name().equalsIgnoreCase(s)) {
                    set(c);
                    return true;
                }
            }
            return false;
        }

        @Override public String displayValue() {
            // Enums with their own label (toString) show it, e.g. "Extreme (512)".
            String t = get().toString();
            return t.equals(get().name()) ? pretty(get().name()) : t;
        }

        public static String pretty(String enumName) {
            String lower = enumName.toLowerCase(Locale.ROOT).replace('_', ' ');
            return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
        }
    }

    /** Dropdown whose options are computed at runtime (e.g. schematic files on disk). */
    public static final class ChoiceSetting extends Setting<String> {
        private final Supplier<List<String>> options;

        public ChoiceSetting(String name, String description, String defaultValue, Supplier<List<String>> options) {
            super(name, description, defaultValue);
            this.options = options;
        }

        public List<String> options() {
            List<String> list = options.get();
            return list == null ? List.of() : list;
        }

        public String cycle(int direction) {
            List<String> opts = options();
            if (opts.isEmpty()) {
                return get();
            }
            int idx = opts.indexOf(get());
            int next = Math.floorMod(idx + (direction < 0 ? -1 : 1), opts.size());
            return set(opts.get(next));
        }

        @Override public JsonElement toJson() { return new JsonPrimitive(get()); }

        @Override public boolean fromJson(JsonElement json) {
            if (json != null && json.isJsonPrimitive()) {
                set(json.getAsString());
                return true;
            }
            return false;
        }
    }

    public static final class ColorSetting extends Setting<Integer> {
        public ColorSetting(String name, String description, int argb) {
            super(name, description, argb);
        }

        public int argb() { return get(); }
        public int alpha() { return (get() >>> 24) & 0xFF; }
        public int red() { return (get() >>> 16) & 0xFF; }
        public int green() { return (get() >>> 8) & 0xFF; }
        public int blue() { return get() & 0xFF; }

        public int withAlpha(int alpha) { return (Math.max(0, Math.min(255, alpha)) << 24) | (get() & 0xFFFFFF); }

        @Override public JsonElement toJson() { return new JsonPrimitive(String.format(Locale.ROOT, "#%08X", get())); }

        @Override public boolean fromJson(JsonElement json) {
            if (json == null || !json.isJsonPrimitive()) {
                return false;
            }
            try {
                if (json.getAsJsonPrimitive().isNumber()) {
                    set(json.getAsInt());
                    return true;
                }
                String s = json.getAsString().trim();
                if (s.startsWith("#")) {
                    s = s.substring(1);
                }
                if (s.length() == 6) {
                    s = "FF" + s;
                }
                if (s.length() != 8) {
                    return false;
                }
                set((int) Long.parseLong(s, 16));
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        @Override public String displayValue() { return String.format(Locale.ROOT, "#%08X", get()); }
    }

    public static final class StringSetting extends Setting<String> {
        private final int maxLength;

        public StringSetting(String name, String description, String defaultValue, int maxLength) {
            super(name, description, defaultValue);
            this.maxLength = maxLength;
        }

        @Override protected String sanitize(String candidate) {
            return candidate.length() > maxLength ? candidate.substring(0, maxLength) : candidate;
        }

        @Override public JsonElement toJson() { return new JsonPrimitive(get()); }

        @Override public boolean fromJson(JsonElement json) {
            if (json != null && json.isJsonPrimitive()) {
                set(json.getAsString());
                return true;
            }
            return false;
        }
    }

    /** A key binding. {@link Keybind#NONE} means unbound. */
    public static final class KeybindSetting extends Setting<Integer> {
        public KeybindSetting(String name, String description, int defaultKey) {
            super(name, description, defaultKey);
        }

        public int key() { return get(); }
        public boolean isBound() { return get() != Keybind.NONE; }

        @Override public JsonElement toJson() { return new JsonPrimitive(get()); }

        @Override public boolean fromJson(JsonElement json) {
            if (json != null && json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) {
                set(json.getAsInt());
                return true;
            }
            return false;
        }

        @Override public String displayValue() { return Keybind.name(get()); }
    }

    /** A button in the GUI. Never persisted. */
    public static final class ActionSetting extends Setting<String> {
        private final Runnable action;

        public ActionSetting(String name, String description, Runnable action) {
            super(name, description, "");
            this.action = action;
        }

        public void run() { action.run(); }

        @Override public boolean persistent() { return false; }

        @Override public JsonElement toJson() { return JsonNull.INSTANCE; }

        @Override public boolean fromJson(JsonElement json) { return true; }

        @Override public String displayValue() { return ""; }
    }
}
