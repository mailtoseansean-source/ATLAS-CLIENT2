package dev.atlasclient.core.debris;

/**
 * One ancient debris block the client has actually received.
 *
 * <p>{@link Exposure#EXPOSED} means at least one neighbour is air, fluid or another non-occluding
 * block. Anti-xray plugins must send real data for visible blocks, so exposed debris is reliable
 * even on servers that obfuscate ores. {@link Exposure#ENCLOSED} debris is fully surrounded: on a
 * server with fake-ore anti-xray it may not exist.</p>
 */
public record DebrisRecord(String dimension, int x, int y, int z, Exposure exposure, Source source,
                           long firstSeenMillis, long lastSeenMillis) {
    public enum Exposure { EXPOSED, ENCLOSED }

    public enum Source { CLIENT_CHUNK, INTEGRATED_SERVER, LOCAL_SAVE_FILE }

    public double distanceTo(double px, double py, double pz) {
        double dx = x + 0.5 - px, dy = y + 0.5 - py, dz = z + 0.5 - pz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public DebrisRecord seenAgain(long now, Exposure newExposure) {
        return new DebrisRecord(dimension, x, y, z, newExposure, source, firstSeenMillis, now);
    }
}
