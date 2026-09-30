package net.exylia.lib.input;

import net.exylia.lib.text.Phrases;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;

/**
 * A request for a precise, player-formatted amount.
 *
 * @since 1.31.0
 */
public final class AmountInput extends InputRequest<BigDecimal, AmountInput> {

    private BigDecimal minimum;
    private BigDecimal maximum;

    AmountInput(String pluginName, Player player, String prompt) {
        super(pluginName, player, prompt, InputParser.amount());
    }

    /** Applies an inclusive minimum amount. */
    public @NotNull AmountInput minimum(@NotNull BigDecimal minimum) {
        Inputs.require(minimum, "minimum");
        if (maximum != null && minimum.compareTo(maximum) > 0) {
            throw new InputException("minimum must not be greater than maximum");
        }
        this.minimum = minimum;
        return validate(value -> value.compareTo(minimum) >= 0,
                Phrases.tr("Enter an amount of at least {0}.", minimum));
    }

    /** Applies an inclusive maximum amount. */
    public @NotNull AmountInput maximum(@NotNull BigDecimal maximum) {
        Inputs.require(maximum, "maximum");
        if (minimum != null && minimum.compareTo(maximum) > 0) {
            throw new InputException("minimum must not be greater than maximum");
        }
        this.maximum = maximum;
        return validate(value -> value.compareTo(maximum) <= 0,
                Phrases.tr("Enter an amount of at most {0}.", maximum));
    }
}
