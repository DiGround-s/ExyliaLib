package net.exylia.lib.api.betcore.event;

import net.exylia.lib.api.betcore.BetMatch;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Both stakes are held and the match is about to be played.
 *
 * <p>Deliberately not cancellable. The money is already out of both balances,
 * and a listener that could stop a match here would leave a pot with nobody to
 * pay it to.
 *
 * @since 1.0.0
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

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
