package dev.atlasclient.render;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import dev.atlasclient.Atlas;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.util.Identifier;

/**
 * Render layers used by the overlay renderer. Depth-tested layers are vanilla's own
 * ({@code RenderLayers.lines()} / {@code debugFilledBox()}). The optional see-through line layer is
 * vanilla's lines pipeline with depth testing disabled; if it cannot be created (for example a
 * rendering mod changed the pipeline registry) the renderer falls back to normal lines.
 */
public final class AtlasRenderLayers {
    private static RenderLayer seeThroughLines;
    private static RenderLayer seeThroughFilled;
    private static boolean initialized;
    private static String failure = "";

    private AtlasRenderLayers() { }

    /** Called once during client initialization. */
    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        try {
            RenderPipeline pipeline = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.RENDERTYPE_LINES_SNIPPET)
                    .withLocation(Identifier.of(Atlas.MOD_ID, "pipeline/lines_see_through"))
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .build());
            seeThroughLines = RenderLayer.of(Atlas.MOD_ID + "_lines_see_through", RenderSetup.builder(pipeline).build());
        } catch (Throwable t) {
            failure = t.toString();
            seeThroughLines = null;
            Atlas.LOG.warn("See-through line layer unavailable, using depth-tested lines: {}", failure);
        }
        try {
            // Same vertex layout and draw mode as vanilla's debug filled box, but drawn over terrain.
            RenderPipeline base = RenderPipelines.DEBUG_FILLED_BOX;
            RenderPipeline pipeline = RenderPipelines.register(RenderPipeline.builder(RenderPipelines.POSITION_COLOR_SNIPPET)
                    .withLocation(Identifier.of(Atlas.MOD_ID, "pipeline/filled_see_through"))
                    .withVertexFormat(base.getVertexFormat(), base.getVertexFormatMode())
                    .withCull(false)
                    .withBlend(BlendFunction.TRANSLUCENT)
                    .withDepthWrite(false)
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .build());
            seeThroughFilled = RenderLayer.of(Atlas.MOD_ID + "_filled_see_through", RenderSetup.builder(pipeline).build());
        } catch (Throwable t) {
            seeThroughFilled = null;
            failure = failure.isEmpty() ? t.toString() : failure + "; " + t;
            Atlas.LOG.warn("See-through fill layer unavailable, using depth-tested fill: {}", t.toString());
        }
    }

    public static RenderLayer filled(boolean seeThrough) {
        return seeThrough && seeThroughFilled != null ? seeThroughFilled : RenderLayers.debugFilledBox();
    }

    public static boolean seeThroughFillAvailable() { return seeThroughFilled != null; }

    public static RenderLayer lines(boolean seeThrough) {
        return seeThrough && seeThroughLines != null ? seeThroughLines : RenderLayers.lines();
    }

    public static RenderLayer filled() { return RenderLayers.debugFilledBox(); }

    public static boolean seeThroughAvailable() { return seeThroughLines != null; }

    public static String failure() { return failure; }
}
