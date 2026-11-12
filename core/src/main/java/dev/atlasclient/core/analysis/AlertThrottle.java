package dev.atlasclient.core.analysis;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Keeps Base Finder notifications useful: a site is announced once per confidence level it reaches,
 * finds close to one just announced are grouped into it, each alert kind has a cooldown, and there
 * is a global limit per time window. Suppressed finds are counted and mentioned in the next alert
 * ("+3 more nearby"). Client thread only.
 */
public final class AlertThrottle {
    public enum Decision { NOTIFY, GROUPED, SKIP }

    private record Sent(String kind, double x, double z, long at) { }

    private final Map<String, Confidence> announced = new HashMap<>();
    private final Map<String, Long> lastByKind = new HashMap<>();
    private final Deque<Sent> recent = new ArrayDeque<>();
    private long cooldownMillis = 20_000;
    private double groupRadius = 96;
    private long groupWindowMillis = 300_000;
    private int maxPerWindow = 3;
    private long windowMillis = 10_000;
    private int grouped;

    public void configure(long cooldownMillis, double groupRadius) {
        this.cooldownMillis = Math.max(0, cooldownMillis);
        this.groupRadius = Math.max(0, groupRadius);
    }

    /**
     * @param siteKey stable key of the site
     * @return NOTIFY (show it), GROUPED (counted into the next one) or SKIP (already announced at this level)
     */
    public Decision offer(String kind, String siteKey, double x, double z, Confidence c, long now) {
        Confidence before = announced.get(siteKey);
        if (before != null && !c.atLeast(next(before))) return Decision.SKIP;
        announced.put(siteKey, c);
        prune(now);
        for (Sent s : recent) {
            double dx = s.x() - x, dz = s.z() - z;
            if (now - s.at() <= groupWindowMillis && dx * dx + dz * dz <= groupRadius * groupRadius && before == null) {
                grouped++;
                return Decision.GROUPED;
            }
        }
        Long last = lastByKind.get(kind);
        int inWindow = 0;
        for (Sent s : recent) if (now - s.at() <= windowMillis) inWindow++;
        if ((last != null && now - last < cooldownMillis) || inWindow >= maxPerWindow) {
            grouped++;
            return Decision.GROUPED;
        }
        lastByKind.put(kind, now);
        recent.addLast(new Sent(kind, x, z, now));
        return Decision.NOTIFY;
    }

    /** Finds held back since the last notification (and resets the count). */
    public int takeGrouped() {
        int g = grouped;
        grouped = 0;
        return g;
    }

    public void reset() {
        announced.clear();
        lastByKind.clear();
        recent.clear();
        grouped = 0;
    }

    private void prune(long now) {
        for (Iterator<Sent> it = recent.iterator(); it.hasNext(); ) {
            if (now - it.next().at() > groupWindowMillis) it.remove();
        }
        if (announced.size() > 4096) announced.clear();
    }

    private static Confidence next(Confidence c) {
        Confidence[] all = Confidence.values();
        return all[Math.min(all.length - 1, c.ordinal() + 1)];
    }
}
