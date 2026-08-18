package net.exylia.lib.input;

import net.exylia.lib.input.internal.InputRuntime;
import net.exylia.lib.input.internal.InputSession;
import net.exylia.lib.input.internal.TransportKind;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * Shared self-typed builder for requests that produce one value.
 *
 * <p>Raw text always passes through {@link #parseRaw(String)}. Keeping transform,
 * parse, and validation in this single method prevents dialog, Bedrock, menu,
 * and chat handlers from accepting different answers as earlier implementations
 * did.
 *
 * @param <T>    produced value type
 * @param <SELF> concrete builder type
 * @since 1.31.0
 */
public abstract class InputRequest<T, SELF extends InputRequest<T, SELF>>
        implements InputSession.Pending {

    private final String pluginName;
    private final Player player;
    private final String prompt;
    private final InputParser<T> parser;
    private final List<Rule<T>> validations = new ArrayList<>();

    private Duration timeout;
    private T defaultValue;
    private UnaryOperator<String> transform = UnaryOperator.identity();
    private List<TransportKind> preferredTransports = List.of();

    InputRequest(String pluginName, Player player, String prompt, InputParser<T> parser) {
        this.pluginName = Inputs.requireText(pluginName, "pluginName");
        this.player = Inputs.require(player, "player");
        this.prompt = Inputs.requireText(prompt, "prompt");
        this.parser = Inputs.require(parser, "parser");
        this.timeout = Inputs.defaultTimeout();
    }

    /** Sets the positive maximum time the player may leave this request open. */
    public @NotNull SELF timeout(@NotNull Duration timeout) {
        this.timeout = Inputs.requirePositive(timeout, "timeout");
        return self();
    }

    /** Sets the value transports may offer when the player does not type one. */
    public @NotNull SELF defaultValue(@Nullable T defaultValue) {
        this.defaultValue = defaultValue;
        return self();
    }

    /**
     * Adds a player-facing constraint checked after parsing.
     *
     * <p>A rejected answer is returned as validation data and never thrown,
     * because typing an invalid value is expected player behavior.
     */
    public @NotNull SELF validate(@NotNull Predicate<T> predicate, @NotNull String message) {
        validations.add(new Rule<>(Inputs.require(predicate, "predicate"),
                Inputs.requireText(message, "validation message")));
        return self();
    }

    /**
     * Changes raw text before parsing.
     *
     * <p>The transform is shared by every transport, preventing a normalization
     * such as case folding from working in chat but not in a native dialog.
     */
    public @NotNull SELF transform(@NotNull UnaryOperator<String> transform) {
        this.transform = Inputs.require(transform, "transform");
        return self();
    }

    /**
     * Restricts and orders presentation fallbacks.
     *
     * <p>An empty array restores runtime order. Duplicate kinds are harmless and
     * are removed here so transports never receive contradictory preference data.
     */
    public @NotNull SELF transports(@NotNull TransportKind... kinds) {
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
        this.preferredTransports = List.copyOf(ordered);
        return self();
    }

    /** Opens the request and completes exactly once for every terminal outcome. */
    public @NotNull CompletionStage<InputResult<T>> open() {
        beforeOpen();
        InputSession session = new InputSession(pluginName, player.getUniqueId(), this);
        return InputRuntime.submit(session, preferredTransports);
    }

    /** Hook for request types that compile immutable transport data at open time. */
    protected void beforeOpen() {
    }

    /** Opens the request and invokes an action only when a valid value was supplied. */
    public @NotNull CompletionStage<InputResult<T>> open(@NotNull Consumer<? super T> completed) {
        Inputs.require(completed, "completed");
        return open().thenApply(result -> result.ifCompleted(completed));
    }

    /**
     * Runs every registered predicate in declaration order.
     *
     * <p>Transports call this rather than implementing constraints themselves;
     * the first rejection is stable regardless of presentation mechanism.
     */
    public @NotNull Validation validate(@NotNull T value) {
        if (value == null) {
            return Validation.error("A value is required.");
        }
        for (Rule<T> rule : validations) {
            if (!rule.predicate().test(value)) {
                return Validation.error(rule.message());
            }
        }
        return Validation.ok();
    }

    /**
     * Applies transform, parser, and validation in that order.
     *
     * <p>This is the sole player-answer pipeline. It catches transform and
     * predicate failures as rejections so player-controlled input never escapes
     * a transport callback as an exception.
     */
    public @NotNull InputParser.Parsed<T> parseRaw(@NotNull String raw) {
        if (raw == null) {
            return InputParser.Parsed.rejected("A value is required.");
        }
        final String transformed;
        try {
            transformed = transform.apply(raw.trim());
        } catch (RuntimeException failure) {
            return InputParser.Parsed.rejected("That value could not be read.");
        }
        if (transformed == null) {
            return InputParser.Parsed.rejected("That value could not be read.");
        }
        final InputParser.Parsed<T> parsed;
        try {
            parsed = parser.parse(transformed.trim());
        } catch (RuntimeException failure) {
            return InputParser.Parsed.rejected("That value could not be read.");
        }
        if (parsed == null || !parsed.ok()) {
            return parsed == null
                    ? InputParser.Parsed.rejected("That value could not be read.")
                    : parsed;
        }
        Validation verdict;
        try {
            verdict = validate(parsed.value());
        } catch (RuntimeException failure) {
            return InputParser.Parsed.rejected("That value is not accepted.");
        }
        return verdict.valid() ? parsed : InputParser.Parsed.rejected(verdict.messages().getFirst());
    }

    /** Prompt displayed by a transport. */
    @ApiStatus.Internal
    public final @NotNull String prompt() {
        return prompt;
    }

    /** Parser metadata used to select a suitable control; answers still go through parseRaw. */
    @ApiStatus.Internal
    public final @NotNull InputParser<T> parser() {
        return parser;
    }

    /** Optional initial answer displayed by a transport. */
    @ApiStatus.Internal
    public final @Nullable T defaultValue() {
        return defaultValue;
    }

    /** Immutable validation descriptors for diagnostics and rich transports. */
    @ApiStatus.Internal
    public final @NotNull List<ValidationRule<T>> validations() {
        return validations.stream()
                .map(rule -> new ValidationRule<>(rule.predicate(), rule.message()))
                .toList();
    }

    /** Raw-text transform; transports should normally call parseRaw instead. */
    @ApiStatus.Internal
    public final @NotNull UnaryOperator<String> transform() {
        return transform;
    }

    /** Forced fallback order, or an empty list for runtime order. */
    @ApiStatus.Internal
    public final @NotNull List<TransportKind> preferredTransports() {
        return preferredTransports;
    }

    /** Positive request timeout copied into the runtime session. */
    @Override
    @ApiStatus.Internal
    public final @NotNull Duration timeout() {
        return timeout;
    }

    /** Player targeted by this request. */
    @ApiStatus.Internal
    public final @NotNull Player player() {
        return player;
    }

    /** Plugin name used for lifecycle ownership. */
    @ApiStatus.Internal
    public final @NotNull String pluginName() {
        return pluginName;
    }

    /** Immutable transport-facing validation descriptor. */
    @ApiStatus.Internal
    public record ValidationRule<T>(@NotNull Predicate<T> predicate, @NotNull String message) {
    }

    @SuppressWarnings("unchecked")
    private SELF self() {
        return (SELF) this;
    }

    private record Rule<T>(Predicate<T> predicate, String message) {
    }
}
