package net.exylia.lib.screen;

import net.exylia.lib.config.Comment;
import net.exylia.lib.config.Menu;
import org.jetbrains.annotations.NotNull;

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
 * @param screenSize   how large screens are drawn, in percent, for everyone (since 1.519.0; players chose their own until 1.587.0)
 * @param hideSidebar  whether a player's sidebar is hidden while a screen is open (since 1.522.0)
 * @param quietChat    whether chat is kept quiet while a screen is open (since 1.523.0)
 * @param cursorMode   how the cursor follows the mouse for players who did not choose (since 1.546.0): {@code NORMAL},
 *                     {@code RAW} or {@code PREDICTION}
 * @param cursorAssist whether cursor assist is on for players who did not choose (since 1.547.0)
 * @param firstScreenSetup whether a player is asked to fit screens and the HUD to their window the first time they
 *                         meet either (since 1.558.0; the fitting since 1.584.0)
 * @param adminStyle   {@code CLASSIC} keeps admin menus chest windows, {@code FOLLOW} lets them follow the style
 *                     (since 1.562.0)
 * @param board        how a BOARD menu floats in front of the player (since 1.617.0)
 * @param cursorPoll   {@code AUTO} or {@code OFF} (since 1.664.0): whether the cursor follows the mouse every frame for
 *                     players whose client and this server are 1.21.9 or later, by asking the client where it looks many
 *                     times a tick; {@code OFF} keeps every player on the tick-by-tick cursor
 * @param hoverHighlight {@code AUTO} or {@code OFF} (since 1.665.0): whether, with the frame cursor, the box under the
 *                     pointer lights up the frame the arrow reaches it, by a lit copy moved over it, before the hover
 *                     itself is drawn; {@code OFF} lights it on the server's tick, as everyone else
 * @since 1.495.0
 */
