package dev.atlasclient.module.client;

import dev.atlasclient.Atlas;
import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.setting.Keybind;
import dev.atlasclient.core.setting.Settings.BooleanSetting;
import dev.atlasclient.core.setting.Settings.KeybindSetting;
import dev.atlasclient.core.setting.Settings.NumberSetting;
import dev.atlasclient.gui.ClickGuiScreen;
import dev.atlasclient.module.AtlasModule;
import net.minecraft.client.MinecraftClient;

/** ClickGUI options. Always on. The "Open key" defaults to Right Shift. */
public final class GuiSettingsModule extends AtlasModule {
    public final KeybindSetting openKey = add(new KeybindSetting("Open key", "Key that opens the ClickGUI", Keybind.KEY_RIGHT_SHIFT));
    public final KeybindSetting espKey = add(new KeybindSetting("ESP key", "Switches all ESP on/off together (players, mobs, items, containers)", 72));
    public final NumberSetting animationSpeed = add(new NumberSetting("Animation speed", "1 = default, lower = slower", 1, 0.2, 3, 0.1));
    public final BooleanSetting moduleList = add(new BooleanSetting("Module list", "Show enabled modules on the HUD", true));
    public final NumberSetting hudX = add(new NumberSetting("HUD X", "Left offset of the info column (move it if MiniHUD or other HUD mods overlap)", 4, 0, 1000, 1));
    public final NumberSetting hudY = add(new NumberSetting("HUD Y", "Top offset of the info column", 4, 0, 1000, 1));
    private int boundKey = Keybind.NONE;
    private int boundEspKey = Keybind.NONE;

    public GuiSettingsModule() {
        super("GUI Settings", "ClickGUI keybind, animations and HUD module list", Category.CLIENT);
        setEnabled(true);
    }

    @Override public boolean canToggle() { return false; }

    /** The four ESP modules, switched together by the ESP key and the simple menu's ESP card. */
    public static java.util.List<dev.atlasclient.core.module.Module> espModules() {
        var m = Atlas.get().modules;
        return java.util.List.of(m.get(dev.atlasclient.module.render.PlayerEspModule.class), m.get(dev.atlasclient.module.render.MobEspModule.class),
                m.get(dev.atlasclient.module.render.ItemEspModule.class), m.get(dev.atlasclient.module.render.ContainerEspModule.class));
    }

    /** The features listed on the HUD (helpers such as Block Highlight or Notifications are not). */
    public static boolean isMainFeature(dev.atlasclient.core.module.Module m) {
        return m instanceof dev.atlasclient.module.render.XRayModule || m instanceof dev.atlasclient.module.render.EspModule
                || m instanceof dev.atlasclient.module.camera.FreecamModule || m instanceof dev.atlasclient.module.building.AutoMineModule
                || m instanceof dev.atlasclient.module.world.BaseFinderModule || m instanceof dev.atlasclient.module.world.AncientDebrisFinderModule
                || m instanceof dev.atlasclient.module.movement.AuthorizedFlightModule || m instanceof dev.atlasclient.module.building.AutoBuilderModule
                || m instanceof dev.atlasclient.module.movement.NavigationAssistModule;
    }

    public static boolean anyEspOn() {
        for (var e : espModules()) if (e.isEnabled()) return true;
        return false;
    }

    /** Any ESP on: all off. All off: all on. */
    public static void toggleEsp() {
        boolean on = !anyEspOn();
        for (var e : espModules()) e.setEnabled(on);
    }

    /** Re-binds the global open-GUI and ESP keys whenever the settings change. */
    public void syncKey() {
        Atlas atlas = Atlas.get();
        if (boundEspKey != Keybind.NONE) {
            atlas.keybinds.unbindGlobal(boundEspKey);
        }
        boundEspKey = espKey.key();
        if (boundEspKey != Keybind.NONE) {
            atlas.keybinds.bindGlobal(boundEspKey, () -> {
                if (MinecraftClient.getInstance().currentScreen == null) toggleEsp();
            });
        }
        if (boundKey != Keybind.NONE) {
            atlas.keybinds.unbindGlobal(boundKey);
        }
        boundKey = openKey.key();
        if (boundKey != Keybind.NONE) {
            atlas.keybinds.bindGlobal(boundKey, () -> {
                MinecraftClient mc = MinecraftClient.getInstance();
                if (mc.currentScreen == null) {
                    mc.setScreen(new ClickGuiScreen());
                }
            });
        }
    }
}
