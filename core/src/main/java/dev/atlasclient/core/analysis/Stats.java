package dev.atlasclient.core.analysis;

/** Statistical helpers for growth-stage evidence. */
public final class Stats {
    private Stats() { }

    /**
     * One-sided z-score of observing {@code k} successes in {@code n} trials when the natural
     * success probability is {@code p0}. Positive values mean "more than world generation
     * typically produces".
     */
    public static double binomialZ(int k, int n, double p0) {
        if (n <= 0 || p0 <= 0 || p0 >= 1) {
            return 0;
        }
        double mean = n * p0;
        double sd = Math.sqrt(n * p0 * (1 - p0));
        return (k - mean) / sd;
    }

    /** Maps a z-score to 0..1: nothing below 1.5 sigma, saturating at 4.5 sigma. */
    public static double zToStrength(double z) {
        return Math.max(0, Math.min(1, (z - 1.5) / 3.0));
    }

    /** Diminishing-returns transform for counts above a baseline. */
    public static double excessLog(int count, int baseline) {
        int excess = Math.max(0, count - baseline);
        return Math.log(1 + excess) / Math.log(2);
    }
}
