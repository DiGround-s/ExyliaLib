package net.exylia.lib.api.staff.event;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * A player is about to be frozen.
 *
 * <p>Fired once the plugin has agreed to the freeze — the target is not the
 * staff member, does not hold the bypass and is not frozen already — and
 * before anything happens to them: no row is written, nothing is taken off and
 * no other server is told. Fired for {@code /freeze} and for
 * {@link net.exylia.lib.api.staff.StaffService#freeze(Player, Player)} alike.
 *
 * <p>Cancelling leaves the target free. The staff member is told nothing,
 * because the handler that refused the freeze is the only one that knows why —
 * a match plugin protecting a player mid-fight should say so itself.
 *
 * <p>Only the freeze is gated. A release is never refused, since a player
 * stuck frozen is worse than any reason to keep them there;
 * {@link StaffStateChangeEvent} with {@link StaffStateChangeEvent.Kind#FROZEN}
 * reports both once they have happened.
 *
 * <p>Called on the thread the freeze was issued from.
 *
 * @since 1.3.0
 */
public final class PlayerFreezeEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player target;
    private final CommandSender staff;

    private boolean cancelled;

    /**
     * @param target the player about to be frozen
     * @param staff  who is freezing them
     */
    public PlayerFreezeEvent(@NotNull Player target, @NotNull CommandSender staff) {
        this.target = target;
        this.staff = staff;
    }

    /**
     * The player about to be frozen.
     *
     * @return the target
     */
    public @NotNull Player getTarget() {
        return target;
    }

    /**
     * Who is freezing them.
     *
     * @return a {@link Player} for a staff member, or the console
     */
    public @NotNull CommandSender getStaff() {
        return staff;
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

    /**
     * Bukkit's handler list for this event.
     *
     * @return the handler list
     */
    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
