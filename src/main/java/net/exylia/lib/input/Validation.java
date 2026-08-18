package net.exylia.lib.input;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * The verdict on a value, or on a whole form.
 *
 * <pre>{@code
 * .validate(values -> values.get(MIN) <= values.get(MAX)
 *         ? Validation.ok()
 *         : Validation.error(MAX, "The maximum cannot be below the minimum"));
 * }</pre>
 *
 * <p>Carries the message a player reads and, for a form, which field it belongs
 * to. Naming the field is what lets a form come back with the error next to the
 * box that caused it, instead of a single line at the top that the player has to
 * match up themselves.
 *
 * @since 1.31.0
 */
public final class Validation {

    private static final Validation OK = new Validation(Map.of(), null);

    private final Map<String, String> fieldErrors;
    private final String generalError;

    private Validation(Map<String, String> fieldErrors, String generalError) {
        this.fieldErrors = fieldErrors;
        this.generalError = generalError;
    }

    /**
     * Everything is fine.
     *
     * @return an accepting verdict
     */
    public static @NotNull Validation ok() {
        return OK;
    }

    /**
     * A problem that belongs to no single field.
     *
     * @param message what the player reads
     * @return a rejecting verdict
     */
    public static @NotNull Validation error(@NotNull String message) {
        return new Validation(Map.of(), message);
    }

    /**
     * A problem with one field.
     *
     * @param key     the field it belongs to
     * @param message what the player reads
     * @return a rejecting verdict
     */
    public static @NotNull Validation error(@NotNull FormKey<?> key, @NotNull String message) {
        return new Validation(Map.of(key.name(), message), null);
    }

    /**
     * Problems with several fields at once.
     *
     * <p>All of them are shown together. Reporting one error, waiting for a
     * correction, then reporting the next is how a five-field form takes five
     * round trips to fill in.
     *
     * @param errors field name to message
     * @return a rejecting verdict
     */
    public static @NotNull Validation errors(@NotNull Map<String, String> errors) {
        return errors.isEmpty() ? OK : new Validation(Map.copyOf(errors), null);
    }

    /**
     * Whether the value or form is acceptable.
     *
     * @return {@code true} when there is nothing to report
     */
    public boolean valid() {
        return fieldErrors.isEmpty() && generalError == null;
    }

    /**
     * The problems, by field name.
     *
     * @return the field errors, possibly empty
     */
    public @NotNull Map<String, String> fieldErrors() {
        return fieldErrors;
    }

    /**
     * The problem that belongs to no field.
     *
     * @return the message, or {@code null}
     */
    public @Nullable String generalError() {
        return generalError;
    }

    /**
     * Every message, in the order they should be shown.
     *
     * @return the messages
     */
    public @NotNull List<String> messages() {
        if (valid()) {
            return List.of();
        }
        if (generalError != null && fieldErrors.isEmpty()) {
            return List.of(generalError);
        }
        List<String> all = new java.util.ArrayList<>(fieldErrors.size() + 1);
        all.addAll(fieldErrors.values());
        if (generalError != null) {
            all.add(generalError);
        }
        return List.copyOf(all);
    }

    /**
     * Both verdicts together.
     *
     * <p>Used to gather per-field errors before running the form-wide check, so
     * a player sees every problem at once.
     *
     * @param other the other verdict
     * @return the combined verdict
     */
    public @NotNull Validation and(@NotNull Validation other) {
        if (valid()) {
            return other;
        }
        if (other.valid()) {
            return this;
        }
        Map<String, String> merged = new java.util.LinkedHashMap<>(fieldErrors);
        merged.putAll(other.fieldErrors);
        String general = generalError != null ? generalError : other.generalError;
        return new Validation(Map.copyOf(merged), general);
    }

    @Override
    public String toString() {
        return valid() ? "Validation{ok}" : "Validation" + messages();
    }
}
