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
     * The head, and the only part drawn with the real skin.
     *
     * <p>A player head item wears the whole face, hat layer and all, so the one
     * piece anybody actually recognises is exact rather than approximated.
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
