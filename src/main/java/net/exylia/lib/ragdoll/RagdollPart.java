package net.exylia.lib.ragdoll;

/**
 * One piece of a player, in the proportions the vanilla model is drawn in.
 *
 * <p>Everything here is in skin pixels, because that is the only unit in which
 * the numbers are exact: a player is thirty-two pixels tall, an arm is four
 * wide, and a skin is a picture of those pixels. Blocks come out of it by
 * dividing by sixteen, which is done once, where a display is built.
 *
 * <h2>Where a part sits</h2>
 * The anchor is the ground under the body's feet, so a part's height is its
 * height above where the player was standing. That is what a death gives us and
 * what an effect is drawn around.
 *
 * @since 1.120.0
 */
public enum RagdollPart {

    /**
     * The head, and the one part that always wears the real skin.
     *
     * <p>A player head item wears the whole face, hat layer and all, so the one
     * piece anybody actually recognises is exact from the first death. The
     * other parts wear it too once their cubes have been made, and are drawn
     * in blocks until then.
     */
    HEAD(8, 8, 8, 0, 28, 8, 8, 8, 8),

    /** The chest. Where a shirt, a jacket and whatever is printed on them are. */
    TORSO(8, 12, 4, 0, 18, 20, 20, 8, 12),

    /** The right arm, as the wearer's right. */
    ARM_RIGHT(4, 12, 4, -6, 18, 44, 20, 4, 12),

    /** The left arm. On a legacy 64&times;32 skin this mirrors the right. */
    ARM_LEFT(4, 12, 4, 6, 18, 36, 52, 4, 12),

    /** The right leg. */
    LEG_RIGHT(4, 12, 4, -2, 6, 4, 20, 4, 12),

    /** The left leg. On a legacy 64&times;32 skin this mirrors the right. */
    LEG_LEFT(4, 12, 4, 2, 6, 20, 52, 4, 12);

    /** Pixels to a block. */
    public static final float PIXEL = 1 / 16f;

    private final int width;
    private final int height;
    private final int depth;
    private final int offsetX;
    private final int centreY;
    private final int skinX;
    private final int skinY;
    private final int skinWidth;
    private final int skinHeight;

    RagdollPart(int width, int height, int depth, int offsetX, int centreY,
                int skinX, int skinY, int skinWidth, int skinHeight) {
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.offsetX = offsetX;
        this.centreY = centreY;
        this.skinX = skinX;
        this.skinY = skinY;
        this.skinWidth = skinWidth;
        this.skinHeight = skinHeight;
    }

    /** How wide it is, in blocks. */
    public float blockWidth() {
        return width * PIXEL;
    }

    /** How tall it is, in blocks. */
    public float blockHeight() {
        return height * PIXEL;
    }

    /** How deep it is, in blocks. */
    public float blockDepth() {
        return depth * PIXEL;
    }

    /** How far right of the middle its centre sits, in blocks. */
    public float blockOffsetX() {
        return offsetX * PIXEL;
    }

    /** How far above the feet its centre sits, in blocks. */
    public float blockCentreY() {
        return centreY * PIXEL;
    }

    /**
     * How many cells across this part is cut into at a level of detail.
     *
     * <p>Cells are kept as near to square as the skin's own pixels allow, so a
     * limb four pixels wide is cut into fewer columns than rows. A skin is drawn
     * in horizontal bands &mdash; a cuff, a sleeve, a belt, a shoe &mdash; and a
     * square grid spends its pieces on columns nobody can tell apart while it
     * smears those bands together. Detail 1 is one cell, whatever the part.
     *
     * @param detail the level of detail, from 1 to 4
     * @return the columns
     * @since 1.135.0
     */
    public int columns(int detail) {
        return detail <= 1 ? 1 : Math.max(1, Math.round(width * detail / 8f));
    }

    /**
     * How many cells down this part is cut into at a level of detail.
     *
     * @param detail the level of detail, from 1 to 4
     * @return the rows
     * @since 1.135.0
     */
    public int rows(int detail) {
        return detail <= 1 ? 1 : Math.max(1, Math.round(height * detail / 8f));
    }

    /** Where its front face starts across the skin. */
    public int skinX() {
        return skinX;
    }

    /** Where its front face starts down the skin. */
    public int skinY() {
        return skinY;
    }

    /** How wide its front face is on the skin. */
    public int skinWidth() {
        return skinWidth;
    }

    /** How tall its front face is on the skin. */
    public int skinHeight() {
        return skinHeight;
    }

    /**
     * Where this part is joined to the body, across, in blocks.
     *
     * <p>The one number every pose needs and none of them had. A limb that is
     * opened out is turned about its shoulder; a limb that is moved out to the
     * side instead comes away from the body, and the gap is the first thing
     * anybody notices. Rotating about the joint keeps it attached for free,
     * because that is what a joint is.
     */
    public float jointX() {
        return switch (this) {
            case ARM_RIGHT -> -6 * PIXEL;
            case ARM_LEFT -> 6 * PIXEL;
            case LEG_RIGHT -> -2 * PIXEL;
            case LEG_LEFT -> 2 * PIXEL;
            case HEAD, TORSO -> 0f;
        };
    }

    /** How high that joint sits above the feet, in blocks. */
    public float jointY() {
        return switch (this) {
            // Shoulders and the neck are both the top of the chest.
            case ARM_RIGHT, ARM_LEFT, HEAD -> 24 * PIXEL;
            case LEG_RIGHT, LEG_LEFT -> 12 * PIXEL;
            case TORSO -> 18 * PIXEL;
        };
    }

    /**
     * How far the part's own middle sits from that joint when it hangs
     * straight, in blocks.
     *
     * <p>Negative for everything that hangs down from its joint, which is
     * everything but the head.
     */
    public float fromJoint() {
        return switch (this) {
            case HEAD -> 4 * PIXEL;
            case TORSO -> 0f;
            default -> -6 * PIXEL;
        };
    }

    /**
     * The part whose skin region stands in for this one on a legacy skin.
     *
     * <p>A 64&times;32 skin has no left arm and no left leg: the client mirrors
     * the right ones. Sampling the missing half of such a file reads whatever
     * happens to be there, which is usually nothing.
     */
    public RagdollPart legacy() {
        return switch (this) {
            case ARM_LEFT -> ARM_RIGHT;
            case LEG_LEFT -> LEG_RIGHT;
            default -> this;
        };
    }
}
