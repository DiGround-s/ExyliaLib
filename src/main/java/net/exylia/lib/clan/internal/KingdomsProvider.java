package net.exylia.lib.clan.internal;

import net.exylia.lib.clan.Clan;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * KingdomsX integration through reflection.
 *
 * <p>Kingdoms has alliances and enemies, so both are plumbed in.
 */
final class KingdomsProvider implements ClanProvider {

    private static final String PLUGIN = "Kingdoms";

    private final boolean present;
    private final Method getKingdomPlayer;
    private final Method getKingdomByName;
    private final Method getKingdoms;

    private KingdomsProvider(boolean present, Method getKingdomPlayer,
                             Method getKingdomByName, Method getKingdoms) {
        this.present = present;
        this.getKingdomPlayer = getKingdomPlayer;
        this.getKingdomByName = getKingdomByName;
        this.getKingdoms = getKingdoms;
    }

    static KingdomsProvider tryCreate() {
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled(PLUGIN)) {
                return new KingdomsProvider(false, null, null, null);
            }
            Class<?> kpClass = Class.forName("org.kingdoms.constants.player.KingdomPlayer");
            Class<?> kingdomClass = Class.forName("org.kingdoms.constants.group.Kingdom");
            return new KingdomsProvider(true,
                    kpClass.getMethod("getKingdomPlayer", UUID.class),
                    kingdomClass.getMethod("getKingdom", String.class),
                    kingdomClass.getMethod("getKingdoms"));
        } catch (Throwable e) {
            Logger.getLogger("ExyliaLib").warning(
                    "Kingdoms is installed but its API could not be reached: "
                            + e.getMessage());
            return new KingdomsProvider(false, null, null, null);
        }
    }

    @Override
    public boolean enabled() {
        return present;
    }

    @Override
    public String name() {
        return "Kingdoms";
    }

    @Override
    public Optional<Clan> clanOf(UUID player) {
        try {
            Object kp = getKingdomPlayer.invoke(null, player);
            if (kp == null) return Optional.empty();
            Object kingdom = kp.getClass().getMethod("getKingdom").invoke(kp);
            return Optional.ofNullable(kingdom).map(this::toClan);
        } catch (Throwable e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Clan> clanOf(Player player) {
        return clanOf(player.getUniqueId());
    }

    @Override
    public Optional<Clan> byTag(String tag) {
        try {
            Object kingdom = getKingdomByName.invoke(null, tag);
            return Optional.ofNullable(kingdom).map(this::toClan);
        } catch (Throwable e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Clan> byId(String id) {
        return byTag(id);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Collection<Clan> all() {
        try {
            var kingdoms = (java.util.Map<?, ?>) getKingdoms.invoke(null);
            return kingdoms.values().stream().map(this::toClan).toList();
        } catch (Throwable e) {
            return List.of();
        }
    }

    @Override
    public boolean hasClan(UUID player) {
        try {
            Object kp = getKingdomPlayer.invoke(null, player);
            if (kp == null) return false;
            return kp.getClass().getMethod("getKingdom").invoke(kp) != null;
        } catch (Throwable e) {
            return false;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Collection<String> alliesOf(String clanId) {
        try {
            Object kingdom = getKingdomByName.invoke(null, clanId);
            if (kingdom == null) return List.of();
            java.util.Collection<?> raw = (java.util.Collection<?>)
                    kingdom.getClass().getMethod("getAllies").invoke(kingdom);
            List<String> ids = new ArrayList<>(raw.size());
            for (Object ally : raw) {
                ids.add((String) ally.getClass().getMethod("getName").invoke(ally));
            }
            return ids;
        } catch (Throwable e) {
            return List.of();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Collection<String> rivalsOf(String clanId) {
        try {
            Object kingdom = getKingdomByName.invoke(null, clanId);
            if (kingdom == null) return List.of();
            java.util.Collection<?> raw = (java.util.Collection<?>)
                    kingdom.getClass().getMethod("getEnemies").invoke(kingdom);
            List<String> ids = new ArrayList<>(raw.size());
            for (Object enemy : raw) {
                ids.add((String) enemy.getClass().getMethod("getName").invoke(enemy));
            }
            return ids;
        } catch (Throwable e) {
            return List.of();
        }
    }

    @Override
    public boolean areInSameClan(UUID player, UUID other) {
        try {
            Object first = getKingdomPlayer.invoke(null, player);
            Object second = getKingdomPlayer.invoke(null, other);
            if (first == null || second == null) return false;
            Object k1 = first.getClass().getMethod("getKingdom").invoke(first);
            Object k2 = second.getClass().getMethod("getKingdom").invoke(second);
            return k1 != null && k2 != null && k1.equals(k2);
        } catch (Throwable e) {
            return false;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Collection<UUID> onlineMembersOf(UUID player) {
        try {
            Object kp = getKingdomPlayer.invoke(null, player);
            if (kp == null) return List.of();
            Object kingdom = kp.getClass().getMethod("getKingdom").invoke(kp);
            if (kingdom == null) return List.of();
            java.util.Collection<?> members = (java.util.Collection<?>)
                    kingdom.getClass().getMethod("getOnlineMembers").invoke(kingdom);
            List<UUID> ids = new ArrayList<>(members.size());
            for (Object member : members) {
                ids.add((UUID) member.getClass().getMethod("getId").invoke(member));
            }
            return ids;
        } catch (Throwable e) {
            return List.of();
        }
    }

    private Clan toClan(Object kingdom) {
        try {
            Class<?> c = kingdom.getClass();
            String name = (String) c.getMethod("getName").invoke(kingdom);
            String displayName = (String) c.getMethod("getDisplayName").invoke(kingdom);
            Object king = c.getMethod("getKing").invoke(kingdom);
            UUID kingId = king != null
                    ? (UUID) king.getClass().getMethod("getId").invoke(king)
                    : new UUID(0, 0);

            Clan.Builder builder = Clan.builder(name).name(name).tag(name)
                    .displayName(displayName).leader(kingId).provider("Kingdoms");

            java.util.Collection<?> members = (java.util.Collection<?>)
                    c.getMethod("getMembers").invoke(kingdom);
            if (members != null) {
                for (Object member : members) {
                    builder.member((UUID) member.getClass().getMethod("getId").invoke(member));
                }
            }
            builder.allies(alliesOf(name));
            builder.rivals(rivalsOf(name));
            return builder.build();
        } catch (Throwable e) {
            throw new IllegalStateException("Could not read Kingdoms data for "
                    + kingdom.getClass().getName() + ": " + e.getMessage(), e);
        }
    }
}
