package dev.atlasclient.module.utility;

import dev.atlasclient.Atlas;
import dev.atlasclient.AtlasClient;
import dev.atlasclient.core.analysis.DiscoveryStore;
import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.setting.Settings.ActionSetting;
import dev.atlasclient.gui.DiscoveriesScreen;
import dev.atlasclient.module.AtlasModule;
import dev.atlasclient.util.Env;
import net.minecraft.client.MinecraftClient;

import java.nio.file.Path;
import java.util.List;

/**
 * Persists saved / hidden / dismissed discoveries per world under
 * config/atlasclient/worlds/<world>/discoveries.json and lists saved findings.
 */
public final class SavedDiscoveriesModule extends AtlasModule {
    final ActionSetting open = add(new ActionSetting("Open list", "Open the discoveries screen", () ->
            MinecraftClient.getInstance().setScreen(new DiscoveriesScreen(MinecraftClient.getInstance().currentScreen))));
    final ActionSetting forget = add(new ActionSetting("Forget all marks", "Clear saved/hidden/dismissed for this world", this::forget));
    private String loadedFor = "";
    private boolean dirty;
    private int ticks;

    public SavedDiscoveriesModule() {
        super("Saved Discoveries", "Keeps your saved, hidden and dismissed findings per world", Category.UTILITY);
        setEnabled(true);
    }

    @Override public boolean canToggle() { return false; }

    public void markDirty() { dirty = true; }

    private Path file(String key) { return Atlas.get().worldDir(key).resolve("discoveries.json"); }

    @Override public void onTick() {
        String key = Env.worldKey();
        if (!key.equals(loadedFor)) {
            if (!loadedFor.isEmpty()) save(loadedFor);
            loadedFor = key;
            if (!key.isEmpty()) {
                try {
                    AtlasClient.scan().discoveries().load(file(key));
                } catch (Exception e) {
                    warn("Saved discoveries", "Could not load: " + e.getMessage());
                }
            }
        }
        if (dirty && ++ticks % 40 == 0) {
            save(loadedFor);
        }
        List<DiscoveryStore.SavedFinding> saved = AtlasClient.scan().discoveries().savedFindings();
        setStatus(saved.size() + " saved");
    }

    private void save(String key) {
        if (key.isEmpty()) return;
        try {
            AtlasClient.scan().discoveries().save(file(key));
            dirty = false;
        } catch (Exception e) {
            error("Saved discoveries", "Could not save: " + e.getMessage());
        }
    }

    private void forget() {
        AtlasClient.scan().discoveries().clearAll();
        dirty = true;
        info("Saved discoveries", "All marks cleared for this world");
    }

    @Override public void onSessionReset() {
        save(loadedFor);
        loadedFor = "";
    }
}
