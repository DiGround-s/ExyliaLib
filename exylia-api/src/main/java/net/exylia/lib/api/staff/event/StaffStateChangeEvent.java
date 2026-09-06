package net.exylia.lib.api.staff.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A piece of a player's staff state flipped: staff mode, vanish, freeze, or a
 * toggle such as staff chat or alerts.
 *
 * <p>Fired on the player's thread after the change has been applied, so the
 * service already answers the new way by the time a listener runs. ExyliaStaff's
 * own modules react to this instead of calling each other — the hotbar redraws
 * its vanish item, the log counts a freeze, the scoreboard updates a line — and
 * one event rather than six means a plugin that cares about all of them
 * registers one listener.
 *
 * <p>Not cancellable: the state has already changed. To refuse a change, gate
 * the permission that allows it.
 *
 * @since 1.0.0
 */
public final class StaffStateChangeEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Kind kind;
    private final boolean enabled;

    /**
     * @param player  the player whose state changed
     * @param kind    which piece changed
     * @param enabled whether it is now on
     */
    public StaffStateChangeEvent(@NotNull Player player, @NotNull Kind kind, boolean enabled) {
        this.player = player;
        this.kind = kind;
        this.enabled = enabled;
    }

    /**
     * The player whose state changed.
     *
     * @return the player
     */
    public @NotNull Player getPlayer() {
        return player;
    }

    /**
     * Which piece of state changed.
     *
     * @return the kind
     */
    public @NotNull Kind getKind() {
        return kind;
    }

    /**
     * Whether it is on now.
     *
     * @return {@code true} when the state was switched on
     */
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * Bukkit's handler list for this event.
     *
     * @return the handler list
     */
    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }

    /**
     * The pieces of staff state that report a change.
     *
     * <p>Every module that owns a per-player switch reports it here rather than
     * defining an event of its own, so the list grows as modules are added. A
     * listener that only cares about one kind filters; one that switches over
     * all of them should have a default arm.
     *
     * @since 1.0.0
     */
    public enum Kind {

        /** A staff session opened or closed. */
        STAFF_MODE,

        /** The player was hidden or shown. */
        VANISH,

        /** The player was frozen or released. */
        FROZEN,

        /** Their normal chat now goes to staff chat, or no longer does. */
        STAFF_CHAT,

        /** Their chat reach changed; {@code enabled} is false only for off. */
        GLOBAL_CHAT,

        /** They started or stopped receiving suspicious-mining alerts. */
        MINING_ALERTS,

        /** They started or stopped seeing ores through stone. */
        XRAY_VISION,

        /** Their staff session entered or left spectator. */
        SPECTATOR
    }
}
