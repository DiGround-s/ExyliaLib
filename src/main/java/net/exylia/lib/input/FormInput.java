package net.exylia.lib.input;

import net.exylia.lib.input.internal.InputRuntime;
import net.exylia.lib.input.internal.InputSession;
import net.exylia.lib.input.internal.TransportKind;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A multi-field request whose parsing and cross-field validation are shared by
 * every transport.
 *
 * <p>{@link #parseRaw(Map)} returns either immutable {@link FormValues} or a
 * {@link Validation}. This explicit two-outcome boundary lets dialog, Bedrock,
 * and chat render errors differently without reimplementing what is valid.
 *
 * @since 1.31.0
 */
public final class FormInput implements InputSession.Pending {

    private final String pluginName;
    private final Player player;
    private final String prompt;
    private final LinkedHashMap<String, FormField<?>> fields = new LinkedHashMap<>();
    private final List<Function<FormValues, Validation>> validations = new ArrayList<>();

    private Duration timeout = Inputs.defaultTimeout();
    private List<TransportKind> preferredTransports = List.of();
    private String submitLabel = "Submit";

    FormInput(String pluginName, Player player, String prompt) {
        this.pluginName = Inputs.requireText(pluginName, "pluginName");
        this.player = Inputs.require(player, "player");
        this.prompt = Inputs.requireText(prompt, "prompt");
    }

    /**
     * Adds a field in display order.
     *
     * <p>The key argument must match the field's key. Rejecting mismatches here
     * prevents a value from being parsed as one type and later read under another.
     */
    public <T> @NotNull FormInput field(@NotNull FormKey<T> key,
                                        @NotNull FormField<T> field) {
        Inputs.require(key, "key");
        Inputs.require(field, "field");
        if (!key.equals(field.key())) {
            throw new InputException("field key '" + field.key() + "' does not match '" + key + "'");
        }
        if (fields.containsKey(key.name())) {
            throw new InputException("duplicate form key: '" + key.name() + "'");
        }
        fields.put(key.name(), field);
        return this;
    }

    /** Adds a required text field. */
    public @NotNull FormInput text(@NotNull FormKey<String> key, @NotNull String label) {
        return field(key, FormField.text(key, label));
    }

    /** Adds a required whole-number field. */
    public @NotNull FormInput integer(@NotNull FormKey<Long> key, @NotNull String label) {
        return field(key, FormField.integer(key, label));
    }

    /** Adds a required exact-decimal field. */
    public @NotNull FormInput decimal(@NotNull FormKey<BigDecimal> key, @NotNull String label) {
        return field(key, FormField.decimal(key, label));
    }

    /** Adds a required player-formatted amount field. */
    public @NotNull FormInput amount(@NotNull FormKey<BigDecimal> key, @NotNull String label) {
        return field(key, FormField.amount(key, label));
    }

    /** Adds a required yes-or-no field. */
    public @NotNull FormInput flag(@NotNull FormKey<Boolean> key, @NotNull String label) {
        return field(key, FormField.flag(key, label));
    }

    /** Adds a required duration field. */
    public @NotNull FormInput duration(@NotNull FormKey<Duration> key, @NotNull String label) {
        return field(key, FormField.duration(key, label));
    }

    /**
     * Adds a cross-field validation run only after every field parsed.
     *
     * <p>Deferring it avoids calling {@link FormValues#get(FormKey)} against
     * values that do not exist because their individual fields were invalid.
     */
    public @NotNull FormInput validate(@NotNull Function<FormValues, Validation> validation) {
        validations.add(Inputs.require(validation, "validation"));
        return this;
    }

    /** Sets the positive maximum time this form may remain pending. */
    public @NotNull FormInput timeout(@NotNull Duration timeout) {
        this.timeout = Inputs.requirePositive(timeout, "timeout");
        return this;
    }

    /** Restricts and orders transports, or restores defaults with no arguments. */
    public @NotNull FormInput transports(@NotNull TransportKind... kinds) {
        if (kinds == null) {
            throw new InputException("transports must not be null");
        }
        List<TransportKind> ordered = new ArrayList<>(kinds.length);
        for (TransportKind kind : kinds) {
            if (kind == null) {
                throw new InputException("transports must not contain null");
            }
            if (!ordered.contains(kind)) {
                ordered.add(kind);
            }
        }
        preferredTransports = List.copyOf(ordered);
        return this;
    }

    /** Sets the text of the form's final action. */
    public @NotNull FormInput submitLabel(@NotNull String label) {
        this.submitLabel = Inputs.requireText(label, "submit label");
        return this;
    }

    /** Opens the form and completes once for every terminal outcome. */
    public @NotNull CompletionStage<InputResult<FormValues>> open() {
        if (fields.isEmpty()) {
            throw new InputException("a form needs at least one field");
        }
        InputSession session = new InputSession(pluginName, player.getUniqueId(), this);
        return InputRuntime.submit(session, preferredTransports);
    }

    /** Opens the form and invokes an action only after successful submission. */
    public @NotNull CompletionStage<InputResult<FormValues>> open(
            @NotNull Consumer<? super FormValues> completed) {
        Inputs.require(completed, "completed");
        return open().thenApply(result -> result.ifCompleted(completed));
    }

    /**
     * Parses a raw submission into either {@link FormValues} or {@link Validation}.
     *
     * <p>All field errors are collected in one pass so a player can correct the
     * complete form rather than discover one failure per round trip.
     *
     * @return a {@link FormValues} on success, otherwise a {@link Validation}
     */
    public @NotNull Object parseRaw(@NotNull Map<String, String> rawByFieldName) {
        if (rawByFieldName == null) {
            return Validation.error("The form could not be read.");
        }
        Map<String, Object> parsed = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();
        for (FormField<?> field : fields.values()) {
            parseField(field, rawByFieldName.get(field.key().name()), parsed, errors);
        }
        if (!errors.isEmpty()) {
            return Validation.errors(errors);
        }

        FormValues values = new FormValues(Map.copyOf(parsed));
        Validation combined = Validation.ok();
        for (Function<FormValues, Validation> validation : validations) {
            final Validation verdict;
            try {
                verdict = validation.apply(values);
            } catch (RuntimeException failure) {
                return Validation.error("The form could not be validated.");
            }
            if (verdict == null) {
                return Validation.error("The form could not be validated.");
            }
            combined = combined.and(verdict);
        }
        return combined.valid() ? values : combined;
    }

    private static <T> void parseField(FormField<T> field, String raw,
                                        Map<String, Object> parsed,
                                        Map<String, String> errors) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            if (field.defaultValue() != null) {
                parsed.put(field.key().name(), field.defaultValue());
            } else if (field.isRequired()) {
                errors.put(field.key().name(), "This field is required.");
            }
            return;
        }
        InputParser.Parsed<T> result = field.parse(text);
        if (result.ok()) {
            parsed.put(field.key().name(), result.value());
        } else {
            errors.put(field.key().name(), result.error() != null
                    ? result.error() : "That value is not accepted.");
        }
    }

    /** Form title or instruction displayed by transports. */
    @ApiStatus.Internal
    public @NotNull String prompt() {
        return prompt;
    }

    /** Immutable fields in declaration and display order. */
    @ApiStatus.Internal
    public @NotNull List<FormField<?>> fields() {
        return List.copyOf(fields.values());
    }

    /** Text used for the final action. */
    @ApiStatus.Internal
    public @NotNull String submitLabel() {
        return submitLabel;
    }

    /** Forced fallback order, or empty for runtime defaults. */
    @ApiStatus.Internal
    public @NotNull List<TransportKind> preferredTransports() {
        return preferredTransports;
    }

    /** Positive pending timeout. */
    @Override
    @ApiStatus.Internal
    public @NotNull Duration timeout() {
        return timeout;
    }

    /** Player targeted by this form. */
    @ApiStatus.Internal
    public @NotNull Player player() {
        return player;
    }

    /** Plugin name used for disable-time ownership. */
    @ApiStatus.Internal
    public @NotNull String pluginName() {
        return pluginName;
    }
}
