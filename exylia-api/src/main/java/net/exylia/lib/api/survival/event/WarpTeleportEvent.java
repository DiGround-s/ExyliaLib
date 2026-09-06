package net.exylia.lib.api.survival.event;

import net.exylia.lib.api.survival.Warp;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player is travelling to a warp.
 *
 * <p>Fired once every check the plugin makes has passed — the world, the warp
 * being enabled and having a location, the player's permission, their cooldown
 * and their balance — and before the warmup starts. A handler therefore sees
 * only trips that would otherwise happen, and cancelling stops the countdown as
 * well as the jump rather than leaving a player standing through a warmup that
 * goes nowhere.
 *
 * <p>Nothing is charged and no cooldown is written yet: both are spent when the
 * countdown finishes, so a cancelled event costs the player nothing. It also
 * tells them nothing — the handler that refused the trip is expected to say
 * why.
 *
 * <p>An uncancelled event is not a promise of arrival. The warmup that follows
 * is cancelled if the player moves or is hurt, and the payment is only taken
 * once it survives to the end.
 *
 * <p>Called on the thread that owns the player, which on Folia is their region
 * thread rather than a single main thread. Anything touching the wider world
 * has to be scheduled.
 *
 * @since 1.1.0
 */
public class WarpTeleportEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Warp warp;

    private boolean cancelled;

    /**
     * Creates the event.
     *
     * @param player the player travelling
     * @param warp   where they are going
     */
    public WarpTeleportEvent(@NotNull Player player, @NotNull Warp warp) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.warp = warp;
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
     * @return the warp
     */
    @NotNull
    public Warp getWarp() {
        return warp;
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
