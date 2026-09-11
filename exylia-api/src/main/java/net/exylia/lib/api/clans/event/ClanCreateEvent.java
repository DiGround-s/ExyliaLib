package net.exylia.lib.api.clans.event;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player is founding a clan.
 *
 * <p>Fired once the plugin knows the creation would otherwise go through: the
 * player is clanless, the name is valid and free, and the server has default
 * roles to hand the new clan. A handler therefore never sees a creation that
 * was going to be refused anyway, and an uncancelled event is followed by the
 * clan existing before the call that fired it returns.
 *
 * <p>Cancelling writes nothing and says nothing: the handler that refused the
 * clan is the only one that knows why, and is expected to tell the player.
 *
 * <p>Called on the thread that owns the player, which on Folia is their region
 * thread rather than a single main thread. Anything touching the wider world
 * has to be scheduled.
 *
 * @since 1.3.0
 */
public class ClanCreateEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String name;

    private boolean cancelled;

    /**
     * Creates the event.
     *
     * @param player who is founding the clan, and will lead it
     * @param name   the name the clan will go by
     */
    public ClanCreateEvent(@NotNull Player player, @NotNull String name) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.name = name;
    }

    /**
     * The player founding the clan.
     *
     * @return the future leader
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }

    /**
     * The name the clan will go by.
     *
     * @return the name, exactly as the player typed it
     */
    @NotNull
    public String getName() {
        return name;
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
