package net.exylia.lib.util.internal;

import net.exylia.lib.util.PvpZones;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * The claim plugins behind {@link PvpZones}, reached by reflection.
 *
 * <p>Resolved through {@link ClaimHooks}: once per plugin instance, and a hook
 * that cannot be resolved, or throws when asked, answers "allowed" from then on.
 */
public final class ClaimPvp {

    private static final ClaimHooks<Hook> HOOKS = new ClaimHooks<>("PvP zones", "allowing PvP everywhere");

    private ClaimPvp() {
    }

    public static boolean denies(@NotNull PvpZones.Source source, @NotNull Location at, @Nullable Player player) {
        Plugin plugin = ClaimHooks.enabled(source.pluginName());
        if (plugin == null) {
            return false;
        }
        Hook hook = HOOKS.hook(plugin, enabled -> switch (source) {
            case LANDS -> new Lands(enabled);
            case GRIEF_PREVENTION -> new GriefPrevention(enabled);
            case TOWNY -> new Towny(enabled);
            case SUPERIOR_SKYBLOCK -> new SuperiorSkyblock(enabled);
            default -> null;
        });
        if (hook == null) {
            return false;
        }
        try {
            return hook.denies(at, player);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            HOOKS.broken(plugin, failure);
            return false;
        }
    }

    private static Class<?> type(Plugin plugin, String name) throws ClassNotFoundException {
        return ClaimHooks.type(plugin, name);
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
