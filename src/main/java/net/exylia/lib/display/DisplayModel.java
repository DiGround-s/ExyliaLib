package net.exylia.lib.display;

import net.kyori.adventure.text.Component;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * What a display draws: an item, a block or a line of text.
 *
 * <pre>{@code
 * DisplayModel blade = DisplayModel.item(new ItemStack(Material.NETHERITE_SWORD))
 *         .glow(0xFF6B9D)
 *         .light(15);
 * }</pre>
 *
 * <h2>Model, not motion</h2>
 * This says <em>what</em> is drawn and never <em>where it goes</em>: the same
 * model is used by a sword that falls, one that spins and one that stands still,
 * and duplicating its item stack three times to say so would be three copies of
 * the same thing. {@link DisplayMotion} carries the movement.
 *
 * <p>Immutable and shared. One model built when configuration was read serves
 * every player who ever triggers the effect.
 *
 * @since 1.85.0
 */
public final class DisplayModel {

    /** The item context that draws a model at its own size. */
    private static final byte HOLD_NONE = 0;

    /** Which display entity draws this. */
    public enum Kind {

        /** An item, drawn as the client draws a dropped one. */
        ITEM,

        /** A block, drawn at its full cubic size. */
        BLOCK,

        /** A line of text, drawn on a panel. */
        TEXT
    }

    /** How a text display lines up its lines. */
    public enum Align {

        /** Every line centred, the client's default. */
        CENTER,

        /** Every line against the left edge. */
        LEFT,

        /** Every line against the right edge. */
        RIGHT
    }

    // Not final so that the copy constructor can change one field at a time;
    // nothing outside this class writes them, so a model is still immutable.
    private Kind kind;
    private ItemStack item;
    private BlockData block;
    private Component text;
    private int glowArgb;
    private int brightness;
    private byte billboard;
    private byte itemTransform;
    private Function<Player, Component> textFor;
    private int background = UNSET;
    private int opacity = UNSET;
    private boolean seeThrough = true;
    private boolean textShadow;
    private Align align = Align.CENTER;
    private int lineWidth = UNSET;
    private float viewRange = UNSET;
    private float shadowRadius = UNSET;
    private float shadowStrength = UNSET;
    private float cullWidth = UNSET;
    private float cullHeight = UNSET;

    /** A field the client keeps at its own default. */
    private static final int UNSET = -1;

    private DisplayModel(Kind kind, ItemStack item, BlockData block, Component text,
                         int glowArgb, int brightness, byte billboard, byte itemTransform) {
        this.kind = kind;
        this.item = item;
        this.block = block;
        this.text = text;
        this.glowArgb = glowArgb;
        this.brightness = brightness;
        this.billboard = billboard;
        this.itemTransform = itemTransform;
    }

    /** A copy to change one field of. */
    private DisplayModel copy() {
        DisplayModel copy = new DisplayModel(kind, item, block, text, glowArgb, brightness,
                billboard, itemTransform);
        copy.textFor = textFor;
        copy.background = background;
        copy.opacity = opacity;
        copy.seeThrough = seeThrough;
        copy.textShadow = textShadow;
        copy.align = align;
        copy.lineWidth = lineWidth;
        copy.viewRange = viewRange;
        copy.shadowRadius = shadowRadius;
        copy.shadowStrength = shadowStrength;
        copy.cullWidth = cullWidth;
        copy.cullHeight = cullHeight;
        return copy;
    }

    /**
     * An item display.
     *
     * @param item what it shows; kept as given, so build it once
     * @return the model
     */
    public static @NotNull DisplayModel item(@NotNull ItemStack item) {
        // NONE: the model at its own size, so size:1 is one block and a server
        // owner can picture what they wrote. Every other context bakes in a
        // scale of somebody else's choosing — GROUND halves it, GUI flattens
        // it — which turns every number in the file into a number times a
        // constant nobody can see.
        return new DisplayModel(Kind.ITEM, item, null, null, -1, -1, (byte) 0, HOLD_NONE);
    }

