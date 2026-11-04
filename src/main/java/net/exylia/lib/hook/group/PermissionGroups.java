package net.exylia.lib.hook.group;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The permission groups (ranks) a player belongs to, for rules written by rank:
 * "staff never play events", "only VIPs may enter".
 *
 * <pre>{@code
 * if (PermissionGroups.isIn(player, "staff")) refuse(player);
 * List<String> ranks = PermissionGroups.all();   // for a picker; empty when unknown
 * }</pre>
 *
 * <p>Groups come from Vault's permission service when one is registered
 * (LuckPerms, PermissionsEx, GroupManager...), reached through reflection so a
 * server without Vault loads this class fine. Without it, membership falls back
 * to the {@code group.<name>} node LuckPerms and most permission plugins grant
 * their members, counted only when it is explicitly set: an operator, who holds
 * every unset node, is never mistaken for a member of every group.
 *
 * <p>Group names are compared case-insensitively. Thread-safe; callable from any
 * thread the permission plugin allows (LuckPerms: any).
 *
 * @since 1.469.0
 */
public final class PermissionGroups {

    private static final String SERVICE = "net.milkbowl.vault.permission.Permission";

    private static volatile Handles handles;
    private static volatile boolean resolved;

    private PermissionGroups() {
    }

    /** Whether the server can list its groups, so a menu can offer a picker. */
    public static boolean canList() {
        return service() != null;
    }

    /** Every group the permission plugin knows, lower-cased and sorted; empty when it cannot say. */
    public static @NotNull List<String> all() {
        Object service = service();
        if (service == null) return List.of();
        try {
            String[] groups = (String[]) handles.groups.invoke(service);
            return normalized(groups);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return List.of();
        }
    }

    /** The groups the player is in, lower-cased and sorted. */
    public static @NotNull List<String> of(@NotNull Player player) {
        Object service = service();
        if (service != null) {
            try {
                return normalized((String[]) handles.playerGroups.invoke(service, player));
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Fall through to the nodes.
            }
        }
        return player.getEffectivePermissions().stream()
                .filter(info -> info.getValue() && info.getPermission().startsWith("group."))
                .map(info -> info.getPermission().substring("group.".length()).toLowerCase(Locale.ROOT))
                .distinct()
                .sorted()
                .toList();
    }

    /** Whether the player is in the group, inherited groups included. */
    public static boolean isIn(@NotNull Player player, @NotNull String group) {
        Object service = service();
        if (service != null) {
            try {
                return (boolean) handles.inGroup.invoke(service, player, group);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Fall through to the node.
            }
        }
        String node = "group." + group.toLowerCase(Locale.ROOT);
        return player.isPermissionSet(node) && player.hasPermission(node);
    }

    /** Whether the player is in any of the groups. */
    public static boolean isInAny(@NotNull Player player, @NotNull Iterable<String> groups) {
        for (String group : groups) {
            if (isIn(player, group)) return true;
        }
        return false;
    }

    private static List<String> normalized(String[] groups) {
        if (groups == null) return List.of();
        return Arrays.stream(groups)
                .filter(name -> name != null && !name.isBlank())
                .map(name -> name.toLowerCase(Locale.ROOT))
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * The registered Vault permission service, asked for on every call: which
     * plugin serves it can change at runtime, and a held one would answer for a
     * plugin that no longer does.
     */
    private static @Nullable Object service() {
        Handles resolvedHandles = resolve();
        if (resolvedHandles == null) return null;
        RegisteredServiceProvider<?> provider = Bukkit.getServicesManager().getRegistration(resolvedHandles.type);
        if (provider == null) return null;
        Object service = provider.getProvider();
        try {
            return (boolean) resolvedHandles.groupSupport.invoke(service) ? service : null;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            return null;
        }
    }

    private static @Nullable Handles resolve() {
        if (resolved) return handles;
        synchronized (PermissionGroups.class) {
            if (!resolved) {
                handles = Handles.find();
                resolved = true;
            }
        }
        return handles;
    }

    private record Handles(Class<?> type, Method groupSupport, Method groups, Method playerGroups, Method inGroup) {

        static @Nullable Handles find() {
            try {
                Class<?> type = Class.forName(SERVICE);
                return new Handles(type,
                        type.getMethod("hasGroupSupport"),
                        type.getMethod("getGroups"),
                        type.getMethod("getPlayerGroups", Player.class),
                        type.getMethod("playerInGroup", Player.class, String.class));
            } catch (ReflectiveOperationException | LinkageError absent) {
                return null;
            }
        }
    }
}