@Comment("How the menus of every Exylia plugin open.")
@Comment("")
@Comment("CLASSIC opens chest windows. SCREEN opens full-screen menus drawn")
@Comment("in front of the player, with a mouse cursor and no resource pack.")
@Comment("BOARD floats the menu as a panel in the world in front of the")
@Comment("player, who keeps walking and aims with the crosshair; a plugin")
@Comment("that has no boards shows SCREEN instead.")
@Comment("A client that cannot draw screens (Bedrock, or older than 1.20.2)")
@Comment("always gets chest windows.")
@Comment("")
@Comment("Run /exylialib reload after editing. No restart is needed.")
@Menu.About({"How menus open on this server:", "chest windows or screens."})
public record ScreenSettings(

        @Comment("The style of every plugin that does not set its own")
        @Comment("menu-style (or sets it to DEFAULT). CLASSIC, SCREEN or BOARD.")
        @Menu.Label("Default style") @Menu.Icon("CHEST")
        @Menu.About({"How menus open unless a plugin", "or the player says otherwise."})
        @Menu.Choices(value = {"CLASSIC", "SCREEN", "BOARD"}, labels = {"Chest windows", "Screens", "Floating board"})
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

        @Comment("How large screens are drawn, in percent, for everyone. 100 fills")
        @Comment("three quarters of the window; each player's FOV and window are")
        @Comment("fitted once on their first screen, so it holds at any FOV.")
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
        String cursorMode,

        @Comment("Whether cursor assist is on for players who did not choose. It makes")
        @Comment("buttons easier to hit: a little friction over them, a slight pull")
        @Comment("toward them on a slow move, and a click just off one still lands.")
        @Comment("It never acts on game boards, stakes or dangerous buttons.")
        @Menu.Label("Cursor assist") @Menu.Icon("SLIME_BALL")
        @Menu.About({"Makes buttons easier to hit,", "unless the player says otherwise."})
        boolean cursorAssist,

        @Comment("Ask a player to fit screens and the HUD to their FOV and window")
        @Comment("the first time they meet either, since the server cannot see")
        @Comment("them. Players can fit again from a screen's settings.")
        @Menu.Label("First screen setup") @Menu.Icon("COMPARATOR")
        @Menu.About({"Asks players to fit screens and", "the HUD to their window once."})
        boolean firstScreenSetup,

        @Comment("How admin menus open (any menu under an admin/ folder, and these")
        @Comment("settings screens). CLASSIC keeps them chest windows whatever the")
        @Comment("style: admin work is dense lists, editors and typed values, which")
        @Comment("a chest does best. FOLLOW lets them follow the style like any menu.")
        @Menu.Label("Admin menus") @Menu.Icon("COMMAND_BLOCK")
        @Menu.About({"Admin menus as chest windows,", "or following the menu style."})
        @Menu.Choices(value = {"CLASSIC", "FOLLOW"}, labels = {"Chest windows", "Follow the style"})
        String adminStyle,

        @Comment("How a BOARD menu floats in front of the player.")
        Board board,

        @Comment("AUTO moves the cursor every frame, instead of every tick, for")
        @Comment("players whose client and this server are 1.21.9 or later: the")
        @Comment("server asks their client where it looks many times a tick, and")
        @Comment("the answers never reach the server or other plugins while a")
        @Comment("screen is open. OFF keeps everyone on the tick-by-tick cursor.")
        @Menu.Label("Frame cursor") @Menu.Icon("CLOCK")
        @Menu.About({"The cursor follows the mouse", "every frame on 1.21.9+."})
        @Menu.Choices(value = {"AUTO", "OFF"}, labels = {"Auto", "Off"})
        String cursorPoll,

        @Comment("AUTO lights the box under the pointer the very frame the frame")
        @Comment("cursor reaches it, instead of on the server's next tick: a few")
        @Comment("lit copies wait out of sight and move over it. Only boxes whose")
        @Comment("hover just changes a solid colour. OFF lights every box on the tick.")
        @Menu.Label("Instant hover") @Menu.Icon("GLOWSTONE_DUST")
        @Menu.About({"Buttons light up the frame", "the frame cursor reaches them."})
        @Menu.Choices(value = {"AUTO", "OFF"}, labels = {"Auto", "Off"})
        String hoverHighlight
) {

    /** The settings as they were before 1.665.0, with the instant hover where the frame cursor runs. */
    public ScreenSettings(String defaultStyle, boolean playerChoice, Map<String, String> plugins, boolean chestSkin,
                          String motion, int motionCeiling, double cursorAcceleration, boolean spriteObjects, int screenSize,
                          boolean hideSidebar, boolean quietChat, String cursorMode, boolean cursorAssist,
                          boolean firstScreenSetup, String adminStyle, Board board, String cursorPoll) {
        this(defaultStyle, playerChoice, plugins, chestSkin, motion, motionCeiling, cursorAcceleration, spriteObjects,
                screenSize, hideSidebar, quietChat, cursorMode, cursorAssist, firstScreenSetup, adminStyle, board, cursorPoll,
                "AUTO");
    }

    /** The settings as they were before 1.664.0, with the frame cursor where it can run. */
    public ScreenSettings(String defaultStyle, boolean playerChoice, Map<String, String> plugins, boolean chestSkin,
                          String motion, int motionCeiling, double cursorAcceleration, boolean spriteObjects, int screenSize,
                          boolean hideSidebar, boolean quietChat, String cursorMode, boolean cursorAssist,
                          boolean firstScreenSetup, String adminStyle, Board board) {
        this(defaultStyle, playerChoice, plugins, chestSkin, motion, motionCeiling, cursorAcceleration, spriteObjects,
                screenSize, hideSidebar, quietChat, cursorMode, cursorAssist, firstScreenSetup, adminStyle, board, "AUTO");
    }

    /** Whether {@link #hoverHighlight} lights boxes the frame the frame cursor reaches them; a missing value is {@code AUTO}. @since 1.665.0 */
    public boolean hoverHighlights() {
        return !"OFF".equalsIgnoreCase(hoverHighlight);
    }

    /** Whether {@link #cursorPoll} lets the cursor follow every frame where it can; a missing value is {@code AUTO}. @since 1.664.0 */
    public boolean cursorPolls() {
        return !"OFF".equalsIgnoreCase(cursorPoll);
    }

    /**
     * A BOARD menu: a panel in the world in front of the player, who is not frozen. It glides back in front of them
     * when they walk away from it or turn well away.
     *
     * @param distance       blocks from the eyes to the panel
     * @param width          blocks across; the height follows the screen's shape
     * @param closeWithSneak whether a sneak with no click closes it (sneak and click is a shift-click)
     * @since 1.617.0
     */
    public record Board(
            @Comment("Blocks from the player's eyes to the panel.")
            @Menu.Label("Board distance") @Menu.Icon("LEAD") @Menu.Range(min = 1.5, max = 6)
            double distance,

            @Comment("How wide the panel is, in blocks. Its height follows the menu.")
            @Menu.Label("Board width") @Menu.Icon("PAINTING") @Menu.Range(min = 1, max = 8)
            double width,

            @Comment("Whether sneaking without clicking closes it. Sneak and click")
            @Comment("is a shift-click either way.")
            @Menu.Label("Close with sneak") @Menu.Icon("LEATHER_BOOTS")
            boolean closeWithSneak
    ) {

        /** 2.6 blocks away, 4.2 wide, a sneak closes it. */
        public Board() {
            this(2.6, 4.2, true);
        }
    }

    /** The settings as they were before 1.617.0, with the default board. */
    public ScreenSettings(String defaultStyle, boolean playerChoice, Map<String, String> plugins, boolean chestSkin,
                          String motion, int motionCeiling, double cursorAcceleration, boolean spriteObjects, int screenSize,
                          boolean hideSidebar, boolean quietChat, String cursorMode, boolean cursorAssist,
                          boolean firstScreenSetup, String adminStyle) {
        this(defaultStyle, playerChoice, plugins, chestSkin, motion, motionCeiling, cursorAcceleration, spriteObjects,
                screenSize, hideSidebar, quietChat, cursorMode, cursorAssist, firstScreenSetup, adminStyle, new Board());
    }

    /** The board in force, never {@code null}. @since 1.617.0 */
    public @NotNull Board boardOrDefault() {
        return board == null ? new Board() : board;
    }

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

    /** The settings as they were before 1.547.0, with no cursor assist. */
    public ScreenSettings(String defaultStyle, boolean playerChoice, Map<String, String> plugins, boolean chestSkin,
                          String motion, int motionCeiling, double cursorAcceleration, boolean spriteObjects, int screenSize,
                          boolean hideSidebar, boolean quietChat, String cursorMode) {
        this(defaultStyle, playerChoice, plugins, chestSkin, motion, motionCeiling, cursorAcceleration, spriteObjects,
                screenSize, hideSidebar, quietChat, cursorMode, false);
    }

    /** The settings as they were before 1.558.0, with the first screen setup on. */
    public ScreenSettings(String defaultStyle, boolean playerChoice, Map<String, String> plugins, boolean chestSkin,
                          String motion, int motionCeiling, double cursorAcceleration, boolean spriteObjects, int screenSize,
                          boolean hideSidebar, boolean quietChat, String cursorMode, boolean cursorAssist) {
        this(defaultStyle, playerChoice, plugins, chestSkin, motion, motionCeiling, cursorAcceleration, spriteObjects,
                screenSize, hideSidebar, quietChat, cursorMode, cursorAssist, true);
    }

    /** The settings as they were before 1.562.0, with admin menus kept as chest windows. */
    public ScreenSettings(String defaultStyle, boolean playerChoice, Map<String, String> plugins, boolean chestSkin,
                          String motion, int motionCeiling, double cursorAcceleration, boolean spriteObjects, int screenSize,
                          boolean hideSidebar, boolean quietChat, String cursorMode, boolean cursorAssist,
                          boolean firstScreenSetup) {
        this(defaultStyle, playerChoice, plugins, chestSkin, motion, motionCeiling, cursorAcceleration, spriteObjects,
                screenSize, hideSidebar, quietChat, cursorMode, cursorAssist, firstScreenSetup, "CLASSIC");
    }

    /** Whether admin menus stay chest windows whatever the style. @since 1.562.0 */
    public boolean adminWindows() {
        return !"FOLLOW".equalsIgnoreCase(adminStyle);
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
