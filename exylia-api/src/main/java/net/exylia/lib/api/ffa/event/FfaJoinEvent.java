package net.exylia.lib.api.ffa.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player is about to enter an arena, to fight or to watch.
 *
 * <p>Fired once every check the plugin makes has passed — the arena is enabled,
 * the player may enter it, there is room — and before anything is taken from
 * them: no session claim, no inventory snapshot, no teleport. Cancelling here
 * therefore leaves the player exactly as they were, which is why this is the
 * one FFA event that can be refused. The plugin says nothing to a player whose
 * join was cancelled; telling them why is the canceller's job.
 *
 * <p>Synchronous, on the thread that owns the player.
 *
 * @since 1.3.0
 */
public class FfaJoinEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String arenaId;
    private final boolean spectating;
    private boolean cancelled;

    /**
     * Creates the event.
     *
     * @param player     the player entering
     * @param arenaId    the arena they are entering
     * @param spectating whether they are entering to watch
     */
    public FfaJoinEvent(@NotNull Player player, @NotNull String arenaId, boolean spectating) {
        this.player = player;
        this.arenaId = arenaId;
        this.spectating = spectating;
    }

    /**
     * The player entering.
     *
     * @return the player
     */
    @NotNull
    public Player player() {
        return player;
    }

    /**
     * The arena they are entering.
     *
     * @return the arena id
     */
    @NotNull
    public String arenaId() {
        return arenaId;
    }

    /**
     * Whether they are entering to watch rather than to fight.
     *
     * @return {@code true} for a spectator
     */
    public boolean spectating() {
        return spectating;
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
