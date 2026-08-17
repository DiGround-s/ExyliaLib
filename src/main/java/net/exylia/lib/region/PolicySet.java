package net.exylia.lib.region;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable typed collection of explicit region policy declarations.
 *
 * <p>Absence is distinct from a key's default value. Each update returns a new set, and no mutable
 * backing collection is exposed.
 *
 * @since 1.23.0
 */
public final class PolicySet {
    private static final PolicySet EMPTY = new PolicySet(Map.of());

    private final Map<PolicyKey<?>, Object> values;

    private PolicySet(Map<PolicyKey<?>, Object> values) {
        this.values = values;
    }

    /** Returns the shared empty policy set. */
    public static @NotNull PolicySet empty() {
        return EMPTY;
    }

    /**
     * Creates a set containing one explicit declaration.
     *
     * @param key policy key
     * @param value explicit value
     * @param <T> policy value type
     * @return an immutable policy set
     */
    public static <T> @NotNull PolicySet of(@NotNull PolicyKey<T> key, @NotNull T value) {
        return EMPTY.with(key, value);
    }

    /**
     * Returns a set with an explicit value added or replaced.
     *
     * @param key policy key
     * @param value explicit value
     * @param <T> policy value type
     * @return the updated immutable set
     */
    public <T> @NotNull PolicySet with(@NotNull PolicyKey<T> key, @NotNull T value) {
        Objects.requireNonNull(key, "key");
        T checked = key.cast(value);
        PolicyKey<?> existing = matchingKey(key.id());
        if (existing != null && !existing.type().equals(key.type())) {
            throw new IllegalArgumentException("Policy id " + key.id()
                    + " is already declared with type " + existing.type().getName());
        }
        Object current = values.get(key);
        if (Objects.equals(current, checked)) {
            return this;
        }
        Map<PolicyKey<?>, Object> updated = new HashMap<>(values);
        updated.put(key, checked);
        return new PolicySet(Map.copyOf(updated));
    }

    /**
     * Returns a set without a declaration for the supplied key identifier.
     *
     * @param key policy key
     * @return the updated immutable set
     */
    public @NotNull PolicySet without(@NotNull PolicyKey<?> key) {
        Objects.requireNonNull(key, "key");
        if (!values.containsKey(key)) {
            return this;
        }
        if (values.size() == 1) {
            return EMPTY;
        }
        Map<PolicyKey<?>, Object> updated = new HashMap<>(values);
        updated.remove(key);
        return new PolicySet(Map.copyOf(updated));
    }

    /** Returns whether this set explicitly declares the supplied key. */
    public boolean declares(@NotNull PolicyKey<?> key) {
        return values.containsKey(Objects.requireNonNull(key, "key"));
    }

    /**
     * Returns an explicit value without applying the key default.
     *
     * @param key policy key
     * @param <T> policy value type
     * @return the explicit value, or an empty optional when absent
     */
    public <T> @NotNull Optional<T> explicit(@NotNull PolicyKey<T> key) {
        Objects.requireNonNull(key, "key");
        Object value = values.get(key);
        return value == null ? Optional.empty() : Optional.of(key.cast(value));
    }

    /** Returns an immutable set of explicitly declared keys. */
    public @NotNull Set<PolicyKey<?>> keys() {
        return values.keySet();
    }

    /** Returns whether this set contains no explicit declarations. */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    Map<PolicyKey<?>, Object> values() {
        return values;
    }

    private PolicyKey<?> matchingKey(RegionId id) {
        for (PolicyKey<?> key : values.keySet()) {
            if (key.id().equals(id)) {
                return key;
            }
        }
        return null;
    }

    static @NotNull PolicySet copyOf(@NotNull Collection<PolicyEntry<?>> entries) {
        PolicySet result = EMPTY;
        for (PolicyEntry<?> entry : entries) {
            result = putEntry(result, entry);
        }
        return result;
    }

    private static <T> PolicySet putEntry(PolicySet set, PolicyEntry<T> entry) {
        return set.with(entry.key(), entry.value());
    }

    record PolicyEntry<T>(PolicyKey<T> key, T value) { }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof PolicySet set && values.equals(set.values);
    }

    @Override
    public int hashCode() {
        return values.hashCode();
    }

    @Override
    public @NotNull String toString() {
        return values.toString();
    }
}
