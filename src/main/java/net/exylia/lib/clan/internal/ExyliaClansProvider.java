package net.exylia.lib.clan.internal;

import net.exylia.lib.clan.Clan;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ExyliaClans integration through reflection.
 *
 * <p>Our own clan plugin, reached the same way as any other: through its public
 * API and by name, so ExyliaLib never depends on it and a server without it
 * loses nothing.
 *
 * <p>ExyliaClans keeps relations in a table keyed by both clans rather than as
 * a list on each clan, so {@link #areAllied} and {@link #areRivals} ask one
 * question and {@link #alliesOf} walks every clan to answer. A {@link Clan}
 * snapshot therefore carries no allies or rivals: filling them would mean that
 * walk on every lookup, and these run on damage events.
 */
final class ExyliaClansProvider implements ClanProvider {

    private static final String PLUGIN = "ExyliaClans";
    private static final String API = "net.exylia.exyliaClans.api.ClansAPI";

    private final boolean present;

    private ExyliaClansProvider(boolean present) {
        this.present = present;
    }

    static ExyliaClansProvider tryCreate() {
        return new ExyliaClansProvider(
                Reflect.pluginEnabled(PLUGIN) && Reflect.type(API) != null);
    }

    @Override
    public boolean enabled() {
        return present;
    }

    @Override
    public String name() {
        return "ExyliaClans";
    }

    // ------------------------------------------------------------------
    // Lookups
    // ------------------------------------------------------------------

    @Override
    public Optional<Clan> clanOf(UUID player) {
        return Optional.ofNullable(api("getClan", player)).map(this::toClan);
    }

    @Override
    public Optional<Clan> clanOf(Player player) {
        return clanOf(player.getUniqueId());
    }

    @Override
    public Optional<Clan> byTag(String tag) {
        return Optional.ofNullable(api("getClanByName", tag)).map(this::toClan);
    }

    @Override
    public Optional<Clan> byId(String id) {
        Object clan = api("getClanById", id);
        return clan != null ? Optional.of(toClan(clan)) : byTag(id);
    }

    @Override
    public Collection<Clan> all() {
        List<Clan> clans = new ArrayList<>();
        for (Object clan : rawClans()) {
            clans.add(toClan(clan));
        }
        return clans;
    }

    @Override
    public boolean hasClan(UUID player) {
        return Boolean.TRUE.equals(api("isInClan", player));
    }

    // ------------------------------------------------------------------
    // Relations
    // ------------------------------------------------------------------

    @Override
    public Collection<String> alliesOf(String clanId) {
        return related(clanId, "areAllies");
    }

    @Override
    public Collection<String> rivalsOf(String clanId) {
        return related(clanId, "areRivals");
    }

    @Override
    public boolean areInSameClan(UUID player, UUID other) {
        return Boolean.TRUE.equals(api("areSameClan", player, other));
    }

    @Override
    public boolean areAllied(UUID player, UUID other) {
        return relation("areAllies", player, other);
    }

    @Override
    public boolean areRivals(UUID player, UUID other) {
        return relation("areRivals", player, other);
    }

    @Override
    public Collection<UUID> onlineMembersOf(UUID player) {
        Object clan = api("getClan", player);
        if (clan == null) {
            return List.of();
        }
        List<UUID> online = new ArrayList<>();
        for (UUID id : roster(clan)) {
            if (Reflect.isOnline(id)) {
                online.add(id);
            }
        }
        return online;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Calls a static API method and unwraps the optional it hands back. */
    private static Object api(String method, Object... args) {
        return Reflect.unwrap(Reflect.statically(API, method, args));
    }

    private Collection<?> rawClans() {
        Object clans = api("getAllClans");
        return clans instanceof Collection<?> collection ? collection : List.of();
    }

    private String idOf(Object clan) {
        String id = Reflect.string(clan, "getId");
        return id == null ? "" : id;
    }

    /**
     * Returns whether two players' clans stand in the named relation.
     *
     * <p>Asked both ways round, because a rivalry ExyliaClans records on one
     * side only is still a rivalry from the other side's point of view.
     */
    private boolean relation(String method, UUID player, UUID other) {
        Object first = api("getClan", player);
        Object second = api("getClan", other);
        if (first == null || second == null) {
            return false;
        }
        String firstId = idOf(first);
        String secondId = idOf(second);
        return Boolean.TRUE.equals(api(method, firstId, secondId))
                || Boolean.TRUE.equals(api(method, secondId, firstId));
    }

    private List<String> related(String clanId, String method) {
        List<String> ids = new ArrayList<>();
        for (Object other : rawClans()) {
            String otherId = idOf(other);
            if (otherId.equals(clanId)) {
                continue;
            }
            if (Boolean.TRUE.equals(api(method, clanId, otherId))
                    || Boolean.TRUE.equals(api(method, otherId, clanId))) {
                ids.add(otherId);
            }
        }
        return ids;
    }

    /** Returns every member id, the leader included. */
    private List<UUID> roster(Object clan) {
        List<UUID> ids = new ArrayList<>();
        UUID leader = Reflect.uuid(clan, "getLeaderUUID", "getLeaderId");
        if (leader != null) {
            ids.add(leader);
        }
        Object members = Reflect.statically(API, "getClanMembers", idOf(clan));
        if (members instanceof Collection<?> collection) {
            for (Object member : collection) {
                UUID id = Reflect.uuid(member, "getUUID", "getUuid");
                if (id != null && !id.equals(leader)) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    private Clan toClan(Object clan) {
        String name = Reflect.string(clan, "getDisplayName", "getName");
        Clan.Builder builder = Clan.builder(idOf(clan))
                .name(name).tag(name).displayName(name)
                .balance(Reflect.number(clan, "getBalance"))
                .provider("ExyliaClans");

        UUID leader = Reflect.uuid(clan, "getLeaderUUID", "getLeaderId");
        if (leader != null) {
            builder.leader(leader);
        }

        int online = 0;
        for (UUID id : roster(clan)) {
            if (!id.equals(leader)) {
                builder.member(id);
            }
            if (Reflect.isOnline(id)) {
                online++;
            }
        }

        return builder.onlineCount(online).build();
    }
}
