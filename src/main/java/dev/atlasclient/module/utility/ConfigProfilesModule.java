package dev.atlasclient.module.utility;

import dev.atlasclient.Atlas;
import dev.atlasclient.core.config.ConfigManager;
import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.setting.Settings.ActionSetting;
import dev.atlasclient.core.setting.Settings.ChoiceSetting;
import dev.atlasclient.core.setting.Settings.StringSetting;
import dev.atlasclient.module.AtlasModule;

/** Multiple named configuration profiles (config/atlasclient/profiles/*.json). */
public final class ConfigProfilesModule extends AtlasModule {
    final ChoiceSetting profile = add(new ChoiceSetting("Profile", "Profile to load", ConfigManager.DEFAULT_PROFILE,
            () -> Atlas.get().config.listProfiles()));
    final StringSetting newName = add(new StringSetting("New name", "Name for 'Save as' (letters, digits, - and _)", "", 32));
    final ActionSetting load = add(new ActionSetting("Load", "Load the selected profile", this::load));
    final ActionSetting save = add(new ActionSetting("Save", "Save current settings into the active profile", this::save));
    final ActionSetting saveAs = add(new ActionSetting("Save as", "Save current settings as a new profile", this::saveAs));
    final ActionSetting delete = add(new ActionSetting("Delete", "Delete the selected profile (not the active one)", this::delete));

    public ConfigProfilesModule() {
        super("Configuration Profiles", "Save and switch between named configurations", Category.UTILITY);
        setEnabled(true);
    }

    @Override public boolean canToggle() { return false; }

    private void load() {
        String name = profile.get();
        // Loading replaces this module's own settings too; remember the target first.
        if (Atlas.get().config.loadProfile(name)) {
            success("Profiles", "Loaded '" + name + "'");
        } else {
            error("Profiles", "Could not load '" + name + "'");
        }
    }

    private void save() {
        ConfigManager c = Atlas.get().config;
        if (c.saveProfile(c.activeProfile())) success("Profiles", "Saved '" + c.activeProfile() + "'");
    }

    private void saveAs() {
        String n = newName.get().trim();
        if (!ConfigManager.isValidProfileName(n)) {
            warn("Profiles", "Invalid name. Use 1-32 letters, digits, - or _.");
            return;
        }
        if (Atlas.get().config.saveProfile(n)) {
            profile.set(n);
            success("Profiles", "Saved as '" + n + "' (now active)");
        }
    }

    private void delete() {
        String n = profile.get();
        if (Atlas.get().config.deleteProfile(n)) {
            info("Profiles", "Deleted '" + n + "'");
            profile.set(Atlas.get().config.activeProfile());
        } else {
            warn("Profiles", "Cannot delete '" + n + "' (missing or active)");
        }
    }

    @Override public void onTick() { setStatus("Active: " + Atlas.get().config.activeProfile()); }
}
