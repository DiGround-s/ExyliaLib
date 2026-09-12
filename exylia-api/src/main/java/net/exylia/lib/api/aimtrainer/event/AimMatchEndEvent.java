package net.exylia.lib.api.aimtrainer.event;

import net.exylia.lib.api.aimtrainer.AimMatch;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A duel ended, decided or cancelled, before the result is written.
 *
 * @since 1.5.0
 */
public class AimMatchEndEvent extends AimTrainerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final AimMatch match;
    private final java.util.UUID winner;
    private final boolean cancelled;

    public AimMatchEndEvent(@NotNull AimMatch match, @Nullable java.util.UUID winner, boolean cancelled) {
        this.match = match;
        this.winner = winner;
        this.cancelled = cancelled;
    }

    public @NotNull AimMatch match() {
        return match;
    }

    /** The winner, or {@code null} for a cancelled duel. */
    public @Nullable java.util.UUID winner() {
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
