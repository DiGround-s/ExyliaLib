package net.exylia.lib.api.betcore.event;

import net.exylia.lib.api.betcore.BetMatch;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Somebody is taking a bet.
 *
 * <p>Fired before either stake is charged, so cancelling costs nobody
 * anything.
 *
 * @since 1.0.0
 */
public class BetMatchJoinEvent extends BetEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final BetMatch match;
    private final Player player;
    private boolean cancelled;

    public BetMatchJoinEvent(@NotNull BetMatch match, @NotNull Player player) {
        this.match = match;
        this.player = player;
    }

    public @NotNull BetMatch getMatch() {
        return match;
    }

    public @NotNull Player getPlayer() {
        return player;
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
