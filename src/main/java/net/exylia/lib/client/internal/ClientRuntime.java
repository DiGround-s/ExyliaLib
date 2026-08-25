package net.exylia.lib.client.internal;

import net.exylia.lib.client.ClientBrand;
import net.exylia.lib.client.Clients;
import net.exylia.lib.client.Cooldown;
import net.exylia.lib.client.Waypoint;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The client module's working parts.
 *
 * <p>Each feature is one small implementation that does the same three things:
 * find the player's client, ask it whether it can do this, and remember what
 * was sent so it can be sent again. Everything client-specific lives behind
 * {@link ClientLink}.
 */
public final class ClientRuntime {

    /** The waypoint API handed out by {@link Clients#waypoints()}. */
    public static final Clients.Waypoints WAYPOINTS = new WaypointsImpl(null);

    /** The cooldown API handed out by {@link Clients#cooldowns()}. */
    public static final Clients.Cooldowns COOLDOWNS = new CooldownsImpl(null);

    /** The marker API handed out by {@link Clients#markers()}. */
    public static final Clients.Markers MARKERS = new MarkersImpl();

    private ClientRuntime() {
    }

    /**
     * Returns a plugin's team registry.
     *
     * @param plugin the owning plugin
     * @return its teams
     */
    public static net.exylia.lib.client.PluginTeams teamsOf(Plugin plugin) {
        return TeamRegistry.of(plugin.getName());
    }

    /**
     * Returns a plugin's own view of the client features.
     *
     * @param plugin the owning plugin
     * @return its view
     */
    public static net.exylia.lib.client.PluginClients of(Plugin plugin) {
        String owner = plugin.getName();
        return new net.exylia.lib.client.PluginClients(
                new WaypointsImpl(owner), new CooldownsImpl(owner), teamsOf(plugin));
    }

    /**
     * Takes down everything a plugin that is going away put on a screen.
     *
     * <p>Its teams, and now its waypoints and cooldowns too. These are packets
     * to players who are still here: a waypoint whose plugin is gone can never
     * be removed by anybody, so it would sit on the minimap until the player
     * reconnected.
     *
     * @param pluginName the plugin going away
     */
    public static void release(String pluginName) {
        TeamRegistry.release(pluginName);
        RESTORERS.remove(pluginName);
        for (UUID id : ClientState.waypointViewers(pluginName)) {
            Player player = org.bukkit.Bukkit.getPlayer(id);
            if (player != null) {
                removeAllOf(pluginName, player);
            }
        }
        for (UUID id : ClientState.cooldownViewers(pluginName)) {
            Player player = org.bukkit.Bukkit.getPlayer(id);
            if (player != null) {
                clearCooldownsOf(pluginName, player);
            }
        }
    }

    /**
     * Loads whichever client integrations are installed.
     *
     * <p>Called by ExyliaLib at startup.
     *
     * @param plugin the library plugin
     */
    public static void init(Plugin plugin) {
        ClientState.logger(plugin.getLogger());
        ClientRegistry.load(plugin.getLogger());
    }

    public static boolean isSupported() {
        return ClientRegistry.anyAvailable();
    }

    public static ClientBrand brandOf(Player player) {
        return ClientRegistry.brandOf(player);
    }

    /** Removes everything sent to a player, across every feature. */
    public static void clearEverything(Player player) {
        WAYPOINTS.clear(player);
        COOLDOWNS.clear(player);
        MARKERS.clear(player);
    }

    /**
     * Re-sends what a player had, after their client forgot it.
     *
     * <p>Called on join, once the client has had time to announce itself, and
     * on a world change for clients that drop waypoints with the world.
     *
     * @param player      the player
     * @param worldChange whether this is a world change rather than a join
     */
    public static void resend(Player player, boolean worldChange) {
        UUID id = player.getUniqueId();
        // A team draws everyone's markers from the membership it owns, so it
        // is re-sent whatever the client does with waypoints.
        TeamRegistry.resend(id);

        ClientLink link = ClientRegistry.of(player);
        if (!link.supportsWaypoints()) {
            return;
        }
        if (worldChange && !link.resendsOnWorldChange()) {
            return;
        }

        for (java.util.Map.Entry<ClientState.Key, ClientState.Sent> entry
                : ClientState.waypointEntriesOf(id)) {
            Waypoint waypoint = entry.getValue().waypoint();
            // A waypoint belongs to a world: after a change, only the ones for
            // the world the player is now in are worth sending.
            if (worldChange && !waypoint.worldName().equals(player.getWorld().getName())) {
                continue;
            }
            Object handle = link.showWaypoint(player, waypoint);
            if (handle != null) {
                // Put back under the owner it went out with: re-sending under
                // nobody would leave a marker its own plugin can no longer
                // remove, on every reconnect.
                ClientState.rememberWaypoint(id, entry.getKey().owner(), waypoint, handle);
            }
        }

        if (!worldChange) {
            restore(player);
        }
    }

