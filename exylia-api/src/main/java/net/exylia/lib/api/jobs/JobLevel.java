package net.exylia.lib.api.jobs;

import org.jetbrains.annotations.NotNull;

/**
 * Where a player stands in one job.
 *
 * @param jobId    the job
 * @param level    the level, from {@code 1}
 * @param xp       experience earned towards the next level
 * @param prestige prestige stars, {@code 0} before the first prestige
 * @since 3.6.0
 */
public record JobLevel(@NotNull String jobId, int level, double xp, int prestige) {
}
