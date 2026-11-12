package dev.atlasclient.render;

import dev.atlasclient.core.render.OverlayCuller;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;

/**
 * Vertex emission for boxes. Filled faces use the same vertex order as Fabric API's own 1.21.11
 * world-render test (quads per face). Lines follow vanilla's line format
 * (position, color, normal = line direction, per-vertex line width).
 */
public final class BoxGeometry {
    private BoxGeometry() { }

    public static void filled(Matrix4f m, VertexConsumer vc, OverlayCuller.Box b, int color) {
        float x0 = (float) b.minX(), y0 = (float) b.minY(), z0 = (float) b.minZ();
        float x1 = (float) b.maxX(), y1 = (float) b.maxY(), z1 = (float) b.maxZ();
        // front (z0)
        vc.vertex(m, x0, y0, z0).color(color);
        vc.vertex(m, x1, y0, z0).color(color);
        vc.vertex(m, x1, y1, z0).color(color);
        vc.vertex(m, x0, y1, z0).color(color);
        // back (z1)
        vc.vertex(m, x1, y0, z1).color(color);
        vc.vertex(m, x0, y0, z1).color(color);
        vc.vertex(m, x0, y1, z1).color(color);
        vc.vertex(m, x1, y1, z1).color(color);
        // left (x0)
        vc.vertex(m, x0, y0, z1).color(color);
        vc.vertex(m, x0, y0, z0).color(color);
        vc.vertex(m, x0, y1, z0).color(color);
        vc.vertex(m, x0, y1, z1).color(color);
        // right (x1)
        vc.vertex(m, x1, y0, z0).color(color);
        vc.vertex(m, x1, y0, z1).color(color);
        vc.vertex(m, x1, y1, z1).color(color);
        vc.vertex(m, x1, y1, z0).color(color);
        // top (y1)
        vc.vertex(m, x0, y1, z0).color(color);
        vc.vertex(m, x1, y1, z0).color(color);
        vc.vertex(m, x1, y1, z1).color(color);
        vc.vertex(m, x0, y1, z1).color(color);
        // bottom (y0)
        vc.vertex(m, x0, y0, z1).color(color);
        vc.vertex(m, x1, y0, z1).color(color);
        vc.vertex(m, x1, y0, z0).color(color);
        vc.vertex(m, x0, y0, z0).color(color);
    }

    public static void outline(MatrixStack.Entry e, VertexConsumer vc, OverlayCuller.Box b, int color, float width) {
        float x0 = (float) b.minX(), y0 = (float) b.minY(), z0 = (float) b.minZ();
        float x1 = (float) b.maxX(), y1 = (float) b.maxY(), z1 = (float) b.maxZ();
        // bottom rectangle
        line(e, vc, x0, y0, z0, x1, y0, z0, color, width);
        line(e, vc, x1, y0, z0, x1, y0, z1, color, width);
        line(e, vc, x1, y0, z1, x0, y0, z1, color, width);
        line(e, vc, x0, y0, z1, x0, y0, z0, color, width);
        // top rectangle
        line(e, vc, x0, y1, z0, x1, y1, z0, color, width);
        line(e, vc, x1, y1, z0, x1, y1, z1, color, width);
        line(e, vc, x1, y1, z1, x0, y1, z1, color, width);
        line(e, vc, x0, y1, z1, x0, y1, z0, color, width);
        // verticals
        line(e, vc, x0, y0, z0, x0, y1, z0, color, width);
        line(e, vc, x1, y0, z0, x1, y1, z0, color, width);
        line(e, vc, x1, y0, z1, x1, y1, z1, color, width);
        line(e, vc, x0, y0, z1, x0, y1, z1, color, width);
    }

    public static void line(MatrixStack.Entry e, VertexConsumer vc, float ax, float ay, float az, float bx, float by, float bz,
                            int color, float width) {
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6f) return;
        dx /= len;
        dy /= len;
        dz /= len;
        vc.vertex(e, ax, ay, az).color(color).normal(e, dx, dy, dz).lineWidth(width);
        vc.vertex(e, bx, by, bz).color(color).normal(e, dx, dy, dz).lineWidth(width);
    }
}
