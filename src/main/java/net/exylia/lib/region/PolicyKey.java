package net.exylia.lib.region;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Typed identifier and immutable default for one region policy.
 *
 * <p>Identity is intentionally the namespaced identifier alone so independently created keys can
 * address persisted values. Callers supplying key collections to a codec must not provide the same
 * identifier with incompatible types.
 *
 * @param <T> policy value type
 * @since 1.23.0
 */
public final class PolicyKey<T> {
    private final RegionId id;
    private final Class<T> type;
    private final T defaultValue;

    private PolicyKey(RegionId id, Class<T> type, T defaultValue) {
        this.id = id;
        this.type = type;
        this.defaultValue = defaultValue;
    }

    /**
     * Creates a typed policy key.
     *
     * @param id namespaced policy identifier
     * @param type exact runtime value type
     * @param defaultValue non-null value used when no region declares the policy
     * @param <T> policy value type
     * @return the immutable key
     */
    public static <T> @NotNull PolicyKey<T> of(@NotNull RegionId id, @NotNull Class<T> type,
                                               @NotNull T defaultValue) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(defaultValue, "defaultValue");
        if (!type.isInstance(defaultValue)) {
            throw new IllegalArgumentException("Default value is not a " + type.getName());
        }
        return new PolicyKey<>(id, type, defaultValue);
    }

    /** Returns the stable namespaced identifier. */
    public @NotNull RegionId id() {
        return id;
    }

    /** Returns the exact runtime value type. */
    public @NotNull Class<T> type() {
        return type;
    }

    /** Returns the immutable default value. */
    public @NotNull T defaultValue() {
        return defaultValue;
    }

    /** Casts and validates a value for this key. */
    @NotNull T cast(@NotNull Object value) {
        Objects.requireNonNull(value, "value");
        if (!type.isInstance(value)) {
            throw new IllegalArgumentException("Policy " + id + " requires " + type.getName()
                    + ", got " + value.getClass().getName());
        }
        return type.cast(value);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof PolicyKey<?> key && id.equals(key.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public @NotNull String toString() {
        return id + "<" + type.getSimpleName() + ">";
    }
}
