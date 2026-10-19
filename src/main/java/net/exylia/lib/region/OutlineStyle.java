package net.exylia.lib.region;

/**
 * What an outline is drawn with.
 *
 * <p>{@link #LINES} is the default since 1.312.0. A server without PacketEvents cannot send the
 * displays it is made of, and draws the same outline with the particle the options name instead,
 * so asking for lines is never asking for nothing.
 *
 * @since 1.312.0
 */
public enum OutlineStyle {

    /**
     * Thin glowing bars, one per edge, sent as display entities only the viewer has.
     *
     * <p>Solid, lit at night and visible through the walls they run behind. They are sent once
     * and moved only when the outline does, so a still outline costs nothing per frame.
     */
    LINES,

    /** A particle every so often along each edge, redrawn every frame. */
    PARTICLES
}
