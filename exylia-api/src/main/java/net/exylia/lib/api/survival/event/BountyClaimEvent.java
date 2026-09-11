package net.exylia.lib.api.survival.event;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;

/**
 * A player is collecting the bounties on somebody they killed.
 *
 * <p>Fired once the victim is dead and the killer is known, and before the
 * bounties are taken off the victim or a coin is paid. Every bounty on the
 * victim is collected at once, so {@link #getAmount()} is the whole pool and
 * {@link #getBounties()} how many placements made it up.
 *
 * <p>Cancelling keeps every bounty on the victim for the next kill and pays
 * nothing — the hook for refusing a kill that should not count, such as two
 * clan members or two accounts from one address trading deaths. Nobody is
 * told, because the handler that refused the claim is the only one that knows
 * why.
 *
 * <p>Called on the thread that owns the killer, which on Folia is their region
 * thread rather than a single main thread, and after the death itself.
 *
 * @since 1.3.0
 */
public class BountyClaimEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player killer;
    private final Player victim;
    private final BigDecimal amount;
    private final int bounties;

    private boolean cancelled;

    /**
     * Creates the event.
     *
     * @param killer   who is collecting
     * @param victim   whose bounties are being collected
     * @param amount   the total about to be paid
     * @param bounties how many bounties make up that total
     */
    public BountyClaimEvent(@NotNull Player killer, @NotNull Player victim,
                            @NotNull BigDecimal amount, int bounties) {
        super(!Bukkit.isPrimaryThread());
        this.killer = killer;
        this.victim = victim;
        this.amount = amount;
        this.bounties = bounties;
    }

    /**
     * The player collecting.
     *
     * @return the killer
     */
    @NotNull
    public Player getKiller() {
        return killer;
    }

    /**
     * The player whose bounties are being collected.
     *
     * @return the victim
     */
    @NotNull
    public Player getVictim() {
        return victim;
    }

    /**
     * What the killer is about to be paid.
     *
     * @return the whole pool on the victim
     */
    @NotNull
    public BigDecimal getAmount() {
        return amount;
    }

    /**
     * How many bounties make up the pool.
     *
     * @return the number of placements, at least {@code 1}
     */
    public int getBounties() {
        return bounties;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
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
