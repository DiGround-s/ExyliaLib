package net.exylia.lib.api.survival.event;

import net.exylia.lib.api.survival.Home;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player is travelling to one of their homes.
 *
 * <p>Fired before the plugin decides whether the trip is instant or has a
 * countdown, so cancelling stops the whole thing rather than only the jump at
 * the end of a warmup. Nothing is said to the player when it is cancelled: the
 * handler that refused the trip is expected to say why.
 *
 * <p>An uncancelled event is not a promise of arrival. What follows can be a
 * warmup the player walks out of or is hit during, and the home can point at a
 * world this server cannot resolve. Listen for a movement of your own if you
 * need to know they got there.
 *
 * <p>Called on the thread that owns the player, which on Folia is their region
 * thread rather than a single main thread. Anything touching the wider world
 * has to be scheduled.
 *
 * @since 1.0.0
 */
public class HomeTeleportEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Home home;

    private boolean cancelled;

    /**
     * Creates the event.
     *
     * @param player the player travelling
     * @param home   where they are going
     */
    public HomeTeleportEvent(@NotNull Player player, @NotNull Home home) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.home = home;
    }

    /**
     * The player travelling.
     *
     * @return the player
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }

    /**
     * Where they are going.
     *
     * @return the home
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
