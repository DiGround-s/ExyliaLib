package net.exylia.lib.client.internal;

import net.exylia.lib.client.ClientTeam;
import net.exylia.lib.client.Clients;
import net.exylia.lib.client.PluginTeams;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is on which team, and whose team it is.
 *
 * <p>Two indexes rather than one: teams by id, and the team each player is in.
 * The second is what makes "a player belongs to one team" cheap to enforce —
 * without it, adding a player would mean walking every team of every plugin to
 * find the one that already had them.
 *
 * <p>Membership is stored as ids, never as {@code Player} objects. A team that
 * outlives a player's session must not be the reason the server keeps their
 * entity alive; the online player is looked up when the markers are drawn.
 */
public final class TeamRegistry {

    /** Every live team, by id. */
    private static final Map<UUID, Team> TEAMS = new ConcurrentHashMap<>();

    /** Which team each player is in. One team per player, server-wide. */
    private static final Map<UUID, UUID> PLAYER_TEAM = new ConcurrentHashMap<>();

    /** Each plugin's registry, so disabling one takes its teams with it. */
    private static final Map<String, Teams> BY_PLUGIN = new ConcurrentHashMap<>();

    private TeamRegistry() {
    }

    /** Returns the registry for a plugin, creating it on first use. */
    public static PluginTeams of(String pluginName) {
        return BY_PLUGIN.computeIfAbsent(pluginName, Teams::new);
    }

    /** Deletes every team belonging to a plugin that is going away. */
    public static void release(String pluginName) {
        Teams teams = BY_PLUGIN.remove(pluginName);
        if (teams != null) {
            teams.clear();
        }
    }

    /**
     * Drops a player who left from whichever team held them.
     *
     * <p>Their own markers are not cleared: their client is gone. Their
     * teammates are re-drawn, because a marker pointing at somebody who left
     * is the one thing the viewer can still see.
     */
    public static void forget(UUID playerId) {
        UUID teamId = PLAYER_TEAM.remove(playerId);
        if (teamId == null) {
            return;
        }
        Team team = TEAMS.get(teamId);
        if (team != null) {
            team.members.remove(playerId);
            team.draw();
        }
    }

    /** Re-draws the team a player is in, after their client forgot it. */
    public static void resend(UUID playerId) {
        UUID teamId = PLAYER_TEAM.get(playerId);
        if (teamId == null) {
            return;
        }
        Team team = TEAMS.get(teamId);
        if (team != null) {
            team.draw();
        }
    }

    /** Drops everything. Used on shutdown and by tests. */
    public static void clear() {
        TEAMS.clear();
        PLAYER_TEAM.clear();
        BY_PLUGIN.clear();
    }

    /** How many teams exist. For diagnostics and tests. */
    public static int tracked() {
        return TEAMS.size();
    }

    // ------------------------------------------------------------------

    /** One plugin's view of the shared registry. */
    private static final class Teams implements PluginTeams {

        private final String plugin;

        private Teams(String plugin) {
            this.plugin = plugin;
        }

        @Override
        public @NotNull ClientTeam create() {
            Team team = new Team(plugin);
            TEAMS.put(team.id, team);
            return team;
        }

        @Override
        public @NotNull ClientTeam create(@NotNull Collection<? extends Player> players) {
            ClientTeam team = create();
            team.addAll(players);
            return team;
        }

        @Override
        public ClientTeam find(@NotNull UUID teamId) {
            return TEAMS.get(teamId);
        }

        @Override
        public ClientTeam of(@NotNull Player player) {
            UUID teamId = PLAYER_TEAM.get(player.getUniqueId());
            return teamId == null ? null : TEAMS.get(teamId);
        }

        @Override
        public void leave(@NotNull Player player) {
            UUID teamId = PLAYER_TEAM.get(player.getUniqueId());
            if (teamId == null) {
                return;
            }
            Team team = TEAMS.get(teamId);
            if (team != null) {
                team.remove(player);
            }
        }

