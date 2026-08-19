package net.exylia.lib.client;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.UUID;

/**
 * A group of players who see each other's markers.
 *
 * <p>Handed out by {@link PluginTeams}. A team is a handle, not a copy: the
 * members it reports are whoever is in it right now.
 *
 * <pre>{@code
 * PluginTeams teams = Clients.teams(this);
 *
 * ClientTeam red = teams.create();
 * red.add(player);
 * ...
 * red.delete();   // when the game ends
 * }</pre>
 *
 * <h2>Why a registry and not just {@link Clients.Markers}</h2>
 * {@code markers()} is a push: it draws a set of teammates and forgets. A game
 * that lasts has to answer "who is on this team" every time somebody joins,
 * leaves, dies or reconnects, and every caller that kept that list in a map of
 * its own got the same three things wrong — a player in two teams at once, a
 * team left behind when the game ended, and a member who logged out. The team
 * owns the list, so those are answered once.
 *
 * <h2>Lifecycle</h2>
 * A team lives until {@link #delete()} or until the plugin that created it is
 * disabled. It is not tied to a world or a game; deleting it clears the markers
 * of everyone who was in it.
 *
 * <h2>Threading</h2>
 * Every method is safe from any thread.
 *
 * @since 1.36.0
 */
public interface ClientTeam {

    /**
     * Returns the id this team was created with.
     *
     * <p>For a caller that stores teams in a map of its own rather than
     * holding the handle.
     *
     * @return the id, unique for the life of the server
     */
    @NotNull UUID id();

    /**
     * Adds a player, removing them from whichever team they were in.
     *
     * <p>A player belongs to one team at a time. Two teams both believing they
     * own the same player is how a player ends up seeing the other team's
     * markers, so the previous team is left first.
     *
     * @param player the player
     */
    void add(@NotNull Player player);

    /**
     * Adds several players at once.
     *
     * <p>Cheaper than a loop of {@link #add(Player)}: the markers are drawn
     * once at the end rather than once per player added.
     *
     * @param players the players
     */
    void addAll(@NotNull Collection<? extends Player> players);

    /**
     * Removes a player and clears the markers they were seeing.
     *
     * @param player the player
     */
    void remove(@NotNull Player player);

    /**
     * Returns whether a player is in this team.
     *
     * @param playerId the player's id
     * @return {@code true} when they are a member
     */
    boolean has(@NotNull UUID playerId);

    /**
     * Returns the members who are still online.
     *
     * <p>Offline members are dropped rather than reported: a team holding a
     * player who logged out is the stale reference this module exists to avoid.
     *
     * @return the online members, never {@code null}
     */
    @NotNull Collection<Player> members();

    /**
     * Returns how many members are online.
     *
     * @return the member count
     */
    int size();

    /**
     * Re-draws every member's markers.
     *
     * <p>Rarely needed: the team does this itself whenever it changes. Useful
     * after something the team cannot see, such as a member changing world.
     */
    void refresh();

    /**
     * Deletes the team and clears its members' markers.
     *
     * <p>Deleting twice is not an error.
     */
    void delete();

    /**
     * Returns whether this team still exists.
     *
     * @return {@code false} once deleted
     */
    boolean alive();
}
