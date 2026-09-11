package net.exylia.lib.api.clans.event;

import net.exylia.lib.api.clans.ClanClaim;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player is claiming land for their clan.
 *
 * <p>Fired once every check has passed — the size, overlap with other clans,
 * the separation between them, WorldGuard regions and the clan bank — and
 * before anything is charged or written. A handler therefore never sees a
 * claim that was going to be refused anyway, and cancelling is the only thing
 * that can still stop it.
 *
 * <p>Cancelling charges nothing, draws nothing and says nothing, and the
 * player keeps their claiming session for another try. The handler that refused
 * the land is expected to tell them why.
 *
 * <p>Only a new claim fires this. Resizing a clan's existing claim does not,
 * because it cannot reach land the clan did not already own.
 *
 * <p>Called on the thread that owns the player, which on Folia is their region
 * thread rather than a single main thread. Anything touching the wider world
 * has to be scheduled.
 *
 * @since 1.3.0
 */
public class ClanClaimEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final ClanClaim claim;
    private final double price;

    private boolean cancelled;

    /**
     * Creates the event.
     *
     * @param player who is claiming
     * @param claim  the claim as it will be stored
     * @param price  what the clan bank will be charged
     */
    public ClanClaimEvent(@NotNull Player player, @NotNull ClanClaim claim, double price) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.claim = claim;
        this.price = price;
    }

    /**
     * The player claiming the land.
     *
     * @return the player
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }

    /**
     * The land being claimed.
     *
     * <p>Its id is the one it will be stored under, but nothing resolves it
     * until the event has passed uncancelled.
     *
     * @return the claim
     */
    @NotNull
    public ClanClaim getClaim() {
        return claim;
    }

    /**
     * What the clan bank will be charged.
     *
     * @return the price
     */
    public double getPrice() {
        return price;
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
