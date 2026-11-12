package dev.atlasclient.core.camera;

/**
 * Free camera position integrator: WASD relative to the camera's yaw, vertical keys, a boost
 * multiplier and exponential smoothing so the camera accelerates and glides to a stop instead of
 * snapping. Units are blocks and ticks; Minecraft yaw convention (0 = +Z/south, 90 = -X/west).
 */
public final class CameraMotion {
    private double x, y, z, lastX, lastY, lastZ;
    private double vx, vy, vz;

    /** Input for one tick. {@code speed} in blocks per second. {@code smoothing} 0 = instant, 0.95 = very floaty. */
    public record Input(boolean forward, boolean back, boolean left, boolean right, boolean up, boolean down, boolean boost,
                        double yawDegrees, double speed, double boostMultiplier, double verticalMultiplier, double smoothing) { }

    /** Decides whether the camera may enter a position (for example: only loaded chunks, within a distance). */
    public interface Bounds {
        boolean allowed(double x, double y, double z);
    }

    public void reset(double x, double y, double z) {
        this.x = this.lastX = x;
        this.y = this.lastY = y;
        this.z = this.lastZ = z;
        vx = vy = vz = 0;
    }

    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }
    public double lastX() { return lastX; }
    public double lastY() { return lastY; }
    public double lastZ() { return lastZ; }
    public double speedBlocksPerTick() { return Math.sqrt(vx * vx + vy * vy + vz * vz); }

    /** Interpolated position for rendering between ticks. */
    public double[] lerp(double t) {
        return new double[]{lastX + (x - lastX) * t, lastY + (y - lastY) * t, lastZ + (z - lastZ) * t};
    }

    public void tick(Input in, Bounds bounds) {
        lastX = x;
        lastY = y;
        lastZ = z;
        double f = (in.forward() ? 1 : 0) - (in.back() ? 1 : 0);
        double s = (in.right() ? 1 : 0) - (in.left() ? 1 : 0);
        double u = (in.up() ? 1 : 0) - (in.down() ? 1 : 0);
        double yaw = Math.toRadians(in.yawDegrees());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);   // forward
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);  // right
        double tx = fx * f + rx * s, tz = fz * f + rz * s;
        double len = Math.sqrt(tx * tx + tz * tz);
        if (len > 1e-9) {
            tx /= len;
            tz /= len;
        }
        double perTick = Math.max(0, in.speed()) / 20.0 * (in.boost() ? Math.max(1, in.boostMultiplier()) : 1);
        double targetX = tx * perTick, targetZ = tz * perTick, targetY = u * perTick * Math.max(0, in.verticalMultiplier());
        double k = 1 - Math.max(0, Math.min(0.98, in.smoothing()));
        vx += (targetX - vx) * k;
        vy += (targetY - vy) * k;
        vz += (targetZ - vz) * k;
        if (Math.abs(vx) < 1e-5) vx = 0;
        if (Math.abs(vy) < 1e-5) vy = 0;
        if (Math.abs(vz) < 1e-5) vz = 0;
        double nx = x + vx, ny = y + vy, nz = z + vz;
        if (bounds == null || bounds.allowed(nx, ny, nz)) {
            x = nx;
            y = ny;
            z = nz;
        } else {
            // Try each axis on its own so the camera slides along the boundary.
            if (bounds.allowed(x + vx, y, z)) x += vx; else vx = 0;
            if (bounds.allowed(x, y + vy, z)) y += vy; else vy = 0;
            if (bounds.allowed(x, y, z + vz)) z += vz; else vz = 0;
        }
    }
}
