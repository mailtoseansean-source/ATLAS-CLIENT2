package dev.atlasclient.core.notify;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Thread-safe notification queue with bounded history. Background threads may push; the HUD reads
 * {@link #active()} each frame.
 */
public final class NotificationManager {
    private final Deque<Notification> active = new ArrayDeque<>();
    private final Deque<Notification> history = new ArrayDeque<>();
    private final AtomicLong ids = new AtomicLong();
    private final LongSupplier clock;
    private volatile long durationMillis = 4000;
    private volatile int maxVisible = 5;
    private volatile int historyLimit = 200;
    private volatile Set<Notification.Level> muted = EnumSet.noneOf(Notification.Level.class);

    public NotificationManager() { this(System::currentTimeMillis); }

    public NotificationManager(LongSupplier clock) { this.clock = clock; }

    public void setDurationMillis(long millis) { this.durationMillis = Math.max(500, Math.min(60_000, millis)); }
    public long durationMillis() { return durationMillis; }
    public void setMaxVisible(int max) { this.maxVisible = Math.max(1, Math.min(20, max)); }
    public void setHistoryLimit(int limit) { this.historyLimit = Math.max(10, Math.min(10_000, limit)); }

    public void setMuted(Set<Notification.Level> levels) {
        this.muted = levels.isEmpty() ? EnumSet.noneOf(Notification.Level.class) : EnumSet.copyOf(levels);
    }

    public Notification push(Notification.Level level, String title, String message) {
        Notification n = new Notification(ids.incrementAndGet(), level, title, message, clock.getAsLong(), durationMillis);
        synchronized (this) {
            history.addLast(n);
            while (history.size() > historyLimit) {
                history.removeFirst();
            }
            if (!muted.contains(level)) {
                active.addLast(n);
                while (active.size() > maxVisible) {
                    active.removeFirst();
                }
            }
        }
        return n;
    }

    public void info(String title, String message) { push(Notification.Level.INFO, title, message); }
    public void success(String title, String message) { push(Notification.Level.SUCCESS, title, message); }
    public void warn(String title, String message) { push(Notification.Level.WARNING, title, message); }
    public void error(String title, String message) { push(Notification.Level.ERROR, title, message); }
    public void discovery(String title, String message) { push(Notification.Level.DISCOVERY, title, message); }

    /** Currently visible notifications, oldest first; prunes expired ones. */
    public synchronized List<Notification> active() {
        long now = clock.getAsLong();
        active.removeIf(n -> n.expired(now));
        return new ArrayList<>(active);
    }

    /** Full history, newest first. */
    public synchronized List<Notification> history() {
        List<Notification> list = new ArrayList<>(history);
        java.util.Collections.reverse(list);
        return list;
    }

    public synchronized void clearActive() { active.clear(); }

    public synchronized void clearHistory() { history.clear(); }

    public long nowMillis() { return clock.getAsLong(); }
}
