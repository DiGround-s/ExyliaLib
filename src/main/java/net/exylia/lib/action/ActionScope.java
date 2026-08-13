package net.exylia.lib.action;

import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mutable values shared by every step of one action sequence.
 *
 * <p>Specials' damage step can store {@code LAST_DAMAGE}; the following heal
 * step reads it. Typed keys make that contract visible and prevent casts.
 * Thread-safe because an asynchronous step may complete before a synchronous
 * continuation resumes.
 *
 * @since 1.20.0
 */
public final class ActionScope {
    private final Map<ActionKey<?>, Object> values = new ConcurrentHashMap<>();

    public <T> void set(@NotNull ActionKey<T> key, @NotNull T value) {
        setUnchecked(key, value);
    }

    /** Package seam used by an ActionStep whose heterogeneous map was validated on build. */
    void setUnchecked(ActionKey<?> key, Object value) {
        if (!key.type().isInstance(value)) {
            throw new IllegalArgumentException(value + " is not " + key.type().getName());
        }
        values.put(key, value);
    }

    public <T> @NotNull Optional<T> get(@NotNull ActionKey<T> key) {
        Object value = values.get(key);
        return value == null ? Optional.empty() : Optional.of(key.type().cast(value));
    }

    public <T> T require(@NotNull ActionKey<T> key) {
        return get(key).orElseThrow(() -> new IllegalStateException("Missing action value: " + key.name()));
    }

    public boolean has(@NotNull ActionKey<?> key) { return values.containsKey(key); }
    public void remove(@NotNull ActionKey<?> key) { values.remove(key); }
    public int size() { return values.size(); }
}
