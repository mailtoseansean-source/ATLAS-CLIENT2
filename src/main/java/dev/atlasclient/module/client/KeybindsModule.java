package dev.atlasclient.module.client;

import dev.atlasclient.Atlas;
import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.module.Module;
import dev.atlasclient.core.setting.Keybind;
import dev.atlasclient.core.setting.Settings.ActionSetting;
import dev.atlasclient.module.AtlasModule;

import java.util.List;
import java.util.Map;

/** Lists keybind conflicts and resets module keybinds. Individual keys are set on each module card. */
public final class KeybindsModule extends AtlasModule {
    final ActionSetting check = add(new ActionSetting("Check conflicts", "Report keys bound to more than one action", this::check));
    final ActionSetting clearAll = add(new ActionSetting("Unbind all modules", "Remove every module keybind (GUI key stays)", this::clearAll));

    public KeybindsModule() {
        super("Keybinds", "Keybind overview and conflict check", Category.CLIENT);
        setEnabled(true);
    }

    @Override public boolean canToggle() { return false; }

    private void check() {
        Map<Integer, List<String>> conflicts = Atlas.get().keybinds.conflicts();
        if (conflicts.isEmpty()) {
            success("Keybinds", "No conflicts");
            return;
        }
        conflicts.forEach((key, names) -> warn("Keybind conflict", Keybind.name(key) + ": " + String.join(", ", names)));
    }

    private void clearAll() {
        for (Module m : Atlas.get().modules.all()) {
            m.keybind().set(Keybind.NONE);
        }
        Atlas.get().config.markDirty();
        info("Keybinds", "All module keybinds removed");
    }

    @Override public void onTick() {
        int n = Atlas.get().keybinds.conflicts().size();
        setStatus(n == 0 ? "No conflicts" : n + " conflicting keys");
    }
}
