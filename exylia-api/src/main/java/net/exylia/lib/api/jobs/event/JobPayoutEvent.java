package net.exylia.lib.api.jobs.event;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * A job is about to pay a player for an action.
 *
 * <p>Fired after every multiplier and limit, before the money and
 * experience are given: change them with {@link #setMoney} and
 * {@link #setXp}, or cancel to pay nothing. Called on the player's thread.
 *
 * @since 3.6.0
 */
public class JobPayoutEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String jobId;
    private final String action;
    private final String target;
    private double money;
    private double xp;
    private boolean cancelled;

    /**
     * Creates the event.
     *
     * @param player the player
     * @param jobId the job
     * @param action the action, upper case ({@code BREAK}, {@code KILL}, {@code FISH}...)
     * @param target what the action was on: a block, entity or item id
     * @param money the money to pay
     * @param xp the job experience to give
     */
    public JobPayoutEvent(@NotNull Player player, @NotNull String jobId, @NotNull String action, @NotNull String target, double money, double xp) {
        super(player, !Bukkit.isPrimaryThread());
        this.jobId = jobId;
        this.action = action;
        this.target = target;
        this.money = money;
        this.xp = xp;
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
     * The action, upper case ({@code BREAK}, {@code KILL}, {@code FISH}...).
     *
     * @return the value
     */
    @NotNull
    public String getAction() {
        return action;
    }

    /**
     * What the action was on: a block, entity or item id.
     *
     * @return the value
     */
    @NotNull
    public String getTarget() {
        return target;
    }

    /**
     * The money to pay.
     *
     * @return the value
     */
    public double getMoney() {
        return money;
    }

    /**
     * Changes the money to pay.
     *
     * @param money the new value
     */
    public void setMoney(double money) {
        this.money = money;
    }

    /**
     * The job experience to give.
     *
     * @return the value
     */
    public double getXp() {
        return xp;
    }

    /**
     * Changes the job experience to give.
     *
     * @param xp the new value
     */
    public void setXp(double xp) {
        this.xp = xp;
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
