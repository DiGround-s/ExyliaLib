package net.exylia.lib.api.pearls;

/**
 * How much of the player's body has to fit for a spot to count as safe.
 *
 * <p>The multiplier scales the player's height before the bounding box is
 * tested. A taller box is stricter: fewer spots qualify, so a pearl is more
 * often pulled back to an earlier point instead of landing where it hit.
 *
 * @since 1.0.0
 */
public enum CheckMode {

    /** The whole body has to fit. */
    FULL(1.0),

    /** Sneak height. */
    PARTIAL(1.5 / 1.8),

    /** One full block. */
    HALF(1.0 / 1.8),

    /** Swim height, and the default. */
    NORMAL(0.6 / 1.8);

    private final double multiplier;

    CheckMode(double multiplier) {
        this.multiplier = multiplier;
    }

    /**
     * What the player's height is multiplied by before the box is tested.
     *
     * @return the multiplier, between zero and one
     */
    public double multiplier() {
        return multiplier;
    }
}
