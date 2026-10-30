package net.exylia.lib.replay;

import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * An anticheat flagged somebody: every {@link Replays#flag} call, and every
 * flag of Grim, Vulcan, Spartan and Matrix the library already listens to.
 *
 * <p>For a plugin that acts on flags as they happen, such as saving a replay
 * once somebody flags often enough. One event per flag, before the black box
 * folds a second's worth of the same check into one entry, so a listener
 * counts what really happened.
 *
 * <p>Fired on whichever thread the anticheat flagged on, often its own, and
 * says so through {@link #isAsynchronous()}: a listener that touches the
 * player hands that to the player's own thread. Nothing is built at all while
 * nobody listens.
 *
 * @since 1.405.0
 */
public final class ReplayFlagEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID player;
    private final String check;
    private final String detail;

    public ReplayFlagEvent(@NotNull UUID player, @NotNull String check, @Nullable String detail) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.check = check;
        this.detail = detail;
    }

    /** Who was flagged. */
    public @NotNull UUID getPlayer() {
        return player;
    }

    /** The check, as the anticheat names it; cut at 64 characters. */
    public @NotNull String getCheck() {
        return check;
    }

    /** What it said beside it, cut at 256 characters, or {@code null}. */
    public @Nullable String getDetail() {
        return detail;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
