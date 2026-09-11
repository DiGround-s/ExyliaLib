package net.exylia.lib.api.events.event;

import net.exylia.lib.api.events.GameEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * The countdown is over and the game is being played.
 *
 * <p>Fired once the players are in the arena and the clock is running, so
 * {@link #getEvent()} reads {@link net.exylia.lib.api.events.GameState#PLAYING}
 * and lists everybody who made it in.
 *
 * <p>Deliberately not cancellable. By now the arena is set up, the players have
 * been moved and a game has been counted against each of them, and there is no
 * earlier state left to put them back into. Hold a run back with
 * {@link GameStartEvent} instead, before it opens.
 *
 * <p>Called on the global thread when the countdown ran out, or on the thread of
 * whoever forced the start. On Folia neither is a single main thread, so
 * anything touching the wider world has to be scheduled.
 *
 * @since 1.3.0
 */
public class GameBeginEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final GameEvent event;

    /**
     * Creates the event.
     *
     * @param event the run, as it began
     */
    public GameBeginEvent(@NotNull GameEvent event) {
        super(!Bukkit.isPrimaryThread());
        this.event = event;
    }

    /**
     * The run, as it was the moment play began.
     *
     * @return a snapshot of the run
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
