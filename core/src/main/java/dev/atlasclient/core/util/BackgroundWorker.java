package dev.atlasclient.core.util;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A single daemon thread with a bounded queue for CPU-only analysis work. Workers never touch game
 * state: they receive immutable snapshots and publish results to a queue that the client thread
 * drains with {@link #drainResults}. A "generation" counter discards results computed for a world
 * the player has already left.
 */
public final class BackgroundWorker<R> implements AutoCloseable {
    private final ExecutorService executor;
    private final ConcurrentLinkedQueue<Result<R>> results = new ConcurrentLinkedQueue<>();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile Consumer<Throwable> errorHandler = t -> { };

    private record Result<R>(long generation, R value) { }

    public BackgroundWorker(String threadName, int queueCapacity) {
        ThreadPoolExecutor ex = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), r -> {
                    Thread t = new Thread(r, threadName);
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                }, new ThreadPoolExecutor.AbortPolicy());
        ex.allowCoreThreadTimeOut(true);
        this.executor = ex;
    }

    public void setErrorHandler(Consumer<Throwable> handler) { this.errorHandler = handler; }

    /** Submits work. Returns false if the queue is full (caller should retry later). */
    public boolean submit(Supplier<R> task) {
        long gen = generation.get();
        try {
            executor.execute(() -> {
                try {
                    R value = task.get();
                    if (value != null && gen == generation.get()) {
                        results.add(new Result<>(gen, value));
                    }
                } catch (Throwable t) {
                    failed.incrementAndGet();
                    errorHandler.accept(t);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            rejected.incrementAndGet();
            return false;
        }
    }

    /** Drains up to {@code max} results for the current generation on the calling (client) thread. */
    public int drainResults(int max, Consumer<R> sink) {
        int n = 0;
        long gen = generation.get();
        Result<R> r;
        while (n < max && (r = results.poll()) != null) {
            if (r.generation() == gen) {
                sink.accept(r.value());
                n++;
            }
        }
        return n;
    }

    /** Invalidates queued and in-flight work (world change). */
    public void newGeneration() {
        generation.incrementAndGet();
        results.clear();
        if (executor instanceof ThreadPoolExecutor tpe) {
            tpe.getQueue().clear();
        }
    }

    public int queued() {
        return executor instanceof ThreadPoolExecutor tpe ? tpe.getQueue().size() : 0;
    }

    public long rejectedCount() { return rejected.get(); }

    public long failedCount() { return failed.get(); }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
