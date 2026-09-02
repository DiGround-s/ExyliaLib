package net.exylia.lib.display;

import net.kyori.adventure.text.Component;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

    private final Kind kind;
    private final ItemStack item;
    private final BlockData block;
    private final Component text;
    private final int glowArgb;
    private final int brightness;
    private final byte billboard;
    private final byte itemTransform;

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
        return new DisplayModel(Kind.ITEM, replacement, null, null,
                glowArgb, brightness, billboard, itemTransform);
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
        return new DisplayModel(kind, item, block, text, glowArgb, brightness, billboard,
                (byte) Math.clamp(context, 0, 8));
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
        return new DisplayModel(kind, item, block, text, rgb, brightness, billboard, itemTransform);
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
        return new DisplayModel(kind, item, block, text, glowArgb, level, billboard, itemTransform);
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
        return new DisplayModel(kind, item, block, text, glowArgb, brightness, packed, itemTransform);
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
