package net.exylia.lib.input;

import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

/**
 * A typed name for one field of a form.
 *
 * <pre>{@code
 * static final FormKey<String> NAME = FormKey.text("name");
 * static final FormKey<Long> SLOTS = FormKey.integer("slots");
 *
 * form.get(NAME);   // a String, checked by the compiler
 * form.get(SLOTS);  // a Long
 * }</pre>
 *
 * <p>The alternative is a {@code Map<String, Object>}, where reading a field is
 * a cast and a guess, and where a key spelled {@code "minPlayers"} on the way in
 * and {@code "min_players"} on the way out compiles perfectly and fails on the
 * server. A key is declared once and used at both ends.
 *
 * @param <T> the type this field produces
 * @since 1.31.0
 */
public final class FormKey<T> {

    /**
     * What a dialog will accept as a field name.
     *
     * <p>The client validates the key of every input it is sent, and rejects
     * anything outside letters, digits and underscores. A hyphen reads fine in
     * a config file and is fatal here.
     */
    private static final java.util.regex.Pattern VALID =
            java.util.regex.Pattern.compile("[a-zA-Z0-9_]+");

    private final String name;
    private final Class<T> type;

    private FormKey(String name, Class<T> type) {
        this.name = name;
        this.type = type;
    }

    /**
     * A key of any type.
     *
     * @param name the field name, unique within its form
     * @param type the type the field produces
     * @param <T>  the type the field produces
     * @return the key
     */
    public static <T> @NotNull FormKey<T> of(@NotNull String name, @NotNull Class<T> type) {
        if (name.isBlank()) {
            throw new InputException("A form key needs a name.");
        }
        if (!VALID.matcher(name).matches()) {
            // Refused here rather than at the client. A dialog input carries
            // its key over the wire, the client validates it, and a key it
            // will not accept fails the decode of the whole packet — which
            // does not drop one field, it disconnects the player looking at
            // the form. Better to fail where the name is written.
            throw new InputException("A form key may only hold letters, digits and"
                    + " underscores, because a dialog sends it to the client as a"
                    + " field name: \"" + name + "\" cannot be one. Try \""
                    + name.replaceAll("[^a-zA-Z0-9_]", "_") + "\".");
        }
        return new FormKey<>(name, type);
    }

    /**
     * A key holding text.
     *
     * @param name the field name
     * @return the key
     */
    public static @NotNull FormKey<String> text(@NotNull String name) {
        return of(name, String.class);
    }

    /**
     * A key holding a whole number.
     *
     * @param name the field name
     * @return the key
     */
    public static @NotNull FormKey<Long> integer(@NotNull String name) {
        return of(name, Long.class);
    }

    /**
     * A key holding an exact decimal.
     *
     * @param name the field name
     * @return the key
     */
    public static @NotNull FormKey<BigDecimal> decimal(@NotNull String name) {
        return of(name, BigDecimal.class);
    }

    /**
     * A key holding a yes or no.
     *
     * @param name the field name
     * @return the key
     */
    public static @NotNull FormKey<Boolean> flag(@NotNull String name) {
        return of(name, Boolean.class);
    }

    /**
     * A key holding a length of time.
     *
     * @param name the field name
     * @return the key
     */
    public static @NotNull FormKey<Duration> duration(@NotNull String name) {
        return of(name, Duration.class);
    }

    /**
     * The field name, as it appears in errors and in the transport.
     *
     * @return the name
     */
    public @NotNull String name() {
        return name;
    }

    /**
     * The type this field produces.
     *
     * @return the type
     */
    public @NotNull Class<T> type() {
        return type;
    }

    /**
     * Casts a value to this key's type.
     *
     * <p>The one place the cast happens, so a mismatch is an
     * {@link InputException} naming both types rather than a
     * {@code ClassCastException} at the call site.
     *
     * @param value the value
     * @return the value, typed
     */
    @NotNull T cast(@NotNull Object value) {
        if (!type.isInstance(value)) {
            throw new InputException("Field '" + name + "' holds a "
                    + value.getClass().getSimpleName() + ", not a " + type.getSimpleName() + '.');
        }
        return type.cast(value);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FormKey<?> that
                && name.equals(that.name)
                && type.equals(that.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, type);
    }

    @Override
    public String toString() {
        return name + ':' + type.getSimpleName();
    }
}
