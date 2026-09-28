package net.exylia.lib.clan.internal;

import net.exylia.lib.clan.Clan;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ZelTeams integration through reflection, written against
 * {@code com.github.Zeltuv:zelteams-api:3.4.0}.
 *
 * <p>ZelTeams calls a clan a team and names it by its tag alone: there is no
 * separate team name, and {@code getDisplayName} answers the tag too. The team
 * UUID is the id; a lookup by anything else is a lookup by tag.
 *
 * <p>The owner is part of {@code getAllMembers} and is read separately, as the
 * leader. Rank is a numeric role priority: anything above zero can act on
 * other members, which is what this library calls a moderator.
 *
 * <p>ZelTeams has alliances, stored on each team as the other teams' UUIDs,
 * and no rivalries. {@code getRank} is a leaderboard position rather than a
 * level, so a ZelTeams clan has no level.
 *
 * <p>A disbanded team can still be cached for a moment; a closed one is read
 * as no team at all.
 */
final class ZelTeamsProvider implements ClanProvider {

    private static final String PLUGIN = "ZelTeams";
    private static final String API = "com.zeltuv.teams.api.ZelTeamsAPI";

    private final boolean present;

    private ZelTeamsProvider(boolean present) {
        this.present = present;
    }

    static ZelTeamsProvider tryCreate() {
        if (!Reflect.pluginEnabled(PLUGIN)) {
            return new ZelTeamsProvider(false);
        }
        return new ZelTeamsProvider(manager() != null);
    }

    @Override
    public boolean enabled() {
        return present;
    }

    @Override
    public String name() {
        return "ZelTeams";
    }

    // ------------------------------------------------------------------
    // Lookups
    // ------------------------------------------------------------------

    @Override
    public Optional<Clan> clanOf(UUID player) {
        return Optional.ofNullable(teamOf(player)).map(this::toClan);
    }

    @Override
    public Optional<Clan> clanOf(Player player) {
        Object team = open(Reflect.get(manager(), "getTeam", player));
        return team != null ? Optional.of(toClan(team)) : clanOf(player.getUniqueId());
    }

    @Override
    public Optional<Clan> byTag(String tag) {
        return Optional.ofNullable(open(Reflect.get(manager(), "getByTag", tag))).map(this::toClan);
    }

    @Override
    public Optional<Clan> byId(String id) {
        Object team = teamById(id);
        return team != null ? Optional.of(toClan(team)) : byTag(id);
    }

    @Override
    public Collection<Clan> all() {
        List<Clan> clans = new ArrayList<>();
        for (Object team : Reflect.map(manager(), "getCachedTeams").values()) {
            if (open(team) != null) {
                clans.add(toClan(team));
            }
        }
        return clans;
    }

    @Override
    public boolean hasClan(UUID player) {
        return teamOf(player) != null;
    }

    @Override
    public Collection<String> alliesOf(String clanId) {
        Object team = teamById(clanId);
        return team == null ? List.of() : allyIds(team);
    }

    @Override
    public Collection<String> rivalsOf(String clanId) {
        return List.of();
    }

    @Override
    public boolean areInSameClan(UUID player, UUID other) {
        UUID first = idOf(teamOf(player));
        return first != null && first.equals(idOf(teamOf(other)));
    }

    @Override
    public boolean areAllied(UUID player, UUID other) {
        Object first = teamOf(player);
        Object second = teamOf(other);
        return first != null && second != null
                && Boolean.TRUE.equals(Reflect.call(first, "isAlliedWith", second));
    }

    @Override
    public Collection<UUID> onlineMembersOf(UUID player) {
        Object team = teamOf(player);
        if (team == null) {
            return List.of();
        }
        List<UUID> online = new ArrayList<>();
        for (UUID id : roster(team)) {
            if (Reflect.isOnline(id)) {
                online.add(id);
            }
        }
        return online;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Object manager() {
        return Reflect.get(Reflect.statically(API, "getInstance"), "getTeamManager");
    }

    private static Object teamOf(UUID player) {
        return open(Reflect.get(manager(), "getOfflinePlayerTeam", player));
    }

    private static Object teamById(String id) {
        UUID teamId = Reflect.toUuid(id);
        return teamId == null ? null : open(Reflect.map(manager(), "getCachedTeams").get(teamId));
    }

    /** The team, or {@code null} when there is none or it was disbanded. */
    private static Object open(Object team) {
        return team == null || Reflect.flag(team, "isClosed") ? null : team;
    }

    private static UUID idOf(Object team) {
        return team == null ? null : Reflect.uuid(team, "getTeamUUID");
    }

    private static UUID ownerOf(Object team) {
        return Reflect.uuid(Reflect.get(team, "getOwner"), "getUuid");
    }

    private static List<String> allyIds(Object team) {
        List<String> allies = new ArrayList<>();
        for (Object ally : Reflect.collection(team, "getAllyList")) {
            UUID id = Reflect.toUuid(ally);
            if (id != null) {
                allies.add(id.toString());
            }
        }
        return allies;
    }

    /** Returns every member id, the owner first. */
    private static List<UUID> roster(Object team) {
        List<UUID> ids = new ArrayList<>();
        UUID owner = ownerOf(team);
        if (owner != null) {
            ids.add(owner);
        }
        for (Object member : Reflect.collection(team, "getAllMembers")) {
            UUID id = Reflect.uuid(member, "getUuid");
            if (id != null && !id.equals(owner)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private Clan toClan(Object team) {
        UUID teamId = idOf(team);
        String tag = Reflect.string(team, "getTag");
        Clan.Builder builder = Clan.builder(teamId != null ? teamId.toString() : tag)
                .name(tag)
                .tag(tag)
                .displayName(Reflect.string(team, "getDisplayName"))
                .balance(Reflect.number(team, "getBankBalance"))
                .allies(allyIds(team))
                .provider("ZelTeams");

        UUID owner = ownerOf(team);
        if (owner != null) {
            builder.leader(owner);
        }

        int online = owner != null && Reflect.isOnline(owner) ? 1 : 0;
        for (Object member : Reflect.collection(team, "getAllMembers")) {
            UUID id = Reflect.uuid(member, "getUuid");
            if (id == null || id.equals(owner)) {
                continue;
            }
            int priority = (int) Reflect.number(Reflect.get(member, "getRole"), "getPriority");
            if (priority > 0) {
                builder.moderator(id);
            } else {
                builder.member(id);
            }
            if (Reflect.isOnline(id)) {
                online++;
            }
        }

        return builder.onlineCount(online).build();
    }
}
