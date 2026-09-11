package net.exylia.lib.api.practice.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Set;

/**
 * A player stopped waiting without getting a match.
 *
 * <p>Fired after they are out. Not fired when the queue pairs them: a paired
 * player is taken out of every queue they were in at once, and is named by the
 * {@link PracticeMatchCreateEvent} that follows instead.
 *
 * @since 1.3.0
 */
public class PracticeQueueLeaveEvent extends PracticeEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    /**
     * Why they stopped waiting.
     *
     * @since 1.3.0
     */
    public enum Reason {
        /** They left, from a menu, a command, an item, or another plugin asking. */
        LEFT,
        /** They logged out. The player object is on its way out with them. */
        DISCONNECTED
    }

    private final Player player;
    private final Set<String> kitIds;
    private final Reason reason;

    public PracticeQueueLeaveEvent(@NotNull Player player, @NotNull Set<String> kitIds,
                                   @NotNull Reason reason) {
        this.player = player;
        this.kitIds = Set.copyOf(kitIds);
        this.reason = reason;
    }

    /** Who left. */
    public @NotNull Player player() {
        return player;
    }

    /**
     * The queues they left, by kit id.
     *
     * <p>Usually every queue they were in. One kit when they clicked out of just
     * that one, in which case they may still be waiting on others — see
     * {@link net.exylia.lib.api.practice.PracticeService#queuedKits}.
     */
    public @NotNull @Unmodifiable Set<String> kitIds() {
        return kitIds;
    }

    /** Why they left. */
    public @NotNull Reason reason() {
        return reason;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
