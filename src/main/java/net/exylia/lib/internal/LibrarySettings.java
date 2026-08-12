package net.exylia.lib.internal;

import net.exylia.lib.ExyliaLib;
import net.exylia.lib.config.Comment;
import net.exylia.lib.config.Configs;

/**
 * Runtime settings for ExyliaLib itself.
 *
 * <p>Generated as {@code plugins/ExyliaLib/config.yml} on first start.
 * Controls whether the library checks for updates after the server is up.
 *
 * @since 1.6.0
 */
@Comment("ExyliaLib runtime settings.")
@Comment("")
@Comment("auto-update: when true (default), the library checks for a newer")
@Comment("version after startup and stages it for the next restart.")
@Comment("Set to false if you prefer to update manually.")
public record LibrarySettings(
        @Comment("Whether to check for and download newer versions automatically.")
        boolean autoUpdate
) {

    /** Safe defaults used when no config file exists yet. */
    public LibrarySettings() {
        this(true);
    }

    private static volatile LibrarySettings instance;

    /**
     * Loads the settings, creating the config file if absent.
     * Called once from {@link ExyliaLib#onEnable()}.
     */
    public static LibrarySettings load(ExyliaLib plugin) {
        if (instance != null) return instance;
        instance = Configs.define(plugin, "config", LibrarySettings.class).load().get();
        return instance;
    }

    /** Returns the singleton, or defaults if not yet loaded. */
    public static LibrarySettings get() {
        LibrarySettings s = instance;
        return s != null ? s : new LibrarySettings();
    }
}
