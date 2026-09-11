package net.exylia.lib.api.pearls.event;

import org.bukkit.Location;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;

/**
 * A pearl landed where its thrower would clip into a block, and is about to
 * send them somewhere safe instead.
 *
 * <p>The one moment ExyliaPearls overrides vanilla, and so the one place to
 * disagree with it. A safe landing never fires this: it is left to vanilla and
 * there is nothing to decide.
 *
 * <h2>What you may change</h2>
 * Cancel to leave the landing to vanilla, which puts the thrower where the
 * pearl hit — inside the block. For a mode that wants the glitch, or a region
 * that must not be rescued into.
 *
 * <p>{@link #destination(Location)} sends them somewhere else. The plugin runs
 * its own fit test on whatever you set before anybody moves: a destination
 * that would clip the thrower drops the pearl and moves nobody, exactly as a
 * stale safe point does.
 *
 * <p>Fired on the thread that owns the pearl, which on Folia is its region
 * thread rather than a single main thread.
 *
 * @since 1.3.0
 */
public final class PearlRedirectEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final EnderPearl pearl;
    private final Location landing;
    private Location destination;
    private boolean cancelled;

    /**
     * @param thrower     who threw the pearl
     * @param pearl       the pearl that landed
     * @param landing     where it hit
     * @param destination where the plugin is about to send the thrower
     */
    public PearlRedirectEvent(@NotNull Player thrower, @NotNull EnderPearl pearl,
                              @NotNull Location landing, @NotNull Location destination) {
        super(thrower);
        this.pearl = pearl;
        this.landing = landing.clone();
        this.destination = destination.clone();
    }

    /**
     * The pearl that landed.
     *
     * <p>Still in the world while this runs; the plugin removes it once the
     * event is through, unless it was cancelled.
     *
     * @return the pearl
     */
    public @NotNull EnderPearl pearl() {
        return pearl;
    }

    /**
     * Where the pearl hit, the spot that would have clipped the thrower.
     *
     * @return a copy of the landing location
     */
    public @NotNull Location landing() {
        return landing.clone();
    }

    /**
     * Where the thrower is about to be sent.
     *
     * <p>The last point along the flight they would have fitted, unless a
     * listener before you changed it. Their yaw and pitch are applied on top
     * when they are moved.
     *
     * @return a copy of the destination
     */
    public @NotNull Location destination() {
        return destination.clone();
    }

    /**
     * Sends the thrower somewhere else.
     *
     * @param destination where to send them; copied, so changing it afterwards
     *                    does nothing
     */
    public void destination(@NotNull Location destination) {
        this.destination = destination.clone();
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

    /**
     * @return the handler list Bukkit registers against
     */
    public static @NotNull HandlerList getHandlerList() {
        return HANDLERS;
    }
}
