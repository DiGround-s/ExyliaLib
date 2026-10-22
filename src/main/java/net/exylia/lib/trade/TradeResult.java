package net.exylia.lib.trade;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * What a trade ended with.
 *
 * <p>When {@link #outcome()} is {@link TradeOutcome#CONFIRMED} each side's
 * items have already been taken out of the window and belong to the caller,
 * who must hand them somewhere &mdash; {@code Escrow.holdItems} for a bet,
 * {@code Escrow.giveItems} to swap them for a plain trade. Otherwise both
 * lists are empty: the items went back to their owners.
 *
 * @param outcome      how it ended
 * @param first        the player passed first to {@code open}
 * @param firstItems   what they put in; empty unless confirmed
 * @param second       the other player
 * @param secondItems  what they put in; empty unless confirmed
 * @param cancelledBy  the player whose close, quit or death ended it; {@code null} otherwise
 * @since 1.349.0
 */
public record TradeResult(@NotNull TradeOutcome outcome,
                          @NotNull UUID first, @NotNull List<ItemStack> firstItems,
                          @NotNull UUID second, @NotNull List<ItemStack> secondItems,
                          @Nullable UUID cancelledBy) {

    public TradeResult {
        firstItems = List.copyOf(firstItems);
        secondItems = List.copyOf(secondItems);
    }

    /** Whether the items changed hands. */
    public boolean confirmed() {
        return outcome.confirmed();
    }

    /**
     * What one of the two players put in.
     *
     * @param player either player
     * @return their items; empty for anyone else or when not confirmed
     */
    public @NotNull List<ItemStack> itemsOf(@NotNull UUID player) {
        return player.equals(first) ? firstItems : player.equals(second) ? secondItems : List.of();
    }
}
