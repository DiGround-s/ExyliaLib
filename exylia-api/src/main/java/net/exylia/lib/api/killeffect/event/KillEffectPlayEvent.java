package net.exylia.lib.api.killeffect.event;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A kill effect is about to play.
 *
 * <p>Fired once everything else has agreed it should: the effect exists, draws
 * something, and the region allows kill effects. A handler therefore never
 * sees an effect that was going to be skipped anyway, and cancelling is the only
 * thing that can still stop it.
 *
 * <p>Fired for the kills the server reports and for the plays another plugin
 * asks for through {@link net.exylia.lib.api.killeffect.KillEffectService}, and
 * the two are not told apart on purpose: an arena that wants kill effects quiet
 * wants both quiet.
 *
 * <p>Cancelling draws nothing and says nothing. The kill itself is untouched;
 * only its effect is gone.
 *
 * <p>Called on the thread that owns the location, which on Folia is a region
 * thread rather than a single main thread. Anything touching the wider world
 * has to be scheduled.
 *
 * @since 1.3.0
 */
public class KillEffectPlayEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final LivingEntity victim;
    private final String effectId;
    private final Location location;

    private boolean cancelled;

    /**
     * Creates the event.
     *
     * @param player   who the effect belongs to
     * @param victim   what it plays on, or {@code null} for nothing
     * @param effectId the effect, normalised
     * @param location where it plays
     */
    public KillEffectPlayEvent(@NotNull Player player, @Nullable LivingEntity victim,
                               @NotNull String effectId, @NotNull Location location) {
        super(!Bukkit.isPrimaryThread());
        this.player = player;
        this.victim = victim;
        this.effectId = effectId;
        this.location = location.clone();
    }

    /**
     * Who the effect belongs to: the killer.
     *
     * @return the player
     */
    @NotNull
    public Player getPlayer() {
        return player;
    }

    /**
     * What the effect plays on.
     *
     * <p>{@code null} when a plugin played an effect at a place rather than on
     * a body.
     *
     * @return the victim, or {@code null}
     */
    @Nullable
    public LivingEntity getVictim() {
        return victim;
    }

    /**
     * The effect about to play.
     *
     * @return its id, as {@code effects.yml} declares it, normalised
     */
    @NotNull
    public String getEffectId() {
        return effectId;
    }

    /**
     * Where the effect plays.
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
