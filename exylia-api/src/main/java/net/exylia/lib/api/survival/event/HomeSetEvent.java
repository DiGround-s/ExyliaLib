package net.exylia.lib.api.survival.event;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player is creating or moving a home.
 *
 * <p>Fired once the plugin knows the call would otherwise go through: the world
 * is not blacklisted and, for a new home, the player is under their limit. A
 * handler therefore never sees a set that was going to be refused anyway, and
 * cancelling is the only thing that can still stop it.
 *
 * <p>Cancelling writes nothing and says nothing: the player keeps the homes
 * they had and is told nothing, because the handler that refused the set is the
 * only one that knows why and is expected to say so itself.
 *
 * <p>Called on the thread that owns the player, which on Folia is their region
 * thread rather than a single main thread. Anything touching the wider world
 * has to be scheduled.
 *
 * @since 1.0.0
 */
public class HomeSetEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String name;
    private final Location location;

    private boolean cancelled;

    /**
     * Creates the event.
     *
     * @param player   whose home it is
     * @param name     what they are calling it
     * @param location where it will point
     */
    public HomeSetEvent(@NotNull Player player, @NotNull String name, @NotNull Location location) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.name = name;
        this.location = location;
    }

    /**
     * The player setting the home.
     *
     * @return the player
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }

    /**
     * What the home is called.
     *
     * <p>The home's id rather than a display name: setting a home whose name a
     * player already used moves that one rather than adding a second.
     *
     * @return the home name
     */
    @NotNull
    public String getName() {
        return name;
    }

    /**
     * Where the home will point.
     *
     * <p>Not necessarily where the player is standing: the set can come from
     * another plugin through
     * {@link net.exylia.lib.api.survival.SurvivalService#setHome(Player, String, Location)}.
     *
     * @return the location
     */
    @NotNull
    public Location getLocation() {
        return location;
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
