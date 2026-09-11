package net.exylia.lib.api.clans.event;

import net.exylia.lib.api.clans.ClanClaim;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A clan's land is no longer claimed.
 *
 * <p>Fired once the claim is gone — its WorldGuard region, its pillars and its
 * row — so the land it covered is wild by the time a handler runs. Covers a
 * member unclaiming, the clan disbanding, and an administrator disbanding it;
 * on a disband it comes just before {@link ClanDisbandEvent}.
 *
 * <p>A notification rather than a request, because a disband cannot keep its
 * land.
 *
 * <p>Called on the thread that removed the claim, which on Folia is a region
 * thread rather than a single main thread. Anything touching the wider world
 * has to be scheduled.
 *
 * @since 1.3.0
 */
public class ClanUnclaimEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final ClanClaim claim;

    /**
     * Creates the event.
     *
     * @param claim the claim as it was before it was removed
     */
    public ClanUnclaimEvent(@NotNull ClanClaim claim) {
        super(!Bukkit.isPrimaryThread());
        this.claim = claim;
    }

    /**
     * The land that was released.
     *
     * @return the claim as it was, which no longer resolves
     */
    @NotNull
    public ClanClaim getClaim() {
        return claim;
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
