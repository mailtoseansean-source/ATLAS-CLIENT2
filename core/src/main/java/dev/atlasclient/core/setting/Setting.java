package dev.atlasclient.core.setting;

import com.google.gson.JsonElement;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * A single configurable value owned by a module.
 *
 * <p>Values are written on the client thread (GUI, commands, config loading) and may be read from
 * background analysis threads, so the value field is volatile and every subclass stores immutable
 * values.</p>
 */
public abstract class Setting<T> {
    private final String name;
    private final String description;
    private final T defaultValue;
    private volatile T value;
    private BooleanSupplier visibility = () -> true;
    private final List<Consumer<T>> listeners = new CopyOnWriteArrayList<>();

    protected Setting(String name, String description, T defaultValue) {
        this.name = Objects.requireNonNull(name);
        this.description = description == null ? "" : description;
        this.defaultValue = Objects.requireNonNull(defaultValue);
        this.value = defaultValue;
    }

    public final String name() { return name; }
    public final String description() { return description; }
    public final T defaultValue() { return defaultValue; }
    public final T get() { return value; }

    /** Sets the value after validation. Returns the value actually stored. */
    public final T set(T newValue) {
        T sanitized = sanitize(Objects.requireNonNull(newValue));
        T old = this.value;
        this.value = sanitized;
        if (!sanitized.equals(old)) {
            for (Consumer<T> listener : listeners) {
                listener.accept(sanitized);
            }
        }
        return sanitized;
    }

    public final void reset() { set(defaultValue); }

    public final boolean isDefault() { return value.equals(defaultValue); }

    /** Whether this setting should be persisted in configuration profiles. */
    public boolean persistent() { return true; }

    public final boolean isVisible() { return visibility.getAsBoolean(); }

    @SuppressWarnings("unchecked")
    public final <S extends Setting<T>> S visibleWhen(BooleanSupplier condition) {
        this.visibility = Objects.requireNonNull(condition);
        return (S) this;
    }

    @SuppressWarnings("unchecked")
    public final <S extends Setting<T>> S onChange(Consumer<T> listener) {
        listeners.add(Objects.requireNonNull(listener));
        return (S) this;
    }

    /** Clamp/validate a candidate value. Must never throw for type-correct input. */
    protected T sanitize(T candidate) { return candidate; }

    public abstract JsonElement toJson();

    /**
     * Reads a value from JSON. Returns false (and keeps the current value) when the JSON is of
     * the wrong type, so a corrupted config never crashes the client.
     */
    public abstract boolean fromJson(JsonElement json);

    /** Human-readable value for GUI display. */
    public String displayValue() { return String.valueOf(value); }
}
