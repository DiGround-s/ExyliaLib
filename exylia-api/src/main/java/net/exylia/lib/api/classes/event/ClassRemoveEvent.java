package net.exylia.lib.api.classes.event;

import net.exylia.lib.api.classes.PlayerClass;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player has left their class.
 *
 * <p>Fired once the class is taken off — passive effects, energy, marks and
 * ability cooldowns with it — so
 * {@link net.exylia.lib.api.classes.ClassesService#classOf} already answers
 * empty. Covers every way out: a piece of armor coming off, dying, leaving the
 * server, a reload, and
 * {@link net.exylia.lib.api.classes.ClassesService#remove}. A server shutting
 * down ends every class without firing it.
 *
 * <p>A notification rather than a request, because most of those exits cannot
 * be refused. To keep somebody in a class, keep their armor on.
 *
 * <p>Called on the thread that owns the player, which on Folia is their region
 * thread rather than a single main thread. The player can be on their way out
 * of the server, so check {@link Player#isOnline()} before messaging them.
 *
 * @since 1.3.0
 */
public class ClassRemoveEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final PlayerClass playerClass;

    /**
     * Creates the event.
     *
     * @param player      who left the class
     * @param playerClass the class they were in
     */
    public ClassRemoveEvent(@NotNull Player player, @NotNull PlayerClass playerClass) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.playerClass = playerClass;
    }

    /**
     * The player who left the class.
     *
     * @return the player, who may be disconnecting
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }

    /**
     * The class they were in.
     *
     * @return the class
     */
    @NotNull
    public PlayerClass getPlayerClass() {
        return playerClass;
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
