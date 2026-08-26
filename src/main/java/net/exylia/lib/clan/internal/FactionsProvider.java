package net.exylia.lib.clan.internal;

import net.exylia.lib.clan.Clan;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * FactionsUUID integration through reflection.
 *
 * <p>Factions is the plugin that made alliances a genre convention, and its
 * relations are a graph rather than a list: a faction does not store who its
 * allies are, it answers what it thinks of a faction you name. So
 * {@link #alliesOf} and {@link #rivalsOf} walk every faction on the server to
 * answer, while {@link #areAllied} and {@link #areRivals} ask the one question
 * directly.
 *
 * <p>That is also why a {@link Clan} snapshot from this provider has empty
 * allies and rivals: filling them would mean the full walk on every lookup, and
 * these lookups sit on damage events. Call {@link #alliesOf} when the list is
 * what you actually need.
 */
final class FactionsProvider implements ClanProvider {

    private static final String PLUGIN = "Factions";
    private static final String FACTIONS = "dev.kitteh.factions.Factions";
    private static final String FPLAYERS = "dev.kitteh.factions.FPlayers";

    /** What Factions calls the two relations we model. */
    private static final String ALLY = "ALLY";
    private static final String ENEMY = "ENEMY";

    private final boolean present;

    private FactionsProvider(boolean present) {
        this.present = present;
    }

    static FactionsProvider tryCreate() {
        if (!Reflect.pluginEnabled(PLUGIN)) {
            return new FactionsProvider(false);
        }
        boolean reachable = Reflect.statically(FACTIONS, "factions") != null
                && Reflect.statically(FPLAYERS, "fPlayers") != null;
        return new FactionsProvider(reachable);
    }

    @Override
    public boolean enabled() {
        return present;
    }

    @Override
    public String name() {
        return "FactionsUUID";
    }

    // ------------------------------------------------------------------
    // Lookups
    // ------------------------------------------------------------------

    @Override
    public Optional<Clan> clanOf(UUID player) {
        return Optional.ofNullable(factionOf(player)).map(this::toClan);
    }

    @Override
    public Optional<Clan> clanOf(Player player) {
        return clanOf(player.getUniqueId());
    }

    @Override
    public Optional<Clan> byTag(String tag) {
        Object faction = Reflect.get(factions(), "get", tag);
        return Optional.ofNullable(real(faction)).map(this::toClan);
    }

    @Override
    public Optional<Clan> byId(String id) {
        try {
            Object faction = Reflect.get(factions(), "get", Integer.parseInt(id));
            return Optional.ofNullable(real(faction)).map(this::toClan);
        } catch (NumberFormatException e) {
            return byTag(id);
        }
    }

    @Override
    public Collection<Clan> all() {
        List<Clan> clans = new ArrayList<>();
        for (Object faction : allFactions()) {
            clans.add(toClan(faction));
        }
        return clans;
    }

    @Override
    public boolean hasClan(UUID player) {
        return factionOf(player) != null;
    }

    // ------------------------------------------------------------------
    // Relations
    // ------------------------------------------------------------------

    @Override
    public Collection<String> alliesOf(String clanId) {
        return related(clanId, ALLY);
    }

    @Override
    public Collection<String> rivalsOf(String clanId) {
        return related(clanId, ENEMY);
    }

    @Override
    public boolean areInSameClan(UUID player, UUID other) {
        Object first = factionOf(player);
        Object second = factionOf(other);
        return first != null && second != null && idOf(first).equals(idOf(second));
    }

    @Override
    public boolean areAllied(UUID player, UUID other) {
        return relationIs(player, other, ALLY);
    }

    @Override
    public boolean areRivals(UUID player, UUID other) {
        return relationIs(player, other, ENEMY);
    }

    @Override
    public Collection<UUID> onlineMembersOf(UUID player) {
        Object faction = factionOf(player);
        if (faction == null) {
            return List.of();
        }
        List<UUID> online = new ArrayList<>();
        for (Object member : Reflect.collection(faction, "members")) {
            UUID id = Reflect.uuid(member, "uniqueId", "getUniqueId", "getId");
            if (id != null && Reflect.isOnline(id)) {
                online.add(id);
            }
        }
        return online;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Object factions() {
        return Reflect.statically(FACTIONS, "factions");
    }

    /** Returns the faction a player belongs to, or {@code null} for none. */
    private Object factionOf(UUID player) {
        Object fPlayers = Reflect.statically(FPLAYERS, "fPlayers");
        Object fPlayer = Reflect.get(fPlayers, "get", player);
        if (fPlayer == null || !Reflect.flag(fPlayer, "hasFaction")) {
            return null;
        }
        return real(Reflect.getAny(fPlayer, new String[]{"faction", "getFaction"}));
    }

    /**
     * Returns the faction unless it is the wilderness.
     *
     * <p>Factions models "no faction" as membership in a pseudo-faction, and a
     * caller asking who a player's clan is must not be told "Wilderness".
     */
    private Object real(Object faction) {
        if (faction == null || Reflect.flag(faction, "isWilderness")) {
            return null;
        }
        return faction;
    }

    private Collection<Object> allFactions() {
        List<Object> factions = new ArrayList<>();
        for (Object faction : Reflect.collection(factions(), "all")) {
            if (real(faction) != null) {
                factions.add(faction);
            }
        }
        return factions;
    }

    private String idOf(Object faction) {
        Object id = Reflect.getAny(faction, new String[]{"id", "getId"});
        return id == null ? "" : String.valueOf(id);
    }

    private List<String> related(String clanId, String relation) {
        Object self = byIdRaw(clanId);
        if (self == null) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (Object other : allFactions()) {
            if (idOf(other).equals(clanId)) {
                continue;
            }
            if (relationName(self, other).equals(relation)) {
                ids.add(idOf(other));
            }
        }
        return ids;
    }

    private boolean relationIs(UUID player, UUID other, String relation) {
        Object first = factionOf(player);
        Object second = factionOf(other);
        if (first == null || second == null) {
            return false;
        }
        return relationName(first, second).equals(relation);
    }

    private String relationName(Object faction, Object other) {
        Object relation = Reflect.callAny(faction,
                new String[]{"relationTo", "relationWith", "getRelationTo"}, other);
        return relation == null ? "" : relation.toString().toUpperCase(java.util.Locale.ROOT);
    }

    private Object byIdRaw(String clanId) {
        for (Object faction : allFactions()) {
            if (idOf(faction).equals(clanId)) {
                return faction;
            }
        }
        return null;
    }

    private Clan toClan(Object faction) {
        String id = idOf(faction);
        String tag = Reflect.string(faction, "tag", "getTag");
        Clan.Builder builder = Clan.builder(id.isEmpty() ? tag : id)
                .name(tag).tag(tag).displayName(tag).provider("FactionsUUID");

        UUID admin = Reflect.uuid(Reflect.getAny(faction, new String[]{"admin", "getFPlayerAdmin"}),
                "uniqueId", "getUniqueId", "getId");
        if (admin != null) {
            builder.leader(admin);
        }

        int online = 0;
        for (Object member : Reflect.collection(faction, "members")) {
            UUID id2 = Reflect.uuid(member, "uniqueId", "getUniqueId", "getId");
            if (id2 == null || id2.equals(admin)) {
                continue;
            }
            String role = Reflect.string(member, "role", "getRole");
            if (role != null && (role.equalsIgnoreCase("COLEADER")
                    || role.equalsIgnoreCase("MODERATOR"))) {
                builder.moderator(id2);
            } else {
                builder.member(id2);
            }
            if (Reflect.isOnline(id2)) {
                online++;
            }
        }
        if (admin != null && Reflect.isOnline(admin)) {
            online++;
        }

        Object founded = Reflect.getAny(faction, new String[]{"founded", "getFoundedDate"});
        if (founded instanceof Instant instant) {
            builder.createdAt(instant.toEpochMilli());
        } else if (founded instanceof Number epoch) {
            builder.createdAt(epoch.longValue());
        }

        return builder.onlineCount(online).build();
    }
}
