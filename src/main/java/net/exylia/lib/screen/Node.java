package net.exylia.lib.screen;

import net.exylia.lib.text.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One box on a {@link Screen}: a row, a column, a line of text or an item, laid out like a small flexbox.
 *
 * <pre>{@code
 * Node.column(
 *         Node.text("{letters}Choose your kit").scale(1.4),
 *         Node.row(
 *                 Node.text("{letters}Queue").padding(8, 16).background("{primary}").hover("{secondary}")
 *                         .onClick(event -> queue.join(event.viewer())),
 *                 Node.text("{letters}Leave").padding(8, 16).background("#2a2240").hover("{error}")
 *                         .onClick(event -> event.session().close()))
 *                 .gap(8))
 *     .padding(16).gap(12).background("#17131f");
 * }</pre>
 *
 * <p>Sizes are in screen pixels: a screen is {@link Screen#WIDTH} by {@link Screen#HEIGHT} unless it says
 * otherwise, and a line of text at scale 1 is ten pixels tall. A box without a width or height takes what its
 * children need; {@link #grow} hands it a share of what is left in its parent. Children run along the main axis
 * (across for a row, down for a column) and, by default, stretch across the other one, as in CSS.
 *
 * <p>Nodes are built fresh on every render ({@link Screen#of}), so they are plain mutable builders and are not
 * shared between threads.
 *
 * @since 1.483.0
 */
public final class Node {

    /** What a box is. */
    public enum Kind {
        ROW, COLUMN, TEXT,
        /** An item drawn as itself, block or sprite or head: {@link #item(ItemStack)}. @since 1.488.0 */
        ITEM,
        /** A small picture drawn pixel by pixel: {@link #image(int, int, int[])}. @since 1.489.0 */
        IMAGE
    }

    /** Where children sit across the main axis. */
    public enum Align { START, CENTER, END, STRETCH }

    /** How spare room along the main axis is shared out when no child grows. */
    public enum Justify { START, CENTER, END, SPACE_BETWEEN }

    private final Kind kind;
    private final List<Node> children = new ArrayList<>();
    private final Component text;
    private ItemStack item;
    private int[] pixels;
    private int pixelWidth;
    private int pixelHeight;
    private boolean overlay;
    private Consumer<ScreenSession> onDismiss;
    private String id;
    private String key;
    private String turntable;
    private double width = -1;
    private double height = -1;
    private double grow;
    private double padTop, padRight, padBottom, padLeft;
    private double gap;
    private double scale = 1;
    private Align align = Align.STRETCH;
    private Align textAlign = Align.START;
    private Justify justify = Justify.START;
    private String background;
    private String hover;
    private String outline;
    private String hoverOutline;
    private double outlineWidth = 1;
    private Consumer<ScreenEvent> onClick;
    private Consumer<ScreenEvent> onScroll;

    private Node(Kind kind, Component text) {
        this.kind = kind;
        this.text = text;
    }

    /** Children side by side, left to right. */
    public static @NotNull Node row(@NotNull Node... children) {
        return new Node(Kind.ROW, null).add(children);
    }

    /** Children stacked, top to bottom. */
    public static @NotNull Node column(@NotNull Node... children) {
        return new Node(Kind.COLUMN, null).add(children);
    }

    /** One line of text, written with {@link Text}: palette tokens, MiniMessage and {@code &} codes. */
    public static @NotNull Node text(@NotNull String text) {
        return text(Text.of(text).build());
    }

    /** One line of text, already parsed. */
    public static @NotNull Node text(@NotNull Component text) {
        return new Node(Kind.TEXT, text);
    }

    /**
     * An item drawn as it looks in an inventory slot: a block, a sprite, a player head with its skin. Sixteen
     * pixels square unless sized. No resource pack is involved: the client draws the item itself.
     *
     * @since 1.488.0
     */
    public static @NotNull Node item(@NotNull ItemStack item) {
        Node node = new Node(Kind.ITEM, null);
        node.item = item.clone();
        return node;
    }

    /**
     * A small picture, drawn with the vanilla font's full block one pixel at a time, so no resource pack is
     * needed: logos, badges, a player's body. One layout pixel a picture pixel unless sized; a size keeps the
     * picture square-pixelled and centred. Pixels more than half see-through take the colour behind them.
     * Pictures past {@link #MAX_IMAGE} pixels a side are refused: each pixel is a glyph sent to the client.
     *
     * @param argb {@code width * height} colours, row by row from the top left
     * @since 1.489.0
     */
    public static @NotNull Node image(int width, int height, int @NotNull [] argb) {
        if (width < 1 || height < 1 || width > MAX_IMAGE || height > MAX_IMAGE) {
            throw new IllegalArgumentException("A picture is 1 to " + MAX_IMAGE + " pixels a side, not " + width + "x" + height);
        }
        if (argb.length != width * height) throw new IllegalArgumentException("Expected " + width * height + " colours");
        Node node = new Node(Kind.IMAGE, null);
        node.pixels = argb.clone();
        node.pixelWidth = width;
        node.pixelHeight = height;
        return node;
    }

    /** {@link #image(int, int, int[])} from a decoded picture. @since 1.489.0 */
    public static @NotNull Node image(@NotNull java.awt.image.BufferedImage picture) {
        int w = picture.getWidth();
        int h = picture.getHeight();
        return image(w, h, picture.getRGB(0, 0, w, h, null, 0, w));
    }

    /** The largest side {@link #image} takes, in pixels. @since 1.489.0 */
    public static final int MAX_IMAGE = 96;

    /** An empty box: a spacer with {@link #grow}, or a block of colour with a size. */
    public static @NotNull Node box() {
        return new Node(Kind.COLUMN, null);
    }

    /** Adds children after the ones already here; a text node takes none. */
    public @NotNull Node add(@NotNull Node... more) {
        if ((kind == Kind.TEXT || kind == Kind.ITEM || kind == Kind.IMAGE) && more.length > 0) {
            throw new IllegalStateException("A " + kind.name().toLowerCase(java.util.Locale.ROOT) + " node has no children");
        }
        for (Node child : more) children.add(java.util.Objects.requireNonNull(child, "child"));
        return this;
    }

    /** Adds children from a list, for rows built in a loop. */
    public @NotNull Node add(@NotNull List<Node> more) {
        return add(more.toArray(Node[]::new));
    }

    /**
     * A movement key that clicks this box: {@code w}, {@code a}, {@code s}, {@code d} or {@code space}. Pressed,
     * it acts as a left click on the box wherever the cursor is, when the box is on screen and can be reached.
     *
     * @since 1.492.0
     */
    public @NotNull Node key(@Nullable String key) {
        this.key = key == null ? null : key.toLowerCase(java.util.Locale.ROOT);
        return this;
    }

    /** @since 1.492.0 */
    public @Nullable String key() {
        return key;
    }

    /**
     * Draws a player's body in this box in the round, turning slowly on the spot, once their skin is known; until
     * then the box shows what it holds (a flat picture of them, say). {@code null} turns it off.
     *
     * @since 1.492.0
     */
    public @NotNull Node turntable(@Nullable String player) {
        this.turntable = player;
        return this;
    }

    /** @since 1.492.0 */
    public @Nullable String turntable() {
        return turntable;
    }

    /** A name for this box, handed back in {@link ScreenEvent#node()}; also keeps it stable between renders. */
    public @NotNull Node id(@Nullable String id) {
        this.id = id;
        return this;
    }

    /** A fixed width, in screen pixels. */
    public @NotNull Node width(double pixels) {
        this.width = pixels;
        return this;
    }

    /** A fixed height, in screen pixels. */
    public @NotNull Node height(double pixels) {
        this.height = pixels;
        return this;
    }

    /** A fixed width and height, in screen pixels. */
    public @NotNull Node size(double width, double height) {
        return width(width).height(height);
    }

    /** A share of the room its parent has left along the main axis; 0 (the default) takes none. */
    public @NotNull Node grow(double share) {
        this.grow = Math.max(0, share);
        return this;
    }

    /** The same room inside every edge. */
    public @NotNull Node padding(double all) {
        return padding(all, all);
    }

    /** Room above and below, and to each side. */
    public @NotNull Node padding(double vertical, double horizontal) {
        this.padTop = this.padBottom = vertical;
        this.padLeft = this.padRight = horizontal;
        return this;
    }

    /**
     * Room inside each edge, clockwise from the top, as in CSS.
     *
     * @since 1.485.0
     */
    public @NotNull Node padding(double top, double right, double bottom, double left) {
        this.padTop = top;
        this.padRight = right;
        this.padBottom = bottom;
        this.padLeft = left;
        return this;
    }

    /** Room between children. */
    public @NotNull Node gap(double pixels) {
        this.gap = pixels;
        return this;
    }

    /** How large text is: 1 draws one pixel of the font as one screen pixel. */
    public @NotNull Node scale(double scale) {
        this.scale = scale;
        return this;
    }

    /** Where children sit across the main axis; {@link Align#STRETCH} by default. */
    public @NotNull Node align(@NotNull Align align) {
        this.align = align;
        return this;
    }

    /**
     * Where a line of text sits in a box wider than it: {@link Align#START} (left, the default, as in HTML),
     * {@link Align#CENTER} or {@link Align#END}.
     *
     * @since 1.488.0
     */
    public @NotNull Node textAlign(@NotNull Align align) {
        this.textAlign = align;
        return this;
    }

    /** How spare room along the main axis is shared out. */
    public @NotNull Node justify(@NotNull Justify justify) {
        this.justify = justify;
        return this;
    }

    /** Fills the box: a palette token ({@code {primary}}), {@code #rrggbb} or {@code #aarrggbb}. */
    public @NotNull Node background(@Nullable String colour) {
        this.background = colour;
        return this;
    }

    /** The fill while the cursor is over the box; it also makes the box pointable. */
    public @NotNull Node hover(@Nullable String colour) {
        this.hover = colour;
        return this;
    }

    /**
     * A line drawn around the box, outside it: a palette token, {@code #rrggbb} or {@code #aarrggbb}.
     *
     * @since 1.485.0
     */
    public @NotNull Node outline(@Nullable String colour, double width) {
        this.outline = colour;
        this.outlineWidth = Math.max(0, width);
        return this;
    }

    /**
     * The outline while the cursor is over the box; it also makes the box pointable.
     *
     * @since 1.485.0
     */
    public @NotNull Node hoverOutline(@Nullable String colour) {
        this.hoverOutline = colour;
        return this;
    }

    /**
     * Lifts the box out of the flow and centres it over the whole screen, above a dimmer, as a dialog: while one
     * is on screen only what is inside it can be pointed at. Shift, or a click on the dimmer, runs
     * {@link #onDismiss} instead of closing the screen.
     *
     * @since 1.488.0
     */
    public @NotNull Node overlay(boolean overlay) {
        this.overlay = overlay;
        return this;
    }

    /**
     * What dismissing an {@link #overlay} does, usually clearing the state that shows it. Without one, Shift
     * closes the screen as usual and the dimmer does nothing.
     *
     * @since 1.488.0
     */
    public @NotNull Node onDismiss(@Nullable Consumer<ScreenSession> handler) {
        this.onDismiss = handler;
        return this;
    }

    /** Runs on a left or right click over the box, on the viewer's thread. */
    public @NotNull Node onClick(@Nullable Consumer<ScreenEvent> handler) {
        this.onClick = handler;
        return this;
    }

    /** Runs on a mouse-wheel step over the box ({@link ScreenEvent#steps()}), on the viewer's thread. */
    public @NotNull Node onScroll(@Nullable Consumer<ScreenEvent> handler) {
        this.onScroll = handler;
        return this;
    }

    public @NotNull Kind kind() {
        return kind;
    }

    public @NotNull List<Node> children() {
        return children;
    }

    public @Nullable Component text() {
        return text;
    }

    /** The colours an {@link Kind#IMAGE} node draws, row by row; do not change them. @since 1.489.0 */
    public int @Nullable [] pixels() {
        return pixels;
    }

    /** An {@link Kind#IMAGE} node's width in picture pixels. @since 1.489.0 */
    public int pixelWidth() {
        return pixelWidth;
    }

    /** An {@link Kind#IMAGE} node's height in picture pixels. @since 1.489.0 */
    public int pixelHeight() {
        return pixelHeight;
    }

    /** The item an {@link Kind#ITEM} node draws. @since 1.488.0 */
    public @Nullable ItemStack item() {
        return item;
    }

    /** @since 1.488.0 */
    public boolean overlay() {
        return overlay;
    }

    /** @since 1.488.0 */
    public @Nullable Consumer<ScreenSession> onDismiss() {
        return onDismiss;
    }

    public @Nullable String id() {
        return id;
    }

    public double width() {
        return width;
    }

    public double height() {
        return height;
    }

    public double grow() {
        return grow;
    }

    public double padTop() {
        return padTop;
    }

    public double padRight() {
        return padRight;
    }

    public double padBottom() {
        return padBottom;
    }

    public double padLeft() {
        return padLeft;
    }

    public double gap() {
        return gap;
    }

    public double scale() {
        return scale;
    }

    public @NotNull Align align() {
        return align;
    }

    /** @since 1.488.0 */
    public @NotNull Align textAlign() {
        return textAlign;
    }

    public @NotNull Justify justify() {
        return justify;
    }

    public @Nullable String background() {
        return background;
    }

    public @Nullable String hover() {
        return hover;
    }

    public @Nullable String outline() {
        return outline;
    }

    public @Nullable String hoverOutline() {
        return hoverOutline;
    }

    public double outlineWidth() {
        return outlineWidth;
    }

    public @Nullable Consumer<ScreenEvent> onClick() {
        return onClick;
    }

    public @Nullable Consumer<ScreenEvent> onScroll() {
        return onScroll;
    }

    /** Whether the cursor can land on it: it reacts to clicks, the wheel or hovering. */
    public boolean pointable() {
        return onClick != null || onScroll != null || hover != null || hoverOutline != null;
    }
}
