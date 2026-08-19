package net.exylia.lib.util.wizard;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

/**
 * A typed name for one answer a wizard collects.
 *
 * <pre>{@code
 * static final WizardKey<String>   ID    = WizardKey.text("id");
 * static final WizardKey<Long>     SLOTS = WizardKey.integer("slots");
 * static final WizardKey<Location> SPAWN = WizardKey.location("spawn");
 *
 * values.get(ID);     // a String, checked by the compiler
 * values.get(SLOTS);  // a Long
 * }</pre>
 *
 * <p>The alternative is a {@code Map<String, Object>} of half-built state, which
 * is exactly what {@code EventConfigWizard} kept: reading a field was a cast and
 * a guess, and a key spelled {@code "minPlayers"} on the way in and
 * {@code "min_players"} on the way out compiled perfectly and failed on the
 * server. A key is declared once, as a constant, and used at both ends.
 *
 * <p>Deliberately the same shape as {@code FormKey} from the {@code input}
 * module &mdash; the same idea, one step at a time instead of all at once &mdash;
 * plus the three types a form cannot ask for because they are not typed: a
 * location, a selected region, and an item held in the hand.
 *
 * @param <T> the type this answer produces
 * @since 1.34.0
 */
public final class WizardKey<T> {

    private final String name;
    private final Class<T> type;

    private WizardKey(String name, Class<T> type) {
        this.name = name;
        this.type = type;
    }

    /**
     * A key of any type.
     *
     * @param name the answer's name, unique within its wizard
     * @param type the type the answer produces
     * @param <T>  the type the answer produces
     * @return the key
     * @throws WizardException when the name is blank
     */
    public static <T> @NotNull WizardKey<T> of(@NotNull String name, @NotNull Class<T> type) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        if (name.isBlank()) {
            throw new WizardException("A wizard key needs a name.");
        }
        return new WizardKey<>(name, type);
    }

    /**
     * A key holding text.
     *
     * @param name the answer's name
     * @return the key
     */
    public static @NotNull WizardKey<String> text(@NotNull String name) {
        return of(name, String.class);
    }

    /**
     * A key holding a whole number.
     *
     * @param name the answer's name
     * @return the key
     */
    public static @NotNull WizardKey<Long> integer(@NotNull String name) {
        return of(name, Long.class);
    }

    /**
     * A key holding an exact decimal.
     *
     * <p>{@link BigDecimal} rather than {@code double} for the same reason the
     * input module chose it: a price typed as {@code 0.1} must still be
     * {@code 0.1} when it is written back to a config.
     *
     * @param name the answer's name
     * @return the key
     */
    public static @NotNull WizardKey<BigDecimal> decimal(@NotNull String name) {
        return of(name, BigDecimal.class);
    }

    /**
     * A key holding a yes or no.
     *
     * @param name the answer's name
     * @return the key
     */
    public static @NotNull WizardKey<Boolean> flag(@NotNull String name) {
        return of(name, Boolean.class);
    }

    /**
     * A key holding a length of time.
     *
     * @param name the answer's name
     * @return the key
     */
    public static @NotNull WizardKey<Duration> duration(@NotNull String name) {
        return of(name, Duration.class);
    }

    /**
     * A key holding a place in the world, answered by clicking a block.
     *
     * @param name the answer's name
     * @return the key
     */
    public static @NotNull WizardKey<Location> location(@NotNull String name) {
        return of(name, Location.class);
    }

    /**
     * A key holding a selected volume, answered with the region selector.
     *
     * @param name the answer's name
     * @return the key
     */
    public static @NotNull WizardKey<net.exylia.lib.region.SelectionResult> region(@NotNull String name) {
        return of(name, net.exylia.lib.region.SelectionResult.class);
    }

    /**
     * A key holding an item, answered by holding it and confirming.
     *
     * <p>What is stored is always a copy. Keeping the live stack would mean the
     * answer changed every time the player moved the item, and would be empty by
     * the time the summary was confirmed.
     *
     * @param name the answer's name
     * @return the key
     */
    public static @NotNull WizardKey<ItemStack> item(@NotNull String name) {
        return of(name, ItemStack.class);
    }

    /**
     * The answer's name, as it appears in errors and in the summary.
     *
     * @return the name
     */
    public @NotNull String name() {
        return name;
    }

    /**
     * The type this answer produces.
     *
     * @return the type
     */
    public @NotNull Class<T> type() {
        return type;
    }

    /**
     * Casts a value to this key's type.
     *
     * <p>The one place the cast happens, so a mismatch is a
     * {@link WizardException} naming both types rather than a
     * {@code ClassCastException} at the call site, three frames from the key
     * that caused it.
     *
     * @param value the value
     * @return the value, typed
     * @throws WizardException when the value is of another type
     */
    @org.jetbrains.annotations.ApiStatus.Internal
    public @NotNull T cast(@NotNull Object value) {
        if (!type.isInstance(value)) {
            throw new WizardException("Answer '" + name + "' holds a "
                    + value.getClass().getSimpleName() + ", not a " + type.getSimpleName() + '.');
        }
        return type.cast(value);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WizardKey<?> that
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
