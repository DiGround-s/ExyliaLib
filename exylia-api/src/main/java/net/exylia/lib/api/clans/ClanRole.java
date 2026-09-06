package net.exylia.lib.api.clans;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Set;

/**
 * One rank inside a clan, and what it is allowed to do.
 *
 * <p>Permission names are the plugin's own vocabulary rather than Bukkit
 * permissions — they gate clan actions, not server commands. Treat an unknown
 * name as a permission this version of the plugin does not have rather than as
 * an error: the set grows between releases.
 *
 * @param id          the role id, unique within the clan
 * @param clanId      the clan this role belongs to
 * @param name        what players see
 * @param weight      higher outranks lower
 * @param defaultRole whether a new member is given this role
 * @param permissions the clan permissions this role grants
 * @since 1.0.0
 */
public record ClanRole(
        @NotNull String id,
        @NotNull String clanId,
        @NotNull String name,
        int weight,
        boolean defaultRole,
        @NotNull @Unmodifiable Set<String> permissions) {

    /**
     * Whether this role grants a clan permission.
     *
     * @param permission the permission name, case insensitive
     * @return {@code true} when the role grants it
     */
    public boolean has(@NotNull String permission) {
        for (String held : permissions) {
            if (held.equalsIgnoreCase(permission)) return true;
        }
        return false;
    }
}
