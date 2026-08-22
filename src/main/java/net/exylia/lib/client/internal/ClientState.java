package net.exylia.lib.client.internal;

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
 *
 * <h2>A waypoint belongs to the plugin that sent it</h2>
 * The key is the owning plugin and the name, never the name alone. Two plugins
 * both showing a {@code "spawn"} waypoint is ordinary — a lobby plugin and a
 * game plugin have every right to that word — and keying by name alone made
 * the second one delete the first one's marker off the player's screen. The
 * same key is what lets a plugin that is being disabled take down its own
 * waypoints and leave everybody else's alone.
 */
public final class ClientState {

    /**
     * Waypoints per player, keyed by owner and name so re-showing one replaces
     * it and two plugins cannot replace each other's.
     */
    private static final Map<UUID, Map<Key, Sent>> WAYPOINTS = new ConcurrentHashMap<>();

    /** Cooldown keys per player, so they can be cleared without guessing. */
    private static final Map<UUID, Collection<Key>> COOLDOWNS = new ConcurrentHashMap<>();

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

    /**
     * Who sent something and what they called it.
     *
     * @param owner the plugin name, or {@code null} for the unowned static API
     * @param name  the name the caller used
     */
    record Key(String owner, String name) {
    }

    /** A waypoint and whatever the client handed back to remove it by. */
    record Sent(Waypoint waypoint, Object handle) {
    }

    // ------------------------------------------------------------------
    // Waypoints
    // ------------------------------------------------------------------

    static void rememberWaypoint(UUID player, String owner, Waypoint waypoint, Object handle) {
        WAYPOINTS.computeIfAbsent(player, id -> new LinkedHashMap<>())
                .put(new Key(owner, waypoint.name()), new Sent(waypoint, handle));
    }

    static Sent forgetWaypoint(UUID player, String owner, String name) {
        Map<Key, Sent> sent = WAYPOINTS.get(player);
        return sent == null ? null : sent.remove(new Key(owner, name));
    }

    static Collection<Sent> waypointsOf(UUID player) {
        Map<Key, Sent> sent = WAYPOINTS.get(player);
        return sent == null ? List.of() : List.copyOf(sent.values());
    }

    /**
     * The waypoints a player has, with the owner each was sent under.
     *
     * <p>Re-sending has to put a waypoint back under the same owner, or a
     * reconnect would quietly move every marker into the unowned bucket and
     * the plugin that sent it could no longer take it down.
     */
    static Collection<Map.Entry<Key, Sent>> waypointEntriesOf(UUID player) {
        Map<Key, Sent> sent = WAYPOINTS.get(player);
        return sent == null ? List.of() : List.copyOf(sent.entrySet());
    }

    /** The waypoints one plugin sent a player, for taking them all down. */
    static Collection<Map.Entry<Key, Sent>> waypointsOf(UUID player, String owner) {
        Map<Key, Sent> sent = WAYPOINTS.get(player);
        if (sent == null) {
            return List.of();
        }
        Collection<Map.Entry<Key, Sent>> mine = new ArrayList<>();
        for (Map.Entry<Key, Sent> entry : sent.entrySet()) {
            if (owner.equals(entry.getKey().owner())) {
                mine.add(entry);
            }
        }
        return mine;
    }

    /** Every player who has a waypoint from this plugin. */
    static Collection<UUID> waypointViewers(String owner) {
        Collection<UUID> viewers = new ArrayList<>();
        for (Map.Entry<UUID, Map<Key, Sent>> entry : WAYPOINTS.entrySet()) {
            for (Key key : entry.getValue().keySet()) {
                if (owner.equals(key.owner())) {
                    viewers.add(entry.getKey());
                    break;
                }
            }
        }
        return viewers;
    }

    static void clearWaypoints(UUID player) {
        WAYPOINTS.remove(player);
    }

    // ------------------------------------------------------------------
    // Cooldowns
    // ------------------------------------------------------------------

    static void rememberCooldown(UUID player, String owner, String name) {
        COOLDOWNS.computeIfAbsent(player, id -> ConcurrentHashMap.newKeySet())
                .add(new Key(owner, name));
    }

    static void forgetCooldown(UUID player, String owner, String name) {
        Collection<Key> names = COOLDOWNS.get(player);
        if (names != null) {
            names.remove(new Key(owner, name));
        }
    }

    static Collection<Key> cooldownsOf(UUID player) {
        Collection<Key> names = COOLDOWNS.get(player);
        return names == null ? List.of() : List.copyOf(names);
    }

    /** The cooldowns one plugin drew for a player. */
    static Collection<Key> cooldownsOf(UUID player, String owner) {
        Collection<Key> names = COOLDOWNS.get(player);
        if (names == null) {
            return List.of();
        }
        Collection<Key> mine = new ArrayList<>();
        for (Key key : names) {
            if (owner.equals(key.owner())) {
                mine.add(key);
            }
        }
        return mine;
    }

    /** Every player who has a cooldown from this plugin. */
    static Collection<UUID> cooldownViewers(String owner) {
        Collection<UUID> viewers = new ArrayList<>();
        for (Map.Entry<UUID, Collection<Key>> entry : COOLDOWNS.entrySet()) {
            for (Key key : entry.getValue()) {
                if (owner.equals(key.owner())) {
                    viewers.add(entry.getKey());
                    break;
                }
            }
        }
        return viewers;
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
