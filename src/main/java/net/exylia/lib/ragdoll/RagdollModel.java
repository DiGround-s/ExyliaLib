package net.exylia.lib.ragdoll;

import net.exylia.lib.ragdoll.internal.SkinCache;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * What a body looks like: whose skin it wears and how finely it is cut.
 *
 * <pre>{@code
 * RagdollModel body = RagdollModel.of(victim).detail(2).light(15);
 * }</pre>
 *
 * <h2>The real skin, or the nearest blocks</h2>
 * The head is a player head item, so it carries the actual face, hat layer and
 * all. On a server with a MineSkin key the rest of the body is too: every piece
 * is a four-pixel cube repainted as a head texture of its own, made once per
 * skin in the background, so a sleeve comes away wearing its real pixels on
 * every face. Until those cubes exist &mdash; or without a key at all &mdash;
 * everything but the head is drawn in the nearest block to the colour that part
 * of the skin actually is, which is what a box tumbling reads as anyway.
 *
 * <p>Immutable. Cheap to build: a skin is decoded once per texture and shared.
 *
 * @since 1.120.0
 */
public final class RagdollModel {

    /** The finest detail there is: a shell with the design on every face. */
    private static final int MAX_DETAIL = 5;

    private final RagdollSkin skin;
    private final ItemStack head;
    private final int detail;
    private final double scale;
    private final int glowArgb;
    private final int brightness;
    private final ItemStack mainHand;
    private final ItemStack offHand;
    private final ItemStack hat;

    private RagdollModel(RagdollSkin skin, ItemStack head, int detail, double scale,
                         int glowArgb, int brightness) {
        this(skin, head, detail, scale, glowArgb, brightness, null, null, null);
    }

    private RagdollModel(RagdollSkin skin, ItemStack head, int detail, double scale,
                         int glowArgb, int brightness, ItemStack mainHand, ItemStack offHand,
                         ItemStack hat) {
        this.skin = skin;
        this.head = head;
        this.detail = detail;
        this.scale = scale;
        this.glowArgb = glowArgb;
        this.brightness = brightness;
        this.mainHand = mainHand;
        this.offHand = offHand;
        this.hat = hat;
    }

    /**
     * What the body carries: an item in each hand and one on its head.
     *
     * <p>Carried, not placed: a rose in a hand goes wherever the hand goes, a
     * pumpkin on a head turns with the head, and both come apart with the
     * body when it does.
     *
     * @param mainHand what the right hand holds, or {@code null}
     * @param offHand  what the left hand holds, or {@code null}
     * @param hat      what is worn on the head, or {@code null}
     * @return a new model
     * @since 1.134.0
     */
    public @NotNull RagdollModel carrying(@Nullable ItemStack mainHand, @Nullable ItemStack offHand,
                                          @Nullable ItemStack hat) {
        return new RagdollModel(skin, head, detail, scale, glowArgb, brightness, mainHand, offHand, hat);
    }

    /** What the right hand holds, or {@code null}. */
    public @Nullable ItemStack mainHand() {
        return mainHand;
    }

    /** What the left hand holds, or {@code null}. */
    public @Nullable ItemStack offHand() {
        return offHand;
    }

    /** What is worn on the head, or {@code null}. */
    public @Nullable ItemStack hat() {
        return hat;
    }

    /**
     * A body wearing a connected player's skin.
     *
     * <p>Resolves inline and never waits: their face is in the profile that
     * arrived when they logged in, and their skin was read into memory then
     * too. A skin that somehow is not there yet draws in the default colours
     * for one death and in the right ones from the next.
     *
     * @param player whose body it is
     * @return the model
     */
    public static @NotNull RagdollModel of(@NotNull Player player) {
        return new RagdollModel(SkinCache.of(player), head(player), 1, 1.0, -1, -1);
    }

    /**
     * A body in colours given rather than read.
     *
     * <p>For a preview with nobody in it, and for tests.
     *
     * @param skin the colours
     * @param head the head item, or {@code null} for a blank one
     * @return the model
     */
    public static @NotNull RagdollModel of(@NotNull RagdollSkin skin, @Nullable ItemStack head) {
        return new RagdollModel(skin, head, 1, 1.0, -1, -1);
    }

    /**
     * How finely a body in blocks is drawn, from 1 to 5.
     *
     * <p>One to four cut each part into a grid of cells, each cell the block most
     * of its pixels are. One is six boxes for a body, and the cheapest a death
     * can be. Two keeps a sleeve apart from a hand and a shirt apart from a
     * belt, at four times the displays. Four is two skin pixels a cell.
     *
     * <p>Five is not a finer grid but a shell: one core block per part, with the
     * skin's design laid over all six faces in thin plates and every run of one
     * block merged into a single plate. It is the most a body in blocks can
     * show and the most one costs &mdash; up to 120 displays, kept under the
     * per-effect ceiling in displays.yml by dropping the smallest plates
     * first. A body that spells a word is cut at four instead, because plates
     * cannot be laid out as letters.
     *
     * <p>A body wearing its real skin ignores this: its cubes are one fixed grid.
     *
     * @param cells the level, from 1 to 5
     * @return a new model
     */
    public @NotNull RagdollModel detail(int cells) {
        return new RagdollModel(skin, head, Math.clamp(cells, 1, MAX_DETAIL), scale,
                glowArgb, brightness, mainHand, offHand, hat);
    }

    /**
     * How big the body is; 1 is player-sized.
     *
     * @param factor the multiplier
     * @return a new model
     */
    public @NotNull RagdollModel scale(double factor) {
        return new RagdollModel(skin, head, detail, Math.max(0.05, factor), glowArgb, brightness,
                mainHand, offHand, hat);
    }

    /**
     * An outline around every piece, in {@code 0xRRGGBB}.
     *
     * @param rgb the colour, or a negative number for none
     * @return a new model
     */
    public @NotNull RagdollModel glow(int rgb) {
        return new RagdollModel(skin, head, detail, scale, rgb, brightness, mainHand, offHand, hat);
    }

    /**
     * A fixed light level from 0 to 15, instead of the light where it falls.
     *
     * <p>Worth setting on any body that dies at night: a piece lit by the world
     * is black in the dark, and a black box tumbling is not a body.
     *
     * @param level the level, or a negative number to use the world's light
     * @return a new model
     */
    public @NotNull RagdollModel light(int level) {
        return new RagdollModel(skin, head, detail, scale, glowArgb, level, mainHand, offHand, hat);
    }

    /** The colours it is drawn in. */
    public @NotNull RagdollSkin skin() {
        return skin;
    }

    /** The head item, or {@code null} for a blank head. */
    public @Nullable ItemStack head() {
        return head;
    }

    /** How many cells each part is cut into on each axis. */
    public int detailCells() {
        return detail;
    }

    /** How big it is, where 1 is player-sized. */
    public double scaleFactor() {
        return scale;
    }

    /** The outline colour as {@code 0xRRGGBB}, or a negative number for none. */
    public int glowArgb() {
        return glowArgb;
    }

    /** The light level override, or a negative number to use the world's. */
    public int brightness() {
        return brightness;
    }

    /** A head item wearing a connected player's face. */
    private static @Nullable ItemStack head(Player player) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        if (!(item.getItemMeta() instanceof SkullMeta meta)) {
            return null;
        }
        meta.setOwningPlayer(player);
        item.setItemMeta(meta);
        return item;
    }
}
