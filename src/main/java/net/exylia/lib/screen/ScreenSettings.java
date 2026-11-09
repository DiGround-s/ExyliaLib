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
 * @param motion         {@code FULL} or {@code REDUCED} (since 1.496.0): reduced draws no arrivals, departures or
 *                       animations and glides in a straight line, for every player; each player may also ask for it
 * @param motionCeiling  the most motion packets a second one screen sends (since 1.496.0); past it, looping
 *                       animations wait and eased glides become one straight glide
 * @param cursorAcceleration how much a fast mouse move speeds the cursor up (since 1.496.0): 0 for none
 * @param spriteObjects  whether items are drawn as one sprite glyph for 1.21.11+ clients on a 1.21.11+ server
 *                       (since 1.496.0), instead of a picture of four displays
 * @param screenSize   how large screens are drawn, in percent, for players who did not choose (since 1.519.0)
 * @param hideSidebar  whether a player's sidebar is hidden while a screen is open (since 1.522.0)
 * @param quietChat    whether chat is kept quiet while a screen is open (since 1.523.0)
 * @param cursorMode   how the cursor follows the mouse for players who did not choose (since 1.546.0): {@code NORMAL},
 *                     {@code RAW} or {@code PREDICTION}
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
        boolean chestSkin,

        @Comment("FULL draws screens with every arrival, glide and animation.")
        @Comment("REDUCED keeps only what is needed: no arrivals or departures,")
        @Comment("no animations, straight short glides. Players may also turn")
        @Comment("on reduced motion for themselves in /settings.")
        @Menu.Label("Motion") @Menu.Icon("FEATHER")
        @Menu.About({"How much screens move:", "everything, or only what is needed."})
        @Menu.Choices(value = {"FULL", "REDUCED"}, labels = {"Full", "Reduced"})
        String motion,

        @Comment("The most motion packets one screen sends a second. Past it,")
        @Comment("looping animations wait and eased glides become straight ones.")
        @Menu.Label("Motion ceiling") @Menu.Icon("HOPPER")
        @Menu.About({"Packets a second one screen", "may spend on motion."})
        int motionCeiling,

        @Comment("How much a fast mouse move speeds the cursor up. 0 turns it")
        @Comment("off; 0.5 helps players who cross the screen often.")
        @Menu.Label("Cursor acceleration") @Menu.Icon("SUGAR")
        @Menu.About({"Fast moves carry the cursor", "further. 0 turns it off."})
        double cursorAcceleration,

        @Comment("Draw items as one sprite glyph for 1.21.11+ players on a")
        @Comment("1.21.11+ server, rather than a picture of four displays.")
        @Menu.Label("Sprite items") @Menu.Icon("ITEM_FRAME")
        @Menu.About({"Items as one glyph where", "the client can draw it."})
        boolean spriteObjects,

        @Comment("How large screens are drawn, in percent, for players who did not")
        @Comment("choose their own size. 100 fills three quarters of the window at")
        @Comment("FOV 70; each player's FOV slider changes that (FOV 110 needs about")
        @Comment("200), and the server cannot see it, so players set their own with")
        @Comment("the button at the bottom right of every screen.")
        @Menu.Label("Screen size") @Menu.Icon("SPYGLASS")
        @Menu.About({"How large screens are drawn,", "in percent, from 50 to 250."})
        int screenSize,

        @Comment("Hide the sidebar while a screen is open, whichever plugin draws")
        @Comment("it. It keeps updating and comes back as it is by then when the")
        @Comment("screen closes.")
        @Menu.Label("Hide sidebar") @Menu.Icon("OAK_SIGN")
        @Menu.About({"The sidebar steps out of the", "way while a screen is open."})
        boolean hideSidebar,

        @Comment("Keep the chat quiet while a screen is open: other players' chat")
        @Comment("and system messages wait until the screen closes.")
        @Comment("A message that answers a click also shows on the screen.")
        @Menu.Label("Quiet chat") @Menu.Icon("WRITABLE_BOOK")
        @Menu.About({"No chat over a screen: messages", "wait until it closes."})
        boolean quietChat,

        @Comment("How the cursor follows the mouse for players who did not choose.")
        @Comment("NORMAL glides after it, RAW moves at once in small steps,")
        @Comment("PREDICTION glides ahead of it and settles back when it stops.")
        @Comment("Only the drawing differs: clicks and hover land the same.")
        @Menu.Label("Cursor mode") @Menu.Icon("COMPASS")
        @Menu.About({"How the cursor follows the mouse,", "unless the player picks their own."})
        @Menu.Choices(value = {"NORMAL", "RAW", "PREDICTION"}, labels = {"Normal", "Raw", "Prediction"})
        String cursorMode
) {

    /** CLASSIC for everyone, players may choose, cards on, full motion. */
    public ScreenSettings() {
        this("CLASSIC", true, Map.of(), true);
    }

    /** The settings as they were before 1.496.0, with full motion. */
    public ScreenSettings(String defaultStyle, boolean playerChoice, Map<String, String> plugins, boolean chestSkin) {
        this(defaultStyle, playerChoice, plugins, chestSkin, "FULL", 300, 0, true, 100, true, true);
    }

    /** The settings as they were before 1.519.0, at the full size. */
    public ScreenSettings(String defaultStyle, boolean playerChoice, Map<String, String> plugins, boolean chestSkin,
                          String motion, int motionCeiling, double cursorAcceleration, boolean spriteObjects) {
        this(defaultStyle, playerChoice, plugins, chestSkin, motion, motionCeiling, cursorAcceleration, spriteObjects, 100);
    }

    /** The settings as they were before 1.522.0, with the sidebar hidden. */
    public ScreenSettings(String defaultStyle, boolean playerChoice, Map<String, String> plugins, boolean chestSkin,
                          String motion, int motionCeiling, double cursorAcceleration, boolean spriteObjects, int screenSize) {
        this(defaultStyle, playerChoice, plugins, chestSkin, motion, motionCeiling, cursorAcceleration, spriteObjects,
                screenSize, true);
    }

    /** The settings as they were before 1.523.0, with the chat kept quiet. */
    public ScreenSettings(String defaultStyle, boolean playerChoice, Map<String, String> plugins, boolean chestSkin,
                          String motion, int motionCeiling, double cursorAcceleration, boolean spriteObjects, int screenSize,
                          boolean hideSidebar) {
        this(defaultStyle, playerChoice, plugins, chestSkin, motion, motionCeiling, cursorAcceleration, spriteObjects,
                screenSize, hideSidebar, true);
    }

    /** The settings as they were before 1.546.0, with the normal cursor. */
    public ScreenSettings(String defaultStyle, boolean playerChoice, Map<String, String> plugins, boolean chestSkin,
                          String motion, int motionCeiling, double cursorAcceleration, boolean spriteObjects, int screenSize,
                          boolean hideSidebar, boolean quietChat) {
        this(defaultStyle, playerChoice, plugins, chestSkin, motion, motionCeiling, cursorAcceleration, spriteObjects,
                screenSize, hideSidebar, quietChat, "NORMAL");
    }

    /** Whether {@link #motion} asks for reduced motion. @since 1.496.0 */
    public boolean reducedMotion() {
        return "REDUCED".equalsIgnoreCase(motion);
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
