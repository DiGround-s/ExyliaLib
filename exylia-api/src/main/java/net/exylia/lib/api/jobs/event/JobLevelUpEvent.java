package net.exylia.lib.api.jobs.event;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * A player went up one or more levels in a job. Fired after the level is
 * stored; informative only.
 *
 * @since 3.6.0
 */
public class JobLevelUpEvent extends PlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String jobId;
    private final int oldLevel;
    private final int newLevel;

    /**
     * Creates the event.
     *
     * @param player the player
     * @param jobId the job
     * @param oldLevel the level before
     * @param newLevel the level now
     */
    public JobLevelUpEvent(@NotNull Player player, @NotNull String jobId, int oldLevel, int newLevel) {
        super(player, !Bukkit.isPrimaryThread());
        this.jobId = jobId;
        this.oldLevel = oldLevel;
        this.newLevel = newLevel;
    }


    /**
     * The job.
     *
     * @return the value
     */
    @NotNull
    public String getJobId() {
        return jobId;
    }

    /**
     * The level before.
     *
     * @return the value
     */
    public int getOldLevel() {
        return oldLevel;
    }

    /**
     * The level now.
     *
     * @return the value
     */
    public int getNewLevel() {
        return newLevel;
    }

    @Override
    @NotNull
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * The handler list Bukkit requires.
     *
     * @return the handler list
     */
    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
