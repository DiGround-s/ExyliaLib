package net.exylia.lib.clan.internal;

import net.exylia.lib.clan.Clan;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A provider that answers from an in-memory table, for tests.
 *
 * <p>Reflection is not testable and mocking is not truthful. This is honest:
 * what the module asks, this answers, and every call is recorded so the test
 * can prove who was asked and how many times.
 */
final class FakeProvider implements ClanProvider {

    private final Map<UUID, String> clans = new ConcurrentHashMap<>();
    private final Map<String, Clan> data = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> allies = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> rivals = new ConcurrentHashMap<>();

    final AtomicInteger lookupCount = new AtomicInteger();

    private boolean enabled = true;
    private final String name;

    FakeProvider(String name) {
        this.name = name;
    }

    /** Adds a clan without associating a player — for by-tag lookups. */
    void addClan(Clan clan) {
        data.put(clan.tag(), clan);
    }

    void add(UUID player, Clan clan) {
        clans.put(player, clan.tag());
        data.put(clan.tag(), clan);
    }

    void addAllies(String clanId, Set<String> alliedIds) {
        allies.put(clanId, alliedIds);
    }

    void addRivals(String clanId, Set<String> rivalIds) {
        rivals.put(clanId, rivalIds);
    }

    void disabled() { enabled = false; }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public @NotNull String name() {
        return name;
    }

    @Override
    public @NotNull Optional<Clan> clanOf(@NotNull UUID player) {
        lookupCount.incrementAndGet();
        String tag = clans.get(player);
        return Optional.ofNullable(tag).map(data::get);
    }

    @Override
    public @NotNull Optional<Clan> clanOf(@NotNull Player player) {
        return clanOf(player.getUniqueId());
    }

    @Override
    public @NotNull Optional<Clan> byTag(@NotNull String tag) {
        return Optional.ofNullable(data.get(tag));
    }

    @Override
    public @NotNull Optional<Clan> byId(@NotNull String id) {
        return byTag(id);
    }

    @Override
    public @NotNull Collection<Clan> all() {
        return List.copyOf(data.values());
    }

    @Override
    public boolean hasClan(@NotNull UUID player) {
        return clans.containsKey(player);
    }

    @Override
    public @NotNull Collection<String> alliesOf(@NotNull String clanId) {
        Set<String> result = allies.get(clanId);
        return result == null ? List.of() : List.copyOf(result);
    }

    @Override
    public @NotNull Collection<String> rivalsOf(@NotNull String clanId) {
        Set<String> result = rivals.get(clanId);
        return result == null ? List.of() : List.copyOf(result);
    }

    @Override
    public boolean areInSameClan(@NotNull UUID player, @NotNull UUID other) {
        String t1 = clans.get(player);
        String t2 = clans.get(other);
        return t1 != null && t2 != null && t1.equals(t2);
    }

    @Override
    public @NotNull Collection<UUID> onlineMembersOf(@NotNull UUID player) {
        List<UUID> online = new ArrayList<>();
        String tag = clans.get(player);
        if (tag == null) return online;
        Clan clan = data.get(tag);
        if (clan == null) return online;
        for (UUID member : clan.allMembers()) {
            Player p = org.bukkit.Bukkit.getPlayer(member);
            if (p != null && p.isOnline()) online.add(member);
        }
        return online;
    }
}
