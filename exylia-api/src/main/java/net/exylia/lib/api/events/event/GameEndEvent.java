package net.exylia.lib.api.events.event;

import net.exylia.lib.api.events.GameEndReason;
import net.exylia.lib.api.events.GameEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Set;
import java.util.UUID;

/**
 * A run is over.
 *
 * <p>Fired once per run. A {@link GameEndReason#FINISHED} run fires it once its
 * winners have been decided and announced; a {@link GameEndReason#STOPPED} one
 * fires it as it is stopped. Either way it comes before anybody is sent home,
 * so {@link #getEvent()} still lists everybody who was in it. A finished run
 * keeps them a few seconds longer to show the result, and the participation
 * rewards run as they are let go.
 *
 * <p>Not cancellable: the result has already been broadcast to the server, and
 * a stop is an admin's or the plugin's decision that nothing downstream should
 * be able to overrule.
 *
 * <p>Called on the global thread for a game decided on the clock, or on the
 * thread that ended or stopped it. On Folia neither is a single main thread, so
 * anything touching the wider world has to be scheduled.
 *
 * @since 1.3.0
 */
public class GameEndEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final GameEvent event;
    private final Set<UUID> winners;
    private final GameEndReason reason;

    /**
     * Creates the event.
     *
     * @param event   the run, as it ended
     * @param winners who won it
     * @param reason  why it ended
     */
    public GameEndEvent(@NotNull GameEvent event, @NotNull Set<UUID> winners,
                        @NotNull GameEndReason reason) {
        super(!Bukkit.isPrimaryThread());
        this.event = event;
        this.winners = Set.copyOf(winners);
        this.reason = reason;
    }

    /**
     * The run, as it was when it ended.
     *
     * @return a snapshot of the run
     */
    @NotNull
    public GameEvent getEvent() {
        return event;
    }

    /**
     * Who won it.
     *
     * <p>More than one for a team game or a shared result, and not necessarily
     * still online.
     *
     * @return the winners, empty for a stopped run and for a finished one
     *         nobody won
     */
    @NotNull
    @Unmodifiable
    public Set<UUID> getWinners() {
        return winners;
    }

    /**
     * Why it ended.
     *
     * @return the reason
     */
    @NotNull
    public GameEndReason getReason() {
        return reason;
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
