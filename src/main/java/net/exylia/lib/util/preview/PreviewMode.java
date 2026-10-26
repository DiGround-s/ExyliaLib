package net.exylia.lib.util.preview;

/**
 * How a plugin shows a player an effect before they own it.
 *
 * @since 1.355.0
 */
public enum PreviewMode {

    /** The player is moved to the stage, shown the effect once, and put back. */
    TELEPORT,

    /**
     * Nobody is moved: the player is lent the effect for a while and tries it
     * on the mannequins the server placed, as many times as they like.
     */
    MANNEQUIN
}
