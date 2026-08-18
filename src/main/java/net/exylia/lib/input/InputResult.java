package net.exylia.lib.input;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * What an input request produced.
 *
 * <pre>{@code
 * inputs.text(player, "Name the arena")
 *       .open()
 *       .thenAccept(result -> result.ifCompleted(this::createArena));
 * }</pre>
 *
 * <p>Always delivered, exactly once, whatever happened — including the endings
 * a callback-based API forgets: a timeout, a disconnect, a request replaced by
 * a newer one. A caller that only cares about the answer uses
 * {@link #ifCompleted(Consumer)}; a caller that wants to explain itself reads
 * {@link #outcome()}.
 *
 * @param <T> the type asked for
 * @since 1.31.0
 */
public final class InputResult<T> {

    private final InputOutcome outcome;
    private final T value;

    private InputResult(InputOutcome outcome, T value) {
        this.outcome = outcome;
        this.value = value;
    }

    /**
     * A completed result.
     *
     * @param value the answer
     * @param <T>   the type asked for
     * @return the result
     */
    public static <T> @NotNull InputResult<T> completed(@NotNull T value) {
        return new InputResult<>(InputOutcome.COMPLETED, value);
    }

    /**
     * A result that carries no answer.
     *
     * @param outcome why it ended; must not be {@link InputOutcome#COMPLETED}
     * @param <T>     the type asked for
     * @return the result
     */
    public static <T> @NotNull InputResult<T> ended(@NotNull InputOutcome outcome) {
        if (outcome == InputOutcome.COMPLETED) {
            throw new IllegalArgumentException("A completed result needs a value.");
        }
        return new InputResult<>(outcome, null);
    }

    /**
     * Why the request ended.
     *
     * @return the outcome
     */
    public @NotNull InputOutcome outcome() {
        return outcome;
    }

    /**
     * Whether the player answered.
     *
     * @return {@code true} when an answer is available
     */
    public boolean completed() {
        return outcome == InputOutcome.COMPLETED;
    }

    /**
     * The answer.
     *
     * @return the answer
     * @throws NoSuchElementException when the request did not complete — check
     *                                {@link #completed()} first, or use
     *                                {@link #orElse(Object)}
     */
    public @NotNull T value() {
        if (value == null) {
            throw new NoSuchElementException("The request ended as " + outcome + ", with no value.");
        }
        return value;
    }

    /**
     * The answer, or a fallback.
     *
     * @param fallback what to use when there is no answer
     * @return the answer or the fallback
     */
    public T orElse(T fallback) {
        return value != null ? value : fallback;
    }

    /**
     * The answer as an optional.
     *
     * @return the answer, or empty
     */
    public @NotNull Optional<T> optional() {
        return Optional.ofNullable(value);
    }

    /**
     * Runs an action only if the player answered.
     *
     * <p>The common case, and the one worth making shortest: most callers do
     * nothing at all when a player changes their mind.
     *
     * @param action what to do with the answer
     * @return this result, so an else-branch can follow
     */
    public @NotNull InputResult<T> ifCompleted(@NotNull Consumer<? super T> action) {
        if (value != null) {
            action.accept(value);
        }
        return this;
    }

    /**
     * Runs an action only if the player did not answer.
     *
     * @param action what to do, given why it ended
     * @return this result
     */
    public @NotNull InputResult<T> otherwise(@NotNull Consumer<InputOutcome> action) {
        if (value == null) {
            action.accept(outcome);
        }
        return this;
    }

    /**
     * Converts the answer, keeping the outcome.
     *
     * <p>For turning a chosen id into the object it names without unwrapping
     * and rewrapping the result by hand.
     *
     * @param mapper how to convert
     * @param <R>    the new type
     * @return a result of the new type
     */
    public <R> @NotNull InputResult<R> map(@NotNull Function<? super T, ? extends R> mapper) {
        return value == null ? InputResult.ended(outcome) : InputResult.completed(mapper.apply(value));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof InputResult<?> that)) {
            return false;
        }
        return outcome == that.outcome && java.util.Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(outcome, value);
    }

    @Override
    public String toString() {
        return completed() ? "InputResult{COMPLETED, " + value + '}' : "InputResult{" + outcome + '}';
    }

    /** The raw value, or {@code null}. Package-private: callers go through the accessors. */
    @Nullable T rawValue() {
        return value;
    }
}
