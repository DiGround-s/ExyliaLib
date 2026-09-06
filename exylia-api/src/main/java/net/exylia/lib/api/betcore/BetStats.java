package net.exylia.lib.api.betcore;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * One player's record at one game.
 *
 * <p>There is a row per game and one more under {@link #ALL_GAMES}, which is
 * the same player across every game. The total is kept as its own row rather
 * than summed on demand, so an overall leaderboard is one indexed read instead
 * of a scan.
 *
 * <p>Money is not here. A stake is per currency and a streak is not, so folding
 * them together would either reset a run of wins when a player switched
 * currency or add coins to points. Ask {@link BetCoreService#money(UUID)} for
 * that half.
 *
 * @param player      the player
 * @param playerName  their name at the last save, so a board can name somebody
 *                    who is offline
 * @param gameId      the game, or {@link #ALL_GAMES}
 * @param wins        matches won
 * @param losses      matches lost
 * @param draws       matches nobody won
 * @param played      matches finished
 * @param winRate     wins as a percentage of matches played
 * @param streak      positive for a run of wins, negative for a run of losses
 * @param bestStreak  the longest run of wins ever held
 * @param updatedAt   when the row was last written, in epoch milliseconds
 * @since 1.0.0
 */
public record BetStats(
        @NotNull UUID player,
        @NotNull String playerName,
        @NotNull String gameId,
        int wins,
        int losses,
        int draws,
        int played,
        double winRate,
        int streak,
        int bestStreak,
        long updatedAt) {

    /** The game id standing for every game at once. */
    public static final String ALL_GAMES = "all";

    /**
     * Whether this row is the across-every-game total rather than one game's.
     *
     * @return {@code true} for the aggregate row
     */
    public boolean isAggregate() {
        return ALL_GAMES.equals(gameId);
    }
}
