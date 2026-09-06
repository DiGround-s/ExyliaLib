package net.exylia.lib.api.practice;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * One player's side of one finished match.
 *
 * <p>Written once, when the match ends, and read back by the history screens.
 * One match produces one of these per player in it, so the same fight is a
 * {@link MatchResult#WIN} here and a {@link MatchResult#LOSS} in the opponent's
 * copy — join them on {@link #matchId} to see both halves.
 *
 * <p>Rows are pruned after the server's retention window. Everything derived
 * from a match was already committed to {@link PlayerStats} when it ended, so a
 * pruned row costs nothing but the ability to look that one fight up again.
 *
 * @param matchId        the match, shared by every player's copy of it
 * @param playerUuid     whose side of it this is
 * @param opponentName   who they fought, by the name that player had then
 * @param kitId          the kit fought with
 * @param arenaId        the arena fought in
 * @param result         how it went for this player
 * @param ranked         whether it moved ELO
 * @param eloBefore      their ELO on this kit going in
 * @param eloAfter       their ELO on this kit coming out
 * @param eloChange      the difference, negative on a loss
 * @param playedAt       when it ended, as epoch milliseconds
 * @param durationMillis how long it lasted
 * @param kills          kills in this match
 * @param deaths         deaths in this match
 * @param damageDealt    damage dealt in this match
 * @param bestCombo      the longest uninterrupted hit streak they landed in it
 * @since 1.0.0
 */
public record MatchRecord(
        @NotNull String matchId,
        @NotNull UUID playerUuid,
        @NotNull String opponentName,
        @NotNull String kitId,
        @NotNull String arenaId,
        @NotNull MatchResult result,
        boolean ranked,
        int eloBefore,
        int eloAfter,
        int eloChange,
        long playedAt,
        long durationMillis,
        int kills,
        int deaths,
        double damageDealt,
        int bestCombo) {
}
