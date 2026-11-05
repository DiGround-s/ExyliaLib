package net.exylia.lib.api.jobs.event;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * A player is about to prestige a job.
 *
 * <p>Fired after the plugin's checks and before the level is reset. Called
 * on the player's thread.
 *
 * @since 3.6.0
 */
public class JobPrestigeEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String jobId;
    private final int newStars;
    private boolean cancelled;

    /**
     * Creates the event.
     *
     * @param player the player
     * @param jobId the job
     * @param newStars the prestige stars the player will have
     */
    public JobPrestigeEvent(@NotNull Player player, @NotNull String jobId, int newStars) {
        super(player, !Bukkit.isPrimaryThread());
        this.jobId = jobId;
        this.newStars = newStars;
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
     * The prestige stars the player will have.
     *
     * @return the value
     */
    public int getNewStars() {
        return newStars;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
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
