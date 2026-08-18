package net.exylia.lib.input;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * One typed form field, including the parser and constraints shared by every
 * form transport.
 *
 * @param <T> produced field type
 *
 * @since 1.31.0
 */
public final class FormField<T> {

    /** Semantic control kind, independent of a particular client protocol. */
    public enum Kind {
        TEXT, INTEGER, DECIMAL, AMOUNT, DURATION, FLAG, CHOICE
    }

    private final FormKey<T> key;
    private final String label;
    private final InputParser<T> parser;
    private final Kind kind;
    private final List<Rule<T>> validations = new ArrayList<>();
    private boolean required = true;
    private T defaultValue;

    private FormField(FormKey<T> key, String label, InputParser<T> parser, Kind kind) {
        this.key = Inputs.require(key, "key");
        this.label = Inputs.requireText(label, "label");
        this.parser = Inputs.require(parser, "parser");
        this.kind = Inputs.require(kind, "kind");
    }

    /** Creates a custom field while retaining a transport-visible semantic kind. */
    public static <T> @NotNull FormField<T> of(@NotNull FormKey<T> key,
                                               @NotNull String label,
                                               @NotNull InputParser<T> parser,
                                               @NotNull Kind kind) {
        return new FormField<>(key, label, parser, kind);
    }

    /** Creates a text field. */
    public static @NotNull FormField<String> text(@NotNull FormKey<String> key,
                                                   @NotNull String label) {
        return of(key, label, InputParser.text(), Kind.TEXT);
    }

    /** Creates a whole-number field. */
    public static @NotNull FormField<Long> integer(@NotNull FormKey<Long> key,
                                                    @NotNull String label) {
        return of(key, label, InputParser.integer(), Kind.INTEGER);
    }

    /** Creates an exact-decimal field. */
    public static @NotNull FormField<BigDecimal> decimal(@NotNull FormKey<BigDecimal> key,
                                                          @NotNull String label) {
        return of(key, label, InputParser.decimal(), Kind.DECIMAL);
    }

    /** Creates a player-formatted amount field. */
    public static @NotNull FormField<BigDecimal> amount(@NotNull FormKey<BigDecimal> key,
                                                         @NotNull String label) {
        return of(key, label, InputParser.amount(), Kind.AMOUNT);
    }

    /** Creates a duration field. */
    public static @NotNull FormField<Duration> duration(@NotNull FormKey<Duration> key,
                                                         @NotNull String label) {
        return of(key, label, InputParser.duration(), Kind.DURATION);
    }

    /** Creates a yes-or-no field. */
    public static @NotNull FormField<Boolean> flag(@NotNull FormKey<Boolean> key,
                                                    @NotNull String label) {
        return of(key, label, InputParser.flag(), Kind.FLAG);
    }

    /** Allows this field to be left blank and omitted from {@link FormValues}. */
    public @NotNull FormField<T> optional() {
        this.required = false;
        return this;
    }

    /** Requires an answer; this is the default and makes intent explicit. */
    public @NotNull FormField<T> required() {
        this.required = true;
        return this;
    }

    /** Sets the initial value a transport may display. */
    public @NotNull FormField<T> defaultValue(@Nullable T defaultValue) {
        this.defaultValue = defaultValue;
        return this;
    }

    /** Adds a player-facing predicate checked after parsing. */
    public @NotNull FormField<T> validate(@NotNull Predicate<T> predicate,
                                          @NotNull String message) {
        validations.add(new Rule<>(Inputs.require(predicate, "predicate"),
                Inputs.requireText(message, "validation message")));
        return this;
    }

    /**
     * Parses and validates one non-empty raw value.
     *
     * <p>Form transports must use this method so the same field cannot be valid
     * in a dialog and invalid in chat. Optional blank handling belongs to the
     * form because {@link InputParser.Parsed} deliberately never represents a
     * successful null value.
     */
    public @NotNull InputParser.Parsed<T> parse(@NotNull String raw) {
        if (raw == null) {
            return InputParser.Parsed.rejected("A value is required.");
        }
        final InputParser.Parsed<T> parsed;
        try {
            parsed = parser.parse(raw.trim());
        } catch (RuntimeException failure) {
            return InputParser.Parsed.rejected("That value could not be read.");
        }
        if (parsed == null || !parsed.ok()) {
            return parsed == null ? InputParser.Parsed.rejected("That value could not be read.") : parsed;
        }
        for (Rule<T> rule : validations) {
            try {
                if (!rule.predicate().test(parsed.value())) {
                    return InputParser.Parsed.rejected(rule.message());
                }
            } catch (RuntimeException failure) {
                return InputParser.Parsed.rejected("That value is not accepted.");
            }
        }
        return parsed;
    }

    /** Typed key used to store the parsed value. */
    public @NotNull FormKey<T> key() {
        return key;
    }

    /** Player-facing label. */
    public @NotNull String label() {
        return label;
    }

    /** Parser metadata; transports submit through {@link #parse(String)}. */
    @ApiStatus.Internal
    public @NotNull InputParser<T> parser() {
        return parser;
    }

    /** Whether blank input is rejected. */
    public boolean isRequired() {
        return required;
    }

    /** Optional initial value. */
    public @Nullable T defaultValue() {
        return defaultValue;
    }

    /** Semantic kind used to choose a client control. */
    public @NotNull Kind kind() {
        return kind;
    }

    /** Immutable validation descriptors for rich transports and diagnostics. */
    @ApiStatus.Internal
    public @NotNull List<InputRequest.ValidationRule<T>> validations() {
        return validations.stream()
                .map(rule -> new InputRequest.ValidationRule<>(rule.predicate(), rule.message()))
                .toList();
    }

    private record Rule<T>(Predicate<T> predicate, String message) {
    }
}
