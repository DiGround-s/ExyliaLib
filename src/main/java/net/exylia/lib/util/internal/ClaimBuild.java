package net.exylia.lib.util.internal;

import net.exylia.lib.util.BuildZones;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * The claim plugins behind {@link BuildZones}, reached by reflection.
 *
 * <p>Resolved through {@link ClaimHooks}, like {@link ClaimPvp}: once per plugin
 * instance, and a hook that cannot be resolved, or throws when asked, answers
 * "allowed" from then on.
 */
public final class ClaimBuild {

    private static final ClaimHooks<Hook> HOOKS = new ClaimHooks<>("Build zones", "allowing building everywhere");

    private ClaimBuild() {
    }

    /** Whether a claim plugin allows the action; {@code true} when it is missing, unhookable or broken. */
    public static boolean allows(@NotNull BuildZones.Source source, @NotNull Player player, @NotNull Block block,
                                 @NotNull BuildZones.Action action) {
        Plugin plugin = ClaimHooks.enabled(source.pluginName());
        if (plugin == null) {
            return true;
        }
        Hook hook = HOOKS.hook(plugin, enabled -> switch (source) {
            case LANDS -> new Lands(enabled);
            case GRIEF_PREVENTION -> new GriefPrevention(enabled);
            case TOWNY -> new Towny(enabled);
            case SUPERIOR_SKYBLOCK -> new SuperiorSkyblock(enabled);
            default -> null;
        });
        if (hook == null) {
            return true;
        }
        try {
            return hook.allows(player, block, action);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            HOOKS.broken(plugin, failure);
            return true;
        }
    }

    private interface Hook {

        boolean allows(Player player, Block block, BuildZones.Action action) throws ReflectiveOperationException;
    }

    /** {@code Area#hasRoleFlag(Player, RoleFlag, Material, false)}: Lands' own check, bypass included. */
    private static final class Lands implements Hook {

        private final Object integration;
        private final Method getArea;
        private final Method hasRoleFlag;
        private final Field place;
        private final Field breaking;
        private final Field interact;
        private final Field container;

        Lands(Plugin plugin) throws ReflectiveOperationException {
            Class<?> integrationType = ClaimHooks.type(plugin, "me.angeschossen.lands.api.LandsIntegration");
            integration = integrationType.getMethod("of", Plugin.class)
                    .invoke(null, org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(ClaimBuild.class));
            getArea = integrationType.getMethod("getArea", Location.class);
            Class<?> roleFlag = ClaimHooks.type(plugin, "me.angeschossen.lands.api.flags.type.RoleFlag");
            hasRoleFlag = ClaimHooks.type(plugin, "me.angeschossen.lands.api.land.Area")
                    .getMethod("hasRoleFlag", Player.class, roleFlag, Material.class, boolean.class);
            Class<?> flags = ClaimHooks.type(plugin, "me.angeschossen.lands.api.flags.type.Flags");
            place = flags.getField("BLOCK_PLACE");
            breaking = flags.getField("BLOCK_BREAK");
            interact = flags.getField("INTERACT_GENERAL");
            container = flags.getField("INTERACT_CONTAINER");
        }

        @Override
        public boolean allows(Player player, Block block, BuildZones.Action action) throws ReflectiveOperationException {
            Object area = getArea.invoke(integration, block.getLocation());
            if (area == null) return true;
            Object flag = (switch (action) {
                case BUILD -> place;
                case BREAK -> breaking;
                case INTERACT -> interact;
                case CONTAINER -> container;
            }).get(null);
            return flag == null || Boolean.TRUE.equals(hasRoleFlag.invoke(area, player, flag, block.getType(), false));
        }
    }

    /** {@code allowBuild}/{@code allowBreak} for blocks, the claim's {@code checkPermission} for use: {@code null} means allowed. */
    private static final class GriefPrevention implements Hook {

        private final Plugin instance;
        private final Method allowBuild;
        private final Method allowBreak;
        private final Field dataStore;
        private final Method getClaimAt;
        private final Method checkPermission;
        private final Object access;
        private final Object inventory;

