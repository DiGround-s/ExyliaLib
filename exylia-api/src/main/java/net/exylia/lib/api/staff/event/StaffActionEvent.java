package net.exylia.lib.api.staff.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A staff member did something worth counting: froze someone, handled a
 * report, issued a punishment, inspected an inventory.
 *
 * <p>ExyliaStaff's own log module listens to this and increments the session's
 * counters; no module calls the log directly, so the log can be off without
 * anyone noticing. The same event is the hook for anything outside the plugin
 * that wants an audit trail, a webhook or a per-staff scoreboard, which is why
 * it lives here rather than inside the plugin.
 *
 * <p>Fired on the staff member's thread, after the action has already run.
 * Nothing is cancellable: this reports what happened, it does not gate it.
 *
 * @since 1.0.0
 */
public final class StaffActionEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player staff;
    private final String action;
    private final UUID target;

    /**
     * @param staff  the staff member who acted
     * @param action a short id such as {@code freeze} or {@code punish}
     * @param target the player it was done to, when there was one
     */
    public StaffActionEvent(@NotNull Player staff, @NotNull String action, @Nullable UUID target) {
        this.staff = staff;
        this.action = action;
        this.target = target;
    }

    /**
     * The staff member who acted.
     *
     * @return the staff member
     */
    public @NotNull Player getStaff() {
        return staff;
    }

    /**
     * What they did.
     *
     * <p>A short id such as {@code freeze}, {@code report_resolve} or
     * {@code punish}. New ids appear as modules are added, so treat an
     * unfamiliar one as an action this version of the plugin has and yours does
     * not know about, rather than as an error.
     *
     * @return the action id
     */
    public @NotNull String getAction() {
        return action;
    }

    /**
     * Who it was done to.
     *
     * @return the target, or {@code null} for an action about nobody in
     *         particular
     */
    public @Nullable UUID getTarget() {
        return target;
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
}
