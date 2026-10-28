package net.exylia.lib.guide;

/**
 * Markers on the ground along a route, drawn only for the stretch just ahead
 * of the player.
 *
 * <p>The answers below are read by the trail on the player's own tick and are
 * safe to read from any thread; they are a few ticks old at most.
 *
 * @since 1.391.0
 */
public interface GuideTrail extends GuideHandle {

    /** How far along the route the player is, in blocks from its start. */
    double progress();

    /** How long the route is, in blocks. */
    double length();

    /** How far the player is from the nearest point of the route, in blocks. */
    double distanceFromRoute();

    /**
     * Whether the player has wandered further from the route than the style
     * allows, in which case the trail also draws a short line back to it.
     */
    boolean isOffRoute();
}
