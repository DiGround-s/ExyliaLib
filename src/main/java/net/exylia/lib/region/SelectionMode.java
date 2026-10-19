package net.exylia.lib.region;

/**
 * What a selection is picking, and therefore what the clicks mean.
 *
 * <p>A plugin offers one or several with {@link SelectionOptions.Builder#modes}; with more than
 * one, a shift + right-click moves to the next and starts the selection over. Every mode works
 * at both {@link SelectionHeight heights} except {@link #SPHERE}, which has a height of its own.
 *
 * <table>
 *   <caption>What each mode picks</caption>
 *   <tr><th>Mode</th><th>Left-click</th><th>Right-click</th><th>Result shape</th></tr>
 *   <tr><td>{@link #CUBOID}</td><td>first corner</td><td>second corner</td>
 *       <td>{@link Cuboid}, or {@link UnboundedYRectangle} at full height</td></tr>
 *   <tr><td>{@link #POLYGON}</td><td>adds a point</td><td>takes the last point back</td>
 *       <td>{@link Polygon}</td></tr>
 *   <tr><td>{@link #FREEHAND}</td><td>puts the pen down, or lifts it</td>
 *       <td>takes the last point back</td><td>{@link Polygon}</td></tr>
 *   <tr><td>{@link #CIRCLE}</td><td>centre</td><td>a block on the edge</td>
 *       <td>{@link Cylinder}, or {@link HorizontalCylinder} at full height</td></tr>
 *   <tr><td>{@link #SPHERE}</td><td>centre</td><td>a block on the surface</td>
 *       <td>{@link Sphere}</td></tr>
 * </table>
 *
 * @since 1.312.0
 */
public enum SelectionMode {

    /** A box between two corners: the selector as it has always been. */
    CUBOID,

    /**
     * An outline of clicked points, joined in order and closed back to the first.
     *
     * <p>Waits for shift + left-click with at least three points, even when the options turn the
     * confirmation off: there is no last click that could mean "done".
     */
    POLYGON,

    /**
     * An outline drawn by looking: with the pen down, the block the player is looking at becomes
     * a point every couple of blocks.
     *
     * <p>Stored as a {@link Polygon}, so it is a polygon with a different way of placing points,
     * not a shape of its own. Straight runs collapse to their ends as they are drawn.
     */
    FREEHAND,

    /**
     * A circle around a centre, through the block clicked on its edge.
     *
     * <p>At normal height the two clicked heights bound it, like a box's corners do.
     */
    CIRCLE,

    /** A ball around a centre, through the block clicked on its surface. */
    SPHERE;

    /** Whether the points of this mode are an outline rather than two clicks. */
    public boolean isOutline() {
        return this == POLYGON || this == FREEHAND;
    }
}
