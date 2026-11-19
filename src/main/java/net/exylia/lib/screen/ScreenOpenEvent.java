package net.exylia.lib.screen;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A screen was opened for a player: another plugin can walk them through it ({@link ScreenSession#coach}), named by
 * {@link Screen#name()} (a markup screen is named after its file). Fired on the viewer's thread, before the screen
 * first shows, and says so through {@link #isAsynchronous()}.
 *
 * @since 1.630.0
 */
public final class ScreenOpenEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final ScreenSession session;

    public ScreenOpenEvent(@NotNull Player player, @NotNull ScreenSession session) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.session = session;
    }

    public @NotNull Player getPlayer() {
        return player;
    }

    public @NotNull ScreenSession getSession() {
        return session;
    }

    /** The screen's {@link Screen#name()}, or {@code null}. */
    public String getName() {
        return session.screen().name();
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
