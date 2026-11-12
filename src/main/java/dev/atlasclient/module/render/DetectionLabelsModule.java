package dev.atlasclient.module.render;

import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.setting.Settings.BooleanSetting;
import dev.atlasclient.core.setting.Settings.ColorSetting;
import dev.atlasclient.core.setting.Settings.NumberSetting;
import dev.atlasclient.module.AtlasModule;

/** Controls the floating text labels of all detection overlays. Disable to hide every label. */
public final class DetectionLabelsModule extends AtlasModule {
    public final NumberSetting maxLabels = add(new NumberSetting("Max labels", "Labels drawn per frame (nearest first)", 24, 0, 200, 1));
    public final NumberSetting maxDistance = add(new NumberSetting("Label distance", "Hide labels beyond this distance", 160, 8, 2048, 8));
    public final BooleanSetting background = add(new BooleanSetting("Background", "Dark background behind labels", true));
    public final ColorSetting textColor = add(new ColorSetting("Text color", "Label text color", 0xFFFFFFFF));

    public DetectionLabelsModule() {
        super("Detection Labels", "Floating labels with score, confidence and distance", Category.RENDER);
        setEnabled(true);
    }
}
