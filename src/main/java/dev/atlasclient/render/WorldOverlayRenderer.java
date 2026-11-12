package dev.atlasclient.render;

import dev.atlasclient.Atlas;
import dev.atlasclient.core.module.Module;
import dev.atlasclient.core.render.OverlayCuller;
import dev.atlasclient.module.AtlasModule;
import dev.atlasclient.module.render.BlockHighlightModule;
import dev.atlasclient.module.render.DetectionLabelsModule;
import dev.atlasclient.util.Env;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.state.CameraRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Draws all module overlays once per frame from {@code WorldRenderEvents.BEFORE_TRANSLUCENT}.
 * Boxes from every enabled module are collected, culled by distance/view cone and capped, then
 * emitted into vanilla buffers. Labels are billboarded text.
 */
public final class WorldOverlayRenderer {
    private final List<OverlayCuller.Box> scratch = new ArrayList<>(1024);

    private final List<AtlasModule.Batch> batches = new ArrayList<>();

    public void render(WorldRenderContext ctx) {
        if (!Env.inWorld() || !Atlas.ready()) {
            return;
        }
        Atlas atlas = Atlas.get();
        long start = System.nanoTime();
        CameraRenderState camera = ctx.worldState().cameraRenderState;
        Vec3d cam = camera.pos;
        float tickDelta = MinecraftClient.getInstance().getRenderTickCounter().getTickProgress(true);
        AtlasModule.Frame frame = new AtlasModule.Frame(cam.x, cam.y, cam.z, tickDelta, Env.dimension());
        Vec3d look = Vec3d.fromPolar(MinecraftClient.getInstance().gameRenderer.getCamera().getPitch(),
                MinecraftClient.getInstance().gameRenderer.getCamera().getYaw());
        int drawn = 0;

        // 1. Classic overlays (Base Finder, debris, chunks, waypoints, preview): Block Highlight's style.
        BlockHighlightModule style = atlas.modules.get(BlockHighlightModule.class);
        scratch.clear();
        if (style.isEnabled()) {
            for (Module m : atlas.modules.all()) {
                if (m.isEnabled() && m instanceof AtlasModule am) {
                    try {
                        am.collectOverlay(frame, scratch);
                    } catch (RuntimeException e) {
                        Atlas.LOG.error("Overlay of {} failed", m.name(), e);
                    }
                }
            }
        }
        if (!scratch.isEmpty()) {
            List<OverlayCuller.Box> boxes = OverlayCuller.cull(scratch, cam.x, cam.y, cam.z, style.renderDistance.get(),
                    style.maxBoxes.intValue(), look.x, look.y, look.z, Math.cos(Math.toRadians(80)));
            AtlasModule.Style s = new AtlasModule.Style(style.fill.get() ? style.fillOpacity.get() * 0.6 : 0, style.outlines.get(),
                    style.lineWidth.floatValue(), style.seeThrough.get(), style.renderDistance.get(), style.maxBoxes.intValue(),
                    false, 0, 1f, true);
            draw(ctx, cam, boxes, s, true);
            drawn += boxes.size();
            DetectionLabelsModule labels = atlas.modules.get(DetectionLabelsModule.class);
            if (labels.isEnabled() && labels.maxLabels.intValue() > 0) {
                drawLabels(ctx, camera, boxes, labels.maxLabels.intValue(), labels.maxDistance.get(), labels.textColor.argb(),
                        labels.background.get(), style.labelScale.floatValue());
            }
        }

        // 2. Modules with their own style (X-Ray, ESP, freecam marker), independent of Block Highlight.
        batches.clear();
        for (Module m : atlas.modules.all()) {
            if (m.isEnabled() && m instanceof AtlasModule am) {
                try {
                    am.collectBatches(frame, batches);
                } catch (RuntimeException e) {
                    Atlas.LOG.error("Overlay batch of {} failed", m.name(), e);
                }
            }
        }
        for (AtlasModule.Batch b : batches) {
            if (b.boxes().isEmpty()) continue;
            AtlasModule.Style st = b.style();
            List<OverlayCuller.Box> boxes = OverlayCuller.cull(b.boxes(), cam.x, cam.y, cam.z, st.maxDistance(), st.maxCount(),
                    st.viewCull() ? look.x : 0, st.viewCull() ? look.y : 0, st.viewCull() ? look.z : 0, Math.cos(Math.toRadians(80)));
            draw(ctx, cam, boxes, st, false);
            if (st.tracers()) tracers(ctx, cam, look, boxes, st);
            drawn += boxes.size();
            if (st.labels()) {
                drawLabels(ctx, camera, boxes, Math.min(64, st.maxCount()), st.maxDistance(), st.labelColor(), true, st.labelScale());
            }
        }
        atlas.perf.record("render/overlay", System.nanoTime() - start);
        atlas.perf.gauge("render/boxes", drawn);
    }

