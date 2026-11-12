package dev.atlasclient.module;

import dev.atlasclient.Atlas;
import dev.atlasclient.core.module.Category;
import dev.atlasclient.core.module.Module;
import dev.atlasclient.core.render.OverlayCuller;
import net.minecraft.client.gui.DrawContext;

import java.util.List;

/** Module with optional world-overlay and HUD contributions. All hooks run on the client thread. */
public abstract class AtlasModule extends Module {
    protected AtlasModule(String name, String description, Category category) {
        super(name, description, category);
    }

    protected AtlasModule(String name, String description, Category category, int defaultKey) {
        super(name, description, category, defaultKey);
    }

    /** Frame information passed to overlay providers. */
    public record Frame(double camX, double camY, double camZ, float tickDelta, String dimension) { }

    /** Adds world-space boxes for this frame (only called while enabled). Drawn with Block Highlight's style. */
    public void collectOverlay(Frame frame, List<OverlayCuller.Box> out) { }

    /**
     * How a module's own batch is drawn (independent of Block Highlight).
     *
     * @param fillAlpha  0 = no fill, 1 = the box colour's own alpha
     * @param seeThrough draw through walls
     * @param maxCount   nearest boxes kept per frame
     * @param labels     draw each box's label above it
     */
    public record Style(double fillAlpha, boolean outline, float lineWidth, boolean seeThrough, double maxDistance, int maxCount,
                        boolean labels, int labelColor, float labelScale, boolean viewCull, boolean tracers) {
        public Style(double fillAlpha, boolean outline, float lineWidth, boolean seeThrough, double maxDistance, int maxCount,
                     boolean labels, int labelColor, float labelScale, boolean viewCull) {
            this(fillAlpha, outline, lineWidth, seeThrough, maxDistance, maxCount, labels, labelColor, labelScale, viewCull, false);
        }
    }

    public record Batch(Style style, List<OverlayCuller.Box> boxes) { }

    /** Adds styled batches for this frame (only called while enabled). */
    public void collectBatches(Frame frame, List<Batch> out) { }

    /** Draws HUD elements (only called while enabled). Returns the height used in the info column. */
    public int renderHud(DrawContext ctx, int x, int y, float tickDelta) { return 0; }

    protected static Atlas atlas() { return Atlas.get(); }

    protected static void info(String title, String message) { Atlas.get().notifications.info(title, message); }

    protected static void warn(String title, String message) { Atlas.get().notifications.warn(title, message); }

    protected static void success(String title, String message) { Atlas.get().notifications.success(title, message); }

    protected static void error(String title, String message) { Atlas.get().notifications.error(title, message); }

    protected static void discovery(String title, String message) { Atlas.get().notifications.discovery(title, message); }
}
