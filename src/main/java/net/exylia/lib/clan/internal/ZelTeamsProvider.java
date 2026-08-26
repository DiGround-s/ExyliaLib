package net.exylia.lib.clan.internal;

import net.exylia.lib.clan.Clan;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ZelTeams integration through reflection.
 *
 * <p>ZelTeams calls a clan a team and keeps the owner outside the member list,
 * so the owner is added as leader before the roster is read. Rank is a numeric
 * priority: anything above zero can act on other members, which is what this
 * library calls a moderator.
 *
 * <p>ZelTeams has no alliances or rivalries, so both always come back empty.
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
        Object api = Reflect.statically(API, "getInstance");
        Object manager = Reflect.get(api, "getTeamManager");
        return new ZelTeamsProvider(manager != null);
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
        return Optional.ofNullable(Reflect.get(manager(), "getOfflinePlayerTeam", player))
                .map(this::toClan);
    }

    @Override
    public Optional<Clan> clanOf(Player player) {
        Object team = Reflect.get(manager(), "getTeam", player);
        return team != null ? Optional.of(toClan(team)) : clanOf(player.getUniqueId());
    }

    @Override
    public Optional<Clan> byTag(String tag) {
        return Optional.ofNullable(Reflect.get(manager(), "getByTag", tag)).map(this::toClan);
    }

    @Override
    public Optional<Clan> byId(String id) {
        UUID teamId = Reflect.toUuid(id);
        if (teamId != null) {
            Object team = Reflect.map(manager(), "getCachedTeams").get(teamId);
            if (team != null) {
                return Optional.of(toClan(team));
            }
        }
        return Optional.ofNullable(Reflect.get(manager(), "getTeamByName", id)).map(this::toClan);
    }

    @Override
    public Collection<Clan> all() {
        List<Clan> clans = new ArrayList<>();
        for (Object team : Reflect.map(manager(), "getCachedTeams").values()) {
            if (team != null) {
                clans.add(toClan(team));
            }
        }
        return clans;
    }

    @Override
    public boolean hasClan(UUID player) {
        return Reflect.get(manager(), "getOfflinePlayerTeam", player) != null;
    }

    @Override
    public Collection<String> alliesOf(String clanId) {
        return List.of();
    }

    @Override
    public Collection<String> rivalsOf(String clanId) {
        return List.of();
    }

    @Override
    public boolean areInSameClan(UUID player, UUID other) {
        Object first = Reflect.get(manager(), "getOfflinePlayerTeam", player);
        Object second = Reflect.get(manager(), "getOfflinePlayerTeam", other);
        if (first == null || second == null) {
            return false;
        }
        UUID firstId = Reflect.uuid(first, "getTeamUUID");
        UUID secondId = Reflect.uuid(second, "getTeamUUID");
        return firstId != null && firstId.equals(secondId);
    }

    @Override
    public Collection<UUID> onlineMembersOf(UUID player) {
        Object team = Reflect.get(manager(), "getOfflinePlayerTeam", player);
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

    private Object manager() {
        return Reflect.get(Reflect.statically(API, "getInstance"), "getTeamManager");
    }

    /** Returns every member id, the owner included. */
    private List<UUID> roster(Object team) {
        List<UUID> ids = new ArrayList<>();
        UUID owner = Reflect.uuid(Reflect.get(team, "getOwner"), "getUuid", "getUniqueId");
        if (owner != null) {
            ids.add(owner);
        }
        for (Object member : Reflect.collection(team, "getAllMembers")) {
            UUID id = Reflect.uuid(member, "getUuid", "getUniqueId");
            if (id != null && !id.equals(owner)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private Clan toClan(Object team) {
        UUID teamId = Reflect.uuid(team, "getTeamUUID");
        String name = Reflect.string(team, "getName");
        Clan.Builder builder = Clan.builder(teamId != null ? teamId.toString() : name)
                .name(name)
                .tag(Reflect.string(team, "getTag"))
                .displayName(Reflect.string(team, "getDisplayName"))
                .balance(Reflect.number(team, "getBankBalance"))
                .provider("ZelTeams");

        int rank = (int) Reflect.number(team, "getRank");
        builder.level(Math.max(rank, 0));

        UUID owner = Reflect.uuid(Reflect.get(team, "getOwner"), "getUuid", "getUniqueId");
        if (owner != null) {
            builder.leader(owner);
        }

        int online = owner != null && Reflect.isOnline(owner) ? 1 : 0;
        for (Object member : Reflect.collection(team, "getAllMembers")) {
            UUID id = Reflect.uuid(member, "getUuid", "getUniqueId");
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
