package net.exylia.lib.api.betcore.event;

import net.exylia.lib.api.betcore.BetMatch;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player is about to play a square.
 *
 * <p>Cancelling refuses the move and leaves the turn exactly where it was, so
 * the player may simply play somewhere else.
 *
 * @since 1.0.0
 */
public class BetMatchMoveEvent extends BetEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final BetMatch match;
    private final Player player;
    private final int cell;
    private boolean cancelled;

    public BetMatchMoveEvent(@NotNull BetMatch match, @NotNull Player player, int cell) {
        this.match = match;
        this.player = player;
        this.cell = cell;
    }

    public @NotNull BetMatch getMatch() {
        return match;
    }

    public @NotNull Player getPlayer() {
        return player;
    }

    /** Which square, in board order. */
    public int getCell() {
        return cell;
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