    /**
     * Asks every plugin what this player should be seeing, and sends it.
     *
     * <p>Only after a join. A world change still has everything remembered, so
     * asking again there would send each waypoint twice — once from the loop
     * above and once from its owner.
     */
    private static void restore(Player player) {
        for (Map.Entry<String, Function<Player, Collection<Waypoint>>> entry : RESTORERS.entrySet()) {
            Collection<Waypoint> waypoints;
            try {
                waypoints = entry.getValue().apply(player);
            } catch (RuntimeException failure) {
                // One plugin's bad answer is not a reason for the next plugin's
                // markers to go missing.
                ClientState.logger().warning("A plugin failed to say what waypoints "
                        + player.getName() + " should see: " + failure);
                continue;
            }
            if (waypoints == null) {
                continue;
            }
            for (Waypoint waypoint : waypoints) {
                showAs(entry.getKey(), player, waypoint);
            }
        }
    }

    /**
     * Forgets a player who left.
     *
     * <p>No packets: their client is gone. This only stops the library from
     * believing a player who left still has anything on screen.
     */
    public static void forget(Player player) {
        UUID id = player.getUniqueId();
        ClientRegistry.forget(id);
        ClientState.forget(id);
        // Their teammates still have a marker pointing at them, and unlike the
        // player who left, they are still looking at it.
        TeamRegistry.forget(id);
    }

    /** Drops every integration and everything remembered. */
    public static void shutdown() {
        ClientRegistry.clear();
        ClientState.clear();
        TeamRegistry.clear();
        RESTORERS.clear();
    }

    // ------------------------------------------------------------------
    // Waypoints
    // ------------------------------------------------------------------

    /**
     * What each plugin says a player should be seeing, by owner.
     *
     * <p>Held rather than the waypoints themselves: a function cannot go stale,
     * and a plugin's own table is the only copy of the answer that is still
     * true after the player has been away.
     */
    private static final Map<String, Function<Player, Collection<Waypoint>>> RESTORERS =
            new ConcurrentHashMap<>();

    private static final class WaypointsImpl implements Clients.Waypoints {

        /** Whose waypoints these are, or {@code null} for the unowned static API. */
        private final String owner;

        WaypointsImpl(String owner) {
            this.owner = owner;
        }

        @Override
        public boolean show(@NotNull Player player, @NotNull Waypoint waypoint) {
            return showAs(owner, player, waypoint);
        }

        @Override
        public void show(@NotNull Collection<? extends Player> players, @NotNull Waypoint waypoint) {
            for (Player player : players) {
                show(player, waypoint);
            }
        }

        @Override
        public void remove(@NotNull Player player, @NotNull String name) {
            ClientState.Sent sent = ClientState.forgetWaypoint(player.getUniqueId(), owner, name);
            if (sent == null) {
                return;
            }
            ClientLink link = ClientRegistry.of(player);
            safely(() -> link.removeWaypoint(player, sent.waypoint(), sent.handle()));
        }

        /**
         * Takes down every waypoint this view sent the player.
         *
         * <p>An owned view removes only its own. Clearing the client outright
         * would take down the lobby's waypoints because a game ended, and the
         * player would have no way to get them back.
         */
        @Override
        public void clear(@NotNull Player player) {
            if (owner == null) {
                ClientState.clearWaypoints(player.getUniqueId());
                ClientLink link = ClientRegistry.of(player);
                if (link.supportsWaypoints()) {
                    safely(() -> link.clearWaypoints(player));
                }
                return;
            }
            removeAllOf(owner, player);
        }

        @Override
        public void restoreWith(@NotNull Function<Player, Collection<Waypoint>> waypoints) {
            Objects.requireNonNull(waypoints, "waypoints");
            RESTORERS.put(owner == null ? "" : owner, waypoints);
        }

        @Override
        public boolean supported(@NotNull Player player) {
            return ClientRegistry.of(player).supportsWaypoints();
        }
    }

    /** Shows one waypoint on behalf of an owner, replacing that owner's own. */
    private static boolean showAs(String owner, Player player, Waypoint waypoint) {
        ClientLink link = ClientRegistry.of(player);
        if (!link.supportsWaypoints()) {
            return false;
        }
        UUID id = player.getUniqueId();
        // Showing the same name twice is a move, not a duplicate: the old
        // one goes first so clients that key by name do not keep both.
        ClientState.Sent previous = ClientState.forgetWaypoint(id, owner, waypoint.name());
        if (previous != null) {
            safely(() -> link.removeWaypoint(player, previous.waypoint(), previous.handle()));
        }

        Object handle = link.showWaypoint(player, waypoint);
        if (handle == null) {
            return false;
        }
        ClientState.rememberWaypoint(id, owner, waypoint, handle);
        return true;
    }

