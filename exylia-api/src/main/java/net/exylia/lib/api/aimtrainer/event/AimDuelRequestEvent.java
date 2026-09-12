package net.exylia.lib.api.aimtrainer.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player is about to be challenged. Fired after the plugin's own checks
 * and before anybody is told; cancelling it sends nothing.
 *
 * @since 1.5.0
 */
public class AimDuelRequestEvent extends AimTrainerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player from;
    private final Player to;
    private final String drillId;
    private final int bestOf;
    private boolean cancelled;

    public AimDuelRequestEvent(@NotNull Player from, @NotNull Player to, @NotNull String drillId, int bestOf) {
        this.from = from;
        this.to = to;
        this.drillId = drillId;
        this.bestOf = bestOf;
    }

    public @NotNull Player from() {
        return from;
    }

    public @NotNull Player to() {
        return to;
    }

    public @NotNull String drillId() {
        return drillId;
    }

    public int bestOf() {
        return bestOf;
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
