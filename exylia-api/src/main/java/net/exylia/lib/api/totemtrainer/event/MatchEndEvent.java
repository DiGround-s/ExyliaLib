package net.exylia.lib.api.totemtrainer.event;

import net.exylia.lib.api.totemtrainer.TotemMatch;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * The match is decided or cancelled. Fires before ratings and history are
 * written, which happens asynchronously afterwards; read the winner here,
 * not the profiles.
 *
 * @since 1.0.0
 */
public final class MatchEndEvent extends TotemTrainerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final TotemMatch match;
    private final UUID winner;
    private final boolean cancelled;

    public MatchEndEvent(@NotNull TotemMatch match, @Nullable UUID winner, boolean cancelled) {
        this.match = match;
        this.winner = winner;
        this.cancelled = cancelled;
    }

    public @NotNull TotemMatch match() {
        return match;
    }

    /** {@code null} when cancelled. */
    public @Nullable UUID winner() {
        return winner;
    }

    public boolean cancelled() {
        return cancelled;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
