package net.exylia.lib.client;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

    /**
     * Names and colours the team, for clients that draw a team panel.
     *
     * <p>A styled team is shown as a list on the HUD with each member's rank,
     * colour and health; an unstyled one is markers only. Clients that only
     * draw markers use the colours and ignore the rest.
     *
     * <p>Takes effect at the next draw: style the team before adding members,
     * or call {@link #refresh()} afterwards.
     *
     * @param name       the team's name
     * @param colour     the colour of the viewer's own group
     * @param allyColour the colour of every other group in the team
     * @since 1.231.0
     */
    void style(@NotNull Component name, @NotNull TextColor colour, @NotNull TextColor allyColour);

    /**
     * Says which group a member belongs to and what rank they hold there.
     *
     * <p>A team can hold several groups that fight together, such as allied
     * clans. Each viewer sees their own group in the team colour with its
     * ranks, and everyone else as an ally. A member never described is a
     * {@link Rank#MEMBER} of everyone's group.
     *
     * <p>Takes effect at the next draw, like {@link #style}.
     *
     * @param playerId the member
     * @param group    their group, such as a clan id; {@code null} for none
     * @param rank     their rank in it
     * @since 1.231.0
     */
    void describe(@NotNull UUID playerId, @Nullable String group, @NotNull Rank rank);

    /**
     * Names one group, so its members see their own group's name on the panel
     * rather than the team's.
     *
     * <p>For a team made of several groups, such as a clan and its allies:
     * each clan sees its own name. Takes effect at the next draw.
     *
     * @param group the group, as passed to {@link #describe}
     * @param name  its name
     * @since 1.231.1
     */
    void nameGroup(@NotNull String group, @NotNull Component name);

    /**
     * A member's standing in their group.
     *
     * @since 1.231.0
     */
    enum Rank {
        /** Runs the group. */
        LEADER,
        /** Helps run it. */
        OFFICER,
        /** A regular member. */
        MEMBER,
        /** On trial. */
        RECRUIT
    }
}
