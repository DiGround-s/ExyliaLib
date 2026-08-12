package net.exylia.lib.client.internal;

import net.exylia.lib.client.ClientBrand;
import net.exylia.lib.client.Cooldown;
import net.exylia.lib.client.Waypoint;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A client integration that records instead of talking to a real client.
 *
 * <p>Apollo and Feather cannot run in a unit test, and mocking them would test
 * the mock. What matters is what the module asks a client to do, so that is
 * what this records: the order, the arguments, and what it refused because the
 * client does not support it.
 */
final class FakeLink implements ClientLink {

    private final ClientBrand brand;
    private final boolean waypoints;
    private final boolean cooldowns;
    private final boolean markers;
    private final boolean resendsOnWorld;

    /** Players this client claims as its own. */
    private final Collection<UUID> owned = new CopyOnWriteArrayList<>();

    private final List<String> calls = new CopyOnWriteArrayList<>();

    /** Set to make every call throw, standing in for a broken integration. */
    volatile boolean broken;

    /** Set to make waypoints fail to send, as a vanilla client would. */
    volatile boolean refuseWaypoints;

    private int nextHandle = 1;

    FakeLink(ClientBrand brand, boolean waypoints, boolean cooldowns, boolean markers,
             boolean resendsOnWorld) {
        this.brand = brand;
        this.waypoints = waypoints;
        this.cooldowns = cooldowns;
        this.markers = markers;
        this.resendsOnWorld = resendsOnWorld;
    }

    /** A client that does everything, like Lunar. */
    static FakeLink full(ClientBrand brand) {
        return new FakeLink(brand, true, true, true, false);
    }

    /** A client that only draws waypoints and forgets them on world change, like Feather. */
    static FakeLink waypointsOnly(ClientBrand brand) {
        return new FakeLink(brand, true, false, false, true);
    }

    FakeLink owning(Player... players) {
        for (Player player : players) {
            owned.add(player.getUniqueId());
        }
        return this;
    }

    @Override
    public ClientBrand brand() {
        return brand;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public boolean recognises(Player player) {
        if (broken) {
            throw new IllegalStateException("integration is broken");
        }
        return owned.contains(player.getUniqueId());
    }

    @Override
    public boolean supportsWaypoints() {
        return waypoints;
    }

    @Override
    public boolean resendsOnWorldChange() {
        return resendsOnWorld;
    }

    @Override
    public Object showWaypoint(Player player, Waypoint waypoint) {
        fail();
        calls.add("waypoint:" + player.getName() + ":" + waypoint.name()
                + ":" + waypoint.worldName());
        return refuseWaypoints ? null : "handle-" + (nextHandle++);
    }

    @Override
    public void removeWaypoint(Player player, Waypoint waypoint, Object handle) {
        fail();
        calls.add("unwaypoint:" + player.getName() + ":" + waypoint.name() + ":" + handle);
    }

    @Override
    public void clearWaypoints(Player player) {
        fail();
        calls.add("clearwaypoints:" + player.getName());
    }

    @Override
    public boolean supportsCooldowns() {
        return cooldowns;
    }

    @Override
    public void showCooldown(Player player, Cooldown cooldown) {
        fail();
        calls.add("cooldown:" + player.getName() + ":" + cooldown.name()
                + ":" + cooldown.duration().toMillis());
    }

    @Override
    public void removeCooldown(Player player, String name) {
        fail();
        calls.add("uncooldown:" + player.getName() + ":" + name);
    }

    @Override
    public void clearCooldowns(Player player) {
        fail();
        calls.add("clearcooldowns:" + player.getName());
    }

    @Override
    public boolean supportsMarkers() {
        return markers;
    }

    @Override
    public void updateMarkers(Player viewer, Collection<Player> teammates) {
        fail();
        List<String> names = new ArrayList<>(teammates.size());
        for (Player teammate : teammates) {
            names.add(teammate.getName());
        }
        calls.add("markers:" + viewer.getName() + ":" + String.join(",", names));
    }

    @Override
    public void clearMarkers(Player viewer) {
        fail();
        calls.add("clearmarkers:" + viewer.getName());
    }

    @Override
    public void forget(UUID playerId) {
        calls.add("forget:" + playerId);
    }

    private void fail() {
        if (broken) {
            throw new IllegalStateException("integration is broken");
        }
    }

    List<String> calls() {
        return new ArrayList<>(calls);
    }

    List<String> calls(String kind) {
        return calls.stream().filter(call -> call.startsWith(kind + ":")).toList();
    }

    void clear() {
        calls.clear();
    }
}
