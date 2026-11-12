package dev.atlasclient.module.client;

import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.setting.Settings.ColorSetting;
import dev.atlasclient.core.setting.Settings.NumberSetting;
import dev.atlasclient.gui.UiKit;
import dev.atlasclient.module.AtlasModule;

/** ClickGUI colors. Always on. */
public final class ThemeModule extends AtlasModule {
    final ColorSetting accent = add(new ColorSetting("Accent", "Highlight color", 0xFF8B5CF6));
    final ColorSetting panel = add(new ColorSetting("Panel", "Main panel color", 0xF0181820));
    final ColorSetting card = add(new ColorSetting("Card", "Module card color", 0xFF22222C));
    final ColorSetting text = add(new ColorSetting("Text", "Primary text color", 0xFFECECF4));
    final NumberSetting opacity = add(new NumberSetting("Opacity", "Panel opacity multiplier", 1, 0.4, 1, 0.05));

    public ThemeModule() {
        super("Theme", "Colors of the ClickGUI", Category.CLIENT);
        setEnabled(true);
    }

    @Override public boolean canToggle() { return false; }

    public UiKit.Palette palette() {
        double o = opacity.get();
        int c = card.argb();
        int hover = UiKit.lerpColor(c, 0xFFFFFFFF, 0.06);
        return new UiKit.Palette(UiKit.withAlpha(0xFF101014, 0.75 * o), UiKit.withAlpha(panel.argb(), o), c, hover,
                accent.argb(), text.argb(), UiKit.lerpColor(text.argb(), 0xFF000000, 0.35), UiKit.lerpColor(c, 0xFFFFFFFF, 0.12));
    }
}
