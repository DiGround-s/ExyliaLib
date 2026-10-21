package net.exylia.lib.util;

import org.bukkit.permissions.Permissible;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.jetbrains.annotations.NotNull;

/**
 * Numbers granted through permission nodes, such as {@code myplugin.homes.5}.
 *
 * <pre>{@code
 * int homes = PermissionNumbers.highest(player, "myplugin.homes.", settings.defaultHomes());
 * }</pre>
 *
 * <p>The largest number wins, because a rank ladder grants every step on the
 * way up: a player holding {@code .3} and {@code .5} has five. A node set to
 * {@code false} grants nothing, and a node whose tail is not a whole number is
 * ignored rather than read as zero.
 *
 * <p>Reads the player's effective permissions, so call it on the thread that
 * owns them.
 *
 * @since 1.319.0
 */
public final class PermissionNumbers {

    private PermissionNumbers() {
    }

    /**
     * The largest number behind a prefix.
     *
     * @param permissible who to ask
     * @param prefix      the node before the number, dot included
     * @param fallback    the answer when no node carries a number
     * @return the largest number granted, or {@code fallback}
     */
    public static int highest(@NotNull Permissible permissible, @NotNull String prefix, int fallback) {
        int highest = Integer.MIN_VALUE;
        for (PermissionAttachmentInfo info : permissible.getEffectivePermissions()) {
            if (!info.getValue()) continue;
            String node = info.getPermission();
            if (node.length() <= prefix.length() || !node.regionMatches(true, 0, prefix, 0, prefix.length())) continue;
            try {
                highest = Math.max(highest, Integer.parseInt(node.substring(prefix.length())));
            } catch (NumberFormatException notANumber) {
                // A node written by hand whose tail is not a number.
            }
        }
        return highest == Integer.MIN_VALUE ? fallback : highest;
    }
}
