package net.exylia.lib.client.internal;

import net.exylia.lib.client.Beam;
import net.exylia.lib.client.ChatChannel;
import net.exylia.lib.client.ClientBrand;
import net.exylia.lib.client.ClientElement;
import net.exylia.lib.client.Keybind;
import net.exylia.lib.client.ProgressBar;
import net.exylia.lib.client.Rally;
import net.exylia.lib.client.Timer;
import net.exylia.lib.client.ZoneBorder;
import net.exylia.lib.client.Clients;
import net.exylia.lib.client.Cooldown;
import net.exylia.lib.client.Waypoint;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
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

    /** The timer API handed out by {@link Clients#timers()}. */
    public static final Clients.Timers TIMERS = new TimersImpl(null);

    /** The rally API handed out by {@link Clients#rallies()}. */
    public static final Clients.Elements<Rally> RALLIES = new RalliesImpl(null);

    /** The beam API handed out by {@link Clients#beams()}. */
    public static final Clients.Elements<Beam> BEAMS = new ElementsImpl<>(Beam.class, null);

    /** The border API handed out by {@link Clients#borders()}. */
    public static final Clients.Elements<ZoneBorder> BORDERS = new ElementsImpl<>(ZoneBorder.class, null);

    /** The progress bar API handed out by {@link Clients#bars()}. */
    public static final Clients.Elements<ProgressBar> BARS = new ElementsImpl<>(ProgressBar.class, null);

    /** Each plugin's chat handler, by plugin name. */
    private static final Map<String, Clients.ChatHandler> CHAT_HANDLERS = new ConcurrentHashMap<>();

    /** Each plugin's keys, by plugin name, then by key name. */
    private static final Map<String, Map<String, Keybind>> KEYBINDS = new ConcurrentHashMap<>();

    /** Each plugin's key handler, by plugin name. */
    private static final Map<String, java.util.function.BiConsumer<Player, String>> KEY_HANDLERS =
            new ConcurrentHashMap<>();

    /** The marker API handed out by {@link Clients#markers()}. */
    public static final Clients.Markers MARKERS = new MarkersImpl();

    /**
     * The library plugin, for the one thing this module has to schedule.
     *
     * <p>A waypoint's duration on a client that does not count it down itself.
     * Held rather than looked up because {@link #showAs} is on the path of
     * every waypoint sent.
     */
    private static volatile Plugin library;

    private ClientRuntime() {
    }

    /** A seam for tests, which have no {@code onEnable} to call {@link #init}. */
    static void library(Plugin plugin) {
        library = plugin;
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
                new WaypointsImpl(owner), new CooldownsImpl(owner), teamsOf(plugin),
                new TimersImpl(owner), new RalliesImpl(owner),
                new ElementsImpl<>(Beam.class, owner), new ElementsImpl<>(ZoneBorder.class, owner),
                new ElementsImpl<>(ProgressBar.class, owner), new ChatImpl(owner), new KeybindsImpl(owner));
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
        for (UUID id : ClientState.elementViewers(pluginName)) {
            Player player = org.bukkit.Bukkit.getPlayer(id);
            if (player != null) {
                for (Class<? extends ClientElement> kind : KINDS) {
                    clearElementsOf(kind, pluginName, player);
                }
            }
        }
        for (UUID id : ClientState.channelViewers(pluginName)) {
            Player player = org.bukkit.Bukkit.getPlayer(id);
            if (player != null) {
                new ChatImpl(pluginName).channels(player, List.of());
            }
        }
        CHAT_HANDLERS.remove(pluginName);
        Map<String, Keybind> keys = KEYBINDS.remove(pluginName);
        if (keys != null) {
            for (String name : keys.keySet()) {
                unregisterEverywhere(pluginName, name);
            }
        }
        KEY_HANDLERS.remove(pluginName);
    }

    /**
     * Loads whichever client integrations are installed.
     *
     * <p>Called by ExyliaLib at startup. This library loads at {@code STARTUP}
     * and Apollo, FeatherServerAPI and SurvivalCore do not, so the sweep here finds neither
     * on a normal server: each one is picked up again when it enables.
     *
     * @param plugin the library plugin
     */
    public static void init(Plugin plugin) {
        library = plugin;
        ClientState.logger(plugin.getLogger());
        ClientRegistry.load(plugin.getLogger());
        Bukkit.getPluginManager().registerEvents(new LateClientWatcher(), plugin);
    }

    /** Looks again when a client plugin enables after this library. */
    private static final class LateClientWatcher implements Listener {
        @EventHandler(priority = EventPriority.MONITOR)
        public void onPluginEnable(PluginEnableEvent event) {
            switch (event.getPlugin().getName()) {
                case "Apollo", "Apollo-Bukkit", "Apollo-Folia", "FeatherServerAPI", "feather-server-api",
                     "SurvivalCore" ->
                        ClientRegistry.load(library.getLogger());
                default -> {
                }
            }
        }
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
        TIMERS.clear(player);
        RALLIES.clear(player);
        BEAMS.clear(player);
        BORDERS.clear(player);
        BARS.clear(player);
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
        if (!worldChange) {
            resendElements(player, link);
        }
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
                // Back to null for the unowned API: the restorer map cannot
                // hold a null key, but the waypoint key can, and putting these
                // under "" left the static remove() unable to find its own.
                String owner = entry.getKey().isEmpty() ? null : entry.getKey();
                showAs(owner, player, waypoint);
            }
        }
    }

    /**
     * Looks again at which client a player runs, and sends them what they
     * should be seeing.
     *
     * <p>For the moment a client finishes announcing itself. Unlike {@link
     * #forget}, nothing the plugins set up is dropped: the player is still
     * here, still in their team, and everything shown to them while they read
     * as vanilla is what they should now receive.
     *
     * @param player the player
     */
    public static void redetect(Player player) {
        ClientRegistry.forget(player.getUniqueId());
        resend(player, false);
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
        library = null;
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
            removeOne(owner, player, name);
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
        // Showing the same name twice is a move, not a duplicate: the old one
        // goes first so clients that keep a handle per waypoint do not keep
        // both. A client that keys by name replaces it on its own, and sending
        // the removal there would delete whatever is in that one slot —
        // possibly another plugin's — a tick before this one takes it.
        ClientState.Sent previous = ClientState.forgetWaypoint(id, owner, waypoint.name());
        if (previous != null && !link.keysWaypointsByName()) {
            safely(() -> link.removeWaypoint(player, previous.waypoint(), previous.handle()));
        }

        Object handle = link.showWaypoint(player, waypoint);
        if (handle == null) {
            return false;
        }
        ClientState.Sent sent = ClientState.rememberWaypoint(id, owner, waypoint, handle);
        expire(player, owner, waypoint, sent.sequence(), link);
        return true;
    }

    /**
     * Takes a waypoint down when its duration is up, for a client that will
     * not.
     *
     * <p>{@code lasting(...)} said the library did this and it did not: only
     * Feather was ever told a duration, so on Lunar a waypoint meant to last
     * five minutes stayed on the minimap until the player logged out.
     *
     * <p>The task carries the sequence it was scheduled for and checks it
     * before removing anything, so a waypoint re-shown in the meantime is not
     * taken down by the previous registration's timer. Bound to the player, so
     * one who leaves cancels it without anything here noticing.
     */
    private static void expire(Player player, String owner, Waypoint waypoint,
                               long sequence, ClientLink link) {
        java.time.Duration duration = waypoint.duration();
        if (duration == null || duration.isZero() || duration.isNegative()
                || link.expiresWaypoints()) {
            return;
        }
        Plugin plugin = library;
        if (plugin == null) {
            // Before init, which is only reachable from a test that never
            // enabled the library. Nothing to schedule on.
            return;
        }
        long ticks = Math.max(1L, duration.toMillis() / 50L);
        UUID id = player.getUniqueId();
        String name = waypoint.name();
        net.exylia.lib.task.Tasks.of(plugin).runAtEntityLater(player, ticks, () -> {
            ClientState.Sent current = ClientState.waypoint(id, owner, name);
            if (current != null && current.sequence() == sequence) {
                removeOne(owner, player, name);
            }
        });
    }

    /**
     * Takes down one waypoint, and hands its slot back if anyone else wants it.
     *
     * <p>The whole of the name-keying problem lives here. On Lunar the client
     * has one waypoint per name per player and no handle of its own, so two
     * plugins showing {@code "spawn"} are sharing one marker whatever this
     * library remembers. Removing used to send the removal unconditionally,
     * which meant the plugin that was <em>not</em> on screen could delete the
     * marker of the one that was, and the library would never put it back —
     * exactly the promise {@code Clients.of(plugin)} makes, broken on half the
     * clients on the server.
     *
     * <p>So: only the plugin holding the slot removes it, and when it does,
     * whoever else still registers that name gets it back.
     */
    private static void removeOne(String owner, Player player, String name) {
        UUID id = player.getUniqueId();
        ClientState.Sent sent = ClientState.forgetWaypoint(id, owner, name);
        if (sent == null) {
            return;
        }
        ClientLink link = ClientRegistry.of(player);
        if (!link.keysWaypointsByName()) {
            safely(() -> link.removeWaypoint(player, sent.waypoint(), sent.handle()));
            return;
        }
        // The record just removed was the latest, so it was the one on screen,
        // unless somebody registered that name after it.
        Map.Entry<ClientState.Key, ClientState.Sent> heir = ClientState.heir(id, name, owner);
        if (heir != null && heir.getValue().sequence() > sent.sequence()) {
            // Another plugin's marker is in that slot; ours was never on the
            // screen to take down. Forgetting our record is the whole job.
            return;
        }
        if (heir == null) {
            safely(() -> link.removeWaypoint(player, sent.waypoint(), sent.handle()));
            return;
        }
        // Showing the heir replaces the slot outright, so no removal is sent:
        // one packet instead of two, and no gap where the marker is missing.
        Object handle;
        try {
            handle = link.showWaypoint(player, heir.getValue().waypoint());
        } catch (Throwable failure) {
            // An integration that throws is their bug, and the caller only
            // asked to remove something.
            ClientState.logger().warning("A client integration failed: " + failure.getMessage());
            handle = null;
        }
        if (handle == null) {
            safely(() -> link.removeWaypoint(player, sent.waypoint(), sent.handle()));
            return;
        }
        ClientState.Sent restored = ClientState.rememberWaypoint(id, heir.getKey().owner(),
                heir.getValue().waypoint(), handle);
        // A new registration needs a new timer: the heir's original one was
        // scheduled against the sequence it has just stopped having.
        expire(player, heir.getKey().owner(), heir.getValue().waypoint(),
                restored.sequence(), link);
    }

    /** Takes down one owner's waypoints on one player. */
    private static void removeAllOf(String owner, Player player) {
        UUID id = player.getUniqueId();
        for (java.util.Map.Entry<ClientState.Key, ClientState.Sent> entry
                : ClientState.waypointsOf(id, owner)) {
            removeOne(owner, player, entry.getKey().name());
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

    /**
     * Draws a team for every member, with how it looks on clients that draw
     * more than markers.
     *
     * @param team the online members
     * @param look the team's name and ranks, or {@code null} for bare markers
     */
    static void drawTeam(Collection<? extends Player> team, TeamLook look) {
        for (Player member : team) {
            updateMarkers(member, team, look);
        }
    }

    private static void updateMarkers(Player viewer, Collection<? extends Player> teammates, TeamLook look) {
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
        safely(() -> link.updateMarkers(viewer, others, look));
        ClientState.rememberMarkers(viewer.getUniqueId(), others);
    }

    private static final class MarkersImpl implements Clients.Markers {

        @Override
        public void update(@NotNull Player viewer, @NotNull Collection<? extends Player> teammates) {
            updateMarkers(viewer, teammates, null);
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
    // ------------------------------------------------------------------
    // Elements: timers, rallies, beams, borders, bars
    // ------------------------------------------------------------------

    /** Every kind of element, for the sweeps that cover them all. */
    private static final List<Class<? extends ClientElement>> KINDS =
            List.of(Timer.class, Rally.class, Beam.class, ZoneBorder.class, ProgressBar.class);

    /**
     * One kind of element, on behalf of one owner.
     *
     * <p>What is shown is remembered even when the player's client cannot draw
     * it yet: a client announces itself a moment after joining, and whatever a
     * plugin showed in that moment is what the player should get once it has.
     */
    private static class ElementsImpl<T extends ClientElement> implements Clients.Elements<T> {

        final Class<T> kind;
        final String owner;

        ElementsImpl(Class<T> kind, String owner) {
            this.kind = kind;
            this.owner = owner;
        }

        @Override
        public boolean show(@NotNull Player player, @NotNull T element) {
            ClientState.rememberElement(player.getUniqueId(), owner, element);
            ClientLink link = ClientRegistry.of(player);
            if (!link.supports(kind)) {
                return false;
            }
            safely(() -> link.show(player, element));
            return true;
        }

        @Override
        public void show(@NotNull Collection<? extends Player> players, @NotNull T element) {
            for (Player player : players) {
                show(player, element);
            }
        }

        @Override
        public void remove(@NotNull Player player, @NotNull String name) {
            ClientState.forgetElement(player.getUniqueId(), kind, owner, name);
            ClientLink link = ClientRegistry.of(player);
            if (link.supports(kind)) {
                safely(() -> link.remove(player, kind, name));
            }
        }

        @Override
        public void clear(@NotNull Player player) {
            if (owner == null) {
                ClientState.clearElements(player.getUniqueId(), kind);
                ClientLink link = ClientRegistry.of(player);
                if (link.supports(kind)) {
                    safely(() -> link.clear(player, kind));
                }
                return;
            }
            clearElementsOf(kind, owner, player);
        }

        @Override
        public boolean supported(@NotNull Player player) {
            return ClientRegistry.of(player).supports(kind);
        }
    }

    private static final class TimersImpl extends ElementsImpl<Timer> implements Clients.Timers {
        TimersImpl(String owner) {
            super(Timer.class, owner);
        }
    }

    /**
     * Rallies, and a waypoint in their place on a client without them.
     *
     * <p>The waypoint goes through the waypoint module under the same owner and
     * name, so it is remembered, expired and re-sent the way every waypoint is.
     */
    private static final class RalliesImpl extends ElementsImpl<Rally> {

        RalliesImpl(String owner) {
            super(Rally.class, owner);
        }

        @Override
        public boolean show(@NotNull Player player, @NotNull Rally rally) {
            if (ClientRegistry.of(player).supports(Rally.class)) {
                return super.show(player, rally);
            }
            ClientState.rememberElement(player.getUniqueId(), owner, rally);
            return showAs(owner, player, Waypoint.at(rally.name(), rally.where())
                    .colour(Waypoint.Colour.of(rally.colour().red(), rally.colour().green(), rally.colour().blue()))
                    .lasting(rally.duration())
                    .icon(Waypoint.Icon.RALLY));
        }

        @Override
        public void remove(@NotNull Player player, @NotNull String name) {
            super.remove(player, name);
            removeOne(owner, player, name);
        }

        @Override
        public void clear(@NotNull Player player) {
            for (String name : ClientState.elementsOf(player.getUniqueId(), Rally.class, owner)) {
                removeOne(owner, player, name);
            }
            super.clear(player);
        }
    }

    /** Takes down one owner's elements of one kind on one player. */
    private static void clearElementsOf(Class<? extends ClientElement> kind, String owner, Player player) {
        UUID id = player.getUniqueId();
        ClientLink link = ClientRegistry.of(player);
        boolean drawn = link.supports(kind);
        for (String name : ClientState.elementsOf(id, kind, owner)) {
            ClientState.forgetElement(id, kind, owner, name);
            if (drawn) {
                safely(() -> link.remove(player, kind, name));
            }
        }
    }

    /**
     * Sends a player every element, channel and key they should have.
     *
     * <p>A timer goes back as it reads now; a rally whose time is up is
     * dropped instead of sent.
     */
    private static void resendElements(Player player, ClientLink link) {
        UUID id = player.getUniqueId();
        java.time.Instant now = java.time.Instant.now();
        for (Map.Entry<ClientState.Key, ClientState.Shown> entry : ClientState.allElementsOf(id)) {
            ClientElement element = entry.getValue().element();
            java.time.Duration passed = java.time.Duration.between(entry.getValue().at(), now);
            if (element instanceof Rally rally) {
                java.time.Duration left = rally.duration().minus(passed);
                if (left.isNegative() || left.isZero()) {
                    ClientState.forgetElement(id, Rally.class, entry.getKey().owner(), rally.name());
                    continue;
                }
                element = rally.lasting(left);
            } else if (element instanceof Timer timer) {
                element = timer.after(passed);
            }
            if (link.supports(element.getClass())) {
                ClientElement current = element;
                safely(() -> link.show(player, current));
            }
        }
        if (link.supportsChat()) {
            Map<String, List<ChatChannel>> channels = ClientState.channelsOf(id);
            if (!channels.isEmpty()) {
                safely(() -> link.setChannels(player, channels));
            }
        }
        if (link.supportsKeybinds()) {
            for (Map.Entry<String, Map<String, Keybind>> entry : KEYBINDS.entrySet()) {
                for (Keybind keybind : entry.getValue().values()) {
                    safely(() -> link.registerKeybind(player, entry.getKey(), keybind));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------

    private static final class ChatImpl implements Clients.Chat {

        private final String owner;

        ChatImpl(String owner) {
            this.owner = owner;
        }

        @Override
        public void channels(@NotNull Player player, @NotNull List<ChatChannel> channels) {
            Map<String, List<ChatChannel>> merged = ClientState.setChannels(player.getUniqueId(), owner, channels);
            ClientLink link = ClientRegistry.of(player);
            if (link.supportsChat()) {
                safely(() -> link.setChannels(player, merged));
            }
        }

        @Override
        public void post(@NotNull Collection<? extends Player> viewers, @NotNull String channel, Player sender,
                         @NotNull net.kyori.adventure.text.Component badge,
                         @NotNull net.kyori.adventure.text.Component message) {
            for (Player viewer : viewers) {
                ClientLink link = ClientRegistry.of(viewer);
                if (link.supportsChat()) {
                    safely(() -> link.postChat(viewer, owner, channel, sender, badge, message));
                }
            }
        }

        @Override
        public void onMessage(@NotNull Clients.ChatHandler handler) {
            CHAT_HANDLERS.put(owner, Objects.requireNonNull(handler, "handler"));
        }

        @Override
        public boolean supported(@NotNull Player player) {
            return ClientRegistry.of(player).supportsChat();
        }
    }

    /**
     * Hands a message typed into a channel to the plugin that owns it.
     *
     * @return whether a plugin took it
     */
    static boolean chatTyped(String owner, Player sender, String channel, String message) {
        Clients.ChatHandler handler = CHAT_HANDLERS.get(owner);
        if (handler == null) {
            return false;
        }
        try {
            handler.handle(sender, channel, message);
        } catch (RuntimeException failure) {
            ClientState.logger().warning(owner + " failed to handle a chat message: " + failure);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Keybinds
    // ------------------------------------------------------------------

    private static final class KeybindsImpl implements Clients.Keybinds {

        private final String owner;

        KeybindsImpl(String owner) {
            this.owner = owner;
        }

        @Override
        public void register(@NotNull Keybind keybind) {
            KEYBINDS.computeIfAbsent(owner, o -> new ConcurrentHashMap<>()).put(keybind.name(), keybind);
            for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
                ClientLink link = ClientRegistry.of(player);
                if (link.supportsKeybinds()) {
                    safely(() -> link.registerKeybind(player, owner, keybind));
                }
            }
        }

        @Override
        public void unregister(@NotNull String name) {
            Map<String, Keybind> mine = KEYBINDS.get(owner);
            if (mine != null && mine.remove(name) != null) {
                unregisterEverywhere(owner, name);
            }
        }

        @Override
        public void onPress(@NotNull java.util.function.BiConsumer<Player, String> handler) {
            KEY_HANDLERS.put(owner, Objects.requireNonNull(handler, "handler"));
        }
    }

    private static void unregisterEverywhere(String owner, String name) {
        for (Player player : org.bukkit.Bukkit.getOnlinePlayers()) {
            ClientLink link = ClientRegistry.of(player);
            if (link.supportsKeybinds()) {
                safely(() -> link.unregisterKeybind(player, owner, name));
            }
        }
    }

    /** Hands a key press to the plugin that registered the key. */
    static void keyPressed(String owner, Player player, String name) {
        java.util.function.BiConsumer<Player, String> handler = KEY_HANDLERS.get(owner);
        if (handler == null) {
            return;
        }
        try {
            handler.accept(player, name);
        } catch (RuntimeException failure) {
            ClientState.logger().warning(owner + " failed to handle a key press: " + failure);
        }
    }
}
