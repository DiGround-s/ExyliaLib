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
 * UltimateClans integration through reflection.
 *
 * <p>UltimateClans has no alliances or rivalries, so those always return empty.
 */
final class UltimateClansProvider implements ClanProvider {

    private static final String PLUGIN = "UltimateClans";

    private final boolean present;
    private final Method getPlayer;
    private final Method getClanByTag;
    private final Method getAllClans;

    private UltimateClansProvider(boolean present, Method getPlayer,
                                  Method getClanByTag, Method getAllClans) {
        this.present = present;
        this.getPlayer = getPlayer;
        this.getClanByTag = getClanByTag;
        this.getAllClans = getAllClans;
    }

    static UltimateClansProvider tryCreate() {
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled(PLUGIN)) {
                return new UltimateClansProvider(false, null, null, null);
            }
            Class<?> apiClass = Class.forName("me.ulrich.clans.UClans");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            Method getPlayer = apiClass.getMethod("getPlayer", UUID.class);
            Method getClanByTag = findMethod(apiClass, "getClan", String.class);
            Method getAllClans = apiClass.getMethod("getClans");
            return new UltimateClansProvider(true, getPlayer, getClanByTag, getAllClans);
        } catch (Throwable e) {
            Logger.getLogger("ExyliaLib").warning(
                    "UltimateClans is installed but its API could not be reached: "
                            + e.getMessage());
            return new UltimateClansProvider(false, null, null, null);
        }
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... paramTypes) {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name)) return m;
        }
        return null;
    }

    @Override
    public boolean enabled() {
        return present;
    }

    @Override
    public String name() {
        return "UltimateClans";
    }

    private Object api() {
        try {
            return Class.forName("me.ulrich.clans.UClans").getMethod("getInstance").invoke(null);
        } catch (Throwable e) {
            return null;
        }
    }

    @Override
    public Optional<Clan> clanOf(UUID player) {
        try {
            Object api = api();
            if (api == null) return Optional.empty();
            Object clanPlayer = getPlayer.invoke(api, player);
            if (clanPlayer == null) return Optional.empty();
            Object clan = clanPlayer.getClass().getMethod("getClan").invoke(clanPlayer);
            return Optional.ofNullable(clan).map(this::toClan);
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
            Object api = api();
            if (api == null || getClanByTag == null) return Optional.empty();
            Object clan = getClanByTag.invoke(api, tag);
            return Optional.ofNullable(clan).map(this::toClan);
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
            Object api = api();
            if (api == null) return List.of();
            java.util.Collection<?> clans = (java.util.Collection<?>) getAllClans.invoke(api);
            return clans.stream().map(this::toClan).toList();
        } catch (Throwable e) {
            return List.of();
        }
    }

    @Override
    public boolean hasClan(UUID player) {
        try {
            Object api = api();
            if (api == null) return false;
            Object clanPlayer = getPlayer.invoke(api, player);
            if (clanPlayer == null) return false;
            return clanPlayer.getClass().getMethod("getClan").invoke(clanPlayer) != null;
        } catch (Throwable e) {
            return false;
        }
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
        try {
            Object api = api();
            if (api == null) return false;
            Object cp1 = getPlayer.invoke(api, player);
            Object cp2 = getPlayer.invoke(api, other);
            if (cp1 == null || cp2 == null) return false;
            Object c1 = cp1.getClass().getMethod("getClan").invoke(cp1);
            Object c2 = cp2.getClass().getMethod("getClan").invoke(cp2);
            return c1 != null && c2 != null && c1.equals(c2);
        } catch (Throwable e) {
            return false;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Collection<UUID> onlineMembersOf(UUID player) {
        try {
            Object api = api();
            if (api == null) return List.of();
            Object clanPlayer = getPlayer.invoke(api, player);
            if (clanPlayer == null) return List.of();
            Object clan = clanPlayer.getClass().getMethod("getClan").invoke(clanPlayer);
            if (clan == null) return List.of();
            java.util.Collection<?> members = (java.util.Collection<?>)
                    clan.getClass().getMethod("getOnlineMembers").invoke(clan);
            List<UUID> ids = new ArrayList<>(members.size());
            for (Object member : members) {
                ids.add((UUID) member.getClass().getMethod("getUniqueId").invoke(member));
            }
            return ids;
        } catch (Throwable e) {
            return List.of();
        }
    }

    private Clan toClan(Object clan) {
        try {
            Class<?> c = clan.getClass();
            String tag = (String) c.getMethod("getTag").invoke(clan);
            String name = (String) c.getMethod("getName").invoke(clan);
            UUID leaderId = (UUID) c.getMethod("getLeader").invoke(clan);

            Clan.Builder builder = Clan.builder(tag).name(name).tag(tag)
                    .displayName(name).leader(leaderId).provider("UltimateClans");

            java.util.Collection<?> members = (java.util.Collection<?>)
                    c.getMethod("getMembers").invoke(clan);
            if (members != null) {
                for (Object member : members) {
                    UUID id = (UUID) member.getClass().getMethod("getUniqueId").invoke(member);
                    builder.member(id);
                }
            }
            return builder.build();
        } catch (Throwable e) {
            throw new IllegalStateException("Could not read UltimateClans data for "
                    + clan.getClass().getName() + ": " + e.getMessage(), e);
        }
    }
}