    /**
     * Emits fill then outlines for one style. {@code legacyLayers}: boxes with layer 0 are outline-only
     * (Base Finder/Chunk Outlines convention).
     */
    private void draw(WorldRenderContext ctx, Vec3d cam, List<OverlayCuller.Box> boxes, AtlasModule.Style st, boolean legacyLayers) {
        if (boxes.isEmpty()) return;
        MatrixStack matrices = ctx.matrices();
        VertexConsumerProvider consumers = ctx.consumers();
        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        MatrixStack.Entry entry = matrices.peek();
        if (st.fillAlpha() > 0) {
            var fillLayer = AtlasRenderLayers.filled(st.seeThrough());
            VertexConsumer fill = consumers.getBuffer(fillLayer);
            for (OverlayCuller.Box b : boxes) {
                if (legacyLayers && b.layer() == 0) continue;
                int color = OverlayCuller.withAlpha(b.argb(), st.fillAlpha());
                if ((color >>> 24) == 0) continue;
                BoxGeometry.filled(entry.getPositionMatrix(), fill, b, color);
            }
            if (st.seeThrough() && AtlasRenderLayers.seeThroughFillAvailable()
                    && consumers instanceof VertexConsumerProvider.Immediate immediate) {
                immediate.draw(fillLayer);
            }
        }
        if (st.outline()) {
            var lineLayer = AtlasRenderLayers.lines(st.seeThrough());
            VertexConsumer lines = consumers.getBuffer(lineLayer);
            for (OverlayCuller.Box b : boxes) {
                BoxGeometry.outline(entry, lines, b, b.argb() | 0xFF000000, st.lineWidth());
            }
            if (st.seeThrough() && AtlasRenderLayers.seeThroughAvailable()
                    && consumers instanceof VertexConsumerProvider.Immediate immediate) {
                immediate.draw(lineLayer);
            }
        }
        matrices.pop();
    }

    /** Lines from just in front of the camera to each box: shows which way to look. */
    private void tracers(WorldRenderContext ctx, Vec3d cam, Vec3d look, List<OverlayCuller.Box> boxes, AtlasModule.Style st) {
        if (boxes.isEmpty()) return;
        MatrixStack matrices = ctx.matrices();
        VertexConsumerProvider consumers = ctx.consumers();
        matrices.push();
        matrices.translate(-cam.x, -cam.y, -cam.z);
        MatrixStack.Entry entry = matrices.peek();
        var lineLayer = AtlasRenderLayers.lines(true);
        VertexConsumer lines = consumers.getBuffer(lineLayer);
        float sx = (float) (cam.x + look.x * 0.6), sy = (float) (cam.y + look.y * 0.6), sz = (float) (cam.z + look.z * 0.6);
        for (OverlayCuller.Box b : boxes) {
            BoxGeometry.line(entry, lines, sx, sy, sz, (float) b.centerX(), (float) b.centerY(), (float) b.centerZ(),
                    (b.argb() & 0xFFFFFF) | 0xC0000000, Math.max(1f, st.lineWidth() * 0.75f));
        }
        if (AtlasRenderLayers.seeThroughAvailable() && consumers instanceof VertexConsumerProvider.Immediate immediate) {
            immediate.draw(lineLayer);
        }
        matrices.pop();
    }

    private void drawLabels(WorldRenderContext ctx, CameraRenderState camera, List<OverlayCuller.Box> boxes,
                            int maxLabels, double maxDistance, int textColor, boolean background, float scale) {
        Vec3d cam = camera.pos;
        List<OverlayCuller.Box> labelled = new ArrayList<>();
        double max2 = maxDistance * maxDistance;
        for (OverlayCuller.Box b : boxes) {
            if (b.label() == null) continue;
            double dx = b.centerX() - cam.x, dy = b.maxY() - cam.y, dz = b.centerZ() - cam.z;
            if (dx * dx + dy * dy + dz * dz <= max2) labelled.add(b);
        }
        labelled.sort(Comparator.comparingDouble(b -> {
            double dx = b.centerX() - cam.x, dz = b.centerZ() - cam.z;
            return dx * dx + dz * dz;
        }));
        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        VertexConsumerProvider consumers = ctx.consumers();
        MatrixStack matrices = ctx.matrices();
        Quaternionf rotation = camera.orientation;
        int bg = background ? 0x80000000 : 0;
        int n = 0;
        for (OverlayCuller.Box b : labelled) {
            if (n++ >= maxLabels) break;
            double x = b.centerX() - cam.x, y = b.maxY() + 0.6 - cam.y, z = b.centerZ() - cam.z;
            double dist = Math.sqrt(x * x + y * y + z * z);
            // Keep labels readable at distance: scale grows with distance (clamped).
            float s = (float) (0.025 * scale * Math.max(1.0, Math.min(dist / 10.0, 12.0)));
            matrices.push();
            matrices.translate(x, y, z);
            matrices.multiply(rotation);
            matrices.scale(s, -s, s);
            Matrix4f m = matrices.peek().getPositionMatrix();
            String text = b.label();
            float w = tr.getWidth(text) / 2f;
            tr.draw(text, -w, 0, textColor, false, m, consumers, TextRenderer.TextLayerType.SEE_THROUGH, bg,
                    LightmapTextureManager.MAX_LIGHT_COORDINATE);
            matrices.pop();
        }
    }
}
