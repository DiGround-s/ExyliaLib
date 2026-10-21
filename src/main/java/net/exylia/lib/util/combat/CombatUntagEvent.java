package net.exylia.lib.util.combat;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player just left combat.
 *
 * <p>Fired by the combat plugin after the tag is gone. Not cancellable: by the
 * time this fires the player is free, and a listener that wants them fighting
 * again tags them again.
 *
 * <p>Fired on whichever thread removed the tag and says so through
 * {@link #isAsynchronous()}.
 *
 * @since 1.325.0
 */
public final class CombatUntagEvent extends Event {

    /** Why the tag ended. */
    public enum Reason {
        /** The timer ran out. */
        EXPIRED,
        /** They killed their opponent. */
        KILLED,
        /** They died. */
        DIED,
        /** They left the server while tagged; a {@link CombatLogEvent} came first. */
        LOGOUT,
        /** They were kicked. */
        KICKED,
        /** A staff command. */
        ADMIN,
        /** Another plugin through {@link Combat#untag}. */
        API,
        /** Anything the combat plugin has no better name for. */
        OTHER
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Reason reason;

    public CombatUntagEvent(@NotNull Player player, @NotNull Reason reason) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.reason = reason;
    }

    public @NotNull Player getPlayer() {
        return player;
    }

    public @NotNull Reason getReason() {
        return reason;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
