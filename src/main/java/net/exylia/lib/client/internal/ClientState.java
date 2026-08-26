package net.exylia.lib.client.internal;

import net.exylia.lib.client.Waypoint;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
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
 *
 * <h2>And it also has a place in a queue</h2>
 * Lunar has one waypoint slot per name per player, whatever this map thinks,
 * so the two plugins above really are competing for one marker on that screen.
 * Every registration carries the order it was made in, which is what says who
 * is holding the slot right now — the last one shown — and who gets it back
 * when that one goes away. See {@link #heir}.
 */
public final class ClientState {

    /**
     * Waypoints per player, keyed by owner and name so re-showing one replaces
     * it and two plugins cannot replace each other's.
     *
     * <p>Concurrent all the way down. The inner map used to be a plain
     * {@code LinkedHashMap}, which made a documented promise — every method on
     * {@code Clients} is safe from any thread — false for the one feature most
     * likely to be shown from a task: two {@code show} calls for the same
     * player from two threads were an unsynchronised {@code HashMap} write.
     * Insertion order went with it, which is what {@link Sent#sequence} is now
     * for.
     */
    private static final Map<UUID, Map<Key, Sent>> WAYPOINTS = new ConcurrentHashMap<>();

    /** Counts registrations, so "who was shown last" survives a concurrent map. */
    private static final java.util.concurrent.atomic.AtomicLong SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong();

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

    /**
     * A waypoint and whatever the client handed back to remove it by.
     *
     * @param waypoint what was sent
     * @param handle   what the client removes it by
     * @param sequence when it was sent, counting registrations; the highest for
     *                 a given name is the one a name-keyed client is showing,
     *                 and it is also the claim an expiry task checks before it
     *                 takes anything down
     */
    record Sent(Waypoint waypoint, Object handle, long sequence) {
    }

    // ------------------------------------------------------------------
    // Waypoints
    // ------------------------------------------------------------------

    /**
     * Records a waypoint as sent, and returns the record.
     *
     * <p>The caller needs the sequence back: an expiry task has to know which
     * registration it was scheduled for, or a waypoint re-shown before its
     * time would be taken down by the old task.
     */
    static Sent rememberWaypoint(UUID player, String owner, Waypoint waypoint, Object handle) {
        Sent record = new Sent(waypoint, handle, SEQUENCE.incrementAndGet());
        WAYPOINTS.computeIfAbsent(player, id -> new ConcurrentHashMap<>())
                .put(new Key(owner, waypoint.name()), record);
        return record;
    }

    static Sent forgetWaypoint(UUID player, String owner, String name) {
        Map<Key, Sent> sent = WAYPOINTS.get(player);
        return sent == null ? null : sent.remove(new Key(owner, name));
    }

    /** The registration under one key, or {@code null}. */
    static Sent waypoint(UUID player, String owner, String name) {
        Map<Key, Sent> sent = WAYPOINTS.get(player);
        return sent == null ? null : sent.get(new Key(owner, name));
    }

    static Collection<Sent> waypointsOf(UUID player) {
        Map<Key, Sent> sent = WAYPOINTS.get(player);
        return sent == null ? List.of() : List.copyOf(sent.values());
    }

    /**
     * Who is on screen under a name, or who should be next.
     *
     * <p>The most recent registration: a client with one slot per name ends up
     * holding whichever was shown last, whatever order this map remembers them
     * in.
     *
     * <p>The same rule as the placeholder registry: a name another plugin took
     * over goes back to whoever still wants it rather than to nobody, because
     * that plugin never stopped asking for it and was only hidden while the
     * other one held the slot.
     *
     * @param player  the player
     * @param name    the waypoint name
     * @param leaving the owner giving it up, skipped
     * @return the next registration, or {@code null} when there is none
     */
    static Map.Entry<Key, Sent> heir(UUID player, String name, String leaving) {
        Map<Key, Sent> sent = WAYPOINTS.get(player);
        if (sent == null) {
            return null;
        }
        Map.Entry<Key, Sent> best = null;
        for (Map.Entry<Key, Sent> entry : sent.entrySet()) {
            if (!entry.getKey().name().equals(name)
                    || (leaving != null && leaving.equals(entry.getKey().owner()))) {
                continue;
            }
            if (best == null || entry.getValue().sequence() > best.getValue().sequence()) {
                best = entry;
            }
        }
        return best;
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
        if (sent == null) {
            return List.of();
        }
        // In the order they were shown, so a client that keeps one slot per
        // name ends up holding the same one it held before.
        List<Map.Entry<Key, Sent>> entries = new ArrayList<>(sent.entrySet());
        entries.sort(java.util.Comparator.comparingLong(entry -> entry.getValue().sequence()));
        return entries;
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
