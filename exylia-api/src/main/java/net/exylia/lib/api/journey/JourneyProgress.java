package net.exylia.lib.api.journey;

import org.jetbrains.annotations.NotNull;

/**
 * Where a player is in one running journey.
 *
 * @param journeyId the journey
 * @param stepIndex the current step, from {@code 0}
 * @param stepCount how many steps the journey has
 * @param stepId    the current step's id
 * @param progress  how much of the current step's objective is done
 * @param goal      how much it needs; {@code 1} for an objective that is done or not
 * @since 3.4.0
 */
public record JourneyProgress(@NotNull String journeyId, int stepIndex, int stepCount, @NotNull String stepId,
                              int progress, int goal) {
}