        GriefPrevention(Plugin plugin) throws ReflectiveOperationException {
            instance = plugin;
            Class<?> main = plugin.getClass();
            Class<?> claim = ClaimHooks.type(plugin, "me.ryanhamshire.GriefPrevention.Claim");
            Class<?> permission = ClaimHooks.type(plugin, "me.ryanhamshire.GriefPrevention.ClaimPermission");
            allowBuild = main.getMethod("allowBuild", Player.class, Location.class, Material.class);
            allowBreak = main.getMethod("allowBreak", Player.class, Block.class, Location.class);
            dataStore = main.getField("dataStore");
            getClaimAt = dataStore.getType().getMethod("getClaimAt", Location.class, boolean.class, claim);
            checkPermission = claim.getMethod("checkPermission", Player.class, permission, org.bukkit.event.Event.class);
            access = permission.getField("Access").get(null);
            inventory = permission.getField("Inventory").get(null);
        }

        @Override
        public boolean allows(Player player, Block block, BuildZones.Action action) throws ReflectiveOperationException {
            Location at = block.getLocation();
            return switch (action) {
                case BUILD -> allowBuild.invoke(instance, player, at, block.getType()) == null;
                case BREAK -> allowBreak.invoke(instance, player, block, at) == null;
                case INTERACT, CONTAINER -> {
                    Object claim = getClaimAt.invoke(dataStore.get(instance), at, false, null);
                    if (claim == null) yield true;
                    Object denial = checkPermission.invoke(claim, player, action == BuildZones.Action.CONTAINER ? inventory : access, null);
                    yield denial == null || ((Supplier<?>) denial).get() == null;
                }
            };
        }
    }

    /** {@code PlayerCacheUtil.getCachePermission}: Towny's cached answer, the one its listeners use. */
    private static final class Towny implements Hook {

        private final Method getCachePermission;
        private final Object build;
        private final Object destroy;
        private final Object doSwitch;

        Towny(Plugin plugin) throws ReflectiveOperationException {
            Class<?> actionType = ClaimHooks.type(plugin, "com.palmergames.bukkit.towny.object.TownyPermission$ActionType");
            getCachePermission = ClaimHooks.type(plugin, "com.palmergames.bukkit.towny.utils.PlayerCacheUtil")
                    .getMethod("getCachePermission", Player.class, Location.class, Material.class, actionType);
            build = actionType.getField("BUILD").get(null);
            destroy = actionType.getField("DESTROY").get(null);
            doSwitch = actionType.getField("SWITCH").get(null);
        }

        @Override
        public boolean allows(Player player, Block block, BuildZones.Action action) throws ReflectiveOperationException {
            Object type = switch (action) {
                case BUILD -> build;
                case BREAK -> destroy;
                case INTERACT, CONTAINER -> doSwitch;
            };
            return Boolean.TRUE.equals(getCachePermission.invoke(null, player, block.getLocation(), block.getType(), type));
        }
    }

    /** {@code Island#hasPermission(SuperiorPlayer, IslandPrivilege)}; outside any island, allowed. */
    private static final class SuperiorSkyblock implements Hook {

        private final Method getIslandAt;
        private final Method getPlayer;
        private final Method hasPermission;
        private final Object build;
        private final Object breaking;
        private final Object interact;
        private final Object chest;

        SuperiorSkyblock(Plugin plugin) throws ReflectiveOperationException {
            Class<?> api = ClaimHooks.type(plugin, "com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI");
            getIslandAt = api.getMethod("getIslandAt", Location.class);
            getPlayer = api.getMethod("getPlayer", Player.class);
            Class<?> privilege = ClaimHooks.type(plugin, "com.bgsoftware.superiorskyblock.api.island.IslandPrivilege");
            Class<?> superiorPlayer = ClaimHooks.type(plugin, "com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer");
            hasPermission = ClaimHooks.type(plugin, "com.bgsoftware.superiorskyblock.api.island.Island")
                    .getMethod("hasPermission", superiorPlayer, privilege);
            Method byName = privilege.getMethod("getByName", String.class);
            build = byName.invoke(null, "BUILD");
            breaking = byName.invoke(null, "BREAK");
            interact = byName.invoke(null, "INTERACT");
            chest = byName.invoke(null, "CHEST_ACCESS");
            if (build == null || breaking == null || interact == null || chest == null) {
                throw new NoSuchFieldException("IslandPrivilege BUILD, BREAK, INTERACT or CHEST_ACCESS");
            }
        }

        @Override
        public boolean allows(Player player, Block block, BuildZones.Action action) throws ReflectiveOperationException {
            Object island = getIslandAt.invoke(null, block.getLocation());
            if (island == null) return true;
            Object privilege = switch (action) {
                case BUILD -> build;
                case BREAK -> breaking;
                case INTERACT -> interact;
                case CONTAINER -> chest;
            };
            return Boolean.TRUE.equals(hasPermission.invoke(island, getPlayer.invoke(null, player), privilege));
        }
    }
}
