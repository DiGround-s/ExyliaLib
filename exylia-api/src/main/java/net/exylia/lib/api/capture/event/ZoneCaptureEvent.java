package net.exylia.lib.api.capture.event;

import net.exylia.lib.api.capture.CaptureEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * A zone has been taken.
 *
 * <p>Fired each time somebody holds a zone long enough to take it: the hill in a
 * KOTH, a point scored in a capture-points KOTH, a conquest zone falling to a
 * clan. It comes after the capture has been counted in the statistics and
 * before the mode decides whether it won the run, so a capture that wins is
 * followed by {@link CaptureEndEvent}. A KOTH set to run on after a capture
 * fires it once per capture.
 *
 * <p>Destroy-the-core, payload and the points KOTH have no moment of taking a
 * zone — a core is broken block by block, a cart is escorted, points accrue
 * while standing — so they never fire this. Watch {@link CaptureEndEvent} for
 * their results.
 *
 * <p>Not cancellable: the capture is already on the players' statistics by the
 * time it fires.
 *
 * <p>Called on the global thread that ticks the zones. On Folia that is not a
 * region thread, so anything touching a block or an entity has to be scheduled.
 *
 * @since 1.3.0
 */
public class ZoneCaptureEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final CaptureEvent event;
    private final String zone;
    private final UUID player;
    private final String clan;

    /**
     * Creates the event.
     *
     * @param event  the run the zone belongs to
     * @param zone   the zone's name, or {@code null} for a mode with one zone
     * @param player who took it, or {@code null} when nobody can be named
     * @param clan   the clan credited, or {@code null} when scored individually
     */
    public ZoneCaptureEvent(@NotNull CaptureEvent event, @Nullable String zone,
                            @Nullable UUID player, @Nullable String clan) {
        super(!Bukkit.isPrimaryThread());
        this.event = event;
        this.zone = zone;
        this.player = player;
        this.clan = clan;
    }

    /**
     * The run, as it was the moment the zone was taken.
     *
     * @return a snapshot of the run
     */
    @NotNull
    public CaptureEvent getEvent() {
        return event;
    }

    /**
     * Which zone was taken.
     *
     * @return the zone's name as the admin set it up, empty for a mode that has
     *         only one zone
     */
    @NotNull
    public Optional<String> getZone() {
        return Optional.ofNullable(zone);
    }

    /**
     * Who took it.
     *
     * @return the player credited with the capture, empty when a clan took it
     *         with nobody left to name
     */
    @NotNull
    public Optional<UUID> getPlayer() {
        return Optional.ofNullable(player);
    }

    /**
     * The clan the capture counts for.
     *
     * @return the clan tag, empty when the run scores players individually
     */
    @NotNull
    public Optional<String> getClan() {
        return Optional.ofNullable(clan);
    }

    @Override
    @NotNull
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * The handler list Bukkit requires.
     *
     * @return the handler list
     */
    @NotNull
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
