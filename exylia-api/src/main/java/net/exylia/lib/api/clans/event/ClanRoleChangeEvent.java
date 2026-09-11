package net.exylia.lib.api.clans.event;

import net.exylia.lib.api.clans.ClanRole;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * A member's role has changed.
 *
 * <p>Fired once the new role is written, from the one place every rank change
 * goes through, so none is missed: a promotion or demotion, the role menu,
 * {@link net.exylia.lib.api.clans.ClansService#setRole}, a leadership transfer
 * re-ranking the old and new leader, and the members of a deleted role falling
 * back to another. Who made the change is not known at that point, and is not
 * reported.
 *
 * <p>The player is a {@link UUID} rather than a {@code Player} because ranks
 * change from menus while the member is offline. Look them up if you need
 * them, and expect {@code null}.
 *
 * <p>Called on the thread that made the change, which on Folia is a region
 * thread rather than a single main thread. Anything touching the wider world
 * has to be scheduled.
 *
 * @since 1.3.0
 */
public class ClanRoleChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final ClanRole previous;
    private final ClanRole role;

    /**
     * Creates the event.
     *
     * @param player   whose role changed, who may be offline
     * @param previous the role they had
     * @param role     the role they have now
     */
    public ClanRoleChangeEvent(@NotNull UUID player, @NotNull ClanRole previous, @NotNull ClanRole role) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.previous = previous;
        this.role = role;
    }

    /**
     * The member whose role changed.
     *
     * @return their id, who may be offline
     */
    @NotNull
    public UUID getPlayer() {
        return player;
    }

    /**
     * The role they had.
     *
     * <p>Can be a role that is being deleted, and no longer resolves by the time
     * a handler asks the service about it.
     *
     * @return the previous role
     */
    @NotNull
    public ClanRole getPrevious() {
        return previous;
    }

    /**
     * The role they have now.
     *
     * @return the new role
     */
    @NotNull
    public ClanRole getRole() {
        return role;
    }

    /**
     * Whether the change moved them up.
     *
     * @return {@code true} when the new role outweighs the previous one
     */
    public boolean isPromotion() {
        return role.weight() > previous.weight();
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
