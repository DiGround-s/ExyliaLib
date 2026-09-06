package net.exylia.lib.api.betcore;

import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What one player has staked and taken, at one game, in one currency.
 *
 * <p>{@link #net()} is stored rather than worked out from the two beside it,
 * for the same reason a win rate is: a leaderboard of who is up has to be an
 * indexed read, and a database sorts columns rather than expressions.
 *
 * @param player     the player
 * @param playerName their name at the last save
 * @param gameId     the game these totals are for
 * @param currency   the currency they are in
 * @param wagered    everything ever put up, whether it came back or not
 * @param won        everything ever taken, before subtracting anything
 * @param lost       every stake that went to somebody else
 * @param net        {@code won - lost}: whether this player is up or down
 * @param biggestPot the largest pot they ever took
 * @param updatedAt  when the row was last written, in epoch milliseconds
 * @since 1.0.0
 */
public record BetMoney(
        @NotNull UUID player,
        @NotNull String playerName,
        @NotNull String gameId,
        @NotNull String currency,
        @NotNull BigDecimal wagered,
        @NotNull BigDecimal won,
        @NotNull BigDecimal lost,
        @NotNull BigDecimal net,
        @NotNull BigDecimal biggestPot,
        long updatedAt) {

    /**
     * Whether this player is ahead in this currency.
     *
     * @return {@code true} when they have taken more than they lost
     */
    public boolean isUp() {
        return net.signum() > 0;
    }
}
