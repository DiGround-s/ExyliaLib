package net.exylia.lib.api.betcore.event;

import net.exylia.lib.api.betcore.BetMatch;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A pot is about to be paid.
 *
 * <p>Fired before the money moves, so the house's share can be changed by a
 * listener — a rank that pays no commission is a plugin listening here.
 *
 * <p><b>Cancelling refunds both stakes rather than voiding the pot.</b> The
 * money is already out of both balances, so "do not pay the winner" cannot mean
 * "keep it": the only safe reading is that the match did not count.
 *
 * @since 1.0.0
 */
public class BetPayoutEvent extends BetEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final BetMatch match;
    private final UUID winner;
    private final String currency;
    private final BigDecimal pot;

    private BigDecimal houseCut;
    private boolean cancelled;

    public BetPayoutEvent(@NotNull BetMatch match, @NotNull UUID winner, @NotNull String currency,
                          @NotNull BigDecimal pot, @NotNull BigDecimal houseCut) {
        this.match = match;
        this.winner = winner;
        this.currency = currency;
        this.pot = pot;
        this.houseCut = houseCut;
    }

    public @NotNull BetMatch getMatch() {
        return match;
    }

    public @NotNull UUID getWinner() {
        return winner;
    }

    public @NotNull String getCurrency() {
        return currency;
    }

    /** Both stakes together. */
    public @NotNull BigDecimal getPot() {
        return pot;
    }

    public @NotNull BigDecimal getHouseCut() {
        return houseCut;
    }

    /** Changes what the house keeps. Clamped to the pot before it is used. */
    public void setHouseCut(@NotNull BigDecimal houseCut) {
        this.houseCut = houseCut;
    }

    /** What the winner is paid, given the cut as it stands. */
    public @NotNull BigDecimal getPayout() {
        return pot.subtract(houseCut.max(BigDecimal.ZERO).min(pot));
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
