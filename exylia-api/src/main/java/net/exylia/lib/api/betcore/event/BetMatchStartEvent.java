package net.exylia.lib.api.betcore.event;

import net.exylia.lib.api.betcore.BetMatch;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Every player of a match is in the arena and the first turn is being played.
 *
 * @since exylia-api 2.0.0
 */
public class BetMatchStartEvent extends BetEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final BetMatch match;

    public BetMatchStartEvent(@NotNull BetMatch match) {
        this.match = match;
    }

    public @NotNull BetMatch getMatch() {
        return match;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
