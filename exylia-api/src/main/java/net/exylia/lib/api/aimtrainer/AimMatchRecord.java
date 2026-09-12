package net.exylia.lib.api.aimtrainer;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * One finished duel from one participant's side.
 *
 * @param points         the participant's points over the whole duel
 * @param opponentPoints the other side's
 * @since 1.5.0
 */
public record AimMatchRecord(@NotNull String matchId, @NotNull UUID player, @NotNull UUID opponent,
                             @NotNull String playerName, @NotNull String opponentName, boolean won,
                             @NotNull String drill, int bestOf, int scoreFor, int scoreAgainst, long durationMillis,
                             int hits, double accuracy, int points, int opponentPoints, long endedAt) {
}
