package dev.atlasclient.core.module;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.atlasclient.core.setting.Keybind;
import dev.atlasclient.core.setting.Setting;
import dev.atlasclient.core.setting.Settings.KeybindSetting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Base class for every feature. Lifecycle hooks are invoked on the client thread only.
 *
 * <p>Exceptions thrown from {@link #onEnable()}, {@link #onDisable()} or {@link #onTick()} are caught
 * by the {@link ModuleManager}, logged, reported as a notification, and the module is disabled so a
 * single broken feature cannot break the game.</p>
 */
public abstract class Module {
    private final String name;
    private final String description;
    private final Category category;
    private final Map<String, Setting<?>> settings = new LinkedHashMap<>();
    private final KeybindSetting keybind;
    private volatile boolean enabled;
    private ModuleManager manager;
    private String statusText = "";

    protected Module(String name, String description, Category category) {
        this(name, description, category, Keybind.NONE);
    }

    protected Module(String name, String description, Category category, int defaultKey) {
        this.name = Objects.requireNonNull(name);
        this.description = Objects.requireNonNull(description);
        this.category = Objects.requireNonNull(category);
        this.keybind = new KeybindSetting("Keybind", "Key that toggles this module", defaultKey);
    }

    public final String name() { return name; }
    public final String description() { return description; }
    public final Category category() { return category; }
    public final boolean isEnabled() { return enabled; }
    public final KeybindSetting keybind() { return keybind; }

    /** Short live status shown on the module card (e.g. "Unavailable: server-hidden"). */
    public String status() { return statusText; }

    protected final void setStatus(String text) { this.statusText = text == null ? "" : text; }

    /** Whether enabling this module is currently allowed. Return a reason string to block, or null. */
    public String enableBlockedReason() { return null; }

    /** Modules that are always on (e.g. GUI settings holders) return false. */
    public boolean canToggle() { return true; }

    protected final <S extends Setting<?>> S add(S setting) {
        String key = setting.name().toLowerCase(Locale.ROOT);
        if (settings.containsKey(key)) {
            throw new IllegalStateException("Duplicate setting '" + setting.name() + "' in " + name);
        }
        settings.put(key, setting);
        return setting;
    }

    public final List<Setting<?>> settings() {
        return Collections.unmodifiableList(new ArrayList<>(settings.values()));
    }

    public final Setting<?> setting(String settingName) {
        return settings.get(settingName.toLowerCase(Locale.ROOT));
    }

    final void attach(ModuleManager manager) { this.manager = manager; }

    protected final ModuleManager manager() { return manager; }

    /** Enables or disables through the manager so hooks and events fire consistently. */
    public final boolean setEnabled(boolean value) {
        if (manager != null) {
            return manager.setEnabled(this, value);
        }
        return setEnabledDirect(value);
    }

    public final boolean toggle() { return setEnabled(!enabled); }

    /** Package-private: performs the state change and hook call. Errors propagate to the manager. */
    boolean setEnabledDirect(boolean value) {
        if (value == enabled) {
            return true;
        }
        if (value) {
            enabled = true;
            try {
                onEnable();
            } catch (RuntimeException e) {
                enabled = false;
                throw e;
            }
        } else {
            enabled = false;
            onDisable();
        }
        return true;
    }

    protected void onEnable() { }
    protected void onDisable() { }
    /** Called every client tick while enabled. */
    public void onTick() { }
    /** Called when the world/server session ends. Must release per-world state. */
    public void onSessionReset() { }

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("enabled", enabled);
        obj.add("key", keybind.toJson());
        JsonObject s = new JsonObject();
        for (Setting<?> setting : settings.values()) {
            if (setting.persistent()) {
                s.add(setting.name(), setting.toJson());
            }
        }
        obj.add("settings", s);
        return obj;
    }

    /**
     * Applies stored values. Unknown keys are ignored; invalid values keep defaults. Returns the list
     * of problems found so the caller can report them.
     */
    public List<String> applyJson(JsonObject obj) {
        List<String> problems = new ArrayList<>();
        if (obj.has("key") && !keybind.fromJson(obj.get("key"))) {
            problems.add(name + ": invalid keybind");
        }
        if (obj.has("settings") && obj.get("settings").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : obj.getAsJsonObject("settings").entrySet()) {
                Setting<?> setting = setting(e.getKey());
                if (setting == null) {
                    continue;
                }
                if (!setting.fromJson(e.getValue())) {
                    problems.add(name + "." + setting.name() + ": invalid value, using default");
                }
            }
        }
        return problems;
    }

    @Override
    public String toString() { return name; }
}
