package dev.atlasclient.core.perf;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lock-light timing and counter registry for the Diagnostics module. Timings keep an exponential
 * moving average and the maximum since the last reset.
 */
public final class PerfMonitor {
    private final Map<String, Timing> timings = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, Long> gauges = new ConcurrentHashMap<>();

    public static final class Timing {
        private volatile double avgNanos;
        private volatile long maxNanos;
        private final AtomicLong samples = new AtomicLong();

        synchronized void add(long nanos) {
            long n = samples.incrementAndGet();
            avgNanos = n == 1 ? nanos : avgNanos * 0.95 + nanos * 0.05;
            if (nanos > maxNanos) {
                maxNanos = nanos;
            }
        }

        public double avgMillis() { return avgNanos / 1_000_000d; }
        public double maxMillis() { return maxNanos / 1_000_000d; }
        public long samples() { return samples.get(); }
        synchronized void resetMax() { maxNanos = 0; }
    }

    public record Snapshot(String name, double avgMillis, double maxMillis, long samples) { }

    public void record(String name, long nanos) {
        timings.computeIfAbsent(name, k -> new Timing()).add(nanos);
    }

    public void increment(String counter, long delta) {
        counters.computeIfAbsent(counter, k -> new AtomicLong()).addAndGet(delta);
    }

    public void gauge(String name, long value) { gauges.put(name, value); }

    public long counter(String name) {
        AtomicLong c = counters.get(name);
        return c == null ? 0 : c.get();
    }

    public Long gaugeValue(String name) { return gauges.get(name); }

    public Timing timing(String name) { return timings.get(name); }

    public List<Snapshot> snapshot() {
        List<Snapshot> list = new ArrayList<>();
        for (Map.Entry<String, Timing> e : new TreeMap<>(timings).entrySet()) {
            Timing t = e.getValue();
            list.add(new Snapshot(e.getKey(), t.avgMillis(), t.maxMillis(), t.samples()));
        }
        return list;
    }

    public Map<String, Long> counters() {
        Map<String, Long> m = new TreeMap<>();
        counters.forEach((k, v) -> m.put(k, v.get()));
        return m;
    }

    public Map<String, Long> gauges() { return new TreeMap<>(gauges); }

    public void resetMaxima() { timings.values().forEach(Timing::resetMax); }

    public void clear() {
        timings.clear();
        counters.clear();
        gauges.clear();
    }
}
