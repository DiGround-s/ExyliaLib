package net.exylia.lib.client;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * One plugin's view of the client features.
 *
 * <pre>{@code
 * PluginClients clients = Clients.of(this);
 *
 * clients.waypoints().show(player, Waypoint.at("spawn", arena.centre()));
 * clients.cooldowns().show(player, Cooldown.seconds("pearl", 16));
 *
 * // Ending a game takes down what this plugin drew, and nothing else.
 * clients.clear(player);
 * }</pre>
 *
 * <h2>Why a plugin should ask for this instead of the static entry points</h2>
 * What a plugin sends is keyed by that plugin, so two of them can both show a
 * waypoint called {@code "spawn"} without deleting each other's — a lobby and
 * a game have every right to that word. The static {@link Clients#waypoints()}
 * shares one unowned bucket, where the second {@code show} replaces the first.
 *
 * <p>The same ownership is what makes {@link #clear(Player)} safe to call when
 * a match ends. {@link Clients#clear(Player)} wipes the player's client, taking
 * down markers other plugins are still relying on.
 *
 * <p>Everything this view sent is taken down on its own when the plugin is
 * disabled: a waypoint whose owner is gone could otherwise never be removed by
 * anybody, and would sit on the minimap until the player reconnected.
 *
 * <h2>Threading</h2>
 * Every method here is safe from any thread.
 *
 * @param waypoints this plugin's waypoints
 * @param cooldowns this plugin's cooldowns
 * @param teams     this plugin's teams
 * @since 1.48.0
 */
public record PluginClients(@NotNull Clients.Waypoints waypoints,
                            @NotNull Clients.Cooldowns cooldowns,
                            @NotNull PluginTeams teams) {

    /**
     * Removes everything this plugin put on a player's screen.
     *
     * <p>Waypoints and cooldowns this plugin sent. Other plugins' are left
     * alone, and so are the player's teams, which belong to whatever game is
     * running rather than to a screen being tidied.
     *
     * @param player the player
     */
    public void clear(@NotNull Player player) {
        waypoints.clear(player);
        cooldowns.clear(player);
    }
}
