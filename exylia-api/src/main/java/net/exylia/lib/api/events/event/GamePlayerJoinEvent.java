package net.exylia.lib.api.events.event;

import net.exylia.lib.api.events.GameEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player is about to join a run to play it.
 *
 * <p>Fired once every check the plugin makes has passed — the run is taking
 * players and has room, the player is free, standing in a world events may be
 * joined from, and inscribed where the event asks for it — and before anything
 * is taken from them: no session claim, no inventory saved, no teleport.
 * Cancelling therefore leaves the player exactly as they were. Whatever the
 * path that asked says about a refusal is generic, so a handler that cancels
 * should tell the player why itself.
 *
 * <p>Only joins to play are asked. Watching a run, and an admin forcing a player
 * in, go around this — and {@code /events join} follows a refused join by
 * letting the player watch instead, a cancelled one included.
 *
 * <p>Called on the thread that owns the player, which on Folia is their region
 * thread rather than a single main thread. Anything touching the wider world
 * has to be scheduled.
 *
 * @since 1.3.0
 */
public class GamePlayerJoinEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final GameEvent event;

    private boolean cancelled;

    /**
     * Creates the event.
     *
     * @param player the player joining
     * @param event  the run they are joining
     */
    public GamePlayerJoinEvent(@NotNull Player player, @NotNull GameEvent event) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.event = event;
    }

    /**
     * The player joining.
     *
     * @return the player
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }

    /**
     * The run they are joining, as it was before they joined.
     *
     * @return a snapshot of the run, not yet counting them
     */
    @NotNull
    public GameEvent getEvent() {
        return event;
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
