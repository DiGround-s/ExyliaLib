package net.exylia.lib.api.staff.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * A player is reporting another.
 *
 * <p>Fired once every check the plugin makes has passed — not themselves, not
 * protected staff, not on cooldown, not over their open-report limit — and
 * before anything is stored or anybody alerted. Fired for {@code /report}, for
 * the reason menu, and for reports other plugins file through
 * {@link net.exylia.lib.api.staff.StaffService#report(Player, Player, String)}.
 *
 * <p>This is the event a Discord webhook or an anticheat cross-check listens
 * to: it fires on the server the reporter is on, once, whereas the staff alert
 * goes out on every server of the network.
 *
 * <p>Cancelling stores nothing, alerts nobody and starts no cooldown, so the
 * reporter may try again at once. They are told nothing, because the handler
 * that refused the report is the only one that knows why.
 *
 * <p>Called on the reporter's thread.
 *
 * @since 1.3.0
 */
public final class PlayerReportEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player reporter;
    private final UUID target;
    private final String targetName;
    private final String reason;

    private boolean cancelled;

    /**
     * @param reporter   the player filing the report
     * @param target     the player being reported
     * @param targetName the reported player's name
     * @param reason     why, as it will be stored
     */
    public PlayerReportEvent(@NotNull Player reporter, @NotNull UUID target, @NotNull String targetName,
                             @NotNull String reason) {
        this.reporter = reporter;
        this.target = target;
        this.targetName = targetName;
        this.reason = reason;
    }

    /**
     * The player filing the report.
     *
     * @return the reporter
     */
    public @NotNull Player getReporter() {
        return reporter;
    }

    /**
     * The player being reported.
     *
     * @return their id
     */
    public @NotNull UUID getTarget() {
        return target;
    }

    /**
     * The reported player's name, as the report stores it.
     *
     * @return the name
     */
    public @NotNull String getTargetName() {
        return targetName;
    }

    /**
     * Why they are being reported.
     *
     * <p>Plain text with no formatting: a configured reason's display name, or
     * whatever the reporter typed. Treat it as player input.
     *
     * @return the reason
     */
    public @NotNull String getReason() {
        return reason;
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
