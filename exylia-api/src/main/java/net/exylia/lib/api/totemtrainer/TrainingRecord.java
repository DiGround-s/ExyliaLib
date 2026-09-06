package net.exylia.lib.api.totemtrainer;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * A player's best results for one mode at one tick interval.
 *
 * <p>Kept per speed rather than per mode because a 10-tick run and a 30-tick
 * run of the same mode are not the same achievement. A board over every speed
 * therefore has several rows per player and shows the best of them, which is
 * why every field here is a best rather than a total.
 *
 * @param player                the player
 * @param name                  their name at the last save
 * @param modeId                which mode
 * @param ticks                 the interval these bests were set at
 * @param sessions              sessions finished at this mode and speed
 * @param bestPops              the most totems popped in one session
 * @param bestAverageMillis     the lowest average reaction, {@code 0} for none
 * @param bestStreak            the longest run without a miss
 * @param longestSurvivalMillis the longest a session lasted
 * @param bestScore             the highest score, 0 to 100
 * @param updatedAt             when the row was last written, in epoch milliseconds
 * @since 1.0.0
 */
public record TrainingRecord(
        @NotNull UUID player,
        @NotNull String name,
        @NotNull String modeId,
        int ticks,
        int sessions,
        int bestPops,
        long bestAverageMillis,
        int bestStreak,
        long longestSurvivalMillis,
        int bestScore,
        long updatedAt) {
}