    /**
     * A block display.
     *
     * @param block the block state it shows
     * @return the model
     */
    public static @NotNull DisplayModel block(@NotNull BlockData block) {
        return new DisplayModel(Kind.BLOCK, null, block, null, -1, -1, (byte) 0, (byte) 0);
    }

    /**
     * A text display, facing the viewer.
     *
     * @param text the line it shows
     * @return the model
     */
    public static @NotNull DisplayModel text(@NotNull Component text) {
        // CENTER billboarding, because text that a player can walk behind and
        // read backwards is text nobody made a decision about.
        return new DisplayModel(Kind.TEXT, null, null, text, -1, -1, (byte) 3, (byte) 0);
    }

    /**
     * A text display whose line depends on who is reading it.
     *
     * <pre>{@code
     * DisplayModel banner = DisplayModel.text(viewer -> viewer.equals(opener)
     *         ? Text.of("{success}You won %reward%").build()
     *         : Text.of("{letters}" + opener.getName() + " won %reward%").build());
     * }</pre>
     *
     * <p>Asked once per viewer when the display is spawned for them, when a
     * player walks into a tracked display's range, and on every
     * {@link DisplayHandle#restyle}; never per frame. It runs on whatever thread
     * sends the packets, often the asynchronous display driver, so it must read
     * memory only and must not throw.
     *
     * @param text the line for one viewer
     * @return the model
     * @since 1.272.0
     */
    public static @NotNull DisplayModel text(@NotNull Function<Player, Component> text) {
        DisplayModel model = text(Component.empty());
        model.textFor = text;
        return model;
    }

    /**
     * The same model showing a different item.
     *
     * <p>For the one thing that cannot be decided when a file is read: a head
     * wearing the face of whoever the effect happened to. Everything else about
     * the model &mdash; its glow, its light, how it is held &mdash; was a
     * decision the file already made, and is kept.
     *
     * @param replacement what it shows instead
     * @return a new model
     */
    public @NotNull DisplayModel showing(@NotNull ItemStack replacement) {
        DisplayModel copy = copy();
        copy.kind = Kind.ITEM;
        copy.item = replacement;
        copy.block = null;
        copy.text = null;
        copy.textFor = null;
        return copy;
    }

    /**
     * How an item is held, as the protocol numbers the display contexts.
     *
     * <p>A calibration knob rather than a feature: which way a model faces and
     * how big it is are facts about that model, and a resource pack whose blade
     * reads sideways needs a number here, not a rebuild. {@code 0} is the model
     * at its own size, {@code 5} is the head context, {@code 7} is a dropped
     * item and {@code 8} is an item frame.
     *
     * @param context the display context
     * @return a new model
     */
    public @NotNull DisplayModel held(int context) {
        DisplayModel copy = copy();
        copy.itemTransform = (byte) Math.clamp(context, 0, 8);
        return copy;
    }

    /**
     * An outline around the model, in {@code 0xRRGGBB}.
     *
     * <p>The one thing that makes a display read against a bright sky or a
     * white floor, and the cheapest depth an effect gets: an outline is drawn
     * through geometry, so a glowing blade stays visible inside an explosion.
     *
     * @param rgb the outline colour, or a negative number for none
     * @return a new model
     */
    public @NotNull DisplayModel glow(int rgb) {
        DisplayModel copy = copy();
        copy.glowArgb = rgb;
        return copy;
    }

    /**
     * A fixed light level from 0 to 15, instead of the light where it stands.
     *
     * <p>Worth setting on almost every effect: a display lit by the world is
     * black at night and black in a cave, and an effect that vanishes after
     * sunset is an effect players think is broken.
     *
     * @param level the level, or a negative number to use the world's light
     * @return a new model
     */
    public @NotNull DisplayModel light(int level) {
        DisplayModel copy = copy();
        copy.brightness = level;
        return copy;
    }

    /**
     * How the model turns to face the viewer.
     *
     * @param constraint {@code FIXED}, {@code VERTICAL}, {@code HORIZONTAL} or {@code CENTER}
     * @return a new model
     */
    public @NotNull DisplayModel billboard(@NotNull String constraint) {
        byte packed = switch (constraint.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "VERTICAL" -> 1;
            case "HORIZONTAL" -> 2;
            case "CENTER" -> 3;
            default -> 0;
        };
        DisplayModel copy = copy();
        copy.billboard = packed;
        return copy;
    }

