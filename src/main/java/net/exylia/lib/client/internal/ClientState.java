package net.exylia.lib.client.internal;

import net.exylia.lib.client.Cooldown;
import net.exylia.lib.client.Waypoint;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * What each player has been sent, so it can be sent again.
 *
 * <p>A modified client forgets everything when the player reconnects, and
 * Feather forgets waypoints when they change world. Without this every plugin
 * would grow its own "re-send my waypoints on join" listener, and they would
 * all get it slightly wrong.
 *
 * <p>State lives in memory only. A waypoint is a thing on a screen, not a
 * record worth a file: a restart clears them, which is the same behaviour the
 * previous implementation had.
 */
public final class ClientState {

    /** Waypoints per player, keyed by name so re-showing one replaces it. */
    private static final Map<UUID, Map<String, Sent>> WAYPOINTS = new ConcurrentHashMap<>();

    /** Cooldown names per player, so they can be cleared without guessing. */
    private static final Map<UUID, Collection<String>> COOLDOWNS = new ConcurrentHashMap<>();

    /** Marker groups per player: who each viewer currently sees. */
    private static final Map<UUID, Collection<UUID>> MARKERS = new ConcurrentHashMap<>();

    private static Logger logger = Logger.getLogger("ExyliaLib");

    private ClientState() {
    }

    static void logger(Logger value) {
        logger = value;
    }

    static Logger logger() {
        return logger;
    }

    /** A waypoint and whatever the client handed back to remove it by. */
    record Sent(Waypoint waypoint, Object handle) {
    }

    // ------------------------------------------------------------------
    // Waypoints
    // ------------------------------------------------------------------

    static void rememberWaypoint(UUID player, Waypoint waypoint, Object handle) {
        WAYPOINTS.computeIfAbsent(player, id -> new LinkedHashMap<>())
                .put(waypoint.name(), new Sent(waypoint, handle));
    }

    static Sent forgetWaypoint(UUID player, String name) {
        Map<String, Sent> sent = WAYPOINTS.get(player);
        return sent == null ? null : sent.remove(name);
    }

    static Collection<Sent> waypointsOf(UUID player) {
        Map<String, Sent> sent = WAYPOINTS.get(player);
        return sent == null ? List.of() : List.copyOf(sent.values());
    }

    static void clearWaypoints(UUID player) {
        WAYPOINTS.remove(player);
    }

    // ------------------------------------------------------------------
    // Cooldowns
    // ------------------------------------------------------------------

    static void rememberCooldown(UUID player, String name) {
        COOLDOWNS.computeIfAbsent(player, id -> ConcurrentHashMap.newKeySet()).add(name);
    }

    static void forgetCooldown(UUID player, String name) {
        Collection<String> names = COOLDOWNS.get(player);
        if (names != null) {
            names.remove(name);
        }
    }

    static Collection<String> cooldownsOf(UUID player) {
        Collection<String> names = COOLDOWNS.get(player);
        return names == null ? List.of() : List.copyOf(names);
    }

    static void clearCooldowns(UUID player) {
        COOLDOWNS.remove(player);
    }

    // ------------------------------------------------------------------
    // Markers
    // ------------------------------------------------------------------

    static void rememberMarkers(UUID viewer, Collection<Player> teammates) {
        Collection<UUID> ids = new ArrayList<>(teammates.size());
        for (Player teammate : teammates) {
            ids.add(teammate.getUniqueId());
        }
        MARKERS.put(viewer, ids);
    }

    static Collection<UUID> markersOf(UUID viewer) {
        Collection<UUID> ids = MARKERS.get(viewer);
        return ids == null ? List.of() : List.copyOf(ids);
    }

    static void clearMarkers(UUID viewer) {
        MARKERS.remove(viewer);
    }

    /** Drops everything remembered about a player who left. */
    public static void forget(UUID player) {
        WAYPOINTS.remove(player);
        COOLDOWNS.remove(player);
        MARKERS.remove(player);
        // Somebody else's markers may still point at them; the game that owns
        // those markers updates them on its own schedule, so nothing is sent
        // here.
    }

    /** Drops everything. Used on shutdown and by tests. */
    public static void clear() {
        WAYPOINTS.clear();
        COOLDOWNS.clear();
        MARKERS.clear();
    }

    /** How many players have something remembered. For diagnostics and tests. */
    public static int tracked() {
        return WAYPOINTS.size() + COOLDOWNS.size() + MARKERS.size();
    }
}
