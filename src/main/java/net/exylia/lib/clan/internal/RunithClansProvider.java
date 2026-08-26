package net.exylia.lib.clan.internal;

import net.exylia.lib.clan.Clan;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * RunithClans integration through reflection.
 *
 * <p>Everything hangs off one storage object: a player resolves to a member,
 * a member knows its clan, and the clan carries the roster. Roles are an enum
 * behind {@code getRole().type()}, where a leader or admin can disband and a
 * co-leader or mod can only invite and kick.
 *
 * <p>RunithClans has no alliances or rivalries, so both always come back empty.
 */
final class RunithClansProvider implements ClanProvider {

    private static final String PLUGIN = "RunithClans";
    private static final String API = "net.runith.clan.api.ClanAPI";

    private final boolean present;

    private RunithClansProvider(boolean present) {
        this.present = present;
    }

    static RunithClansProvider tryCreate() {
        if (!Reflect.pluginEnabled(PLUGIN)) {
            return new RunithClansProvider(false);
        }
        return new RunithClansProvider(storageOf() != null);
    }

    @Override
    public boolean enabled() {
        return present;
    }

    @Override
    public String name() {
        return "RunithClans";
    }

    // ------------------------------------------------------------------
    // Lookups
    // ------------------------------------------------------------------

    @Override
    public Optional<Clan> clanOf(UUID player) {
        return Optional.ofNullable(clanFor(player)).map(this::toClan);
    }

    @Override
    public Optional<Clan> clanOf(Player player) {
        return clanOf(player.getUniqueId());
    }

    @Override
    public Optional<Clan> byTag(String tag) {
        return Optional.ofNullable(Reflect.get(storageOf(), "getClan", tag)).map(this::toClan);
    }

    @Override
    public Optional<Clan> byId(String id) {
        UUID clanId = Reflect.toUuid(id);
        if (clanId == null) {
            return byTag(id);
        }
        Object clan = Reflect.get(storageOf(), "getClan", clanId);
        return clan != null ? Optional.of(toClan(clan)) : byTag(id);
    }

    @Override
    public Collection<Clan> all() {
        List<Clan> clans = new ArrayList<>();
        for (Object clan : Reflect.collection(storageOf(), "getOnlineClans")) {
            clans.add(toClan(clan));
        }
        return clans;
    }

    @Override
    public boolean hasClan(UUID player) {
        return clanFor(player) != null;
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
        Object first = clanFor(player);
        Object second = clanFor(other);
        if (first == null || second == null) {
            return false;
        }
        UUID firstId = Reflect.uuid(first, "getUuid");
        UUID secondId = Reflect.uuid(second, "getUuid");
        return firstId != null && firstId.equals(secondId);
    }

    @Override
    public Collection<UUID> onlineMembersOf(UUID player) {
        Object clan = clanFor(player);
        if (clan == null) {
            return List.of();
        }
        List<UUID> online = new ArrayList<>();
        for (Object member : members(clan)) {
            UUID id = Reflect.uuid(member, "getUuid", "getUniqueId");
            if (id != null && Reflect.flag(member, "isOnline")) {
                online.add(id);
            }
        }
        return online;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Object storageOf() {
        return Reflect.get(Reflect.statically(API, "getInstance"), "storage");
    }

    /** Returns the clan a player belongs to, or {@code null} for none. */
    private Object clanFor(UUID player) {
        Object member = Reflect.get(storageOf(), "getMember", player);
        return member == null ? null : Reflect.get(member, "getClan");
    }

    private Collection<?> members(Object clan) {
        Object roster = Reflect.get(clan, "getClanMembers");
        return roster == null ? List.of() : Reflect.collection(roster, "members");
    }

    private Clan toClan(Object clan) {
        UUID clanId = Reflect.uuid(clan, "getUuid");
        String name = Reflect.string(clan, "getName");
        Clan.Builder builder = Clan.builder(clanId != null ? clanId.toString() : name)
                .name(name)
                .tag(Reflect.string(clan, "getTag"))
                .displayName(Reflect.string(clan, "getTag"))
                .balance(Reflect.number(clan, "getBalance"))
                .provider("RunithClans");

        int online = 0;
        for (Object member : members(clan)) {
            UUID id = Reflect.uuid(member, "getUuid", "getUniqueId");
            if (id == null) {
                continue;
            }
            String rank = rankOf(member);
            if (rank.equals("LEADER") || rank.equals("ADMIN")) {
                builder.leader(id);
            } else if (rank.equals("CO_LEADER") || rank.equals("MOD")) {
                builder.moderator(id);
            } else {
                builder.member(id);
            }
            if (Reflect.flag(member, "isOnline")) {
                online++;
            }
        }

        return builder.onlineCount(online).build();
    }

    private String rankOf(Object member) {
        Object role = Reflect.get(member, "getRole");
        Object type = Reflect.getAny(role, new String[]{"type", "getType"});
        return type == null ? "" : type.toString().toUpperCase(java.util.Locale.ROOT);
    }
}
