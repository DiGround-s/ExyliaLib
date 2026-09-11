package net.exylia.lib.api.events.event;

import net.exylia.lib.api.events.GameEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player has left the run they were in, playing or watching.
 *
 * <p>Fired after they are out, so {@link #getEvent()} no longer lists them. A
 * leave command, a menu, another plugin evicting them, an API call and
 * disconnecting all count. Being eliminated does not — an eliminated player
 * stays in the event to watch — and neither does the run ending, which lets
 * everybody go at once and is {@link GameEndEvent}'s to report.
 *
 * <p>A player who was the last one in a run that had not begun stops it by
 * leaving, and that run's {@link GameEndEvent} arrives before this one.
 *
 * <p>Called on the thread that owns the player, which on Folia is their region
 * thread rather than a single main thread. Anything touching the wider world
 * has to be scheduled.
 *
 * @since 1.3.0
 */
public class GamePlayerLeaveEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final GameEvent event;

    /**
     * Creates the event.
     *
     * @param player the player who left
     * @param event  the run they left
     */
    public GamePlayerLeaveEvent(@NotNull Player player, @NotNull GameEvent event) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.event = event;
    }

    /**
     * The player who left.
     *
     * <p>Still the live player while a disconnect is being handled, and gone
     * right after it.
     *
     * @return the player
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }

    /**
     * The run they left, as it was once they were out of it.
     *
     * @return a snapshot of the run, no longer counting them
     */
    @NotNull
    public GameEvent getEvent() {
        return event;
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
