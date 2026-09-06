package net.exylia.lib.api.betcore.event;

import net.exylia.lib.api.betcore.BetMatch;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Somebody is about to watch a match.
 *
 * @since 1.0.0
 */
public class BetSpectateEvent extends BetEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final BetMatch match;
    private final Player spectator;
    private boolean cancelled;

    public BetSpectateEvent(@NotNull BetMatch match, @NotNull Player spectator) {
        this.match = match;
        this.spectator = spectator;
    }

    public @NotNull BetMatch getMatch() {
        return match;
    }

    public @NotNull Player getSpectator() {
        return spectator;
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
