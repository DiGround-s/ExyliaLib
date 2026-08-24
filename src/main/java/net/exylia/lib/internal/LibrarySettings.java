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
@Comment("")
@Comment("update-check-minutes: how often to look again while the server runs.")
@Comment("The staged jar is applied on the next restart either way; checking")
@Comment("periodically means a server that crashes instead of stopping still")
@Comment("has the newest version waiting. 0 disables the periodic check and")
@Comment("leaves only the ones at startup and shutdown.")
@Comment("")
@Comment("debug: turns on the detail lines of every Exylia plugin at once.")
@Comment("Leave it false in production; turn it on to diagnose a problem")
@Comment("without editing each plugin's own config.")
@Comment("")
@Comment("small-text: draws every line in small capitals, so WELCOME reaches")
@Comment("the screen as \u1D21\u1D07\u029F\u1D04\u1D0F\u1D0D\u1D07. Applies to every message, item name,")
@Comment("lore, scoreboard and hologram of every Exylia plugin at once. This")
@Comment("is the Exylia look, so it is on by default; set it to false for a")
@Comment("server that wants ordinary capitals.")
@Comment("Values a plugin substitutes are left alone: a player named Steve")
@Comment("stays Steve, and a number stays a number.")
@Comment("")
@Comment("bedrock-prefix: the character Floodgate puts in front of a Bedrock")
@Comment("player's name. Used to tell Bedrock players from Java ones when")
@Comment("Floodgate itself is not installed to be asked. It lives here")
@Comment("rather than in input.yml because who is on Bedrock is a fact about")
@Comment("your players, not about asking them questions: menus, forms and")
@Comment("anything else that adapts to the client reads the same value.")
@Comment("")
@Comment("fallback-head: the texture a head is drawn with when it has none of")
@Comment("its own — a lookup that failed, or a source that never carried one.")
@Comment("Same base64 texture property every source in this module accepts.")
@Comment("An invalid value falls back to the library default and is reported")
@Comment("once, the same as any other unreadable config value.")
public record LibrarySettings(
        @Comment("Whether to check for and download newer versions automatically.")
        boolean autoUpdate,

        @Comment("Minutes between update checks while running. 0 disables them.")
        int updateCheckMinutes,

        @Comment("Whether debug lines print, for every plugin using ExyliaLib.")
        boolean debug,

        @Comment("Whether text is drawn in small capitals.")
        boolean smallText,

        @Comment("The prefix Floodgate adds to a Bedrock player's name.")
        @Comment("Leave it empty if your Bedrock players have no prefix.")
        String bedrockPrefix,

        @Comment("The base64 texture drawn on a head with no texture of its own.")
        String fallbackHead
) {

    /**
     * The neutral head texture ExyliaCommons shipped as its default, kept so
     * a config written by neither library still draws the same fallback.
     */
    public static final String DEFAULT_FALLBACK_HEAD =
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0"
                    + "L3RleHR1cmUvYmFkYzA0OGE3Y2U3OGY3ZGFkNzJhMDdkYTI3ZDg1YzA5MTY4ODFlNTUyMmVl"
                    + "ZWQxZTNkYWYyMTdhMzhjMWEifX19";

    /** Safe defaults used when no config file exists yet. */
    public LibrarySettings() {
        this(true, 30, false, true, "*", DEFAULT_FALLBACK_HEAD);
    }

    private static volatile LibrarySettings instance;
    private static volatile net.exylia.lib.config.ConfigFile<LibrarySettings> file;

    /**
     * Loads the settings, creating the config file if absent.
     * Called once from {@link ExyliaLib#onEnable()}.
     */
    public static LibrarySettings load(ExyliaLib plugin) {
        if (instance != null) return instance;
        file = Configs.define(plugin, "config", LibrarySettings.class).load();
        instance = file.get();
        return instance;
    }

    /**
     * Re-reads the file, so the debug switch takes effect without a restart.
     *
     * @return the settings now in force
     */
    public static LibrarySettings reload() {
        net.exylia.lib.config.ConfigFile<LibrarySettings> current = file;
        if (current == null) return get();
        current.reload();
        instance = current.get();
        return instance;
    }

    /** Returns the singleton, or defaults if not yet loaded. */
    public static LibrarySettings get() {
        LibrarySettings s = instance;
        return s != null ? s : new LibrarySettings();
    }
}
