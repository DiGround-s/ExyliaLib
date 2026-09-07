package net.exylia.lib.packet;

/**
 * How an invisible player is drawn to a viewer who may see them.
 *
 * @since 1.116.0
 */
public enum RevealStyle {

    /** Drawn whole, as if the invisibility were not there. */
    SOLID,

    /**
     * Still invisible, outlined through the world.
     *
     * <p>The glow flag on an invisible body is what the client draws as a bare
     * outline: less to read than a whole player, and visible through the wall
     * they are standing behind.
     */
    OUTLINE
}
