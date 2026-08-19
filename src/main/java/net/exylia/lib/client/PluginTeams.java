package net.exylia.lib.client;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.UUID;

/**
 * One plugin's teams.
 *
 * <p>Obtained from {@link Clients#teams(org.bukkit.plugin.Plugin)}. Teams
 * belong to the plugin that created them and are deleted when it is disabled,
 * so a plugin that forgets to clean up cannot leave markers on a screen.
 *
 * <pre>{@code
 * PluginTeams teams = Clients.teams(this);
 *
 * ClientTeam red  = teams.create(redPlayers);
 * ClientTeam blue = teams.create(bluePlayers);
 *
 * // a player who dies leaves whichever team held them
 * teams.leave(player);
 * }</pre>
 *
 * <h2>Threading</h2>
 * Every method is safe from any thread.
 *
 * @since 1.36.0
 */
public interface PluginTeams {

    /**
     * Creates an empty team.
     *
     * @return the team
     */
    @NotNull ClientTeam create();

    /**
     * Creates a team with these members already in it.
     *
     * @param players the members
     * @return the team
     */
    @NotNull ClientTeam create(@NotNull Collection<? extends Player> players);

    /**
     * Finds a team by id.
     *
     * @param teamId the id
     * @return the team, or {@code null} when it never existed or was deleted
     */
    ClientTeam find(@NotNull UUID teamId);

    /**
     * Returns the team a player is in.
     *
     * <p>Across every plugin, not just this one: a player is in one team at a
     * time, and which plugin put them there does not change that.
     *
     * @param player the player
     * @return their team, or {@code null} when they are in none
     */
    ClientTeam of(@NotNull Player player);

    /**
     * Removes a player from whichever team they are in.
     *
     * <p>For a caller that knows a player left the game but not which team
     * held them.
     *
     * @param player the player
     */
    void leave(@NotNull Player player);

    /**
     * Returns every team this plugin created and has not deleted.
     *
     * @return the teams, never {@code null}
     */
    @NotNull Collection<ClientTeam> all();

    /**
     * Deletes every team this plugin created.
     *
     * <p>Done automatically when the plugin is disabled.
     */
    void clear();
}
