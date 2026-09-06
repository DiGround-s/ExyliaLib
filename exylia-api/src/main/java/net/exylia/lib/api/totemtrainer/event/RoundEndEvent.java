package net.exylia.lib.api.totemtrainer.event;

import net.exylia.lib.api.totemtrainer.TotemMatch;
import net.exylia.lib.api.totemtrainer.MatchRound;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A round was scored. {@link MatchRound#winner()} is {@code null} for a replayed draw.
 *
 * @since 1.0.0
 */
public final class RoundEndEvent extends TotemTrainerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final TotemMatch match;
    private final MatchRound round;

    public RoundEndEvent(@NotNull TotemMatch match, @NotNull MatchRound round) {
        this.match = match;
        this.round = round;
    }

    public @NotNull TotemMatch match() {
        return match;
    }

    public @NotNull MatchRound round() {
        return round;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