        @Override
        public @NotNull Collection<ClientTeam> all() {
            List<ClientTeam> mine = new ArrayList<>();
            for (Team team : TEAMS.values()) {
                if (team.plugin.equals(plugin)) {
                    mine.add(team);
                }
            }
            return mine;
        }

        @Override
        public void clear() {
            for (ClientTeam team : all()) {
                team.delete();
            }
        }
    }

    // ------------------------------------------------------------------

    /** A live team. */
    private static final class Team implements ClientTeam {

        private final UUID id = UUID.randomUUID();
        private final String plugin;

        /** Member ids, in insertion order so the markers are stable. */
        private final Set<UUID> members = java.util.Collections.synchronizedSet(new LinkedHashSet<>());

        private volatile boolean alive = true;

        private Team(String plugin) {
            this.plugin = plugin;
        }

        @Override
        public @NotNull UUID id() {
            return id;
        }

        @Override
        public void add(@NotNull Player player) {
            if (!alive) {
                return;
            }
            if (claim(player.getUniqueId())) {
                draw();
            }
        }

        @Override
        public void addAll(@NotNull Collection<? extends Player> players) {
            if (!alive) {
                return;
            }
            boolean changed = false;
            for (Player player : players) {
                if (player != null && claim(player.getUniqueId())) {
                    changed = true;
                }
            }
            if (changed) {
                draw();
            }
        }

        /**
         * Takes a player from whoever had them.
         *
         * @return whether this team's membership changed
         */
        private boolean claim(UUID playerId) {
            UUID previousId = PLAYER_TEAM.put(playerId, id);
            if (id.equals(previousId)) {
                return false;
            }
            if (previousId != null) {
                Team previous = TEAMS.get(previousId);
                if (previous != null) {
                    previous.members.remove(playerId);
                    previous.draw();
                }
            }
            members.add(playerId);
            return true;
        }

        @Override
        public void remove(@NotNull Player player) {
            UUID playerId = player.getUniqueId();
            if (!members.remove(playerId)) {
                return;
            }
            PLAYER_TEAM.remove(playerId, id);
            // The player who left keeps nothing: they are no longer being told
            // about anyone, so what they still see would never be updated.
            Clients.markers().clear(player);
            draw();
        }

        @Override
        public boolean has(@NotNull UUID playerId) {
            return members.contains(playerId);
        }

        @Override
        public @NotNull Collection<Player> members() {
            return online();
        }

        @Override
        public int size() {
            return online().size();
        }

        @Override
        public void refresh() {
            if (alive) {
                draw();
            }
        }

        @Override
        public void delete() {
            if (!alive) {
                return;
            }
            alive = false;
            TEAMS.remove(id);
            List<Player> leaving = online();
            for (UUID memberId : snapshot()) {
                PLAYER_TEAM.remove(memberId, id);
            }
            members.clear();
            for (Player member : leaving) {
                Clients.markers().clear(member);
            }
        }

        @Override
        public boolean alive() {
            return alive;
        }

        /** Draws the current membership for every member who can see it. */
        private void draw() {
            List<Player> present = online();
            if (present.isEmpty()) {
                return;
            }
            Clients.markers().updateTeam(present);
        }

        /**
         * Resolves the members who are online.
         *
         * <p>A member who logged out is dropped here rather than left in the
         * set, so a team that nobody cleaned up still shrinks to nothing.
         */
        private List<Player> online() {
            List<Player> present = new ArrayList<>();
            for (UUID memberId : snapshot()) {
                Player player = Bukkit.getPlayer(memberId);
                if (player != null && player.isOnline()) {
                    present.add(player);
                } else {
                    members.remove(memberId);
                    PLAYER_TEAM.remove(memberId, id);
                }
            }
            return present;
        }

        /** A copy, so iterating cannot race a member being added. */
        private List<UUID> snapshot() {
            synchronized (members) {
                return List.copyOf(members);
            }
        }
    }
}