    /**
     * The same text model with another line, for everyone.
     *
     * @param replacement the new line
     * @return a new model
     * @since 1.272.0
     */
    public @NotNull DisplayModel withText(@NotNull Component replacement) {
        DisplayModel copy = copy();
        copy.text = replacement;
        copy.textFor = null;
        return copy;
    }

    /**
     * The same text model with a line per viewer; see {@link #text(Function)}.
     *
     * @param replacement the line for one viewer
     * @return a new model
     * @since 1.272.0
     */
    public @NotNull DisplayModel withText(@NotNull Function<Player, Component> replacement) {
        DisplayModel copy = copy();
        copy.text = Component.empty();
        copy.textFor = replacement;
        return copy;
    }

    /**
     * A panel behind the text, in {@code 0xAARRGGBB}.
     *
     * <p>Unset, a text display keeps the client's default panel, which is what
     * every effect before 1.272.0 drew. {@code 0} is no panel at all, which is
     * what a floating label over a reveal usually wants.
     *
     * @param argb the panel colour with its alpha
     * @return a new model
     * @since 1.272.0
     */
    public @NotNull DisplayModel background(int argb) {
        DisplayModel copy = copy();
        copy.background = argb;
        return copy;
    }

    /**
     * How opaque the text itself is, from 0 to 255.
     *
     * <p>Values under 26 are drawn fully opaque by the client, which is a
     * vanilla quirk rather than ours; fade a label out by shrinking it instead.
     *
     * @param alpha the opacity, or a negative number for the client's default
     * @return a new model
     * @since 1.272.0
     */
    public @NotNull DisplayModel opacity(int alpha) {
        DisplayModel copy = copy();
        copy.opacity = alpha < 0 ? UNSET : Math.min(255, alpha);
        return copy;
    }

    /**
     * Whether the text shows through blocks. On by default, because a label in
     * an effect is read, not walked around.
     *
     * @param through whether walls hide it
     * @return a new model
     * @since 1.272.0
     */
    public @NotNull DisplayModel seeThrough(boolean through) {
        DisplayModel copy = copy();
        copy.seeThrough = through;
        return copy;
    }

    /**
     * Whether the text casts the drop shadow chat text has. Off by default.
     *
     * @param shadow whether to draw it
     * @return a new model
     * @since 1.272.0
     */
    public @NotNull DisplayModel textShadow(boolean shadow) {
        DisplayModel copy = copy();
        copy.textShadow = shadow;
        return copy;
    }

    /**
     * How the lines of a text display line up.
     *
     * @param alignment left, centre or right
     * @return a new model
     * @since 1.272.0
     */
    public @NotNull DisplayModel align(@NotNull Align alignment) {
        DisplayModel copy = copy();
        copy.align = alignment;
        return copy;
    }

    /**
     * Where a text display wraps, in pixels of the default font.
     *
     * @param pixels the width, or a negative number for the client's 200
     * @return a new model
     * @since 1.272.0
     */
    public @NotNull DisplayModel lineWidth(int pixels) {
        DisplayModel copy = copy();
        copy.lineWidth = pixels < 0 ? UNSET : pixels;
        return copy;
    }

    /**
     * How far away it is still drawn, as a multiple of the client's entity
     * distance (1 is about 64 blocks at the default setting).
     *
     * @param multiplier the range, or a negative number for the default
     * @return a new model
     * @since 1.272.0
     */
    public @NotNull DisplayModel viewRange(float multiplier) {
        DisplayModel copy = copy();
        copy.viewRange = multiplier < 0 ? UNSET : multiplier;
        return copy;
    }

    /**
     * A round shadow on the ground under it.
     *
     * @param radius   how wide, in blocks; up to 64
     * @param strength how dark, where 1 is the vanilla mob shadow
     * @return a new model
     * @since 1.272.0
     */
    public @NotNull DisplayModel shadow(float radius, float strength) {
        DisplayModel copy = copy();
        copy.shadowRadius = Math.max(0f, radius);
        copy.shadowStrength = Math.max(0f, strength);
        return copy;
    }

