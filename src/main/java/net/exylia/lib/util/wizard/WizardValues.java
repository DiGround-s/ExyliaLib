package net.exylia.lib.util.wizard;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The answers a wizard collected so far.
 *
 * <pre>{@code
 * wizards.define("arena")
 *        .ask(ID, step -> step.id("Enter the arena id"))
 *        .onFinish(values -> createArena(values.get(ID), values.get(SLOTS)))
 *        .build();
 * }</pre>
 *
 * <p>Immutable, and typed by {@link WizardKey}. Reading an answer that was never
 * collected is a {@link WizardException} naming it and listing what <em>was</em>
 * collected, not a {@code null} that becomes a {@code NullPointerException}
 * three lines later inside the plugin's own creation code.
 *
 * <h2>Also handed to branch predicates</h2>
 * A {@code when} is evaluated against the answers gathered up to that point, so
 * a predicate reads the same object the finish callback eventually receives.
 * That is why {@link #has(WizardKey)} exists: a branch inside a branch may be
 * asking about a key that a skipped step never filled.
 *
 * <p>Insertion order is preserved, because it is the order the player answered
 * in and therefore the order the summary lists.
 *
 * @since 1.34.0
 */
public final class WizardValues {

    private static final WizardValues EMPTY = new WizardValues(Map.of());

    private final Map<String, Object> values;

    private WizardValues(Map<String, Object> values) {
        this.values = values;
    }

    /**
     * The answers of a run that has collected nothing yet.
     *
     * @return an empty set of answers
     */
    public static @NotNull WizardValues empty() {
        return EMPTY;
    }

    /**
     * Snapshots a run's answers so far.
     *
     * <p>Copies, on purpose. A session hands the same instance to a branch
     * predicate, to a validator and eventually to the finish callback, and a
     * predicate able to write into the session's own map could change what a
     * later step asks.
     *
     * @param values the answers, by name, in the order they were collected
     * @return an immutable snapshot
     */
    @org.jetbrains.annotations.ApiStatus.Internal
    public static @NotNull WizardValues of(@NotNull Map<String, Object> values) {
        return values.isEmpty()
                ? EMPTY
                : new WizardValues(Collections.unmodifiableMap(new LinkedHashMap<>(values)));
    }

    /**
     * The value of an answer.
     *
     * @param key the answer
     * @param <T> the answer's type
     * @return the value
     * @throws WizardException when nothing collected that answer
     */
    public <T> @NotNull T get(@NotNull WizardKey<T> key) {
        Object value = values.get(key.name());
        if (value == null) {
            throw new WizardException("This wizard collected no answer named '" + key.name()
                    + "'. It has: " + values.keySet() + '.');
        }
        return key.cast(value);
    }

    /**
     * The value of an answer, or a fallback.
     *
     * <p>The right accessor for anything behind a {@code when}: a branch that
     * did not run collected nothing, and that is not an error.
     *
     * @param key      the answer
     * @param fallback what to use when it was never collected
     * @param <T>      the answer's type
     * @return the value or the fallback
     */
    public <T> @NotNull T getOr(@NotNull WizardKey<T> key, @NotNull T fallback) {
        Object value = values.get(key.name());
        return value == null ? fallback : key.cast(value);
    }

    /**
     * Whether an answer was collected.
     *
     * @param key the answer
     * @return {@code true} when there is a value
     */
    public boolean has(@NotNull WizardKey<?> key) {
        return values.containsKey(key.name());
    }

    /**
     * A text answer's value.
     *
     * @param key the answer
     * @return the value
     */
    public @NotNull String getText(@NotNull WizardKey<String> key) {
        return get(key);
    }

    /**
     * A whole-number answer's value.
     *
     * @param key the answer
     * @return the value
     */
    public long getLong(@NotNull WizardKey<Long> key) {
        return get(key);
    }

    /**
     * A decimal answer's value.
     *
     * @param key the answer
     * @return the value
     */
    public @NotNull BigDecimal getDecimal(@NotNull WizardKey<BigDecimal> key) {
        return get(key);
    }

    /**
     * A yes-or-no answer's value.
     *
     * @param key the answer
     * @return the value
     */
    public boolean getBoolean(@NotNull WizardKey<Boolean> key) {
        return get(key);
    }

    /**
     * A duration answer's value.
     *
     * @param key the answer
     * @return the value
     */
    public @NotNull Duration getDuration(@NotNull WizardKey<Duration> key) {
        return get(key);
    }

    /**
     * A picked place's value.
     *
     * @param key the answer
     * @return the value, a copy the caller may mutate freely
     */
    public @NotNull Location getLocation(@NotNull WizardKey<Location> key) {
        return get(key).clone();
    }

    /**
     * A selected region's value.
     *
     * @param key the answer
     * @return the value
     */
    public @NotNull net.exylia.lib.region.SelectionResult getRegion(
            @NotNull WizardKey<net.exylia.lib.region.SelectionResult> key) {
        return get(key);
    }

    /**
     * A held item's value.
     *
     * <p>A copy, like everything else this module stores about an item: handing
     * back the same stack twice would let one caller's edit show up in another's
     * copy of the same answer.
     *
     * @param key the answer
     * @return the value, a copy the caller may mutate freely
     */
    public @NotNull ItemStack getItem(@NotNull WizardKey<ItemStack> key) {
        return get(key).clone();
    }

    /**
     * Every answer, by name, in the order they were collected.
     *
     * <p>For logging and for a caller that genuinely wants to iterate. Reading a
     * known answer goes through {@link #get(WizardKey)}, which is typed.
     *
     * @return the answers, read-only
     */
    public @NotNull Map<String, Object> asMap() {
        return Collections.unmodifiableMap(values);
    }

    /**
     * How many answers have been collected.
     *
     * @return the count
     */
    public int size() {
        return values.size();
    }

    @Override
    public String toString() {
        return "WizardValues" + values;
    }
}
