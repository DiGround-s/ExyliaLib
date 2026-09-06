package net.exylia.lib.api.totemtrainer.event;

import net.exylia.lib.api.totemtrainer.TotemMatch;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Both players arrived in the arena; the first round's countdown starts next.
 *
 * @since 1.0.0
 */
public final class MatchStartEvent extends TotemTrainerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final TotemMatch match;

    public MatchStartEvent(@NotNull TotemMatch match) {
        this.match = match;
    }

    public @NotNull TotemMatch match() {
        return match;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
