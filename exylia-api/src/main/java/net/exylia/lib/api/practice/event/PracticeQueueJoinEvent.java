package net.exylia.lib.api.practice.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player is joining a queue.
 *
 * <p>Fired once practice has agreed they may — they are free, their statistics
 * have loaded, the kit is queueable — and before they are added, so cancelling
 * it keeps them out. Nobody is told anything when it is cancelled; say why
 * yourself.
 *
 * <p>Fired per kit, since a player may wait in several queues at once, and
 * whichever way they got there: a menu, a command, a requeue, or
 * {@link net.exylia.lib.api.practice.PracticeService#joinQueue}. Not fired for
 * the click that takes them out of a queue they were already in, which is the
 * same button.
 *
 * @since 1.3.0
 */
public class PracticeQueueJoinEvent extends PracticeEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final String kitId;
    private final boolean ranked;

    private boolean cancelled;

    public PracticeQueueJoinEvent(@NotNull Player player, @NotNull String kitId, boolean ranked) {
        this.player = player;
        this.kitId = kitId;
        this.ranked = ranked;
    }

    /** Who is joining. */
    public @NotNull Player player() {
        return player;
    }

    /** The kit they want a match on. */
    public @NotNull String kitId() {
        return kitId;
    }

    /** Whether the match they would get moves ELO. */
    public boolean ranked() {
        return ranked;
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
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
