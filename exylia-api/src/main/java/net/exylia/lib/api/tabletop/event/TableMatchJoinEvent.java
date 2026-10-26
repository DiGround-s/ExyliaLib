package net.exylia.lib.api.tabletop.event;

import net.exylia.lib.api.tabletop.TableMatch;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player is about to take a seat in a match, before their stake is taken.
 *
 * <p>Cancelling it keeps the seat free with nothing charged; the plugin that cancels tells the
 * player why.
 *
 * @since exylia-api 3.0.0
 */
public class TableMatchJoinEvent extends TableEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final TableMatch match;
    private final Player player;
    private boolean cancelled;

    public TableMatchJoinEvent(@NotNull TableMatch match, @NotNull Player player) {
        this.match = match;
        this.player = player;
    }

    public @NotNull TableMatch getMatch() {
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
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
