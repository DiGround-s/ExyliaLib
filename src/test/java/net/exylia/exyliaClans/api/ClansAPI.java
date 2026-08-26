package net.exylia.exyliaClans.api;

import net.exylia.exyliaClans.database.Clan;
import net.exylia.exyliaClans.database.ClanMember;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A stand-in for ExyliaClans' public API, with the signatures the real one has.
 *
 * <p>Every method here mirrors
 * {@code ExyliaClans/src/main/java/net/exylia/exyliaClans/api/ClansAPI.java}:
 * same package, same name, same parameter and return types, including the
 * {@code Player} and {@code UUID} overloads that the provider's reflection has
 * to tell apart. The bodies are in-memory, because what is under test is
 * whether ExyliaLib still reaches this shape — not what ExyliaClans does with
 * it.
 *
 * <p>When the real API changes, this file stops matching it and the provider's
 * test starts failing, which is the point: the break surfaces here rather than
 * on a live server.
 */
public final class ClansAPI {

    private static final Map<String, Clan> CLANS = new LinkedHashMap<>();
    private static final Map<UUID, String> MEMBERSHIP = new LinkedHashMap<>();
    private static final Set<String> ALLIES = new java.util.LinkedHashSet<>();
    private static final Set<String> RIVALS = new java.util.LinkedHashSet<>();

    private ClansAPI() {
    }

    // ------------------------------------------------------------------
    // Test wiring, which the real API does not have
    // ------------------------------------------------------------------

    public static void reset() {
        CLANS.clear();
        MEMBERSHIP.clear();
        ALLIES.clear();
        RIVALS.clear();
    }

    public static void add(Clan clan, UUID... members) {
        CLANS.put(clan.getId(), clan);
        MEMBERSHIP.put(clan.getLeaderUUID(), clan.getId());
        for (UUID member : members) {
            MEMBERSHIP.put(member, clan.getId());
        }
    }

    public static void ally(String first, String second) {
        ALLIES.add(first + ">" + second);
    }

    public static void rival(String first, String second) {
        RIVALS.add(first + ">" + second);
    }

    // ------------------------------------------------------------------
    // The real API's surface
    // ------------------------------------------------------------------

    public static boolean isInClan(Player player) {
        return isInClan(player.getUniqueId());
    }

    public static boolean isInClan(UUID uuid) {
        return MEMBERSHIP.containsKey(uuid);
    }

    public static Optional<Clan> getClan(Player player) {
        return getClan(player.getUniqueId());
    }

    public static Optional<Clan> getClan(UUID uuid) {
        return Optional.ofNullable(MEMBERSHIP.get(uuid)).map(CLANS::get);
    }

    public static Optional<Clan> getClanById(String id) {
        return Optional.ofNullable(CLANS.get(id));
    }

    public static Optional<Clan> getClanByName(String name) {
        return CLANS.values().stream()
                .filter(clan -> clan.getDisplayName().equalsIgnoreCase(name))
                .findFirst();
    }

    public static List<ClanMember> getClanMembers(String clanId) {
        List<ClanMember> members = new ArrayList<>();
        MEMBERSHIP.forEach((player, id) -> {
            if (id.equals(clanId)) {
                members.add(new ClanMember(player, clanId));
            }
        });
        return members;
    }

    public static Collection<Clan> getAllClans() {
        return List.copyOf(CLANS.values());
    }

    public static boolean areAllies(String clanIdA, String clanIdB) {
        return ALLIES.contains(clanIdA + ">" + clanIdB)
                || ALLIES.contains(clanIdB + ">" + clanIdA);
    }

    public static boolean areRivals(String clanIdA, String clanIdB) {
        return RIVALS.contains(clanIdA + ">" + clanIdB);
    }

    public static boolean areSameClan(UUID playerA, UUID playerB) {
        String first = MEMBERSHIP.get(playerA);
        return first != null && first.equals(MEMBERSHIP.get(playerB));
    }
}
