package net.exylia.lib.network;

import net.exylia.lib.database.PluginDatabase;
import net.exylia.lib.internal.LibrarySettings;
import net.exylia.lib.redis.Redis;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Which servers of a network share a plugin's data: every server, the servers
 * of one group, or this server alone.
 *
 * <p>A network is rarely one game. Two survival servers share their money and
 * their auction house, a boxpvp server keeps its own, and the lobby has none.
 * The group is said once per server, in ExyliaLib's {@code config.yml}
 * ({@code network-group: survival}), and every plugin reads it from here
 * instead of asking for it again.
 *
 * <p>A plugin offers the choice per thing it keeps, with the same words
 * everywhere:
 * <ul>
 *   <li>{@code default} (or blank) — this server's group; every server when it has none</li>
 *   <li>{@code global} — every server on the database</li>
 *   <li>{@code server} — this server alone, by its {@code server-id}</li>
 *   <li>any other name — that group, whatever this server's own is</li>
 * </ul>
 *
 * <pre>{@code
 * String scope = NetworkGroups.scope(plugin, settings.auctionsGroup());
 * Repository<AuctionRow> auctions = Databases.of(plugin).repository(AuctionRow.class, scope);
 * }</pre>
 *
 * @since 1.576.0
 */
public final class NetworkGroups {

    /** Every server on the database. */
    public static final String GLOBAL = "global";
    /** This server's group, or every server when it has none. */
    public static final String DEFAULT = "default";
    /** This server alone. */
    public static final String SERVER = "server";

    private static final Logger LOG = Logger.getLogger("ExyliaLib");
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private NetworkGroups() {
        throw new AssertionError("No instances.");
    }

    /**
     * This server's group, normalised the way database scopes are.
     *
     * @return the group, or an empty string when the server has none (or an unusable one, reported once)
     */
    public static @NotNull String current() {
        return normalise(LibrarySettings.get().networkGroup(), "network-group in ExyliaLib's config.yml");
    }

    /**
     * Whether this server belongs to a group.
     *
     * @return whether {@link #current()} is not empty
     */
    public static boolean hasGroup() {
        return !current().isEmpty();
    }

    /**
     * The database scope a plugin's group setting names: what
     * {@code repository(type, scope)} takes.
     *
     * <p>Never throws. A name that cannot be a scope is reported once and
     * shares with every server, which is what a server had before it set one.
     *
     * @param plugin the plugin whose {@code server-id} {@code server} stands for
     * @param choice the setting: {@code default}, {@code global}, {@code server} or a group's name
     * @return the group or server the data is kept for, empty for every server
     */
    public static @NotNull String scope(@NotNull Plugin plugin, @Nullable String choice) {
        String chosen = choice == null ? "" : choice.trim();
        if (chosen.isEmpty() || chosen.equalsIgnoreCase(DEFAULT)) return current();
        if (chosen.equalsIgnoreCase(GLOBAL)) return "";
        if (chosen.equalsIgnoreCase(SERVER)) {
            return normalise(Redis.serverId(plugin), "server-id in " + plugin.getName() + "'s database.yml");
        }
        return normalise(chosen, "the group '" + chosen + "'");
    }

    /**
     * Whether this server is one of the places a list names.
     *
     * <p>Each entry is a group or a {@code server-id}, compared the way scopes
     * are ({@code Survival-1} is {@code survival_1}). An empty list is every
     * server. For "this exists only on survival and boxpvp".
     *
     * @param plugin where the {@code server-id} is read
     * @param where  groups and server ids; empty or {@code null} for everywhere
     * @return whether this server is in it
     */
    public static boolean matches(@NotNull Plugin plugin, @Nullable Collection<String> where) {
        if (where == null || where.isEmpty()) return true;
        String group = current();
        String server = clean(Redis.serverId(plugin));
        for (String entry : where) {
            String name = clean(entry);
            if (name.isEmpty()) continue;
            if (name.equals(server) || (!group.isEmpty() && name.equals(group))) return true;
        }
        return false;
    }

    /** A name as a scope, or empty (reported once) when it cannot be one. */
    private static String normalise(@Nullable String name, String where) {
        try {
            return PluginDatabase.scope(name);
        } catch (IllegalArgumentException invalid) {
            if (WARNED.add(where)) {
                LOG.warning("[ExyliaLib] " + where + " is '" + name + "', which is not a usable group name"
                        + " (letters, digits, - and _, at most 24). It shares with every server until it is fixed.");
            }
            return "";
        }
    }

    /** A name compared the way scopes are, without rejecting it. */
    private static String clean(@Nullable String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }
}
