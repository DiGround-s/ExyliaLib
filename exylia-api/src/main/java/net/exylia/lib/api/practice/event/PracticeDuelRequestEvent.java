package net.exylia.lib.api.practice.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A player is challenging another to a duel.
 *
 * <p>Fired once practice has agreed the request may be sent — both players are
 * free, the target accepts requests, the sender has none outstanding — and
 * before it exists, so cancelling it sends nothing to either side. Neither is
 * told why; that is the canceller's to say.
 *
 * <p>Not fired for {@link net.exylia.lib.api.practice.PracticeService#startDuel},
 * which has no request in it. Its match fires {@link PracticeMatchCreateEvent}
 * like any other.
 *
 * @since 1.3.0
 */
public class PracticeDuelRequestEvent extends PracticeEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player sender;
    private final Player target;
    private final String kitId;
    private final int rounds;
    private final String arenaId;

    private boolean cancelled;

    public PracticeDuelRequestEvent(@NotNull Player sender, @NotNull Player target,
                                    @NotNull String kitId, int rounds, @Nullable String arenaId) {
        this.sender = sender;
        this.target = target;
        this.kitId = kitId;
        this.rounds = rounds;
        this.arenaId = arenaId;
    }

    /** The challenger. */
    public @NotNull Player sender() {
        return sender;
    }

    /** Who is being challenged. */
    public @NotNull Player target() {
        return target;
    }

    /** The kit the duel would be fought with. */
    public @NotNull String kitId() {
        return kitId;
    }

    /** How many rounds would decide it. */
    public int rounds() {
        return rounds;
    }

    /** The arena the sender picked, or {@code null} when they left it to the plugin. */
    public @Nullable String arenaId() {
        return arenaId;
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
