package net.exylia.lib.clan.internal;

import net.exylia.lib.clan.Clan;
import net.exylia.lib.clan.ClanBridge;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A {@link ClanProvider} wrapping an external {@link ClanBridge}.
 *
 * <p>Converts snapshots to the library's own model, once per lookup. The bridge
 * contract is intentionally limited to UUIDs and primitives so the implementor
 * does not pull in Bukkit.
 */
final class BridgeAdapter implements ClanProvider {

    private final ClanBridge bridge;

    BridgeAdapter(ClanBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public boolean enabled() {
        return bridge.available();
    }

    @Override
    public String name() {
        return bridge.name();
    }

    @Override
    public Optional<Clan> clanOf(UUID player) {
        ClanBridge.Snapshot snap = bridge.of(player);
        return Optional.ofNullable(snap).map(s -> toClan(s));
    }

    @Override
    public Optional<Clan> clanOf(Player player) {
        return clanOf(player.getUniqueId());
    }

    @Override
    public Optional<Clan> byTag(String tag) {
        ClanBridge.Snapshot snap = bridge.byTag(tag);
        return Optional.ofNullable(snap).map(s -> toClan(s));
    }

    @Override
    public Optional<Clan> byId(String id) {
        ClanBridge.Snapshot snap = bridge.byId(id);
        return Optional.ofNullable(snap).map(s -> toClan(s));
    }

    @Override
    public Collection<Clan> all() {
        return bridge.all().stream().map(this::toClan).toList();
    }

    @Override
    public boolean hasClan(UUID player) {
        return bridge.hasClan(player);
    }

    @Override
    public Collection<String> alliesOf(String clanId) {
        return bridge.alliesOf(clanId);
    }

    @Override
    public Collection<String> rivalsOf(String clanId) {
        return bridge.rivalsOf(clanId);
    }

    @Override
    public boolean areInSameClan(UUID player, UUID other) {
        return bridge.sameClan(player, other);
    }

    @Override
    public Collection<UUID> onlineMembersOf(UUID player) {
        Optional<Clan> found = clanOf(player);
        if (found.isEmpty()) {
            return List.of();
        }
        List<UUID> online = new ArrayList<>();
        for (UUID member : found.get().allMembers()) {
            Player p = Bukkit.getPlayer(member);
            if (p != null && p.isOnline()) {
                online.add(member);
            }
        }
        return online;
    }

    private Clan toClan(ClanBridge.Snapshot snap) {
        return Clan.builder(snap.id())
                .name(snap.name())
                .tag(snap.tag())
                .displayName(snap.displayName())
                .leaders(snap.leaders())
                .moderators(snap.moderators())
                .members(snap.members())
                .onlineCount(snap.onlineCount())
                .level(snap.level())
                .balance(snap.balance())
                .createdAt(snap.createdAt())
                .maxMembers(snap.maxMembers())
                .allies(snap.allies())
                .rivals(snap.rivals())
                .provider(bridge.name())
                .build();
    }
}
