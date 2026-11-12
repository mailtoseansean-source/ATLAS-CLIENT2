package dev.atlasclient.core.gui;

import java.util.HashMap;
import java.util.Map;

/**
 * Frame-rate independent easing. Each keyed value moves toward its target with exponential decay,
 * so animations look identical at 30 or 240 FPS.
 */
public final class Animator {
    private final Map<Object, Double> values = new HashMap<>();
    private double speed = 14.0; // larger = faster
    private long lastNanos = -1;
    private double dt;

    public void setSpeed(double speed) { this.speed = Math.max(0.1, speed); }

    /** Call once per frame before reading values. */
    public void frame(long nowNanos) {
        if (lastNanos < 0) {
            dt = 0;
        } else {
            dt = Math.min(0.25, (nowNanos - lastNanos) / 1_000_000_000d);
        }
        lastNanos = nowNanos;
    }

    public double dt() { return dt; }

    /** Returns the eased value for the key, moving it toward the target. */
    public double approach(Object key, double target) {
        Double current = values.get(key);
        if (current == null) {
            values.put(key, target);
            return target;
        }
        double alpha = 1 - Math.exp(-speed * dt);
        double next = current + (target - current) * alpha;
        if (Math.abs(target - next) < 1e-3) {
            next = target;
        }
        values.put(key, next);
        return next;
    }

    public void snap(Object key, double value) { values.put(key, value); }

    public void clear() {
        values.clear();
        lastNanos = -1;
    }
}
