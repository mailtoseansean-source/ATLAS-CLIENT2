package dev.atlasclient.core.notify;

public record Notification(long id, Level level, String title, String message, long createdMillis, long durationMillis) {
    public enum Level { INFO, SUCCESS, WARNING, ERROR, DISCOVERY }

    public boolean expired(long nowMillis) { return nowMillis - createdMillis >= durationMillis; }

    /** 0..1 progress through the display duration. */
    public double progress(long nowMillis) {
        if (durationMillis <= 0) {
            return 1;
        }
        return Math.max(0, Math.min(1, (nowMillis - createdMillis) / (double) durationMillis));
    }
}
