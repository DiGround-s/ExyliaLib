package net.exylia.lib.api.jobs.event;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * A player is about to choose a perk in a job's perk tier.
 *
 * <p>Fired before the choice is stored or anything is charged. Called on the
 * player's thread.
 *
 * @since 3.6.0
 */
public class JobPerkChosenEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String jobId;
    private final int tier;
    private final String perkKey;
    private final boolean swap;
    private boolean cancelled;

    /**
     * Creates the event.
     *
     * @param player the player
     * @param jobId the job
     * @param tier the perk tier
     * @param perkKey the perk chosen
     * @param swap whether it replaces a perk already chosen in that tier
     */
    public JobPerkChosenEvent(@NotNull Player player, @NotNull String jobId, int tier, @NotNull String perkKey, boolean swap) {
        super(player, !Bukkit.isPrimaryThread());
        this.jobId = jobId;
        this.tier = tier;
        this.perkKey = perkKey;
        this.swap = swap;
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
     * The perk tier.
     *
     * @return the value
     */
    public int getTier() {
        return tier;
    }

    /**
     * The perk chosen.
     *
     * @return the value
     */
    @NotNull
    public String getPerkKey() {
        return perkKey;
    }

    /**
     * Whether it replaces a perk already chosen in that tier.
     *
     * @return the value
     */
    public boolean isSwap() {
        return swap;
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
