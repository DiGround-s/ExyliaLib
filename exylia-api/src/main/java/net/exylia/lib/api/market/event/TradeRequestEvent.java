package net.exylia.lib.api.market.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player asked another to trade face to face, and the request was delivered.
 *
 * <p>For a plugin that wants to say something to either side before they deal,
 * such as a warning about a low reputation. Not cancellable: the request is
 * already on its way. Called on the main thread, with both players on this
 * server.
 *
 * @since 1.11.0
 */
public class TradeRequestEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player from;
    private final Player to;

    /**
     * Creates the event.
     *
     * @param from who asked
     * @param to   who was asked
     */
    public TradeRequestEvent(@NotNull Player from, @NotNull Player to) {
        this.from = from;
        this.to = to;
    }

    /** Who asked to trade. */
    public @NotNull Player getFrom() {
        return from;
    }

    /** Who was asked. */
    public @NotNull Player getTo() {
        return to;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * The handler list Bukkit requires.
     *
     * @return the handler list
     */
    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
