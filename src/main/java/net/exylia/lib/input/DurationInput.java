package net.exylia.lib.input;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;

/**
 * A request for a duration such as {@code 30s} or {@code 1h30m}.
 *
 * @since 1.31.0
 */
public final class DurationInput extends InputRequest<Duration, DurationInput> {

    private Duration minimum;
    private Duration maximum;

    DurationInput(String pluginName, Player player, String prompt) {
        super(pluginName, player, prompt, InputParser.duration());
    }

    /** Applies an inclusive shortest duration. */
    public @NotNull DurationInput atLeast(@NotNull Duration minimum) {
        Inputs.require(minimum, "minimum");
        if (minimum.isNegative()) {
            throw new InputException("minimum duration must not be negative");
        }
        if (maximum != null && minimum.compareTo(maximum) > 0) {
            throw new InputException("minimum duration must not exceed maximum duration");
        }
        this.minimum = minimum;
        return validate(value -> value.compareTo(minimum) >= 0,
                "Enter a duration of at least " + minimum + '.');
    }

    /** Applies an inclusive longest duration. */
    public @NotNull DurationInput atMost(@NotNull Duration maximum) {
        Inputs.require(maximum, "maximum");
        if (maximum.isNegative()) {
            throw new InputException("maximum duration must not be negative");
        }
        if (minimum != null && minimum.compareTo(maximum) > 0) {
            throw new InputException("minimum duration must not exceed maximum duration");
        }
        this.maximum = maximum;
        return validate(value -> value.compareTo(maximum) <= 0,
                "Enter a duration of at most " + maximum + '.');
    }
}
