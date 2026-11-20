package net.exylia.lib.tablist;

import net.exylia.lib.tablist.internal.TabRuntime;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Entry point of the tab list module: header, footer, listed names and order,
 * per player.
 *
 * <pre>{@code
 * TabLists.of(this)
 *         .header(p -> "{primary}&lMY SERVER<nl>{letters}Welcome, " + p.getName())
 *         .footer(p -> "{letters_black}" + Bukkit.getOnlinePlayers().size() + " online")
 *         .name(p -> "{highlight}[R" + rank(p) + "] {letters}" + p.getName())
 *         .order(p -> rank(p))
 *         .every(Duration.ofSeconds(1));
 * }</pre>
 *
 * @since 1.633.0
 */
public final class TabLists {

    private TabLists() {
        throw new AssertionError("No instances.");
    }

    /**
     * Returns a plugin's tab list handle, creating it on first use.
     *
     * <p>When several plugins set the same field, the one that registered
     * last wins that field.
     *
     * @param plugin the owner; everything it set is undone when it is disabled
     * @return the handle
     */
    public static @NotNull PluginTabList of(@NotNull Plugin plugin) {
        return TabRuntime.of(plugin);
    }

    /**
     * Drops a plugin's tab list and puts the players it touched back to the
     * next plugin's values, or to vanilla. Called by ExyliaLib on disable.
     *
     * @param plugin the owner
     */
    public static void release(@NotNull Plugin plugin) {
        TabRuntime.release(plugin.getName());
    }
}
