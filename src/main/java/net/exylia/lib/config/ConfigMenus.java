package net.exylia.lib.config;

import net.exylia.lib.web.internal.ConfigMenu;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Consumer;

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
 *     ConfigMenus.of(plugin)
 *             .permission("myplugin.admin")
 *             .back(hub::open)
 *             .open(context.player());
 *     return ActionResult.success();
 * });
 * }</pre>
 *
 * <p>The files offered are the web editor's {@code settings} group: a file the plugin
 * registered as a web editor subject, or excluded from discovery, is left out. Maps and lists
 * of sections are shown read-only and edited with {@code /exylialib editor}.
 *
 * <p>Any thread. The viewer needs {@link #PERMISSION} to change anything, or the permission
 * given to {@link Opener#permission} for that plugin's files.
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

    /**
     * Opens one plugin's settings from that plugin's own screens.
     *
     * @param plugin whose settings
     * @return an opener; nothing opens until {@link Opener#open}
     * @since 1.445.0
     * @deprecated a plugin's admin hub carries its own settings screens, built with
     *             {@link ConfigEdits}; this generic tree stays behind {@code /exylialib config}.
     */
    @Deprecated(since = "1.555.0")
    public static @NotNull Opener of(@NotNull Plugin plugin) {
        return new Opener(Objects.requireNonNull(plugin, "plugin"));
    }

    /**
     * One plugin's settings, entered from its admin hub: who may edit them there and where
     * BACK returns.
     *
     * @since 1.445.0
     */
    public static final class Opener {

        private final Plugin plugin;
        private @Nullable String permission;
        private @Nullable Consumer<Player> back;

        private Opener(Plugin plugin) {
            this.plugin = plugin;
        }

        /**
         * Lets a viewer holding {@code permission} edit this plugin's files, as well as one
         * holding {@link #PERMISSION}. Usually the plugin's admin permission, the one its hub asks.
         *
         * @param permission the plugin's admin permission
         * @return this opener
         */
        public @NotNull Opener permission(@NotNull String permission) {
            this.permission = Objects.requireNonNull(permission, "permission");
            return this;
        }

        /**
         * What BACK on the top screen (the file list, or a single file's first page) does,
         * instead of showing every plugin with settings. Runs on the viewer's thread.
         *
         * @param back usually reopens the hub the viewer came from
         * @return this opener
         */
        public @NotNull Opener back(@NotNull Consumer<Player> back) {
            this.back = Objects.requireNonNull(back, "back");
            return this;
        }

        /**
         * Opens the settings: the plugin's only file straight away, or the list of its files.
         *
         * @param viewer who edits; any thread
         */
        public void open(@NotNull Player viewer) {
            ConfigMenu.open(viewer, plugin, permission, back);
        }
    }
}
