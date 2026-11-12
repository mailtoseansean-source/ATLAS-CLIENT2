package dev.atlasclient.core.event;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Minimal, thread-safe, synchronous event bus.
 *
 * <p>Listeners are keyed by the exact event class. Posting is synchronous on the calling thread;
 * callers that post from background threads must only use events whose listeners are thread-safe.
 * A failing listener never stops delivery to the remaining listeners; failures are reported to the
 * configured error handler.</p>
 */
public final class EventBus {
    private final Map<Class<?>, List<Listener<?>>> listeners = new ConcurrentHashMap<>();
    private volatile BiConsumer<Object, Throwable> errorHandler = (event, error) -> { };

    private record Listener<E>(Class<E> type, Consumer<? super E> handler) { }

    /** Handle returned by {@link #subscribe}; closing it removes the listener. */
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    public void setErrorHandler(BiConsumer<Object, Throwable> handler) {
        this.errorHandler = Objects.requireNonNull(handler);
    }

    public <E> Subscription subscribe(Class<E> type, Consumer<? super E> handler) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");
        Listener<E> listener = new Listener<>(type, handler);
        List<Listener<?>> list = listeners.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>());
        list.add(listener);
        return () -> list.remove(listener);
    }

    @SuppressWarnings("unchecked")
    public <E> int post(E event) {
        Objects.requireNonNull(event, "event");
        List<Listener<?>> list = listeners.get(event.getClass());
        if (list == null || list.isEmpty()) {
            return 0;
        }
        int delivered = 0;
        for (Listener<?> raw : list) {
            Listener<E> listener = (Listener<E>) raw;
            try {
                listener.handler().accept(event);
                delivered++;
            } catch (Throwable t) {
                errorHandler.accept(event, t);
            }
        }
        return delivered;
    }

    public int listenerCount(Class<?> type) {
        List<Listener<?>> list = listeners.get(type);
        return list == null ? 0 : list.size();
    }

    public int totalListenerCount() {
        int total = 0;
        for (List<Listener<?>> list : listeners.values()) {
            total += list.size();
        }
        return total;
    }

    /** Removes every listener. Used on client shutdown. */
    public void clear() {
        listeners.clear();
    }
}
