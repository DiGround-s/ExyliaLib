package net.exylia.lib.api.survival.event;

import net.exylia.lib.api.survival.Home;
import org.bukkit.Bukkit;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * A home is about to be removed.
 *
 * <p>Fired before anything is touched — the row is still in the database and
 * still in the player's cached list — so cancelling leaves the home exactly as
 * it was. The player is told nothing either way, so a handler that refuses a
 * deletion should say why itself.
 *
 * <p>The owner is a {@link UUID} rather than a {@code Player} because a home
 * can be deleted for somebody who is not online: an administrator command and
 * the plugin's own cleanup both do it. Look the player up if you need them, and
 * expect {@code null}.
 *
 * <p>Called on the thread that owns the caller, which on Folia is a region
 * thread rather than a single main thread. Anything touching the wider world
 * has to be scheduled.
 *
 * @since 1.1.0
 */
public class HomeDeleteEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final Home home;

    private boolean cancelled;

    /**
     * Creates the event.
     *
     * @param player whose home it is, who may be offline
     * @param home   the home as it stands, before the deletion
     */
    public HomeDeleteEvent(@NotNull UUID player, @NotNull Home home) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.home = home;
    }

    /**
     * Whose home it is.
     *
     * @return the owner's id, who may be offline
     */
    @NotNull
    public UUID getPlayer() {
        return player;
    }

    /**
     * The home being removed.
     *
     * @return the home, as it stands before the deletion
     */
    @NotNull
    public Home getHome() {
        return home;
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
