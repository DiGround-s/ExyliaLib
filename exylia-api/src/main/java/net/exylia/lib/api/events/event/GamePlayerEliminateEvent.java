package net.exylia.lib.api.events.event;

import net.exylia.lib.api.events.GameEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player is out of the game, and stays in the event to watch the rest of it.
 *
 * <p>Fired once the game has taken them out — killed, fallen, caught, beaten in
 * a round — and made them a spectator, and before it checks whether that
 * leaves a winner. {@link #getEvent()} already counts them out of the alive
 * players.
 *
 * <p>A death the game respawns them from is not an elimination and does not
 * fire this, and neither does finishing a course, which takes a winner out of
 * the others' way without putting them out.
 *
 * <p>Deliberately not cancellable. Every minigame decides elimination its own
 * way and has already acted on it by now — a death recorded, a round
 * rearranged, a team's count changed — so a refusal here could only leave the
 * game out of step with itself.
 *
 * <p>Called on the thread the elimination was decided on: the player's own for
 * a death, the global thread for a rule the game checks on its clock. On Folia
 * neither is a single main thread, so anything touching the wider world has to
 * be scheduled.
 *
 * @since 1.3.0
 */
public class GamePlayerEliminateEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final GameEvent event;

    /**
     * Creates the event.
     *
     * @param player the player eliminated
     * @param event  the run they were eliminated from
     */
    public GamePlayerEliminateEvent(@NotNull Player player, @NotNull GameEvent event) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.event = event;
    }

    /**
     * The player eliminated.
     *
     * @return the player
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }

    /**
     * The run, as it was once they were out.
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
