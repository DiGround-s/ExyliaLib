package net.exylia.lib.region;

/**
 * What the clicks of a selection mean vertically.
 *
 * @since 1.168.0
 */
public enum SelectionHeight {

    /**
     * The shape between the clicked heights: every face of it is drawn where it
     * is.
     */
    NORMAL,

    /**
     * The whole column of the world over what the clicks span.
     *
     * <p>The clicked heights are still in the result; they simply mean nothing.
     * The preview draws the outline at the player's own height with a short
     * post at each corner, since the top and bottom of the world are not worth
     * drawing, and the feedback counts columns rather than blocks — so what
     * the player looks at is what they are about to accept.
     */
    FULL
}
