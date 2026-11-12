package dev.atlasclient.core.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.atlasclient.core.module.Module;
import dev.atlasclient.core.module.ModuleManager;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Stores module state in named JSON profiles under {@code <root>/profiles/<name>.json} and
 * remembers the active profile plus GUI preferences in {@code <root>/client.json}.
 *
 * <p>All methods run on the client thread. Files are written atomically.</p>
 */
public final class ConfigManager {
    public static final int FORMAT_VERSION = 1;
    public static final String DEFAULT_PROFILE = "default";
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final Path root;
    private final ModuleManager modules;
    private String activeProfile = DEFAULT_PROFILE;
    private JsonObject uiPrefs = new JsonObject();
    private boolean dirty;
    private Consumer<String> warnings = s -> { };

    public ConfigManager(Path root, ModuleManager modules) {
        this.root = root;
        this.modules = modules;
    }

    public void setWarningSink(Consumer<String> sink) { this.warnings = sink; }

    public Path root() { return root; }
    public Path profilesDir() { return root.resolve("profiles"); }
    public String activeProfile() { return activeProfile; }

    public static boolean isValidProfileName(String name) {
        return name != null && VALID_NAME.matcher(name).matches();
    }

    /** Loads client.json and the active profile. Missing files are created with defaults. */
    public void loadAll() {
        Path clientFile = root.resolve("client.json");
        if (Files.exists(clientFile)) {
            try {
                JsonElement el = JsonFiles.read(clientFile);
                if (el.isJsonObject()) {
                    JsonObject obj = el.getAsJsonObject();
                    if (obj.has("activeProfile") && isValidProfileName(obj.get("activeProfile").getAsString())) {
                        activeProfile = obj.get("activeProfile").getAsString();
                    }
                    if (obj.has("ui") && obj.get("ui").isJsonObject()) {
                        uiPrefs = obj.getAsJsonObject("ui");
                    }
                }
            } catch (IOException | RuntimeException e) {
                warnings.accept("client.json unreadable, using defaults: " + e.getMessage());
            }
        }
        if (!loadProfile(activeProfile)) {
            activeProfile = DEFAULT_PROFILE;
            saveProfile(DEFAULT_PROFILE);
        }
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", FORMAT_VERSION);
        JsonObject mods = new JsonObject();
        for (Module m : modules.all()) {
            mods.add(m.name(), m.toJson());
        }
        root.add("modules", mods);
        return root;
    }

    /** Applies a profile JSON. Returns the list of non-fatal problems. */
    public List<String> applyJson(JsonObject json) {
        List<String> problems = new ArrayList<>();
        if (!json.has("modules") || !json.get("modules").isJsonObject()) {
            problems.add("profile has no 'modules' object");
            return problems;
        }
        JsonObject mods = json.getAsJsonObject("modules");
        for (Map.Entry<String, JsonElement> e : mods.entrySet()) {
            Module m = modules.get(e.getKey());
            if (m == null || !e.getValue().isJsonObject()) {
                continue;
            }
            JsonObject obj = e.getValue().getAsJsonObject();
            problems.addAll(m.applyJson(obj));
            if (obj.has("enabled") && obj.get("enabled").isJsonPrimitive() && m.canToggle()) {
                boolean want = obj.get("enabled").getAsBoolean();
                if (want != m.isEnabled()) {
                    modules.setEnabled(m, want);
                }
            }
        }
        return problems;
    }

    public boolean saveProfile(String name) {
        if (!isValidProfileName(name)) {
            warnings.accept("Invalid profile name: " + name);
            return false;
        }
        try {
            JsonFiles.writeAtomic(profilesDir().resolve(name + ".json"), toJson());
            activeProfile = name;
            saveClientFile();
            dirty = false;
            return true;
        } catch (IOException e) {
            warnings.accept("Failed to save profile '" + name + "': " + e.getMessage());
            return false;
        }
    }

    public boolean loadProfile(String name) {
        if (!isValidProfileName(name)) {
            return false;
        }
        Path file = profilesDir().resolve(name + ".json");
        if (!Files.exists(file)) {
            return false;
        }
        try {
            JsonElement el = JsonFiles.read(file);
            if (!el.isJsonObject()) {
                warnings.accept("Profile '" + name + "' is not a JSON object");
                return false;
            }
            for (String problem : applyJson(el.getAsJsonObject())) {
                warnings.accept(problem);
            }
            activeProfile = name;
            saveClientFile();
            dirty = false;
            return true;
        } catch (IOException e) {
            warnings.accept("Failed to load profile '" + name + "': " + e.getMessage());
            return false;
        }
    }

    public boolean deleteProfile(String name) {
        if (!isValidProfileName(name) || name.equals(activeProfile)) {
            return false;
        }
        try {
            return Files.deleteIfExists(profilesDir().resolve(name + ".json"));
        } catch (IOException e) {
            warnings.accept("Failed to delete profile: " + e.getMessage());
            return false;
        }
    }

    public List<String> listProfiles() {
        List<String> names = new ArrayList<>();
        Path dir = profilesDir();
        if (!Files.isDirectory(dir)) {
            return names;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : ds) {
                String n = p.getFileName().toString();
                n = n.substring(0, n.length() - 5);
                if (isValidProfileName(n)) {
                    names.add(n);
                }
            }
        } catch (IOException e) {
            warnings.accept("Cannot list profiles: " + e.getMessage());
        }
        Collections.sort(names);
        return names;
    }

    public JsonObject uiPrefs() { return uiPrefs; }

    public void markDirty() { dirty = true; }
    public boolean isDirty() { return dirty; }

    /** Saves the active profile if something changed. Cheap to call often. */
    public void flushIfDirty() {
        if (dirty) {
            saveProfile(activeProfile);
        }
    }

    public void saveClientFile() {
        JsonObject obj = new JsonObject();
        obj.addProperty("formatVersion", FORMAT_VERSION);
        obj.addProperty("activeProfile", activeProfile);
        obj.add("ui", uiPrefs);
        try {
            JsonFiles.writeAtomic(root.resolve("client.json"), obj);
        } catch (IOException e) {
            warnings.accept("Failed to save client.json: " + e.getMessage());
        }
    }
}
