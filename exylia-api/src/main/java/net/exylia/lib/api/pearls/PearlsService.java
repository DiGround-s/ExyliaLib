package net.exylia.lib.api.pearls;

import net.exylia.lib.api.ExyliaAPI;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;

/**
 * Reading what ExyliaPearls knows about pearls in flight.
 *
 * <pre>{@code
 * ExyliaAPI.get(PearlsService.class)
 *          .flatMap(pearls -> pearls.pearl(projectile.getUniqueId()))
 *          .ifPresent(tracked -> getLogger().info("would land at " + tracked.lastSafe()));
 * }</pre>
 *
 * <p>Registered with Bukkit's {@link org.bukkit.plugin.ServicesManager} when
 * ExyliaPearls enables. Reach it through {@link ExyliaAPI#get(Class)}, and
 * treat an empty result as "this server does not correct pearl landings"
 * rather than as a failure.
 *
 * <p>Read-only. The plugin's whole job happens between a pearl being thrown and
 * it landing, and nothing outside it decides where a player ends up — so there
 * is nothing here to set. Every method reads a cache and is cheap enough for a
 * per-tick listener.
 *
 * @since 1.0.0
 */
public interface PearlsService {

    /**
     * Whether a pearl is being tracked.
     *
     * <p>Only pearls thrown by a player are: a dispenser's pearl has nobody to
     * rescue. Tracking also ends the moment the pearl lands, so this answers
     * "is this pearl in flight and ours", not "was it ever ours".
     *
     * @param pearlId the entity id of the ender pearl
     * @return {@code true} when the plugin is following it
     */
    boolean isTracked(@NotNull UUID pearlId);

    /**
     * What is remembered about a pearl in flight.
     *
     * @param pearlId the entity id of the ender pearl
     * @return its tracked state, or empty when the pearl is not being followed
     */
    @NotNull
    Optional<TrackedPearl> pearl(@NotNull UUID pearlId);

    /**
     * Where a pearl would put its thrower if it landed right now.
     *
     * <p>The last safe point if the pearl is being tracked, which is what the
     * plugin would fall back to. A landing that is already safe is left to
     * vanilla, so this is the pessimistic answer rather than the likely one.
     *
     * @param pearlId the entity id of the ender pearl
     * @return the fallback destination, or empty when the pearl is not tracked
     */
    @NotNull
    Optional<Location> fallbackDestination(@NotNull UUID pearlId);

    /**
     * Whether a player would fit at a location.
     *
     * <p>The same block-collision test the plugin runs along a pearl's flight,
     * with the player's own width and the configured {@link CheckMode} applied
     * to their height. Useful to anything that teleports players and wants the
     * server's own idea of "not inside a wall".
     *
     * @param player   whose bounding box to test
     * @param location where to test it
     * @return {@code true} when nothing there would clip them
     */
    boolean fitsAt(@NotNull Player player, @NotNull Location location);

    /**
     * How strict the safety check currently is.
     *
     * <p>Server configuration, so it changes on reload rather than per player.
     *
     * @return the configured mode
     */
    @NotNull
    CheckMode checkMode();

    /**
     * How often a pearl's position is sampled, in ticks.
     *
     * <p>The resolution of {@link TrackedPearl#lastSafe()}: at an interval of
     * four, the remembered point can be up to four ticks of flight behind the
     * pearl.
     *
     * @return the interval, never below one
     */
    int checkTickInterval();
}
