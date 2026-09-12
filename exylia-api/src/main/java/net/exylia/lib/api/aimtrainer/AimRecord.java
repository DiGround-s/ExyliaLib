package net.exylia.lib.api.aimtrainer;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * A player's best results for one drill.
 *
 * @param ratingSize     the size setting the best rating was set with
 * @param ratingDistance the distance setting the best rating was set with
 * @since 1.5.0
 */
public record AimRecord(@NotNull UUID uuid, @NotNull String name, @NotNull String drill, @NotNull AimDrillKind kind,
                        int sessions, int bestRating, int bestScore, double bestAccuracy, int bestStreak,
                        int mostHits, int bestCombo, long bestReactionMillis, long bestFlickMillis, double bestOnTarget,
                        double ratingSize, double ratingDistance, long totalHits, long totalShots,
                        long totalTimeMillis, long updatedAt) {
}
