package net.exylia.lib.internal;

import net.exylia.lib.ExyliaLib;
import net.exylia.lib.config.Comment;
import net.exylia.lib.config.Key;
import net.exylia.lib.config.Configs;
import net.exylia.lib.config.Languages;
import net.exylia.lib.config.Time;

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
@Comment("text-shadow: the drop shadow under every line of every Exylia")
@Comment("plugin — messages, item names, lore, scoreboards, holograms.")
@Comment("  #rrggbb    one colour under every line")
@Comment("  #rrggbbaa  the same, saying how strong it is")
@Comment("  auto       a quarter of each letter\'s own colour, so a gradient")
@Comment("             casts a gradient; what vanilla does")
@Comment("  auto:0.4   the same, keeping 40% instead of a quarter; the default,")
@Comment("             easier to see against the dark of a menu or the chat")
@Comment("  none       no shadow at all, not even the client\'s own")
@Comment("  (empty)    whatever the client draws by itself")
@Comment("A line that carries its own <shadow> tag keeps it either way.")
@Comment("Needs Minecraft 1.21.4 or newer; older servers ignore it.")
@Comment("")
@Comment("timezone: the calendar every scheduled thing is read in — the times")
@Comment("an event starts at, and any other timetable a plugin keeps. Empty")
@Comment("means the host's own zone, which is right until the host and the")
@Comment("players are in different countries. Any IANA name works, such as")
@Comment("Europe/Madrid or America/Bogota. It lives here rather than in each")
@Comment("plugin because a network runs on one clock.")
@Comment("")
@Comment("fallback-head: the texture a head is drawn with when it has none of")
@Comment("its own — a lookup that failed, or a source that never carried one.")
@Comment("Same base64 texture property every source in this module accepts.")
@Comment("An invalid value falls back to the library default and is reported")
@Comment("once, the same as any other unreadable config value.")
@Comment("")
@Comment("metrics: reports to stats.exylia.net the server software and versions,")
@Comment("memory and thread use, the names and versions of the plugins installed,")
@Comment("how the Exylia plugins are set up (event counts, region sizes) and the")
@Comment("errors they throw. No player data and no IP addresses. Set enabled to")
@Comment("false and nothing is ever sent.")
@Comment("")
@Comment("plugin-updates: keeps the other Exylia plugins up to date the same way,")
@Comment("from the GitHub releases of the repository each one names. Checked with")
@Comment("the library's own updates, applied on the next restart.")
public record LibrarySettings(
        @Comment("Language of the whole server: en, es, pt or fr. Every Exylia plugin whose own")
        @Comment("language is 'default' follows it, and so do the library's screens and prompts.")
        @Comment("Each one is a folder under lang/. 'custom' holds the messages this server had")
        @Comment("before languages existed; any other name starts as English.")
        String language,

        @Comment("Whether to check for and download newer versions automatically.")
        boolean autoUpdate,

        @Comment("Minutes between update checks while running. 0 disables them.")
        @Comment("Reads a written duration too: 30m, 2h.")
        @Time(Time.Unit.MINUTES) int updateCheckMinutes,

        @Comment("Whether debug lines print, for every plugin using ExyliaLib.")
        boolean debug,

        @Comment("Whether text is drawn in small capitals.")
        boolean smallText,

        @Key("text-shadow")
        @Comment("The shadow under every line: #rrggbb, #rrggbbaa, auto, auto:0.5, none, or empty.")
        String textShadow,

        @Comment("The base64 texture drawn on a head with no texture of its own.")
        String fallbackHead,

        @Comment("The zone every schedule's times are read in.")
        @Comment("Empty means the machine's own. Example: Europe/Madrid")
        String timezone,

        @Key("mineskin-key")
        @Comment("A MineSkin API key, so ragdoll bodies wear the real skin rather than blocks.")
        @Comment("Get one free at https://account.mineskin.org/keys. Empty keeps bodies drawn")
        @Comment("in blocks. Each new skin is uploaded once and kept in the database of the")
        @Comment("plugin that shows the bodies, so that server, or a network sharing that")
        @Comment("database, never uploads it again. The free plan allows 20 uploads a minute")
        @Comment("and 100 an hour; past the hour, uploads wait for it to reset.")
        String mineskinKey,

        @Key("ragdoll-skin-quality")
        @Comment("How finely a ragdoll body in its real skin is cut, and so what a new skin costs:")
        @Comment("  high    18 uploads: 4-pixel cubes, 19 pieces when a body breaks (about 5 new skins an hour on the free plan)")
        @Comment("  normal  10 uploads: every skin pixel kept exactly, 11 larger pieces (about 10 an hour)")
        @Comment("  low      5 uploads: one head per part, a third of the rows lost (about 20 an hour)")
        String ragdollSkinQuality,

        Metrics metrics,

        @Key("plugin-updates")
        PluginUpdates pluginUpdates
) {

    /**
     * The {@code plugin-updates:} block: other Exylia plugins kept up to date from their
     * GitHub releases, staged for the next restart like the library itself.
     *
     * @param enabled whether they are checked at all
     * @param majors  whether a new major version is installed too, rather than only announced
     * @param owners  the GitHub accounts whose releases may be installed
     * @param skip    plugins never updated, by name
     * @since 1.233.0
     */
    public record PluginUpdates(
            @Comment("Whether Exylia plugins that name their GitHub repository are kept up to date.")
            @Comment("Updates are downloaded, checked and applied on the next restart.")
            boolean enabled,

            @Comment("Whether a new major version (2.0.0 after 1.x) is installed too. Majors may change")
            @Comment("configs or commands, so by default they are only announced.")
            boolean majors,

            @Comment("The GitHub accounts whose releases may be installed. A plugin naming any other")
            @Comment("repository is never updated.")
            java.util.List<String> owners,

            @Comment("Plugins never updated, by name.")
            java.util.List<String> skip
    ) {

        /** The default: on, minor and patch releases only, Exylia's own accounts. */
        public PluginUpdates() {
            this(true, false, java.util.List.of("Exylia-Plugins", "DiGround-s"), java.util.List.of());
        }
    }


    /**
     * The {@code metrics:} block.
     *
     * @param enabled whether anything is sent to stats.exylia.net
     * @since 1.160.0
     */
    public record Metrics(
            @Comment("Whether server software, installed plugins, Exylia plugin setup and errors")
            @Comment("are sent to stats.exylia.net. No player data, no IP addresses.")
            boolean enabled
    ) {

        /** The default: on. */
        public Metrics() {
            this(true);
        }
    }

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
        this(Languages.ENGLISH, true, 30, false, true, "auto:0.4", DEFAULT_FALLBACK_HEAD, "", "", "normal", new Metrics(),
                new PluginUpdates());
    }

    private static volatile LibrarySettings instance;
    private static volatile net.exylia.lib.config.ConfigFile<LibrarySettings> file;

    /**
     * Loads the settings, creating the config file if absent.
     * Called once from {@link ExyliaLib#onEnable()}.
     */
    public static LibrarySettings load(ExyliaLib plugin) {
        if (instance != null) return instance;
        file = Configs.define(plugin, "config", LibrarySettings.class)
                // A server that ran the library before languages keeps the
                // messages it had, moved into lang/custom/.
                .version(2).migration(1, Languages.ADOPT_EXISTING)
                .load();
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
