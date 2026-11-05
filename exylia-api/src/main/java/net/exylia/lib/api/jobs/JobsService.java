package net.exylia.lib.api.jobs;

import net.exylia.lib.api.ExyliaAPI;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Jobs in ExyliaJobs: which jobs exist and where a player stands in them.
 *
 * <pre>{@code
 * int mining = ExyliaAPI.get(JobsService.class)
 *         .map(jobs -> jobs.level(player.getUniqueId(), "miner"))
 *         .orElse(0);
 * }</pre>
 *
 * <p>Registered with Bukkit's {@link org.bukkit.plugin.ServicesManager} when
 * ExyliaJobs enables. Reach it through {@link ExyliaAPI#get(Class)}. Progress
 * is read from memory, so it is known for online players; an offline player
 * answers as having no jobs. Every method is safe from any thread.
 *
 * <p>What happens in a job is told through the events in
 * {@code net.exylia.lib.api.jobs.event}.
 *
 * @since 3.6.0
 */
public interface JobsService {

    /**
     * Every job this server knows, enabled or not.
     *
     * @return the ids, in the order the server lists them
     */
    @NotNull List<String> jobIds();

    /**
     * The jobs a player has joined.
     *
     * @param player the player
     * @return one entry per joined job; empty when none, or when the player is offline
     */
    @NotNull List<JobLevel> jobsOf(@NotNull UUID player);

    /**
     * A player's level in one job.
     *
     * @param player the player
     * @param jobId  the job
     * @return the level, or {@code 0} when the player has not joined it (or is offline)
     */
    int level(@NotNull UUID player, @NotNull String jobId);
}
