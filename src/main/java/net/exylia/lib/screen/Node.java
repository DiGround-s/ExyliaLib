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
    private Node backgroundImage;
    private int pixelWidth;
    private int pixelHeight;
    private boolean overlay;
    private boolean assist = true;
    private Consumer<ScreenSession> onDismiss;
    private String id;
    private String key;
    private String turntable;
    private String wearing;
    private boolean solid;
    private net.exylia.lib.spatial.SpatialPreview preview;
    private boolean vanish;
    private double spin;
    private Consumer<ScreenEvent> onHover;
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
    private List<Component> tooltip;
    private Motion.Transform transform = Motion.Transform.NONE;
    private Motion.Transform hoverTransform;
    private Motion.Transform activeTransform;
    private String activeBackground;
    private Motion.Transition transition;
    private Motion.Tween enter;
    private Motion.Tween leave;
    private Motion.Keyframes animation;
    private double marginTop, marginRight, marginBottom, marginLeft;
    private double[] absolute;
    private int layer;
    private double wrap;
    private boolean shadow;
    private boolean decoration;
    private int refresh;
    private Consumer<ScreenEvent> onShiftClick;

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
     * An item as a real 3D model (a block with its sides, a sword with its edge, enchantment glint), turning
     * {@code spin} degrees a second about the upright, 0 to stand still. While one is on screen the viewer's camera
     * is kept within a few degrees of where the screen opened, so the world's lights shade it the same however they
     * move the cursor. Below 1.21.9 a 3D item is the only way to show glint; {@link #item(ItemStack)} draws a flat
     * sprite instead.
     *
     * @since 1.499.0
     */
    public static @NotNull Node item3d(@NotNull ItemStack item, double spin) {
        Node node = item(item);
        node.solid = true;
        node.spin = spin;
        return node;
    }

    /**
     * A window cut in the screen, looking at {@code preview} played for real on a private stage in the world behind
     * it: an effect's bodies, displays and particles, whatever it is made of. Sized by {@code size} or {@code grow};
     * its background colours the stage's back wall. {@code null} or {@link net.exylia.lib.spatial.SpatialPreview#none}
     * is a plain box, drawing whatever else is put in it, so what cannot be previewed keeps its icon. One a screen.
     *
     * @since 1.538.0
     */
    public static @NotNull Node window(@org.jetbrains.annotations.Nullable net.exylia.lib.spatial.SpatialPreview preview) {
        Node node = box();
        node.preview = preview == net.exylia.lib.spatial.SpatialPreview.none() ? null : preview;
        return node;
    }

    /**
     * Whether a {@link #window}'s mannequin disappears while an effect plays, so a kill reads as a death (a spatial
     * layout's {@code vanish-on-play}). Off by default: it stands there and takes the effect.
     *
     * @since 1.538.0
     */
    public @NotNull Node vanish(boolean vanish) {
        this.vanish = vanish;
        return this;
    }

    /** Whether {@link #vanish(boolean)} is on. @since 1.538.0 */
    public boolean vanish() {
        return vanish;
    }

    /** What this box plays as a {@link #window}, or {@code null} for a plain box. @since 1.538.0 */
    public @org.jetbrains.annotations.Nullable net.exylia.lib.spatial.SpatialPreview preview() {
        return preview;
    }

    /** Whether this item is drawn as a 3D model ({@link #item3d}). @since 1.499.0 */
    public boolean solid() {
        return solid;
    }

    /** Degrees a second a {@link #item3d} turns. @since 1.499.0 */
    public double spin() {
        return spin;
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

    /**
     * What the body this box draws ({@link #turntable}, or a flat {@code <img skin>}) wears: armour composited onto
     * the skin from the game's own textures, leather dyed and trims in their colours, and the first other item in
     * its right hand. Words as {@link Screens#outfit} makes them ({@code diamond_helmet iron_chestplate
     * leather_leggings#3a6bd1 netherite_boots@silence:gold diamond_sword}); {@code null} or empty wears nothing.
     *
     * @since 1.498.0
     */
    public @NotNull Node wearing(@Nullable String outfit) {
        this.wearing = outfit == null || outfit.isBlank() ? null : outfit.trim();
        return this;
    }

    /** As {@link #wearing(String)}, from the items themselves (helmet to boots, a held item; {@code null}s skipped). */
    public @NotNull Node wearing(@Nullable ItemStack... items) {
        return wearing(items == null ? null : Screens.outfit(items));
    }

    /** @since 1.498.0 */
    public @Nullable String wearing() {
        return wearing;
    }

    /**
     * Runs once each time the cursor comes onto this box (not again until it has left), on the viewer's thread:
     * show what the box is about somewhere else on the screen ({@code session().set("hovered_kit", id)}). The
     * event's kind is {@link ScreenEvent.Kind#LEFT} and it counts no steps. Makes the box pointable.
     *
     * @since 1.498.0
     */
    public @NotNull Node onHover(@Nullable Consumer<ScreenEvent> handler) {
        this.onHover = handler;
        return this;
    }

    /** @since 1.498.0 */
    public @Nullable Consumer<ScreenEvent> onHover() {
        return onHover;
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
     * {@code false} keeps cursor assist away from this box and everything over it: no friction, no bend toward it and
     * no click tolerance, so a click lands exactly where the pointer is. For boards, stakes and anything dangerous. A
     * scene, a dangerous confirm and {@code <game-frame>} are set already.
     *
     * @since 1.547.0
     */
    public @NotNull Node assist(boolean assist) {
        this.assist = assist;
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

    /**
     * Lines shown in a small panel beside this box while the cursor is over it, as an item's name and lore
     * show over a slot: the first line is the title. Lines are written with {@link Text}; {@code null} or no
     * lines shows none. Costs two displays and one per line, only while hovered.
     *
     * @since 1.495.0
     */
    public @NotNull Node tooltip(@Nullable List<Component> lines) {
        this.tooltip = lines == null || lines.isEmpty() ? null : List.copyOf(lines);
        return this;
    }

    /** The same, from lines written with {@link Text}. @since 1.495.0 */
    public @NotNull Node tooltip(@NotNull String... lines) {
        List<Component> parsed = new ArrayList<>(lines.length);
        for (String line : lines) parsed.add(Text.of(line).build());
        return tooltip(parsed);
    }

    /** The tooltip's lines, or {@code null}. @since 1.495.0 */
    public @Nullable List<Component> tooltip() {
        return tooltip;
    }

    // ------------------------------------------------------------------
    // Motion and placement (1.496.0)
    // ------------------------------------------------------------------

    /**
     * Moves and scales the box and everything in it, after layout, as CSS {@code transform}: its place in the
     * flow and what it can be clicked on stay where layout put it.
     *
     * @since 1.496.0
     */
    public @NotNull Node transform(@NotNull Motion.Transform transform) {
        this.transform = transform;
        return this;
    }

    /** The transform while the cursor (or the keyboard focus) is on the box; it also makes it pointable. @since 1.496.0 */
    public @NotNull Node hoverTransform(@Nullable Motion.Transform transform) {
        this.hoverTransform = transform;
        return this;
    }

    /**
     * How the box looks for a moment after it is clicked, as CSS {@code :active}: a transform (a press) and a fill,
     * either {@code null} to keep the hovered one.
     *
     * @since 1.496.0
     */
    public @NotNull Node active(@Nullable String background, @Nullable Motion.Transform transform) {
        this.activeBackground = background;
        this.activeTransform = transform;
        return this;
    }

    /** How a change of this box's transform (and of what is inside it) glides. Two linear ticks when unset. @since 1.496.0 */
    public @NotNull Node transition(int ticks, @NotNull Motion.Ease ease) {
        return transition(new Motion.Transition(Math.max(0, ticks), ease, false));
    }

    /** @since 1.496.0 */
    public @NotNull Node transition(@Nullable Motion.Transition transition) {
        this.transition = transition;
        return this;
    }

    /**
     * How the box arrives when it was not on screen before: from {@code tween}'s transform to its place, with
     * everything that arrives with it. {@link Motion.Tween#NONE} makes it appear at once.
     *
     * @since 1.496.0
     */
    public @NotNull Node enter(@Nullable Motion.Tween tween) {
        this.enter = tween;
        return this;
    }

    /** How the box goes: it glides to {@code tween}'s transform and is then removed. @since 1.496.0 */
    public @NotNull Node leave(@Nullable Motion.Tween tween) {
        this.leave = tween;
        return this;
    }

    /** An animation of the box's transform, from when it first shows. @since 1.496.0 */
    public @NotNull Node animation(@Nullable Motion.Keyframes animation) {
        this.animation = animation;
        return this;
    }

    /** Room outside each edge, clockwise from the top, as in CSS. @since 1.496.0 */
    public @NotNull Node margin(double top, double right, double bottom, double left) {
        this.marginTop = top;
        this.marginRight = right;
        this.marginBottom = bottom;
        this.marginLeft = left;
        return this;
    }

    /**
     * Takes the box out of the flow and places it inside its parent, as CSS {@code position: absolute}: each edge
     * is a distance from the parent's matching edge, {@link Double#NaN} for none. With neither {@code left} nor
     * {@code right} it sits at the left; with both it stretches between them (the same for top and bottom).
     *
     * @since 1.496.0
     */
    public @NotNull Node absolute(double top, double right, double bottom, double left) {
        this.absolute = new double[]{top, right, bottom, left};
        return this;
    }

    /** Draws the box (and what is in it) this many layers nearer than its depth puts it, as CSS {@code z-index}. @since 1.496.0 */
    public @NotNull Node layer(int layer) {
        this.layer = Math.max(-4, Math.min(8, layer));
        return this;
    }

    /**
     * Wraps a text node's words onto lines no wider than {@code width} pixels (at scale 1 of the font), one display
     * for the whole paragraph. 0 (the default) keeps one line.
     *
     * @since 1.496.0
     */
    public @NotNull Node wrap(double width) {
        this.wrap = Math.max(0, width);
        return this;
    }

    /** Draws a text node's words with the font's shadow, as CSS {@code text-shadow}. Free: a flag of the display. @since 1.496.0 */
    public @NotNull Node shadow(boolean shadow) {
        this.shadow = shadow;
        return this;
    }

    /**
     * Marks the box as decoration ({@code priority="low"}): the first thing left out when a screen would draw more
     * displays than it may.
     *
     * @since 1.496.0
     */
    public @NotNull Node decoration(boolean decoration) {
        this.decoration = decoration;
        return this;
    }

    /**
     * Asks the screen to render this often, in ticks, while the box is on it: a countdown that moves faster than the
     * one-second redraw. 0 (the default) keeps the second.
     *
     * @since 1.496.0
     */
    public @NotNull Node refresh(int ticks) {
        this.refresh = Math.max(0, ticks);
        return this;
    }

    /**
     * Runs on a click made while the player holds sprint (their shift-click on a screen, where Shift closes it),
     * instead of {@link #onClick}. Without one, such a click is an ordinary click with {@link ScreenEvent#shift()}.
     *
     * @since 1.496.0
     */
    public @NotNull Node onShiftClick(@Nullable Consumer<ScreenEvent> handler) {
        this.onShiftClick = handler;
        return this;
    }

    /** @since 1.496.0 */
    public @NotNull Motion.Transform transform() {
        return transform;
    }

    /** @since 1.496.0 */
    public @Nullable Motion.Transform hoverTransform() {
        return hoverTransform;
    }

    /** @since 1.496.0 */
    public @Nullable Motion.Transform activeTransform() {
        return activeTransform;
    }

    /** @since 1.496.0 */
    public @Nullable String activeBackground() {
        return activeBackground;
    }

    /** @since 1.496.0 */
    public @Nullable Motion.Transition transition() {
        return transition;
    }

    /** @since 1.496.0 */
    public @Nullable Motion.Tween enter() {
        return enter;
    }

    /** @since 1.496.0 */
    public @Nullable Motion.Tween leave() {
        return leave;
    }

    /** @since 1.496.0 */
    public @Nullable Motion.Keyframes animation() {
        return animation;
    }

    /** {top, right, bottom, left}. @since 1.496.0 */
    public double[] margin() {
        return new double[]{marginTop, marginRight, marginBottom, marginLeft};
    }

    /** {top, right, bottom, left} with NaN for unset, or {@code null} when the box is in the flow. @since 1.496.0 */
    public double @Nullable [] absolute() {
        return absolute == null ? null : absolute.clone();
    }

    /** @since 1.496.0 */
    public int layer() {
        return layer;
    }

    /** @since 1.496.0 */
    public double wrap() {
        return wrap;
    }

    /** @since 1.496.0 */
    public boolean shadow() {
        return shadow;
    }

    /** @since 1.496.0 */
    public boolean decoration() {
        return decoration;
    }

    /** @since 1.496.0 */
    public int refresh() {
        return refresh;
    }

    /** @since 1.496.0 */
    public @Nullable Consumer<ScreenEvent> onShiftClick() {
        return onShiftClick;
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
    /**
     * A picture behind the box's content and over its background, cut to cover the box with square pixels and
     * centred ({@code background-size: cover}); clear pixels show the background. Made with {@link #image}; keep it
     * near the box's own shape. {@code null} for none.
     *
     * @since 1.497.0
     */
    public @NotNull Node backgroundImage(@Nullable Node picture) {
        if (picture != null && picture.pixels == null) throw new IllegalArgumentException("A background image is a Node.image");
        this.backgroundImage = picture;
        return this;
    }

    /** @since 1.497.0 */
    public @Nullable Node backgroundImage() {
        return backgroundImage;
    }

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

    /** @since 1.547.0 */
    public boolean assist() {
        return assist;
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

    /** Whether the cursor can land on it: it reacts to clicks, the wheel or hovering, or has a tooltip to show. */
    public boolean pointable() {
        return onClick != null || onScroll != null || onHover != null || hover != null || hoverOutline != null
                || hoverTransform != null || tooltip != null;
    }
}
