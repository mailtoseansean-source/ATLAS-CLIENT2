package dev.atlasclient.module.camera;

import dev.atlasclient.camera.FreecamController;
import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.setting.Keybind;
import dev.atlasclient.core.setting.Settings.KeybindSetting;
import dev.atlasclient.core.setting.Settings.NumberSetting;
import dev.atlasclient.module.AtlasModule;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;

import java.util.Locale;

/** How fast and how smoothly the free camera moves. Always available; used while Freecam is on. */
public final class CameraSpeedModule extends AtlasModule {
    final NumberSetting speed = add(new NumberSetting("Speed", "Camera speed in blocks per second", 12, 1, 100, 0.5));
    final NumberSetting boost = add(new NumberSetting("Boost", "Speed multiplier while holding the sprint key", 3, 1, 10, 0.5));
    final NumberSetting vertical = add(new NumberSetting("Vertical speed", "Multiplier for jump (up) and sneak (down)", 1, 0.2, 3, 0.1));
    final NumberSetting smoothing = add(new NumberSetting("Smoothing", "0 = instant stop and start; higher = smoother acceleration and glide", 0.6, 0, 0.95, 0.05));
    final KeybindSetting faster = add(new KeybindSetting("Faster key", "Raise camera speed by 25% while Freecam is on", Keybind.NONE));
    final KeybindSetting slower = add(new KeybindSetting("Slower key", "Lower camera speed by 20% while Freecam is on", Keybind.NONE));
    private boolean fasterDown, slowerDown;

    public CameraSpeedModule() {
        super("Camera Speed", "Free camera speed, boost, vertical speed and smoothing", Category.CAMERA);
        setEnabled(true);
    }

    @Override public boolean canToggle() { return false; }

    public FreecamController.Tuning tuning(CameraSettingsModule s) {
        return new FreecamController.Tuning(speed.get(), boost.get(), vertical.get(), smoothing.get(), s.stayLoaded.get(), s.maxDistance.get());
    }

    @Override
    public void onTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (FreecamController.active() && mc.currentScreen == null) {
            boolean f = faster.isBound() && InputUtil.isKeyPressed(mc.getWindow(), faster.key());
            boolean s = slower.isBound() && InputUtil.isKeyPressed(mc.getWindow(), slower.key());
            if (f && !fasterDown) speed.set(Math.min(100, speed.get() * 1.25));
            if (s && !slowerDown) speed.set(Math.max(1, speed.get() * 0.8));
            fasterDown = f;
            slowerDown = s;
        }
        setStatus(String.format(Locale.ROOT, "%.1f blocks/s (x%.1f boost)", speed.get(), boost.get()));
    }
}
