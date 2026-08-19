package net.exylia.lib.util.teleport;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Fired just before a player is moved by this module, and cancellable.
 *
 * <pre>{@code
 * @EventHandler
 * public void onTeleport(ExyliaTeleportEvent event) {
 *     if (combat.isTagged(event.player()) && event.cause() != TeleportCause.BACK) {
 *         event.setCancelled(true);
 *     }
 * }
 * }</pre>
 *
 * <h2>Why it exists</h2>
 * The plugin that wants to veto a teleport is almost never the plugin that
 * asked for one. A combat-log plugin, a staff freeze, a region that forbids
 * entry — each has to be able to say no without knowing which of the twenty
 * plugins on the server started it, and without any of them depending on it.
 *
 * <p>This is that seam. It is fired for every teleport this module performs, so
 * one listener covers all of them, and it carries {@link #cause()} so a listener
 * can allow the ones it does not mind.
 *
 * <h2>Redirecting rather than refusing</h2>
 * {@link #setTo(Location)} is there for the plugin whose answer is "not there,
 * here". A region that pushes players out of a claim would otherwise have to
 * cancel this and start its own teleport, which fires this event again.
 *
 * <h2>Threading</h2>
 * Always fired on the thread owning the player, immediately before the move, so
 * a listener may touch the player and read the world.
 *
 * @since 1.34.0
 */
public final class ExyliaTeleportEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Location from;
    private Location to;
    private final TeleportCause cause;
    private final Plugin requester;
    private boolean cancelled;

    /**
     * @param player    who is being moved
     * @param from      where they are now
     * @param to        where they are going
     * @param cause     why
     * @param requester the plugin that asked
     */
    public ExyliaTeleportEvent(@NotNull Player player, @NotNull Location from,
                               @NotNull Location to, @NotNull TeleportCause cause,
                               @NotNull Plugin requester) {
        this.player = Objects.requireNonNull(player, "player");
        this.from = Objects.requireNonNull(from, "from");
        this.to = Objects.requireNonNull(to, "to");
        this.cause = Objects.requireNonNull(cause, "cause");
        this.requester = Objects.requireNonNull(requester, "requester");
    }

    /** Who is being moved. */
    public @NotNull Player player() {
        return player;
    }

    /** Who is being moved. */
    public @NotNull Player getPlayer() {
        return player;
    }

    /** Where they are now. */
    public @NotNull Location from() {
        return from;
    }

    /** Where they are now. */
    public @NotNull Location getFrom() {
        return from;
    }

    /** Where they are going. */
    public @NotNull Location to() {
        return to;
    }

    /** Where they are going. */
    public @NotNull Location getTo() {
        return to;
    }

    /**
     * Sends them somewhere else instead.
     *
     * <p>For a listener whose answer is "not there, here". Cancelling and
     * starting a fresh teleport would fire this event again, which is how
     * loops between two well-meaning plugins get written.
     *
     * @param to where to send them instead
     */
    public void setTo(@NotNull Location to) {
        this.to = Objects.requireNonNull(to, "to");
    }

    /** Why they are being moved. */
    public @NotNull TeleportCause cause() {
        return cause;
    }

    /** Why they are being moved. */
    public @NotNull TeleportCause getCause() {
        return cause;
    }

    /** The plugin that asked for this. */
    public @NotNull Plugin requester() {
        return requester;
    }

    /** The plugin that asked for this. */
    public @NotNull Plugin getRequester() {
        return requester;
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

    /** The handler list, as Bukkit's event system requires. */
    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
