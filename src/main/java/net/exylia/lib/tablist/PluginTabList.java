package net.exylia.lib.tablist;

import net.exylia.lib.tablist.internal.TabRuntime;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * One plugin's tab list. Obtained from {@link TabLists#of}.
 *
 * <p>Every function returns MiniMessage text with palette tokens ({@code <nl>}
 * or a newline breaks a line) and is resolved per player on that player's
 * entity thread, so it may read the player freely. Nothing is resent while the
 * rendered text is unchanged. A function that throws is reported once and its
 * field keeps the last value.
 *
 * @since 1.633.0
 */
public final class PluginTabList {

    private final String pluginName;
    private final Set<UUID> removed = ConcurrentHashMap.newKeySet();
    private volatile Function<Player, String> header;
    private volatile Function<Player, String> footer;
    private volatile Function<Player, String> name;
    private volatile ToIntFunction<Player> order;
    private volatile Duration interval = Duration.ofSeconds(1);

    /** Internal: use {@link TabLists#of}. */
    public PluginTabList(@NotNull String pluginName) {
        this.pluginName = pluginName;
    }

    /**
     * Sets the header.
     *
     * @param header text per viewer
     * @return this
     */
    public @NotNull PluginTabList header(@NotNull Function<Player, String> header) {
        this.header = header;
        return changed();
    }

    /**
     * Sets the footer.
     *
     * @param footer text per viewer
     * @return this
     */
    public @NotNull PluginTabList footer(@NotNull Function<Player, String> footer) {
        this.footer = footer;
        return changed();
    }

    /**
     * Sets how each player is listed. The listed name is one per player, not
     * per viewer: the function receives the player being listed.
     *
     * @param name the listed name of a player
     * @return this
     */
    public @NotNull PluginTabList name(@NotNull Function<Player, String> name) {
        this.name = name;
        return changed();
    }

    /**
     * Sets the order of the list: a higher value is listed first. Needs
     * Paper 1.21.2+ and is ignored below that (see {@code docs/tablist.md}).
     *
     * @param order the rank of a player
     * @return this
     */
    public @NotNull PluginTabList order(@NotNull ToIntFunction<Player> order) {
        this.order = order;
        return changed();
    }

    /**
     * Sets how often everything is re-resolved. Default one second, minimum
     * one tick. The fastest interval among all plugins is used.
     *
     * @param interval the period
     * @return this
     */
    public @NotNull PluginTabList every(@NotNull Duration interval) {
        this.interval = interval;
        return changed();
    }

    /**
     * Stops this plugin from managing one player's tab list and puts them
     * back to vanilla (or to another plugin's values). Forgotten on quit.
     *
     * @param player the player
     */
    public void remove(@NotNull Player player) {
        removed.add(player.getUniqueId());
        TabRuntime.refresh(player);
    }

    private PluginTabList changed() {
        TabRuntime.reschedule();
        return this;
    }

    // Read by the runtime.
    public @NotNull String pluginName() { return pluginName; }
    public Function<Player, String> header() { return header; }
    public Function<Player, String> footer() { return footer; }
    public Function<Player, String> name() { return name; }
    public ToIntFunction<Player> order() { return order; }
    public @NotNull Duration interval() { return interval; }
    public boolean removed(@NotNull UUID id) { return removed.contains(id); }
    public void forget(@NotNull UUID id) { removed.remove(id); }
}
