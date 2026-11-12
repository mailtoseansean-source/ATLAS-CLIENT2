package dev.atlasclient.core.gui;

import com.google.gson.JsonObject;
import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.module.Module;
import dev.atlasclient.core.module.ModuleManager;
import dev.atlasclient.core.setting.Keybind;
import dev.atlasclient.core.setting.Settings.KeybindSetting;
import dev.atlasclient.core.setting.Settings.NumberSetting;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI state and interaction logic, separate from drawing so it can be unit tested.
 * The Fabric ClickGUI screen renders this model and forwards input to it.
 */
public final class GuiModel {
    private final ModuleManager modules;
    private Category category = Category.WORLD;
    /** "Active" view: every module that is switched on, across categories. */
    private boolean activeView;
    private final StringBuilder search = new StringBuilder();
    private boolean searchFocused;
    private Module expanded;
    private KeybindSetting capturing;
    private String openDropdown;
    private double scroll;
    private double maxScroll;

    public GuiModel(ModuleManager modules) {
        this.modules = modules;
    }

    public Category category() { return category; }

    public void selectCategory(Category c) {
        if (c != category || activeView) {
            category = c;
            activeView = false;
            scroll = 0;
            expanded = null;
            openDropdown = null;
        }
    }

    public boolean activeView() { return activeView; }

    /** Shows only modules that are currently on (toggleable ones), from every category. */
    public void selectActive() {
        if (!activeView) {
            activeView = true;
            scroll = 0;
            expanded = null;
            openDropdown = null;
        }
    }

    public String search() { return search.toString(); }
    public boolean searchFocused() { return searchFocused; }
    public void focusSearch(boolean focused) { this.searchFocused = focused; }

    public void typeChar(char c) {
        if (searchFocused && search.length() < 48 && !Character.isISOControl(c)) {
            search.append(c);
            scroll = 0;
        }
    }

    public void backspace() {
        if (searchFocused && search.length() > 0) {
            search.setLength(search.length() - 1);
            scroll = 0;
        }
    }

    public void clearSearch() {
        search.setLength(0);
        scroll = 0;
    }

    /**
     * Modules shown in the main panel: search results across all categories when searching,
     * otherwise the selected category.
     */
    public List<Module> visibleModules() {
        if (search.length() > 0) {
            return modules.search(search.toString());
        }
        if (activeView) {
            List<Module> on = new ArrayList<>();
            for (Module m : modules.all()) {
                if (m.isEnabled() && m.canToggle()) on.add(m);
            }
            return on;
        }
        return new ArrayList<>(modules.byCategory(category));
    }

    public Module expanded() { return expanded; }

    public void toggleExpanded(Module m) {
        expanded = expanded == m ? null : m;
        openDropdown = null;
    }

    /** Left click on a module card toggles it; right click expands settings. */
    public void clickModule(Module m, int button) {
        if (button == 0) {
            m.toggle();
        } else if (button == 1) {
            toggleExpanded(m);
        }
    }

    public KeybindSetting capturing() { return capturing; }

    public void beginKeyCapture(KeybindSetting setting) {
        capturing = setting;
        searchFocused = false;
    }

    /**
     * Routes a key press. Returns true when consumed. While capturing a keybind: Escape cancels,
     * Backspace/Delete unbinds, anything else binds.
     */
    public boolean keyPressed(int key) {
        if (capturing != null) {
            if (key == Keybind.KEY_ESCAPE) {
                capturing = null;
            } else if (key == Keybind.KEY_BACKSPACE || key == Keybind.KEY_DELETE) {
                capturing.set(Keybind.NONE);
                capturing = null;
            } else {
                capturing.set(key);
                capturing = null;
            }
            return true;
        }
        if (searchFocused) {
            if (key == Keybind.KEY_BACKSPACE) {
                backspace();
                return true;
            }
            if (key == Keybind.KEY_ESCAPE) {
                searchFocused = false;
                return true;
            }
        }
        return false;
    }

    public String openDropdown() { return openDropdown; }

    public void toggleDropdown(String id) { openDropdown = id.equals(openDropdown) ? null : id; }

    public void closeDropdown() { openDropdown = null; }

    /** Converts a mouse X position over a slider track to a stored value. */
    public static double sliderValue(NumberSetting setting, double mouseX, double trackX, double trackWidth) {
        double fraction = trackWidth <= 0 ? 0 : (mouseX - trackX) / trackWidth;
        return setting.setFraction(fraction);
    }

    public double scroll() { return scroll; }

    public void setMaxScroll(double max) {
        maxScroll = Math.max(0, max);
        scroll = Math.max(0, Math.min(maxScroll, scroll));
    }

    public void scrollBy(double amount) { scroll = Math.max(0, Math.min(maxScroll, scroll + amount)); }

    /** Saves UI preferences (selected category) into the config's UI object. */
    public void savePrefs(JsonObject ui) {
        ui.addProperty("category", activeView ? "ACTIVE" : category.name());
    }

    public void loadPrefs(JsonObject ui) {
        if (ui != null && ui.has("category")) {
            if ("ACTIVE".equals(ui.get("category").getAsString())) {
                activeView = true;
                return;
            }
            try {
                category = Category.valueOf(ui.get("category").getAsString());
            } catch (IllegalArgumentException | UnsupportedOperationException ignored) {
                category = Category.WORLD;
            }
        }
    }
}
