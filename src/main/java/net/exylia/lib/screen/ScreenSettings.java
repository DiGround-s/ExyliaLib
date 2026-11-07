package net.exylia.lib.screen;

import net.exylia.lib.config.Comment;
import net.exylia.lib.config.Menu;

import java.util.Map;

/**
 * How every Exylia plugin's menus open: as chest windows or as screens. Generated as
 * {@code plugins/ExyliaLib/screens.yml}; editable in game through {@code /exylialib config}. Read by
 * {@link net.exylia.lib.ui.MenuStyles}.
 *
 * @param defaultStyle   the style of every plugin that does not choose one
 * @param playerChoice   whether players may pick their own style in {@code /settings}
 * @param plugins        a style for one plugin by its name, over {@code defaultStyle}
 * @param chestSkin      whether a chest menu with no screen of its own is drawn as cards on a screen in SCREEN
 *                       style; off, such menus stay chest windows
 * @since 1.495.0
 */
@Comment("How the menus of every Exylia plugin open.")
@Comment("")
@Comment("CLASSIC opens chest windows. SCREEN opens full-screen menus drawn")
@Comment("in front of the player, with a mouse cursor and no resource pack.")
@Comment("A client that cannot draw screens (Bedrock, or older than 1.20.2)")
@Comment("always gets chest windows.")
@Comment("")
@Comment("Run /exylialib reload after editing. No restart is needed.")
@Menu.About({"How menus open on this server:", "chest windows or screens."})
public record ScreenSettings(

        @Comment("The style of every plugin that does not set its own")
        @Comment("menu-style (or sets it to DEFAULT). CLASSIC or SCREEN.")
        @Menu.Label("Default style") @Menu.Icon("CHEST")
        @Menu.About({"How menus open unless a plugin", "or the player says otherwise."})
        @Menu.Choices(value = {"CLASSIC", "SCREEN"}, labels = {"Chest windows", "Screens"})
        String defaultStyle,

        @Comment("Whether players may choose chest windows or screens for")
        @Comment("themselves, in /settings. Their choice wins over every")
        @Comment("plugin's menu-style.")
        @Menu.Label("Player choice") @Menu.Icon("PLAYER_HEAD")
        @Menu.About({"Players pick chest windows or", "screens for themselves."})
        boolean playerChoice,

        @Comment("A style for one plugin, by its name, over default-style.")
        @Comment("A plugin's own menu-style setting still wins. Example:")
        @Comment("  plugins:")
        @Comment("    ExyliaJobs: SCREEN")
        @Menu.Label("Per plugin") @Menu.Icon("BOOKSHELF")
        Map<String, String> plugins,

        @Comment("In SCREEN style, a menu without a screen of its own is")
        @Comment("drawn as cards on a screen, and its clicks work as in the")
        @Comment("chest. Off, those menus stay chest windows.")
        @Menu.Label("Cards for chest menus") @Menu.Icon("ITEM_FRAME")
        @Menu.About({"Chest menus with no screen of", "their own are drawn as cards."})
        boolean chestSkin
) {

    /** CLASSIC for everyone, players may choose, cards on. */
    public ScreenSettings() {
        this("CLASSIC", true, Map.of(), true);
    }

    private static volatile ScreenSettings current = new ScreenSettings();

    /** The settings in force: the file's once the library has read it, the defaults before. */
    public static ScreenSettings get() {
        return current;
    }

    /** Puts new settings in force. Called by the library when it reads the file. */
    @org.jetbrains.annotations.ApiStatus.Internal
    public static void apply(ScreenSettings settings) {
        current = settings == null ? new ScreenSettings() : settings;
    }
}
