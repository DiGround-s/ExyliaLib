package net.exylia.lib.ui;

import net.exylia.lib.ui.internal.MenuRuntime;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.Optional;

/**
 * Menus written in configuration, compiled once and opened cheaply.
 *
 * <pre>{@code
 * PluginMenus menus = Menus.of(this);
 *
 * // when configs load
 * menus.load("kits", YamlConfiguration.loadConfiguration(kitsFile));
 *
 * // when a player asks
 * menus.open(player, "kits");
 *
 * // filling a list
 * menus.session(player).ifPresent(session ->
 *         session.entries(kits.stream()
 *                 .map(kit -> UiEntry.of(kit)
 *                         .with("kit_name", kit.name())
 *                         .with("kit_icon", kit.icon())
 *                         .build())
 *                 .toList()));
 * }</pre>
 *
 * <h2>What is where</h2>
 * A menu is three things kept apart on purpose. {@link UiDefinition} is what the
 * file says, compiled once and shared by everybody. {@link UiSession} is one
 * player's open window, and the only thing a click is checked against.
 * {@link UiEntry} is a row of a list, carrying the values that fill it and the
 * thing it is about.
 *
 * <p>What an item <em>looks like</em> is not here at all: that is
 * {@link net.exylia.lib.item.Item}, which four plugins use without ever opening
 * a menu.
 *
 * <h2>What it costs</h2>
 * Reading a file happens once. Opening a menu renders the slots that are shown
 * and nothing else, and a slot with no placeholders is rendered once for the
 * whole server. Turning a page redraws that list and leaves the rest of the
 * screen alone; {@link UiSession#invalidate} redraws only the slots that said
 * they depend on what changed.
 *
 * <h2>Threads</h2>
 * {@link PluginMenus#open} is safe from anywhere and moves itself onto the
 * thread that owns the player. Everything on a {@link UiSession} touches an
 * inventory, so it belongs on that thread — which is where a click handler
 * already is.
 *
 * @since 1.22.0
 */
public final class Menus {

    private Menus() {
    }

    /**
     * The menus of a plugin, using a namespace derived from its name.
     *
     * @param plugin the plugin
     * @return its menus
     */
    public static @NotNull PluginMenus of(@NotNull Plugin plugin) {
        return of(plugin, plugin.getName().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_.-]", ""));
    }

    /**
     * The menus of a plugin, with an explicit action namespace.
     *
     * <p>The namespace is the one buttons in the file are written against, so
     * it should be the same one the plugin registers its actions under.
     *
     * @param plugin    the plugin
     * @param namespace the action namespace
     * @return its menus
     */
    public static @NotNull PluginMenus of(@NotNull Plugin plugin, @NotNull String namespace) {
        return new PluginMenus(plugin, namespace);
    }

    /**
     * The menu a player has open, whoever owns it.
     *
     * <p>For a plugin acting on somebody else's screen — a chat listener that
     * has to know whether the player is in a menu. A plugin asking about its
     * own menus should use {@link PluginMenus#session}.
     *
     * @param viewer the player
     * @return the session, or empty when they have no menu open
     */
    public static @NotNull Optional<UiSession> session(@NotNull Player viewer) {
        return Optional.ofNullable(MenuRuntime.anySessionOf(viewer));
    }

    /**
     * Returns whether a player has any menu open.
     *
     * @param viewer the player
     * @return whether one of ours is on screen
     */
    public static boolean hasOpen(@NotNull Player viewer) {
        return MenuRuntime.anySessionOf(viewer) != null;
    }

    /** Releases everything a plugin's menus hold; lifecycle calls this. */
    public static void release(@NotNull String pluginName) {
        MenuRuntime.release(pluginName);
    }

    /** Releases every menu of every plugin. */
    public static void releaseAll() {
        MenuRuntime.releaseAll();
    }

    /** How many plugins have menus, for diagnostics. */
    public static int registered() {
        return MenuRuntime.count();
    }
}
