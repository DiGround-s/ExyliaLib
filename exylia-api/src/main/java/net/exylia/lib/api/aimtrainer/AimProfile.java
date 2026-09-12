package net.exylia.lib.api.aimtrainer;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * A player's lifetime numbers and duel record.
 *
 * @param totalRating the sum of their best rating in every drill, which the overall board ranks
 * @param accuracy    lifetime hits over shots, 0 to 100
 * @since 1.5.0
 */
public record AimProfile(@NotNull UUID uuid, @NotNull String name, int matches, int wins, int losses,
                         double winrate, int rounds, int roundWins, int roundLosses, int currentStreak,
                         int bestStreak, int totalRating, int sessions, long totalHits, long totalShots,
                         double accuracy, long totalTimeMillis, long bestFlickMillis, long bestReactionMillis,
                         long updatedAt) {
}
