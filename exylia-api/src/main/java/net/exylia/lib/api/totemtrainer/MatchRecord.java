package net.exylia.lib.api.totemtrainer;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * One participant's view of one finished duel.
 *
 * <p>Written twice per match, once from each side, because history is asked for
 * per player and a row that had to be matched on either of two columns could
 * not be read from an index. Both names are frozen on the row, so a history
 * screen names who was actually there rather than who holds the name now.
 *
 * @param matchId          the match these two rows share
 * @param player           whose view this is
 * @param opponent         who they fought
 * @param playerName       their name as it was
 * @param opponentName     the opponent's name as it was
 * @param won              whether this player took the series
 * @param modeId           which mode was played
 * @param ticks            the interval it was played at
 * @param bestOf           the length of the series
 * @param scoreFor         rounds this player took
 * @param scoreAgainst     rounds the opponent took
 * @param durationMillis   how long the duel lasted
 * @param pops             totems this player re-equipped in time
 * @param bestPopMillis    their fastest re-equip, {@code 0} for none
 * @param averagePopMillis their mean re-equip
 * @param endedAt          when it was decided, in epoch milliseconds
 * @since 1.0.0
 */
public record MatchRecord(
        @NotNull String matchId,
        @NotNull UUID player,
        @NotNull UUID opponent,
        @NotNull String playerName,
        @NotNull String opponentName,
        boolean won,
        @NotNull String modeId,
        int ticks,
        int bestOf,
        int scoreFor,
        int scoreAgainst,
        long durationMillis,
        int pops,
        long bestPopMillis,
        long averagePopMillis,
        long endedAt) {
}
