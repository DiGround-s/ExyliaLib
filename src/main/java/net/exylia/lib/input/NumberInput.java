package net.exylia.lib.input;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * A request for a numeric value whose exact representation is retained.
 *
 * @param <T> {@link Long} for whole numbers or {@link java.math.BigDecimal} for decimals
 *
 * @since 1.31.0
 */
public final class NumberInput<T extends Number & Comparable<T>>
        extends InputRequest<T, NumberInput<T>> {

    private T minimum;
    private T maximum;

    NumberInput(String pluginName, Player player, String prompt, InputParser<T> parser) {
        super(pluginName, player, prompt, parser);
    }

    /** Applies an inclusive range, rejecting an inverted caller-supplied range. */
    public @NotNull NumberInput<T> range(@NotNull T minimum, @NotNull T maximum) {
        Inputs.require(minimum, "minimum");
        Inputs.require(maximum, "maximum");
        if (minimum.compareTo(maximum) > 0) {
            throw new InputException("minimum must not be greater than maximum");
        }
        return min(minimum).max(maximum);
    }

    /** Applies an inclusive lower bound. */
    public @NotNull NumberInput<T> min(@NotNull T minimum) {
        Inputs.require(minimum, "minimum");
        if (maximum != null && minimum.compareTo(maximum) > 0) {
            throw new InputException("minimum must not be greater than maximum");
        }
        this.minimum = minimum;
        return validate(value -> value.compareTo(minimum) >= 0,
                "Enter a value of at least " + minimum + '.');
    }

    /** Applies an inclusive upper bound. */
    public @NotNull NumberInput<T> max(@NotNull T maximum) {
        Inputs.require(maximum, "maximum");
        if (minimum != null && minimum.compareTo(maximum) > 0) {
            throw new InputException("minimum must not be greater than maximum");
        }
        this.maximum = maximum;
        return validate(value -> value.compareTo(maximum) <= 0,
                "Enter a value of at most " + maximum + '.');
    }
}
