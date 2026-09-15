package net.exylia.lib.region;

/**
 * What the two corners of a selection mean vertically.
 *
 * @since 1.168.0
 */
public enum SelectionHeight {

    /** The box between the two clicked blocks, their heights included. */
    NORMAL,

    /**
     * The whole column of the world over the rectangle the corners span.
     *
     * <p>The clicked heights are still in the result; they simply mean nothing.
     * The preview draws the rectangle at the player's height with a tower up
     * each corner, and the feedback counts columns rather than blocks — so what
     * the player looks at is what they are about to accept.
     */
    FULL
}
