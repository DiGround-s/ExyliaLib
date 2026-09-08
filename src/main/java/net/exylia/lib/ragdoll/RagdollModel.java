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
 * <h2>The head is real, the rest is matched</h2>
 * The head is a player head item, so it carries the actual face, hat layer and
 * all. Everything else is drawn in the nearest block to the colour that part of
 * the skin actually is &mdash; a client cannot be handed an arm-shaped model
 * without a resource pack, and a box of the right colour, tumbling, is what the
 * eye reads anyway.
 *
 * <p>Immutable. Cheap to build: a skin is decoded once per texture and shared.
 *
 * @since 1.120.0
 */
public final class RagdollModel {

    /** Cells a part is cut into, at most, on each axis. */
    private static final int MAX_DETAIL = 4;

    private final RagdollSkin skin;
    private final ItemStack head;
    private final int detail;
    private final double scale;
    private final int glowArgb;
    private final int brightness;

    private RagdollModel(RagdollSkin skin, ItemStack head, int detail, double scale,
                         int glowArgb, int brightness) {
        this.skin = skin;
        this.head = head;
        this.detail = detail;
        this.scale = scale;
        this.glowArgb = glowArgb;
        this.brightness = brightness;
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
     * How finely each part is cut, from 1 to 4.
     *
     * <p>One cell is one colour for a whole limb: six boxes for a body, and the
     * cheapest a death can be. Two keeps a sleeve apart from a hand and a shirt
     * apart from a belt, at four times the displays. Beyond three the pieces
     * are smaller than the eye can follow at the speed they are moving, and all
     * that is left is the cost.
     *
     * @param cells cells per axis
     * @return a new model
     */
    public @NotNull RagdollModel detail(int cells) {
        return new RagdollModel(skin, head, Math.clamp(cells, 1, MAX_DETAIL), scale,
                glowArgb, brightness);
    }

    /**
     * How big the body is; 1 is player-sized.
     *
     * @param factor the multiplier
     * @return a new model
     */
    public @NotNull RagdollModel scale(double factor) {
        return new RagdollModel(skin, head, detail, Math.max(0.05, factor), glowArgb, brightness);
    }

    /**
     * An outline around every piece, in {@code 0xRRGGBB}.
     *
     * @param rgb the colour, or a negative number for none
     * @return a new model
     */
    public @NotNull RagdollModel glow(int rgb) {
        return new RagdollModel(skin, head, detail, scale, rgb, brightness);
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
        return new RagdollModel(skin, head, detail, scale, glowArgb, level);
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
