package net.exylia.lib.util.combat;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;

/**
 * A tagged player is leaving the server, and is about to be punished for it.
 *
 * <p>Fired by the combat plugin while the player is still online, before the
 * punishment (death, a logger NPC, commands). Cancelling spares them the
 * punishment; it cannot keep them on the server. A plugin that knows the quit
 * was not a dodge (a server restart, a proxy move it started) cancels it.
 *
 * <p>Fired on the player's thread and says so through {@link #isAsynchronous()}.
 *
 * @since 1.325.0
 */
public final class CombatLogEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Player opponent;
    private final Duration remaining;
    private boolean cancelled;

    public CombatLogEvent(@NotNull Player player, @Nullable Player opponent, @NotNull Duration remaining) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.opponent = opponent;
        this.remaining = remaining;
    }

    /** Who logged out. */
    public @NotNull Player getPlayer() {
        return player;
    }

    /** Who they were fighting, {@code null} when nobody known. */
    public @Nullable Player getOpponent() {
        return opponent;
    }

    /** How much of their tag was left. */
    public @NotNull Duration getRemaining() {
        return remaining;
    }

    /** {@code true} when the player will not be punished. */
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
