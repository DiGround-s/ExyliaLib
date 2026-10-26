package net.exylia.lib.stage;

import net.exylia.lib.config.Comment;

import java.util.List;

/**
 * How every {@link Stage} of a plugin looks: the table behind a board, its frame, its buttons, its
 * verdict banner, what a win sets off, its sounds, and how far a board is brought towards the eyes.
 *
 * <pre>{@code
 * // Embedded in the plugin's own config record, so the server owner restyles every screen at once:
 * public record MyConfig(@Key("stage") StageTheme stage) {
 *     public MyConfig() {
 *         this(new StageTheme().withTable("BLACK_CONCRETE", "CYAN_CONCRETE", "{info}"));
 *     }
 * }
 *
 * Stage stage = new Stage(player, plane, displays, 60_000L, config.get().stage());
 * }</pre>
 *
 * <p>Nothing in the library reads a file for it: each plugin passes the theme it ships, so two
 * plugins on one server keep their own look. Blocks and items are written by name and fall back to a
 * safe default when unknown; colours are palette tokens ({@code {primary}}) or {@code #rrggbb}.
 *
 * @param tableBlock   the block behind every board
 * @param frameBlock   the frame round it
 * @param frameGlow    the frame's outline
 * @param buttonBlock  what buttons are made of
 * @param buttonGlow   the outline of the button under the crosshair
 * @param hoverSound   played when the crosshair lands on a button; blank for none
 * @param pressSound   played when a button is pressed; blank for none
 * @param verdictBlock the plaque a finished verdict lands on
 * @param verdictWin   its outline on a win
 * @param verdictLose  on a loss
 * @param verdictDraw  on a draw
 * @param rainItem     the item that rains on a win; blank for none
 * @param rainCount    how many of them
 * @param fireworks    whether a win sets off fireworks only the viewer sees
 * @param winColours   the fireworks' colours
 * @param zoom         how many times nearer, and so larger, every board is drawn; 1 draws it where placed
 * @param maxWide      the widest a zoomed board may span, in degrees of the view
 * @param maxTall      the tallest, in degrees
 * @param nearest      the nearest a zoomed board may come to the eyes, in blocks
 * @since 1.356.0
 */
public record StageTheme(
        @Comment("The block behind every board, and the frame round it with its outline colour.")
        String tableBlock,
        String frameBlock,
        String frameGlow,
        @Comment("What buttons are made of, and their outline under the crosshair.")
        String buttonBlock,
        String buttonGlow,
        @Comment("Sounds as NAME|volume|pitch: the crosshair landing on a button, and a button pressed.")
        @Comment("Leave one empty for none.")
        String hoverSound,
        String pressSound,
        @Comment("The banner a finished verdict lands on: its block, and its outline on a win, a loss and a draw.")
        String verdictBlock,
        String verdictWin,
        String verdictLose,
        String verdictDraw,
        @Comment("A win rains this item over the board, this many of them. Empty or 0 for none.")
        String rainItem,
        int rainCount,
        @Comment("A win sets off fireworks only the winner sees, in these colours.")
        boolean fireworks,
        List<String> winColours,
        @Comment("How many times nearer, and so larger, every board is drawn than its own distance says. 1 draws")
        @Comment("each board where it is placed. A board never grows past max-wide by max-tall degrees of the")
        @Comment("view, nor comes nearer than nearest blocks.")
        double zoom,
        double maxWide,
        double maxTall,
        double nearest) {

    /** The library's look: a dark table in the palette's primary colour, no zoom. */
    public StageTheme() {
        this("BLACK_CONCRETE", "PURPLE_CONCRETE", "{primary}", "GRAY_CONCRETE", "{secondary_light}",
                "BLOCK_NOTE_BLOCK_HAT|0.4|1.8", "UI_BUTTON_CLICK|0.6|1.2",
                "BLACK_CONCRETE", "{success}", "{error}", "{warning}",
                "AMETHYST_SHARD", 12, true, List.of("{highlight}", "{success}", "{secondary}"),
                1, 110, 80, 1.5);
    }

    public StageTheme {
        winColours = winColours == null ? List.of() : List.copyOf(winColours);
    }

    /** The library's look; the same as {@code new StageTheme()}. */
    public static StageTheme defaults() {
        return new StageTheme();
    }

    /** The same theme with another table, frame and frame outline. */
    public StageTheme withTable(String tableBlock, String frameBlock, String frameGlow) {
        return new StageTheme(tableBlock, frameBlock, frameGlow, buttonBlock, buttonGlow, hoverSound, pressSound,
                verdictBlock, verdictWin, verdictLose, verdictDraw, rainItem, rainCount, fireworks, winColours,
                zoom, maxWide, maxTall, nearest);
    }

    /** The same theme with other button sounds; blank for none, when the caller plays its own. */
    public StageTheme withSounds(String hoverSound, String pressSound) {
        return new StageTheme(tableBlock, frameBlock, frameGlow, buttonBlock, buttonGlow, hoverSound, pressSound,
                verdictBlock, verdictWin, verdictLose, verdictDraw, rainItem, rainCount, fireworks, winColours,
                zoom, maxWide, maxTall, nearest);
    }

    /** The same theme with another zoom and its limits. */
    public StageTheme withZoom(double zoom, double maxWide, double maxTall, double nearest) {
        return new StageTheme(tableBlock, frameBlock, frameGlow, buttonBlock, buttonGlow, hoverSound, pressSound,
                verdictBlock, verdictWin, verdictLose, verdictDraw, rainItem, rainCount, fireworks, winColours,
                zoom, maxWide, maxTall, nearest);
    }
}
