package net.exylia.lib.api.jobs.event;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * A player is about to leave a job.
 *
 * <p>Fired after the plugin's own checks and before anything is written.
 * Called on the player's thread.
 *
 * @since 3.6.0
 */
public class JobLeaveEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String jobId;
    private boolean cancelled;

    /**
     * Creates the event.
     *
     * @param player the player
     * @param jobId the job
     */
    public JobLeaveEvent(@NotNull Player player, @NotNull String jobId) {
        super(player, !Bukkit.isPrimaryThread());
        this.jobId = jobId;
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
