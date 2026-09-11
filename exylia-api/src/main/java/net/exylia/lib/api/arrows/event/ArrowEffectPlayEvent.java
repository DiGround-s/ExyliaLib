package net.exylia.lib.api.arrows.event;

import net.exylia.lib.api.arrows.ArrowTrigger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One moment of an arrow effect is about to play.
 *
 * <p>Fired once everything else has agreed it should: the effect draws
 * something at this moment, and the region allows arrow effects. A handler
 * therefore never sees a moment that was going to be skipped anyway, and
 * cancelling is the only thing that can still stop it.
 *
 * <p>Fired once per moment rather than once per frame: when the shot leaves
 * the bow, when its trail starts, and where it lands. Cancelling a
 * {@link ArrowTrigger#TRAIL} stops the whole line for that shot, not one step
 * of it; the launch and the impact are asked about separately.
 *
 * <p>Fired for real shots and for the plays another plugin asks for through
 * {@link net.exylia.lib.api.arrows.ArrowsService}, and the two are not told
 * apart on purpose: an arena that wants arrow effects quiet wants both quiet.
 *
 * <p>Cancelling draws nothing and says nothing. The shot itself flies and
 * lands as it would have.
 *
 * <p>Called on the thread that owns the location, which on Folia is a region
 * thread rather than a single main thread. Anything touching the wider world
 * has to be scheduled.
 *
 * @since 1.3.0
 */
public class ArrowEffectPlayEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Entity hit;
    private final String effectId;
    private final ArrowTrigger trigger;
    private final Location location;

    private boolean cancelled;

    /**
     * Creates the event.
     *
     * @param player   who fired the shot
     * @param hit      what it landed on, or {@code null}
     * @param effectId the effect, normalised
     * @param trigger  which moment of it
     * @param location where it plays
     */
    public ArrowEffectPlayEvent(@NotNull Player player, @Nullable Entity hit, @NotNull String effectId,
                                @NotNull ArrowTrigger trigger, @NotNull Location location) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.hit = hit;
        this.effectId = effectId;
        this.trigger = trigger;
        this.location = location.clone();
    }

    /**
     * Who the effect belongs to: the shooter.
     *
     * @return the player
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }

    /**
     * What the shot landed on.
     *
     * @return the entity hit, or {@code null} for a launch, a trail, a block,
     *         or a play that named nothing
     */
    @Nullable
    public Entity getHitEntity() {
        return hit;
    }

    /**
     * The effect about to play.
     *
     * @return its id, normalised
     */
    @NotNull
    public String getEffectId() {
        return effectId;
    }

    /**
     * Which moment of the effect this is.
     *
     * @return the moment
     */
    @NotNull
    public ArrowTrigger getTrigger() {
        return trigger;
    }

    /**
     * Where it plays; for a trail, where the line starts.
     *
     * @return a copy; changing it moves nothing
     */
    @NotNull
    public Location getLocation() {
        return location.clone();
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
