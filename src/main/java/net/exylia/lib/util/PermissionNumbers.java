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

    /**
     * What {@link #limit} answers for a player with no cap. Compare counts with
     * {@code count < limit} and this needs no special case.
     *
     * @since 1.432.0
     */
    public static final int UNLIMITED = Integer.MAX_VALUE;

    private PermissionNumbers() {
    }

    /**
     * A cap granted in tiers, such as {@code myplugin.limit.5} and
     * {@code myplugin.limit.10}: the largest number, {@link #UNLIMITED} for a
     * node ending in {@code unlimited} or {@code *}, or {@code fallback} when
     * the player holds none.
     *
     * <pre>{@code
     * int cap = PermissionNumbers.limit(player, "myplugin.limit.", settings.defaultLimit());
     * if (owned >= cap) refuse();
     * }</pre>
     *
     * <p>Only nodes the player actually holds count, so an operator is capped
     * like anyone else unless the plugin declares {@code <prefix>unlimited}
     * with {@code default: op} in its {@code plugin.yml}.
     *
     * @param permissible who to ask
     * @param prefix      the node before the number, dot included
     * @param fallback    the cap when no node carries one; pass {@link #UNLIMITED} for none
     * @return the cap, never negative unless {@code fallback} is
     * @since 1.432.0
     */
    public static int limit(@NotNull Permissible permissible, @NotNull String prefix, int fallback) {
        for (PermissionAttachmentInfo info : permissible.getEffectivePermissions()) {
            if (!info.getValue()) continue;
            String node = info.getPermission();
            if (node.length() <= prefix.length() || !node.regionMatches(true, 0, prefix, 0, prefix.length())) continue;
            String tail = node.substring(prefix.length());
            if (tail.equals("*") || tail.equalsIgnoreCase("unlimited")) return UNLIMITED;
        }
        return best(permissible, prefix, fallback, true);
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
        return best(permissible, prefix, fallback, true);
    }

    /**
     * The smallest number behind a prefix, for values where less is the perk:
     * a cooldown, a warmup, a tax. A player holding {@code .30} and {@code .10}
     * waits ten.
     *
     * @param permissible who to ask
     * @param prefix      the node before the number, dot included
     * @param fallback    the answer when no node carries a number
     * @return the smallest number granted, or {@code fallback}
     * @since 1.424.0
     */
    public static int lowest(@NotNull Permissible permissible, @NotNull String prefix, int fallback) {
        return best(permissible, prefix, fallback, false);
    }

    private static int best(Permissible permissible, String prefix, int fallback, boolean highest) {
        Integer best = null;
        for (PermissionAttachmentInfo info : permissible.getEffectivePermissions()) {
            if (!info.getValue()) continue;
            String node = info.getPermission();
            if (node.length() <= prefix.length() || !node.regionMatches(true, 0, prefix, 0, prefix.length())) continue;
            try {
                int value = Integer.parseInt(node.substring(prefix.length()));
                if (best == null || (highest ? value > best : value < best)) best = value;
            } catch (NumberFormatException notANumber) {
                // A node written by hand whose tail is not a number.
            }
        }
        return best == null ? fallback : best;
    }
}