    /** Takes down one owner's waypoints on one player. */
    private static void removeAllOf(String owner, Player player) {
        UUID id = player.getUniqueId();
        ClientLink link = ClientRegistry.of(player);
        for (java.util.Map.Entry<ClientState.Key, ClientState.Sent> entry
                : ClientState.waypointsOf(id, owner)) {
            ClientState.Sent sent = entry.getValue();
            ClientState.forgetWaypoint(id, owner, entry.getKey().name());
            safely(() -> link.removeWaypoint(player, sent.waypoint(), sent.handle()));
        }
    }

    // ------------------------------------------------------------------
    // Cooldowns
    // ------------------------------------------------------------------

    private static final class CooldownsImpl implements Clients.Cooldowns {

        /** Whose cooldowns these are, or {@code null} for the unowned static API. */
        private final String owner;

        CooldownsImpl(String owner) {
            this.owner = owner;
        }

        @Override
        public boolean show(@NotNull Player player, @NotNull Cooldown cooldown) {
            ClientLink link = ClientRegistry.of(player);
            if (!link.supportsCooldowns()) {
                return false;
            }
            safely(() -> link.showCooldown(player, cooldown));
            ClientState.rememberCooldown(player.getUniqueId(), owner, cooldown.name());
            return true;
        }

        @Override
        public void show(@NotNull Collection<? extends Player> players, @NotNull Cooldown cooldown) {
            for (Player player : players) {
                show(player, cooldown);
            }
        }

        @Override
        public void remove(@NotNull Player player, @NotNull String name) {
            ClientState.forgetCooldown(player.getUniqueId(), owner, name);
            ClientLink link = ClientRegistry.of(player);
            if (link.supportsCooldowns()) {
                safely(() -> link.removeCooldown(player, name));
            }
        }

        /** Takes down every cooldown this view drew, and only those. */
        @Override
        public void clear(@NotNull Player player) {
            if (owner == null) {
                ClientState.clearCooldowns(player.getUniqueId());
                ClientLink link = ClientRegistry.of(player);
                if (link.supportsCooldowns()) {
                    safely(() -> link.clearCooldowns(player));
                }
                return;
            }
            clearCooldownsOf(owner, player);
        }

        @Override
        public boolean supported(@NotNull Player player) {
            return ClientRegistry.of(player).supportsCooldowns();
        }
    }

    /** Takes down one owner's cooldowns on one player. */
    private static void clearCooldownsOf(String owner, Player player) {
        UUID id = player.getUniqueId();
        ClientLink link = ClientRegistry.of(player);
        boolean drawn = link.supportsCooldowns();
        for (ClientState.Key key : ClientState.cooldownsOf(id, owner)) {
            ClientState.forgetCooldown(id, owner, key.name());
            if (drawn) {
                safely(() -> link.removeCooldown(player, key.name()));
            }
        }
    }

    // ------------------------------------------------------------------
    // Markers
    // ------------------------------------------------------------------

    private static final class MarkersImpl implements Clients.Markers {

        @Override
        public void update(@NotNull Player viewer, @NotNull Collection<? extends Player> teammates) {
            ClientLink link = ClientRegistry.of(viewer);
            if (!link.supportsMarkers()) {
                return;
            }
            List<Player> others = new ArrayList<>(teammates.size());
            for (Player teammate : teammates) {
                if (teammate != null && teammate.isOnline() && !teammate.equals(viewer)) {
                    others.add(teammate);
                }
            }
            safely(() -> link.updateMarkers(viewer, others));
            ClientState.rememberMarkers(viewer.getUniqueId(), others);
        }

        @Override
        public void updateTeam(@NotNull Collection<? extends Player> team) {
            for (Player member : team) {
                update(member, team);
            }
        }

        @Override
        public void clear(@NotNull Player viewer) {
            ClientState.clearMarkers(viewer.getUniqueId());
            ClientLink link = ClientRegistry.of(viewer);
            if (link.supportsMarkers()) {
                safely(() -> link.clearMarkers(viewer));
            }
        }

        @Override
        public boolean supported(@NotNull Player player) {
            return ClientRegistry.of(player).supportsMarkers();
        }
    }

    /**
     * Runs an integration call without letting it escape.
     *
     * <p>These calls end up inside somebody else's plugin. A client integration
     * that throws is their bug, and it must not take down the game that asked
     * for a waypoint.
     */
    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            ClientState.logger().warning("A client integration failed: " + t.getMessage());
        }
    }
}