    /**
     * The box the client tests against the edge of the screen.
     *
     * <p>A display's box is zero by default, so the client culls it the moment
     * its anchor leaves the screen, even while what it draws is still in view.
     * A wheel two blocks wide, a carousel or a beam wants a box as large as what
     * it draws, centred horizontally on the anchor and rising from it.
     *
     * @param width  the box's width, in blocks
     * @param height the box's height, in blocks
     * @return a new model
     * @since 1.272.0
     */
    public @NotNull DisplayModel culling(float width, float height) {
        DisplayModel copy = copy();
        copy.cullWidth = Math.max(0f, width);
        copy.cullHeight = Math.max(0f, height);
        return copy;
    }

    /**
     * The line one viewer reads.
     *
     * @param viewer who reads it, or {@code null} for the shared line
     * @return the line, or {@code null} for a model that is not text
     * @since 1.272.0
     */
    public @Nullable Component textFor(@Nullable Player viewer) {
        if (textFor != null && viewer != null) {
            Component own = textFor.apply(viewer);
            return own != null ? own : Component.empty();
        }
        return text;
    }

    /**
     * Whether the line differs per viewer.
     *
     * @since 1.272.0
     */
    public boolean isPerViewer() {
        return textFor != null;
    }

    /**
     * The panel colour, or {@code null} for the client's default panel.
     *
     * @since 1.272.0
     */
    public @Nullable Integer background() {
        return background == UNSET ? null : background;
    }

    /**

     * The text opacity, or a negative number for the default.

     *

     * @since 1.272.0

     */
    public int opacity() {
        return opacity;
    }

    /**

     * Whether the text shows through blocks.

     *

     * @since 1.272.0

     */
    public boolean seeThrough() {
        return seeThrough;
    }

    /**

     * Whether the text has a drop shadow.

     *

     * @since 1.272.0

     */
    public boolean textShadow() {
        return textShadow;
    }

    /**

     * How the lines line up.

     *

     * @since 1.272.0

     */
    public @NotNull Align align() {
        return align;
    }

    /**

     * Where the text wraps, or a negative number for the default.

     *

     * @since 1.272.0

     */
    public int lineWidth() {
        return lineWidth;
    }

    /**

     * The view range multiplier, or a negative number for the default.

     *

     * @since 1.272.0

     */
    public float viewRange() {
        return viewRange;
    }

    /**

     * The ground shadow's radius, or a negative number for none.

     *

     * @since 1.272.0

     */
    public float shadowRadius() {
        return shadowRadius;
    }

    /**

     * The ground shadow's strength, or a negative number for the default.

     *

     * @since 1.272.0

     */
    public float shadowStrength() {
        return shadowStrength;
    }

    /**

     * The culling box width, or a negative number for the default.

     *

     * @since 1.272.0

     */
    public float cullWidth() {
        return cullWidth;
    }

    /**

     * The culling box height, or a negative number for the default.

     *

     * @since 1.272.0

     */
    public float cullHeight() {
        return cullHeight;
    }

    /** What this draws. */
    public @NotNull Kind kind() {
        return kind;
    }

    /** The item, for an {@link Kind#ITEM} model. */
    public @Nullable ItemStack item() {
        return item;
    }

    /** The block state, for a {@link Kind#BLOCK} model. */
    public @Nullable BlockData block() {
        return block;
    }

    /** The line, for a {@link Kind#TEXT} model. */
    public @Nullable Component text() {
        return text;
    }

    /** The outline colour as {@code 0xRRGGBB}, or a negative number for none. */
    public int glowArgb() {
        return glowArgb;
    }

    /** The light level override, or a negative number to use the world's. */
    public int brightness() {
        return brightness;
    }

    /** The billboard constraint, as the protocol numbers it. */
    public byte billboard() {
        return billboard;
    }

    /** The item display context, as the protocol numbers it. */
    public byte itemTransform() {
        return itemTransform;
    }
}
