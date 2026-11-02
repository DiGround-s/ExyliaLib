package net.exylia.lib.config;

import net.exylia.lib.web.internal.ConfigMenu;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Changing a plugin's settings from in game, with no code in the plugin.
 *
 * <p>Every record file a plugin defined with {@link Configs#define} and that is not a
 * translation is drawn from its {@link Schema}: a nested section becomes a page, a boolean a
 * switch, an enum a choice, a number a stepper, a length of time, a text, a material or a
 * list of lines a prompt. A click writes through {@link ConfigFile#set}, so the YAML reads
 * exactly as if the owner had typed the value, and the plugin is reloaded the way the web
 * editor does it (its declared {@code Reloads}). The screens read the file's values again
 * every second: editing the YAML by hand and reloading shows on an open screen too.
 *
 * <pre>{@code
 * // a "Settings" button in a plugin's admin hub
 * actions.registerSync("settings", "myplugin.admin", (context, arguments) -> {
 *     ConfigMenus.open(context.player(), plugin);
 *     return ActionResult.success();
 * });
 * }</pre>
 *
 * <p>The files offered are the web editor's {@code settings} group: a file the plugin
 * registered as a web editor subject, or excluded from discovery, is left out. Maps and lists
 * of sections are shown read-only and edited with {@code /exylialib editor}.
 *
 * <p>Any thread. The viewer needs {@link #PERMISSION} to change anything.
 *
 * @since 1.443.0
 */
public final class ConfigMenus {

    /** What changing settings in game takes: {@code exylialib.config}, op by default. */
    public static final String PERMISSION = "exylialib.config";

    private ConfigMenus() {
        throw new AssertionError("No instances.");
    }

    /**
     * Opens the list of plugins with settings.
     *
     * @param viewer who edits
     */
    public static void open(@NotNull Player viewer) {
        ConfigMenu.open(viewer);
    }

    /**
     * Opens one plugin's settings: its only file straight away, or the list of its files.
     *
     * @param viewer who edits
     * @param plugin whose settings
     */
    public static void open(@NotNull Player viewer, @NotNull Plugin plugin) {
        ConfigMenu.open(viewer, plugin);
    }
}
