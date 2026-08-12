package net.exylia.lib.clan.internal;

import net.exylia.lib.clan.Clan;
import org.bukkit.Bukkit;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * SimpleClans integration through reflection.
 *
 * <p>SimpleClans has allies and rivals, which is why this provider is here: it
 * is the oldest clan plugin in the ecosystem and the one that defined both
 * concepts.
 */
final class SimpleClansProvider implements ClanProvider {

    private static final String PLUGIN = "SimpleClans";

    private final boolean present;
    private final Object clanManager;
    private final Method getClan;
    private final Method getAllClans;
    private final Method getClanPlayer;
    private final Method getAllyTags;
    private final Method getRivalTags;

    private SimpleClansProvider(boolean present, Object clanManager,
                                Method getClan, Method getAllClans, Method getClanPlayer,
                                Method getAllyTags, Method getRivalTags) {
        this.present = present;
        this.clanManager = clanManager;
        this.getClan = getClan;
        this.getAllClans = getAllClans;
        this.getClanPlayer = getClanPlayer;
        this.getAllyTags = getAllyTags;
        this.getRivalTags = getRivalTags;
    }

    static SimpleClansProvider tryCreate() {
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled(PLUGIN)) {
                return new SimpleClansProvider(false, null, null, null, null, null, null);
            }
            Class<?> scPluginClass = Class.forName(
                    "net.sacredlabyrinth.Phaed.SimpleClans.SimpleClans");
            Object plugin = Bukkit.getPluginManager().getPlugin(PLUGIN);
            Object manager = scPluginClass.getMethod("getClanManager").invoke(plugin);
            Class<?> cmClass = manager.getClass();
            return new SimpleClansProvider(true, manager,
                    cmClass.getMethod("getClan", UUID.class),
                    cmClass.getMethod("getClans"),
                    cmClass.getMethod("getClanPlayer", UUID.class),
                    cmClass.getMethod("getAllyTags", UUID.class),
                    cmClass.getMethod("getRivalTags", UUID.class));
        } catch (Throwable e) {
            Logger.getLogger("ExyliaLib").warning(
                    "SimpleClans is installed but its API could not be reached: "
                            + e.getMessage());
            return new SimpleClansProvider(false, null, null, null, null, null, null);
        }
    }

    @Override
    public boolean enabled() {
        return present;
    }

    @Override
    public String name() {
        return "SimpleClans";
    }

    @Override
    public Optional<Clan> clanOf(UUID player) {
        try {
            Object clan = getClan.invoke(clanManager, player);
            if (clan == null) return Optional.empty();
            return Optional.of(toClan(clan));
        } catch (Throwable e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Clan> clanOf(org.bukkit.entity.Player player) {
        return clanOf(player.getUniqueId());
    }

    @Override
    public Optional<Clan> byTag(String tag) {
        for (Clan clan : all()) {
            if (clan.tag().equalsIgnoreCase(tag)) return Optional.of(clan);
        }
        return Optional.empty();
    }

    @Override
    public Optional<Clan> byId(String id) {
        return byTag(id);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Collection<Clan> all() {
        try {
            List<?> clans = (List<?>) getAllClans.invoke(clanManager);
            return clans.stream().map(this::toClan).toList();
        } catch (Throwable e) {
            return List.of();
        }
    }

    @Override
    public boolean hasClan(UUID player) {
        try {
            return getClan.invoke(clanManager, player) != null;
        } catch (Throwable e) {
            return false;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Collection<String> alliesOf(String clanId) {
        try {
            return (List<String>) getAllyTags.invoke(clanManager, UUID.fromString(clanId));
        } catch (Throwable e) {
            return List.of();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Collection<String> rivalsOf(String clanId) {
        try {
            return (List<String>) getRivalTags.invoke(clanManager, UUID.fromString(clanId));
        } catch (Throwable e) {
            return List.of();
        }
    }

    @Override
    public boolean areInSameClan(UUID player, UUID other) {
        try {
            Object cp = getClanPlayer.invoke(clanManager, player);
            Object ocp = getClanPlayer.invoke(clanManager, other);
            if (cp == null || ocp == null) return false;
            Object clan = cp.getClass().getMethod("getClan").invoke(cp);
            Object oclan = ocp.getClass().getMethod("getClan").invoke(ocp);
            return clan != null && oclan != null && clan.equals(oclan);
        } catch (Throwable e) {
            return false;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Collection<UUID> onlineMembersOf(UUID player) {
        try {
            Object clan = getClan.invoke(clanManager, player);
            if (clan == null) return List.of();
            Collection<?> members = (Collection<?>)
                    clan.getClass().getMethod("getOnlineMembers").invoke(clan);
            List<UUID> ids = new ArrayList<>(members.size());
            for (Object member : members) {
                UUID id = (UUID) member.getClass().getMethod("getUniqueId").invoke(member);
                if (id != null) ids.add(id);
            }
            return ids;
        } catch (Throwable e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Clan toClan(Object clan) {
        try {
            Class<?> c = clan.getClass();
            String tag = (String) c.getMethod("getTag").invoke(clan);
            String name = (String) c.getMethod("getName").invoke(clan);
            UUID leaderId = (UUID) c.getMethod("getLeaderId").invoke(clan);

            Clan.Builder builder = Clan.builder(tag).name(name).tag(tag)
                    .displayName(name).leader(leaderId).provider("SimpleClans");

            Collection<?> members = (Collection<?>) c.getMethod("getMembers").invoke(clan);
            for (Object member : members) {
                builder.member((UUID) member.getClass().getMethod("getUniqueId").invoke(member));
            }

            List<String> allies = (List<String>) getAllyTags.invoke(clanManager, UUID.fromString(tag));
            if (allies != null) builder.allies(allies);
            List<String> rivals = (List<String>) getRivalTags.invoke(clanManager, UUID.fromString(tag));
            if (rivals != null) builder.rivals(rivals);

            return builder.build();
        } catch (Throwable e) {
            throw new IllegalStateException(
                    "Could not read SimpleClans data: " + e.getMessage(), e);
        }
    }
}
