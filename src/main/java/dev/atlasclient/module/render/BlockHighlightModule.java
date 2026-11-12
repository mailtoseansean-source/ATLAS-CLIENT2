package dev.atlasclient.module.render;

import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.setting.Settings.BooleanSetting;
import dev.atlasclient.core.setting.Settings.NumberSetting;
import dev.atlasclient.module.AtlasModule;

/**
 * Shared style for every 3D overlay (Base Finder, Debris Finder, schematic preview, waypoints).
 * When disabled, overlays are not drawn at all. Always present so styles can be configured.
 */
public final class BlockHighlightModule extends AtlasModule {
    public final BooleanSetting outlines = add(new BooleanSetting("Outlines", "Draw 3D box outlines", true));
    public final BooleanSetting fill = add(new BooleanSetting("Fill", "Draw translucent box fill", true));
    public final NumberSetting fillOpacity = add(new NumberSetting("Fill opacity", "Multiplier for fill alpha", 0.6, 0, 1, 0.05));
    public final NumberSetting lineWidth = add(new NumberSetting("Line width", "Outline thickness (per-vertex width in 1.21.11)", 2, 1, 8, 0.5));
    public final BooleanSetting seeThrough = add(new BooleanSetting("See through walls", "Draw outlines on top of terrain", true));
    public final NumberSetting renderDistance = add(new NumberSetting("Render distance", "Global overlay distance limit (blocks)", 256, 16, 4096, 16));
    public final NumberSetting maxBoxes = add(new NumberSetting("Max boxes", "Per-frame cap across all overlays (nearest first)", 1500, 64, 10000, 64));
    public final NumberSetting labelScale = add(new NumberSetting("Label scale", "Size of world labels", 1, 0.5, 3, 0.1));

    public BlockHighlightModule() {
        super("Block Highlighting", "Draws 3D outlines and fills for all detection overlays", Category.RENDER);
        setEnabledDefault();
    }

    private void setEnabledDefault() {
        // Enabled by default (overridden by the saved profile).
        setEnabled(true);
    }
}
