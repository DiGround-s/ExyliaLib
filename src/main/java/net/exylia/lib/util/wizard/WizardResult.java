package net.exylia.lib.util.wizard;

import org.jetbrains.annotations.NotNull;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * What a wizard run produced.
 *
 * <pre>{@code
 * wizards.start(player, arena, () -> menu.open(player))
 *        .result()
 *        .thenAccept(result -> result
 *                .ifCompleted(values -> Text.of("{success}Arena created.").send(player))
 *                .otherwise(outcome -> debug.log("Arena wizard ended as " + outcome)));
 * }</pre>
 *
 * <p>Always delivered, exactly once, whatever happened &mdash; including the
 * endings a chained-callback flow forgets: a timeout at step 4, a disconnect at
 * step 5, a second {@code /arena create} replacing the first. The wizard this
 * module replaces had no result at all; the only way to find out the flow had
 * stopped was that the player never mentioned it again.
 *
 * <p>Deliberately shaped like {@code InputResult}, because a wizard is a chain
 * of inputs and a caller should not have to learn a second vocabulary to read
 * the answer to a chain of them.
 *
 * @since 1.34.0
 */
public final class WizardResult {

    private final WizardOutcome outcome;
    private final WizardValues values;

    private WizardResult(WizardOutcome outcome, WizardValues values) {
        this.outcome = outcome;
        this.values = values;
    }

    /**
     * A completed result.
     *
     * @param values the answers
     * @return the result
     */
    public static @NotNull WizardResult completed(@NotNull WizardValues values) {
        return new WizardResult(WizardOutcome.COMPLETED,
                java.util.Objects.requireNonNull(values, "values"));
    }

    /**
     * A result that carries no answers.
     *
     * @param outcome why it ended; must not be {@link WizardOutcome#COMPLETED}
     * @return the result
     * @throws IllegalArgumentException when handed {@code COMPLETED}
     */
    public static @NotNull WizardResult ended(@NotNull WizardOutcome outcome) {
        java.util.Objects.requireNonNull(outcome, "outcome");
        if (outcome == WizardOutcome.COMPLETED) {
            throw new IllegalArgumentException("A completed result needs its answers.");
        }
        return new WizardResult(outcome, null);
    }

    /**
     * Why the run ended.
     *
     * @return the outcome
     */
    public @NotNull WizardOutcome outcome() {
        return outcome;
    }

    /**
     * Whether the player finished and confirmed.
     *
     * @return {@code true} when answers are available
     */
    public boolean completed() {
        return outcome == WizardOutcome.COMPLETED;
    }

    /**
     * The answers.
     *
     * @return the answers
     * @throws NoSuchElementException when the run did not complete &mdash; check
     *                                {@link #completed()} first, or use
     *                                {@link #ifCompleted(Consumer)}
     */
    public @NotNull WizardValues values() {
        if (values == null) {
            throw new NoSuchElementException("The wizard ended as " + outcome + ", with no answers.");
        }
        return values;
    }

    /**
     * The answers as an optional.
     *
     * @return the answers, or empty
     */
    public @NotNull Optional<WizardValues> optional() {
        return Optional.ofNullable(values);
    }

    /**
     * One answer, if the run completed.
     *
     * <p>A shortcut for the common one-value read, so a caller that wants a
     * single field does not have to unwrap twice.
     *
     * @param key the answer
     * @param <T> the answer's type
     * @return the value, or empty when the run did not complete
     */
    public <T> @NotNull Optional<T> value(@NotNull WizardKey<T> key) {
        return values == null || !values.has(key) ? Optional.empty() : Optional.of(values.get(key));
    }

    /**
     * Runs an action only if the player finished and confirmed.
     *
     * <p>The common case, and the one worth making shortest: most callers do
     * nothing at all when a player changes their mind halfway.
     *
     * @param action what to do with the answers
     * @return this result, so an else-branch can follow
     */
    public @NotNull WizardResult ifCompleted(@NotNull Consumer<? super WizardValues> action) {
        if (values != null) {
            action.accept(values);
        }
        return this;
    }

    /**
     * Runs an action only if the run ended without answers.
     *
     * @param action what to do, given why it ended
     * @return this result
     */
    public @NotNull WizardResult otherwise(@NotNull Consumer<WizardOutcome> action) {
        if (values == null) {
            action.accept(outcome);
        }
        return this;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof WizardResult that
                && outcome == that.outcome
                && java.util.Objects.equals(values, that.values);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(outcome, values);
    }

    @Override
    public String toString() {
        return completed() ? "WizardResult{COMPLETED, " + values + '}' : "WizardResult{" + outcome + '}';
    }
}
