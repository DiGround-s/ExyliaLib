package net.exylia.lib.api.aimtrainer;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * One finished solo session, as the history keeps it.
 *
 * @since 1.5.0
 */
public record AimSessionRecord(@NotNull String id, @NotNull UUID player, @NotNull String drill,
                               @NotNull AimDrillKind kind, int score, int rating, double difficulty, int hits,
                               int misses, double accuracy, int bestStreak, long averageFlickMillis,
                               long averageReactionMillis, double onTarget, double precision, int averagePing,
                               long durationMillis, boolean personalBest, long playedAt) {
}
