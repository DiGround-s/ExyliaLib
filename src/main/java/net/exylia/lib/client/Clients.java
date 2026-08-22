package net.exylia.lib.client;

import net.exylia.lib.client.internal.ClientRuntime;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/**
 * Entry point of the client module.
 *
 * <p>Talks to modified clients — Lunar through Apollo, and Feather — without
 * the caller ever branching on which one a player runs:
 *
 * <pre>{@code
 * Clients.waypoints().show(player, Waypoint.at("Koth", arena.centre()).colour("#8a51c4"));
 * Clients.cooldowns().show(player, Cooldown.seconds("pearl", 16).icon(Icon.item("ENDER_PEARL")));
 * Clients.markers().update(player, team.members());
 *
 * ClientTeam red = Clients.teams(this).create(redPlayers);
 * }</pre>
 *
 * <p>A player on vanilla, or on a client that does not support a feature, is
 * not an error and not a special case: the call costs a map lookup and sends
 * nothing. That is the whole point of this module — a plugin says what the
 * player should see, and whoever can show it, shows it.
 *
 * <h2>What is remembered</h2>
 * Clients forget everything when a player reconnects, and Feather forgets
 * waypoints when they change world. The library remembers what it sent and
 * puts it back, so no plugin needs its own re-send listener. Nothing is
 * written to disk: a restart clears it.
 *
 * <h2>Threading</h2>
 * Every method here is safe from any thread.
 *
 * @since 1.7.0
 */
public final class Clients {

    private Clients() {
        throw new AssertionError("No instances.");
    }

    /**
     * Returns this plugin's own view of the client features.
     *
     * <p>Prefer this to the static entry points below. What a plugin sends is
     * keyed by that plugin, so two of them can both show a {@code "spawn"}
     * waypoint without replacing each other's, ending a game can take down
     * what it drew without wiping somebody else's, and everything it sent is
     * removed on its own when the plugin is disabled.
     *
     * @param plugin the owning plugin
     * @return its view
     * @since 1.48.0
     */
    public static @NotNull PluginClients of(@NotNull org.bukkit.plugin.Plugin plugin) {
        return ClientRuntime.of(plugin);
    }

    /**
     * Waypoints: markers in the world and on the minimap.
     *
     * <p>Unowned: everything sent here shares one bucket keyed by name alone,
     * so a second plugin showing the same name replaces the first, and
     * {@link #clear(Player)} takes down every plugin's. Prefer
     * {@link #of(org.bukkit.plugin.Plugin)}.
     *
     * @return the waypoint API
     */
    public static @NotNull Waypoints waypoints() {
        return ClientRuntime.WAYPOINTS;
    }

    /**
     * Cooldowns drawn by the client next to the hotbar.
     *
     * @return the cooldown API
     */
    public static @NotNull Cooldowns cooldowns() {
        return ClientRuntime.COOLDOWNS;
    }

    /**
     * Teammate markers, drawn on the client's minimap and world.
     *
     * @return the marker API
     */
    public static @NotNull Markers markers() {
        return ClientRuntime.MARKERS;
    }

    /**
     * Teams whose members see each other's markers.
     *
     * <p>{@link #markers()} draws a set of teammates once; a team remembers who
     * is on it, so a game does not have to answer that question again on every
     * join, death and reconnect. Teams die with the plugin that created them.
     *
     * @param plugin the owning plugin
     * @return its team registry
     */
    public static @NotNull PluginTeams teams(@NotNull org.bukkit.plugin.Plugin plugin) {
        return ClientRuntime.teamsOf(plugin);
    }

    /**
     * Returns which client a player is running.
     *
     * <p>Rarely needed: everything above already sends only what a given
     * client understands. Useful for a join message or a statistic.
     *
     * @param player the player
     * @return the brand, {@link ClientBrand#VANILLA} when nothing was detected
     */
    public static @NotNull ClientBrand brandOf(@NotNull Player player) {
        return ClientRuntime.brandOf(player);
    }

