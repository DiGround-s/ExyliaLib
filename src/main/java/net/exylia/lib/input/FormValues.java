package net.exylia.lib.input;

import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;

/**
 * The answers a form collected.
 *
 * <pre>{@code
 * form.open().thenAccept(result -> result.ifCompleted(values -> {
 *     String name = values.get(NAME);
 *     long slots = values.get(SLOTS);
 * }));
 * }</pre>
 *
 * <p>Immutable, and typed by {@link FormKey}. Reading a field that was never
 * declared is an {@link InputException} naming the field, not a {@code null}
 * that becomes a {@code NullPointerException} three lines later in the caller's
 * code.
 *
 * @since 1.31.0
 */
public final class FormValues {

    private final Map<String, Object> values;

    FormValues(Map<String, Object> values) {
        this.values = values;
    }

    /**
     * The value of a field.
     *
     * @param key the field
     * @param <T> the field's type
     * @return the value
     * @throws InputException when the form has no such field
     */
    public <T> @NotNull T get(@NotNull FormKey<T> key) {
        Object value = values.get(key.name());
        if (value == null) {
            throw new InputException("This form has no field named '" + key.name()
                    + "'. It has: " + values.keySet() + '.');
        }
        return key.cast(value);
    }

    /**
     * The value of an optional field, or a fallback.
     *
     * @param key      the field
     * @param fallback what to use when the field was left empty
     * @param <T>      the field's type
     * @return the value or the fallback
     */
    public <T> @NotNull T getOr(@NotNull FormKey<T> key, @NotNull T fallback) {
        Object value = values.get(key.name());
        return value == null ? fallback : key.cast(value);
    }

    /**
     * Whether a field was answered.
     *
     * <p>For optional fields: a field left blank is absent rather than empty,
     * so "not answered" and "answered with nothing" stay different.
     *
     * @param key the field
     * @return {@code true} when there is a value
     */
    public boolean has(@NotNull FormKey<?> key) {
        return values.containsKey(key.name());
    }

    /**
     * A text field's value.
     *
     * @param key the field
     * @return the value
     */
    public @NotNull String getText(@NotNull FormKey<String> key) {
        return get(key);
    }

    /**
     * A whole-number field's value.
     *
     * @param key the field
     * @return the value
     */
    public long getLong(@NotNull FormKey<Long> key) {
        return get(key);
    }

    /**
     * A decimal field's value.
     *
     * @param key the field
     * @return the value
     */
    public @NotNull BigDecimal getDecimal(@NotNull FormKey<BigDecimal> key) {
        return get(key);
    }

    /**
     * A yes-or-no field's value.
     *
     * @param key the field
     * @return the value
     */
    public boolean getBoolean(@NotNull FormKey<Boolean> key) {
        return get(key);
    }

    /**
     * A duration field's value.
     *
     * @param key the field
     * @return the value
     */
    public @NotNull Duration getDuration(@NotNull FormKey<Duration> key) {
        return get(key);
    }

    /**
     * Every answer, by field name.
     *
     * <p>For logging and for a caller that genuinely wants to iterate. Reading
     * a known field goes through {@link #get(FormKey)}, which is typed.
     *
     * @return the answers, read-only
     */
    public @NotNull Map<String, Object> asMap() {
        return Collections.unmodifiableMap(values);
    }

    @Override
    public String toString() {
        return "FormValues" + values;
    }
}
