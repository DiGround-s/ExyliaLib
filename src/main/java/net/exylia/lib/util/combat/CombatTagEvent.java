package net.exylia.lib.util.combat;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Objects;

/**
 * A player is about to be put in combat, or have their tag renewed.
 *
 * <p>Fired by the combat plugin before the tag applies, so any plugin can veto
 * it (a duel arena, a spawn region) or change how long it lasts without
 * depending on which combat plugin is installed. Cancelling leaves the player
 * exactly as they were: untagged, or still on their old timer.
 *
 * <p>Fired on the target's thread and says so through {@link #isAsynchronous()}.
 *
 * <pre>{@code
 * @EventHandler
 * void onTag(CombatTagEvent event) {
 *     if (inArena(event.getPlayer())) event.setCancelled(true);
 * }
 * }</pre>
 *
 * @since 1.325.0
 */
public final class CombatTagEvent extends Event implements Cancellable {

    /** Why the tag is happening. */
    public enum Cause {
        /** The player was hit. */
        DAMAGED,
        /** The player hit somebody. */
        ATTACKED,
        /** The player was hit by, or hit, a mob the combat plugin counts. */
        MOB,
        /** A staff command. */
        ADMIN,
        /** Another plugin through {@link Combat#tag}. */
        API,
        /** Anything the combat plugin has no better name for. */
        OTHER
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Player attacker;
    private final Cause cause;
    private final boolean refresh;
    private Duration duration;
    private boolean cancelled;

    public CombatTagEvent(@NotNull Player player, @Nullable Player attacker, @NotNull Duration duration,
                          @NotNull Cause cause, boolean refresh) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.attacker = attacker;
        this.duration = duration;
        this.cause = cause;
        this.refresh = refresh;
    }

    /** Who is being tagged. */
    public @NotNull Player getPlayer() {
        return player;
    }

    /** Who they are fighting, {@code null} when it is not a player. */
    public @Nullable Player getAttacker() {
        return attacker;
    }

    public @NotNull Cause getCause() {
        return cause;
    }

    /** {@code true} when the player is already tagged and this renews it. */
    public boolean isRefresh() {
        return refresh;
    }

    public @NotNull Duration getDuration() {
        return duration;
    }

    /** How long the tag lasts from now; must be positive. */
    public void setDuration(@NotNull Duration duration) {
        if (Objects.requireNonNull(duration, "duration").isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("A tag must last longer than zero: " + duration);
        }
        this.duration = duration;
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
