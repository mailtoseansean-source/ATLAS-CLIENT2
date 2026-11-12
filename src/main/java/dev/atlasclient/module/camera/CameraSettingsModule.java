package dev.atlasclient.module.camera;

import dev.atlasclient.camera.FreecamController;
import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.setting.Settings.BooleanSetting;
import dev.atlasclient.core.setting.Settings.ColorSetting;
import dev.atlasclient.core.setting.Settings.NumberSetting;
import dev.atlasclient.module.AtlasModule;

/** Free camera behaviour and indicators. Always available; used while Freecam is on. */
public final class CameraSettingsModule extends AtlasModule {
    final BooleanSetting playerMarker = add(new BooleanSetting("Player marker", "Box around your real position while the camera is detached", true));
    final ColorSetting markerColor = add(new ColorSetting("Marker colour", "Colour of the player marker", 0xFF40C4FF));
    final BooleanSetting hudInfo = add(new BooleanSetting("HUD info", "Camera position and distance from your player", true));
    final BooleanSetting stayLoaded = add(new BooleanSetting("Stay in loaded chunks", "Keep the camera where the world around it is loaded and drawn (nothing else can be shown)", true));
    final NumberSetting maxDistance = add(new NumberSetting("Max distance", "0 = Auto: as far as the world is loaded around you. Otherwise a fixed limit in blocks", 0, 0, 512, 8));
    final BooleanSetting exitOnDamage = add(new BooleanSetting("Exit on damage", "Return to your player when it takes damage", true));

    public CameraSettingsModule() {
        super("Camera Settings", "Free camera indicator, limits and safety", Category.CAMERA);
        setEnabled(true);
    }

    @Override public boolean canToggle() { return false; }

    @Override public void onTick() {
        setStatus(FreecamController.active() ? "Camera detached" : "Freecam is off");
    }
}
