package net.exylia.lib.clan.internal;

import net.exylia.lib.clan.Clan;
import org.bukkit.entity.Player;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * HuskTowns integration through reflection.
 *
 * <p>A town is a clan with land. The mapping is otherwise direct, except for
 * ranks: HuskTowns stores a member's role as a numeric weight rather than a
 * name, so the heaviest weight is the mayor, the lightest is a resident, and
 * anything between the two is a moderator.
 *
 * <p>HuskTowns has no alliances or rivalries, so both always come back empty.
 */
final class HuskTownsProvider implements ClanProvider {

    private static final String PLUGIN = "HuskTowns";
    private static final String API = "net.william278.husktowns.api.BukkitHuskTownsAPI";
    private static final String USER = "net.william278.husktowns.user.User";

    private final boolean present;

    private HuskTownsProvider(boolean present) {
        this.present = present;
    }

    static HuskTownsProvider tryCreate() {
        if (!Reflect.pluginEnabled(PLUGIN)) {
            return new HuskTownsProvider(false);
        }
        Object api = Reflect.statically(API, "getInstance");
        return new HuskTownsProvider(api != null && Reflect.flag(api, "isLoaded"));
    }

    @Override
    public boolean enabled() {
        return present;
    }

    @Override
    public String name() {
        return "HuskTowns";
    }

    // ------------------------------------------------------------------
    // Lookups
    // ------------------------------------------------------------------

    @Override
    public Optional<Clan> clanOf(UUID player) {
        return Optional.ofNullable(townOf(player)).map(this::toClan);
    }

    @Override
    public Optional<Clan> clanOf(Player player) {
        Object api = api();
        Object online = Reflect.get(api, "getOnlineUser", player);
        Object member = online == null ? null : Reflect.get(api, "getUserTown", online);
        if (member == null) {
            return clanOf(player.getUniqueId());
        }
        return Optional.ofNullable(Reflect.get(member, "town")).map(this::toClan);
    }

    @Override
    public Optional<Clan> byTag(String tag) {
        return byId(tag);
    }

    @Override
    public Optional<Clan> byId(String id) {
        Object api = api();
        Object town;
        try {
            town = Reflect.get(api, "getTown", Integer.parseInt(id));
        } catch (NumberFormatException e) {
            town = Reflect.get(api, "getTown", id);
        }
        return Optional.ofNullable(town).map(this::toClan);
    }

    @Override
    public Collection<Clan> all() {
        List<Clan> clans = new ArrayList<>();
        for (Object town : Reflect.collection(api(), "getTowns")) {
            clans.add(toClan(town));
        }
        return clans;
    }

    @Override
    public boolean hasClan(UUID player) {
        return townOf(player) != null;
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
        Object first = townOf(player);
        Object second = townOf(other);
        return first != null && second != null && idOf(first).equals(idOf(second));
    }

    @Override
    public Collection<UUID> onlineMembersOf(UUID player) {
        Object town = townOf(player);
        if (town == null) {
            return List.of();
        }
        List<UUID> online = new ArrayList<>();
        for (Object key : Reflect.map(town, "getMembers").keySet()) {
            UUID id = Reflect.toUuid(key);
            if (id != null && Reflect.isOnline(id)) {
                online.add(id);
            }
        }
        return online;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Object api() {
        return Reflect.statically(API, "getInstance");
    }

    /** Returns the town a player belongs to, or {@code null} for none. */
    private Object townOf(UUID player) {
        Object user = Reflect.statically(USER, "of", player, "");
        if (user == null) {
            return null;
        }
        Object member = Reflect.get(api(), "getUserTown", user);
        return member == null ? null : Reflect.get(member, "town");
    }

    private String idOf(Object town) {
        Object id = Reflect.getAny(town, new String[]{"getId", "id"});
        return id == null ? "" : String.valueOf(id);
    }

    private Clan toClan(Object town) {
        String name = Reflect.string(town, "getName", "name");
        Clan.Builder builder = Clan.builder(idOf(town))
                .name(name).tag(name).displayName(name)
                .level((int) Reflect.number(town, "getLevel"))
                .balance(Reflect.number(town, "getMoney"))
                .provider("HuskTowns");

        UUID mayor = Reflect.uuid(town, "getMayor");
        if (mayor != null) {
            builder.leader(mayor);
        }

        Map<?, ?> members = Reflect.map(town, "getMembers");
        int heaviest = weightBound(members, Comparator.naturalOrder());
        int lightest = weightBound(members, Comparator.reverseOrder());

        int online = 0;
        for (Map.Entry<?, ?> entry : members.entrySet()) {
            UUID id = Reflect.toUuid(entry.getKey());
            if (id == null) {
                continue;
            }
            int weight = entry.getValue() instanceof Number n ? n.intValue() : 0;
            if (!id.equals(mayor)) {
                // Between the two extremes is the rank that can invite and
                // kick but not disband, which is what a moderator is here.
                if (weight < heaviest && weight > lightest) {
                    builder.moderator(id);
                } else {
                    builder.member(id);
                }
            }
            if (Reflect.isOnline(id)) {
                online++;
            }
        }

        Object founded = Reflect.get(town, "getFoundedTime");
        if (founded instanceof OffsetDateTime moment) {
            builder.createdAt(moment.toInstant().toEpochMilli());
        }

        return builder.onlineCount(online).build();
    }

    private int weightBound(Map<?, ?> members, Comparator<Integer> order) {
        return members.values().stream()
                .filter(Number.class::isInstance)
                .map(value -> ((Number) value).intValue())
                .max(order)
                .orElse(0);
    }
}