    /**
     * Returns whether any client integration is installed on the server.
     *
     * <p>When {@code false} every call still works and sends nothing.
     *
     * @return {@code true} when at least one integration loaded
     */
    public static boolean isSupported() {
        return ClientRuntime.isSupported();
    }

    /**
     * Removes everything this library sent a player, across every feature.
     *
     * <p>For a game that ends and wants the screen clean, without listing what
     * it sent.
     *
     * @param player the player
     */
    public static void clear(@NotNull Player player) {
        ClientRuntime.clearEverything(player);
    }

    /**
     * Waypoints shown on a client.
     *
     * @since 1.7.0
     */
    public interface Waypoints {

        /**
         * Shows a waypoint, replacing any with the same name.
         *
         * @param player   who sees it
         * @param waypoint what to show
         * @return {@code true} when the client took it
         */
        boolean show(@NotNull Player player, @NotNull Waypoint waypoint);

        /**
         * Shows a waypoint to several players at once.
         *
         * @param players  who see it
         * @param waypoint what to show
         */
        void show(@NotNull Collection<? extends Player> players, @NotNull Waypoint waypoint);

        /**
         * Removes a waypoint by name.
         *
         * @param player who sees it
         * @param name   the name it was shown with
         */
        void remove(@NotNull Player player, @NotNull String name);

        /**
         * Removes every waypoint this library sent a player.
         *
         * @param player who sees them
         */
        void clear(@NotNull Player player);

        /**
         * Returns whether the player's client draws waypoints at all.
         *
         * @param player the player
         * @return {@code true} when they would see one
         */
        boolean supported(@NotNull Player player);
    }

    /**
     * Cooldowns drawn by a client.
     *
     * @since 1.7.0
     */
    public interface Cooldowns {

        /**
         * Draws a cooldown.
         *
         * @param player   who sees it
         * @param cooldown what to draw
         * @return {@code true} when the client took it
         */
        boolean show(@NotNull Player player, @NotNull Cooldown cooldown);

        /**
         * Draws a cooldown for several players at once.
         *
         * @param players  who see it
         * @param cooldown what to draw
         */
        void show(@NotNull Collection<? extends Player> players, @NotNull Cooldown cooldown);

        /**
         * Removes a cooldown by name.
         *
         * @param player who sees it
         * @param name   the name it was shown with
         */
        void remove(@NotNull Player player, @NotNull String name);

        /**
         * Removes every cooldown this library sent a player.
         *
         * @param player who sees them
         */
        void clear(@NotNull Player player);

        /**
         * Returns whether the player's client draws cooldowns at all.
         *
         * @param player the player
         * @return {@code true} when they would see one
         */
        boolean supported(@NotNull Player player);
    }

    /**
     * Teammate markers drawn by a client.
     *
     * @since 1.7.0
     */
    public interface Markers {

        /**
         * Replaces the set of teammates a player sees markers for.
         *
         * <p>The whole set is sent rather than one member at a time, because
         * that is what the clients accept and because it keeps the server's
         * view the only truth. Call it again whenever the team changes.
         *
         * @param viewer    who sees the markers
         * @param teammates who to mark; the viewer themselves is ignored
         */
        void update(@NotNull Player viewer, @NotNull Collection<? extends Player> teammates);

        /**
         * Shows the same team to all of its members.
         *
         * <p>The usual case: everyone on a team sees everyone else.
         *
         * @param team the team
         */
        void updateTeam(@NotNull Collection<? extends Player> team);

        /**
         * Removes every marker a player sees.
         *
         * @param viewer who sees them
         */
        void clear(@NotNull Player viewer);

        /**
         * Returns whether the player's client draws markers at all.
         *
         * @param player the player
         * @return {@code true} when they would see one
         */
        boolean supported(@NotNull Player player);
    }
}
