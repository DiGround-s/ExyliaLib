package net.exylia.lib.util.upgrade;

import net.exylia.lib.economy.EconomyResponse;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;

/**
 * How buying a level of an {@link Upgrade} went.
 *
 * @param outcome     what happened
 * @param level       the level reached on {@link Outcome#BOUGHT}, else the one that was asked for
 * @param price       what that level costs
 * @param shortfall   how much more the player needed, on {@link Outcome#INSUFFICIENT_FUNDS}; else zero
 * @param requirement the requirement short, on {@link Outcome#LOCKED}; else {@code null}
 * @param needed      that requirement's minimum; else 0
 * @param response    the economy's answer when a charge was made, else {@code null}
 * @since 1.381.0
 */
public record Purchase(@NotNull Outcome outcome, int level, @NotNull BigDecimal price, @NotNull BigDecimal shortfall,
                       @Nullable String requirement, int needed, @Nullable EconomyResponse response) {

    /** What happened. Only {@link #BOUGHT} took money (or was free). */
    public enum Outcome {
        BOUGHT,
        MAXED,
        LOCKED,
        INSUFFICIENT_FUNDS,
        /** A purchase of the same upgrade for the same player is still being charged. */
        BUSY,
        /** The economy refused or could not serve; nothing was taken. */
        FAILED
    }

    public boolean bought() {
        return outcome == Outcome.BOUGHT;
    }
}
