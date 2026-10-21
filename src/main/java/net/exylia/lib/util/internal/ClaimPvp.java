package net.exylia.lib.util.internal;

import net.exylia.lib.util.PvpZones;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * The claim plugins behind {@link PvpZones}, reached by reflection.
 *
 * <p>Each hook is resolved once per plugin instance, through that plugin's own
 * class loader, so a reloaded plugin is resolved again and a missing one costs
 * a plugin lookup. A hook that cannot be resolved, or throws when asked, is
 * logged once and answers "allowed" from then on.
 */
public final class ClaimPvp {

    private static final Logger LOGGER = Logger.getLogger("ExyliaLib");
    private static final Map<PvpZones.Source, Resolved> HOOKS = new EnumMap<>(PvpZones.Source.class);

    private ClaimPvp() {
    }

    public static boolean denies(@NotNull PvpZones.Source source, @NotNull Location at, @Nullable Player player) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(source.pluginName());
        if (plugin == null || !plugin.isEnabled()) {
            return false;
        }
        Hook hook = hook(source, plugin);
        if (hook == null) {
            return false;
        }
        try {
            return hook.denies(at, player);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            broken(source, plugin, failure);
            return false;
        }
    }

    private static Hook hook(PvpZones.Source source, Plugin plugin) {
        Resolved resolved;
        synchronized (HOOKS) {
            resolved = HOOKS.get(source);
        }
        if (resolved != null && resolved.plugin == plugin) {
            return resolved.hook;
        }
        Hook hook;
        try {
            hook = switch (source) {
                case LANDS -> new Lands(plugin);
                case GRIEF_PREVENTION -> new GriefPrevention(plugin);
                case TOWNY -> new Towny(plugin);
                case SUPERIOR_SKYBLOCK -> new SuperiorSkyblock(plugin);
                default -> null;
            };
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            LOGGER.warning("PvP zones: could not hook " + plugin.getName() + " " + plugin.getPluginMeta().getVersion()
                    + "; it is treated as allowing PvP everywhere (" + failure + ")");
            hook = null;
        }
        synchronized (HOOKS) {
            HOOKS.put(source, new Resolved(plugin, hook));
        }
        return hook;
    }

    private static void broken(PvpZones.Source source, Plugin plugin, Throwable failure) {
        LOGGER.warning("PvP zones: " + plugin.getName() + " failed when asked and is ignored from now on (" + failure + ")");
        synchronized (HOOKS) {
            HOOKS.put(source, new Resolved(plugin, null));
        }
    }

    private static Class<?> type(Plugin plugin, String name) throws ClassNotFoundException {
        return Class.forName(name, true, plugin.getClass().getClassLoader());
    }

    private record Resolved(Plugin plugin, Hook hook) {
    }

    private interface Hook {

        boolean denies(Location at, Player player) throws ReflectiveOperationException;
    }

    private static final class Lands implements Hook {

        private final Object integration;
        private final Method getArea;
        private final Method hasRoleFlag;
        private final Field attackPlayer;

        Lands(Plugin plugin) throws ReflectiveOperationException {
            Class<?> integrationType = type(plugin, "me.angeschossen.lands.api.LandsIntegration");
            integration = integrationType.getMethod("of", Plugin.class)
                    .invoke(null, JavaPlugin.getProvidingPlugin(ClaimPvp.class));
            getArea = integrationType.getMethod("getArea", Location.class);
            Class<?> roleFlag = type(plugin, "me.angeschossen.lands.api.flags.type.RoleFlag");
            hasRoleFlag = type(plugin, "me.angeschossen.lands.api.land.Area").getMethod("hasRoleFlag", UUID.class, roleFlag);
            attackPlayer = type(plugin, "me.angeschossen.lands.api.flags.type.Flags").getField("ATTACK_PLAYER");
        }

        @Override
        public boolean denies(Location at, Player player) throws ReflectiveOperationException {
            if (player == null) {
                return false;
            }
            Object area = getArea.invoke(integration, at);
            Object flag = attackPlayer.get(null);
            return area != null && flag != null && !Boolean.TRUE.equals(hasRoleFlag.invoke(area, player.getUniqueId(), flag));
        }
    }

    private static final class GriefPrevention implements Hook {

        private final Plugin instance;
        private final Method pvpRulesApply;
        private final Field dataStore;
        private final Method getClaimAt;
        private final Method safeZone;

        GriefPrevention(Plugin plugin) throws ReflectiveOperationException {
            instance = plugin;
            Class<?> main = plugin.getClass();
            Class<?> claim = type(plugin, "me.ryanhamshire.GriefPrevention.Claim");
            pvpRulesApply = main.getMethod("pvpRulesApply", World.class);
            dataStore = main.getField("dataStore");
            getClaimAt = dataStore.getType().getMethod("getClaimAt", Location.class, boolean.class, claim);
            safeZone = main.getMethod("claimIsPvPSafeZone", claim);
        }

        @Override
        public boolean denies(Location at, Player player) throws ReflectiveOperationException {
            if (!Boolean.TRUE.equals(pvpRulesApply.invoke(instance, at.getWorld()))) {
                return false;
            }
            Object claim = getClaimAt.invoke(dataStore.get(instance), at, true, null);
            return claim != null && Boolean.TRUE.equals(safeZone.invoke(instance, claim));
        }
    }

    private static final class Towny implements Hook {

        private final Object api;
        private final Method isPvp;

        Towny(Plugin plugin) throws ReflectiveOperationException {
            Class<?> type = type(plugin, "com.palmergames.bukkit.towny.TownyAPI");
            api = type.getMethod("getInstance").invoke(null);
            isPvp = type.getMethod("isPVP", Location.class);
        }

        @Override
        public boolean denies(Location at, Player player) throws ReflectiveOperationException {
            return Boolean.FALSE.equals(isPvp.invoke(api, at));
        }
    }

    private static final class SuperiorSkyblock implements Hook {

        private final Method getIslandAt;
        private final Method hasSettingsEnabled;
        private final Object pvpFlag;

        SuperiorSkyblock(Plugin plugin) throws ReflectiveOperationException {
            getIslandAt = type(plugin, "com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI")
                    .getMethod("getIslandAt", Location.class);
            Class<?> flag = type(plugin, "com.bgsoftware.superiorskyblock.api.island.IslandFlag");
            pvpFlag = flag.getMethod("getByName", String.class).invoke(null, "PVP");
            if (pvpFlag == null) {
                throw new NoSuchFieldException("IslandFlag PVP");
            }
            hasSettingsEnabled = type(plugin, "com.bgsoftware.superiorskyblock.api.island.Island")
                    .getMethod("hasSettingsEnabled", flag);
        }

        @Override
        public boolean denies(Location at, Player player) throws ReflectiveOperationException {
            Object island = getIslandAt.invoke(null, at);
            return island != null && !Boolean.TRUE.equals(hasSettingsEnabled.invoke(island, pvpFlag));
        }
    }
}
