package dev.atlasclient.core.util;

import dev.atlasclient.core.event.CoreEvents;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Central place that clears per-world state. Every cache, background worker and builder session
 * registers here, and the Fabric layer calls {@link #reset} on disconnect and dimension change.
 */
public final class SessionLifecycle {
    /** Something that holds per-world state. */
    public interface Resettable {
        String resetName();
        void reset(CoreEvents.SessionReset.Reason reason);
    }

    private final List<Resettable> parts = new CopyOnWriteArrayList<>();
    private Consumer<String> errorLog = s -> { };
    private volatile String worldKey = "";
    private long resetCount;

    public void setErrorLog(Consumer<String> log) { this.errorLog = log; }

    public void register(Resettable part) { parts.add(part); }

    public int registeredCount() { return parts.size(); }

    public String worldKey() { return worldKey; }

    public long resetCount() { return resetCount; }

    /** Resets every part; failures are logged and do not stop other parts. Returns failures. */
    public List<String> reset(CoreEvents.SessionReset.Reason reason, String newWorldKey) {
        List<String> failures = new ArrayList<>();
        for (Resettable part : parts) {
            try {
                part.reset(reason);
            } catch (RuntimeException e) {
                String msg = part.resetName() + ": " + e;
                failures.add(msg);
                errorLog.accept("Reset failed for " + msg);
            }
        }
        this.worldKey = newWorldKey == null ? "" : newWorldKey;
        resetCount++;
        return failures;
    }
}
