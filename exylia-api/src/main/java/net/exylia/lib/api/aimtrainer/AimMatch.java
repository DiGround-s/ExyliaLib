package net.exylia.lib.api.aimtrainer;

import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;

/**
 * A duel as it was when asked about: a copy, not a live view.
 *
 * @param scoreA       rounds won by A
 * @param scoreB       rounds won by B
 * @param roundsPlayed rounds played so far, draws included
 * @param winner       the winner once decided
 * @since 1.5.0
 */
public record AimMatch(@NotNull UUID id, @NotNull String arenaId, @NotNull UUID playerA, @NotNull UUID playerB,
                       @NotNull String nameA, @NotNull String nameB, int scoreA, int scoreB, @NotNull String drillId,
                       int bestOf, int roundsToWin, int roundsPlayed, @NotNull AimMatchState state,
                       @NotNull Optional<UUID> winner, long createdAt, long startedAt, long endedAt) {
}
