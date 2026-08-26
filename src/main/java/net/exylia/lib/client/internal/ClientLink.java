package net.exylia.lib.client.internal;

import net.exylia.lib.client.ClientBrand;
import net.exylia.lib.client.Cooldown;
import net.exylia.lib.client.Waypoint;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * One modified client, as far as this library cares.
 *
 * <p>Every integration implements this once. Adding a client means adding one
 * class and one line in {@link ClientRegistry}: nothing else in the module
 * knows Lunar or Feather exist, and no caller ever branches on which client a
 * player runs.
 *
 * <p>A client that cannot do something says so by returning {@code false} from
 * the matching {@code supports} method rather than throwing, because a feature
 * missing from one client is normal, not exceptional.
 */
public interface ClientLink {

    /** Which client this speaks for. */
    ClientBrand brand();

    /**
     * Returns whether this integration is usable at all.
     *
     * <p>False when the plugin behind it is not installed, which is the normal
     * case on most servers.
     */
    boolean available();

    /** Returns whether this specific player is running this client. */
    boolean recognises(Player player);

    // ------------------------------------------------------------------
    // Waypoints
    // ------------------------------------------------------------------

    /** Returns whether this client draws waypoints. */
    default boolean supportsWaypoints() {
        return false;
    }

    /**
     * Shows a waypoint.
     *
     * @return a handle to remove it by, or {@code null} if nothing was sent
     */
    default Object showWaypoint(Player player, Waypoint waypoint) {
        return null;
    }

    /** Removes a waypoint, given whatever {@link #showWaypoint} returned. */
    default void removeWaypoint(Player player, Waypoint waypoint, Object handle) {
    }

    /** Removes every waypoint this library sent the player. */
    default void clearWaypoints(Player player) {
    }

    /**
     * Returns whether waypoints have to be sent again after a world change.
     *
     * <p>Feather keys waypoints by world and drops them when the player
     * leaves it; Lunar does not.
     */
    default boolean resendsOnWorldChange() {
        return false;
    }

    /**
     * Returns whether this client has one waypoint slot per name and per
     * player, rather than a handle of its own.
     *
     * <p>Lunar does: {@code displayWaypoint} and {@code removeWaypoint} both
     * take the name, so two plugins showing a {@code "spawn"} waypoint are
     * competing for one slot on that player's screen no matter how carefully
     * the library keys them apart. Feather does not: it hands back a
     * {@code UUID} per waypoint and keeps as many as it is sent.
     *
     * <p>This is the difference {@link ClientRuntime} needs in order to keep
     * its promise that one plugin cannot take down another's marker. On a
     * client that keys by name, only the plugin that currently holds the slot
     * may remove it, and whoever else still wants that name gets it back.
     */
    default boolean keysWaypointsByName() {
        return false;
    }

    /**
     * Returns whether the client expires a waypoint's duration on its own.
     *
     * <p>Feather does, from {@code WaypointDuration}. Lunar has no such field,
     * so the library has to take the waypoint down itself when the time is up
     * — otherwise {@code lasting(...)} would quietly mean "forever" on half
     * the clients on the server.
     */
    default boolean expiresWaypoints() {
        return false;
    }

    // ------------------------------------------------------------------
    // Cooldowns
    // ------------------------------------------------------------------

    /** Returns whether this client draws cooldowns. */
    default boolean supportsCooldowns() {
        return false;
    }

    /** Draws a cooldown. */
    default void showCooldown(Player player, Cooldown cooldown) {
    }

    /** Removes a cooldown by name. */
    default void removeCooldown(Player player, String name) {
    }

    /** Removes every cooldown this library sent the player. */
    default void clearCooldowns(Player player) {
    }

    // ------------------------------------------------------------------
    // Markers
    // ------------------------------------------------------------------

    /** Returns whether this client draws teammate markers. */
    default boolean supportsMarkers() {
        return false;
    }

    /**
     * Replaces the set of teammates a player sees markers for.
     *
     * <p>Sent as a whole set rather than one by one because that is what the
     * clients accept, and because it makes the server's state the only truth.
     */
    default void updateMarkers(Player viewer, java.util.Collection<Player> teammates) {
    }

    /** Removes every marker a player sees. */
    default void clearMarkers(Player viewer) {
    }

    /** Forgets whatever the integration remembers about a player who left. */
    default void forget(UUID playerId) {
    }
}
