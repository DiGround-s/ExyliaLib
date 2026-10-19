package net.exylia.lib.region.internal;

import net.exylia.lib.region.BlockPosition;
import net.exylia.lib.region.Cuboid;
import net.exylia.lib.region.Cylinder;
import net.exylia.lib.region.HorizontalBounds;
import net.exylia.lib.region.HorizontalCylinder;
import net.exylia.lib.region.Polygon;
import net.exylia.lib.region.RegionShape;
import net.exylia.lib.region.SelectionHeight;
import net.exylia.lib.region.SelectionMode;
import net.exylia.lib.region.Sphere;
import net.exylia.lib.region.UnboundedYRectangle;
import net.exylia.lib.region.VerticalBounds;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * The geometry of a selection while it is being picked: what it covers, how big it is, and the
 * bars that draw it.
 *
 * <p>Pure arithmetic over the clicked blocks, kept apart from the session so every shape a
 * player can pick is tested without a server.
 *
 * <h2>Blocks, not points</h2>
 * A clicked block is part of what was picked. A box reaches the far faces of both corners, an
 * outline runs through the centres of its blocks — which a polygon of blocks contains, edges
 * included — and a circle or a sphere reaches half a block past the centre of the block clicked
 * on its edge, so that block is inside too.
 */
final class SelectionSketch {

    private SelectionSketch() {
    }

    /**
     * Everything a frame needs to know about a selection.
     *
     * @param mode    what is being picked
     * @param height  what the clicks mean vertically
     * @param first   a box's first corner, or a circle's centre
     * @param second  a box's second corner, or the block on a circle's edge
     * @param outline an outline's points, in order
     * @param drawing whether a freehand pen is down
     */
    record State(@NotNull SelectionMode mode, @NotNull SelectionHeight height,
                 @Nullable BlockPosition first, @Nullable BlockPosition second,
                 @NotNull List<BlockPosition> outline, boolean drawing) {

        /** Whether the next click — or the next look, with the pen down — would change it. */
        boolean wantsCursor() {
            if (mode.isOutline()) {
                return !outline.isEmpty() && (mode == SelectionMode.POLYGON || drawing);
            }
            return (first == null) != (second == null);
        }
    }

    // ------------------------------------------------------------------ shape

    /**
     * What a selection covers right now.
     *
     * <p>One corner is the block it is — or the column — so a player who clicked the wrong
     * block sees that before going to the other end. A centre on its own is a circle or ball
     * of half a block. An outline is a shape from its third point.
     *
     * @return the shape, or {@code null} while there is nothing to cover
     */
    static @Nullable RegionShape shapeOf(@NotNull State state) {
        if (state.mode().isOutline()) {
            return outlineShape(state.height(), state.outline());
        }
        BlockPosition anchor = state.first() != null ? state.first() : state.second();
        if (anchor == null) {
            return null;
        }
        BlockPosition other = state.first() != null && state.second() != null
                && state.first().world().equals(state.second().world())
                ? state.second()
                : anchor;
        return twoPointShape(state.mode(), state.height(), anchor, other);
    }

    /** A box, circle or ball from two clicked blocks in the same world. */
    static @NotNull RegionShape twoPointShape(SelectionMode mode, SelectionHeight height,
                                              BlockPosition anchor, BlockPosition other) {
        boolean full = height == SelectionHeight.FULL;
        int minY = Math.min(anchor.y(), other.y());
        int maxY = Math.max(anchor.y(), other.y()) + 1;
        double centerX = anchor.x() + 0.5;
        double centerZ = anchor.z() + 0.5;
        return switch (mode) {
            case CIRCLE -> {
                double radius = Math.hypot(other.x() - anchor.x(), other.z() - anchor.z()) + 0.5;
                yield full
                        ? new HorizontalCylinder(centerX, centerZ, radius)
                        : new Cylinder(centerX, centerZ, radius, minY, maxY);
            }
            case SPHERE -> new Sphere(centerX, anchor.y() + 0.5, centerZ,
                    Math.sqrt(square(other.x() - anchor.x()) + square(other.y() - anchor.y())
                            + square(other.z() - anchor.z())) + 0.5);
            default -> full
                    ? new UnboundedYRectangle(Math.min(anchor.x(), other.x()), Math.min(anchor.z(), other.z()),
                            Math.max(anchor.x(), other.x()) + 1, Math.max(anchor.z(), other.z()) + 1)
                    : Cuboid.blocks(anchor, other);
        };
    }

    /** A polygon through the centres of its blocks, from its third point. */
    static @Nullable Polygon outlineShape(SelectionHeight height, List<BlockPosition> points) {
        if (points.size() < 3) {
            return null;
        }
        List<Polygon.Vertex> vertices = new ArrayList<>(points.size());
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (BlockPosition point : points) {
            vertices.add(Polygon.Vertex.block(point.x(), point.z()));
            minY = Math.min(minY, point.y());
            maxY = Math.max(maxY, point.y());
        }
        return height == SelectionHeight.FULL
                ? Polygon.columns(vertices)
                : Polygon.prism(vertices, minY, maxY + 1);
    }

    // ------------------------------------------------------------------- size

    /**
     * How many blocks a shape holds, or how many columns when it has no top or bottom.
     *
     * <p>Exactly the blocks the shape contains, counted by their centres. A box is a product;
     * every other shape is counted a column or a row at a time, so the cost follows its area
     * rather than its volume.
     *
     * @param shape what {@link #shapeOf} answered
     * @return the count, {@code 0} for nothing
     */
    static long sizeOf(@Nullable RegionShape shape) {
        return switch (shape) {
            case null -> 0L;
            case Cuboid box -> Math.round((box.maxX() - box.minX()) * (box.maxY() - box.minY())
                    * (box.maxZ() - box.minZ()));
            case UnboundedYRectangle columns -> Math.round((columns.maxX() - columns.minX())
                    * (columns.maxZ() - columns.minZ()));
            case Polygon polygon -> polygon.columnCount() * layers(polygon.vertical());
            case HorizontalCylinder cylinder -> circleColumns(cylinder.centerX(), cylinder.centerZ(),
                    cylinder.radius());
            case Cylinder cylinder -> circleColumns(cylinder.centerX(), cylinder.centerZ(), cylinder.radius())
                    * layers(new VerticalBounds(cylinder.minY(), cylinder.maxY()));
            case Sphere sphere -> sphereBlocks(sphere);
        };
    }

    /** How many whole blocks a shape's heights span; one for a shape without any. */
    private static long layers(@Nullable VerticalBounds vertical) {
        return vertical == null ? 1L : Math.round(vertical.maxY() - vertical.minY());
    }

    private static long circleColumns(double centerX, double centerZ, double radius) {
        double squared = radius * radius;
        long total = 0L;
        for (int row = (int) Math.floor(centerZ - radius); row <= (int) Math.floor(centerZ + radius); row++) {
            double deltaZ = row + 0.5 - centerZ;
            total += run(centerX, radius, column -> {
                double deltaX = column + 0.5 - centerX;
                return deltaX * deltaX + deltaZ * deltaZ <= squared;
            });
        }
        return total;
    }

    private static long sphereBlocks(Sphere sphere) {
        double squared = sphere.radius() * sphere.radius();
        HorizontalBounds bounds = sphere.horizontalBounds();
        long total = 0L;
        for (int x = (int) Math.floor(bounds.minX()); x <= (int) Math.floor(bounds.maxX()); x++) {
            double deltaX = x + 0.5 - sphere.centerX();
            for (int z = (int) Math.floor(bounds.minZ()); z <= (int) Math.floor(bounds.maxZ()); z++) {
                double deltaZ = z + 0.5 - sphere.centerZ();
                total += run(sphere.centerY(), sphere.radius(), y -> {
                    double deltaY = y + 0.5 - sphere.centerY();
                    return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ <= squared;
                });
            }
        }
        return total;
    }

    /**
     * How many blocks in a row around {@code center} pass a test that holds on one unbroken run.
     *
     * <p>Starts from the run's ends worked out with a square root and then walks each end to
     * where the test itself says it is, so the count agrees with the shape's own
     * {@code contains} even where rounding would put the root a hair off.
     */
    private static long run(double center, double radius, IntPredicate inside) {
        int low = (int) Math.ceil(center - radius - 0.5);
        int high = (int) Math.floor(center + radius - 0.5);
        while (inside.test(low - 1)) {
            low--;
        }
        while (low <= high && !inside.test(low)) {
            low++;
        }
        while (inside.test(high + 1)) {
            high++;
        }
        while (high >= low && !inside.test(high)) {
            high--;
        }
        return Math.max(0, high - low + 1);
    }

    private static double square(double value) {
        return value * value;
    }

    // ------------------------------------------------------------------ lines

    /**
     * The bars a frame draws.
     *
     * <p>The shape as it stands in {@link Line.Tone#EDGE}, a marker on every clicked point, and
     * in {@link Line.Tone#GHOST} what the next click would make of it, towards the block the
     * player is looking at.
     *
     * @param state  the selection
     * @param cursor the block being looked at, in the selection's world, or {@code null}
     * @param level  the height a full-height outline is drawn at
     * @return the bars, in a stable order
     */
    static @NotNull List<Line> lines(@NotNull State state, @Nullable BlockPosition cursor, double level) {
        List<Line> lines = new ArrayList<>();
        if (state.mode().isOutline()) {
            outlineLines(state, cursor, level, lines);
        } else {
            twoPointLines(state, cursor, level, lines);
        }
        return lines;
    }

    private static void twoPointLines(State state, @Nullable BlockPosition cursor, double level, List<Line> into) {
        RegionShape shape = shapeOf(state);
        if (shape == null) {
            return;
        }
        boolean box = state.mode() == SelectionMode.CUBOID;
        BlockPosition anchor = state.first() != null ? state.first() : state.second();
        boolean complete = state.first() != null && state.second() != null
                && state.first().world().equals(state.second().world());
        if (complete || box) {
            // A box with one corner is that corner's block: the marker that matters.
            into.addAll(OutlineLines.of(shape, level, Line.Tone.EDGE));
        }
        if (complete) {
            if (!box) {
                marker(state, state.first(), level, into);
                marker(state, state.second(), level, into);
            }
            return;
        }
        if (!box) {
            marker(state, anchor, level, into);
        }
        if (cursor == null || cursor.equals(anchor)) {
            return;
        }
        boolean anchorFirst = state.first() != null;
        RegionShape ghost = box
                ? twoPointShape(state.mode(), state.height(), anchorFirst ? anchor : cursor,
                        anchorFirst ? cursor : anchor)
                : twoPointShape(state.mode(), state.height(), anchor, cursor);
        into.addAll(OutlineLines.of(ghost, level, Line.Tone.GHOST));
        if (!box) {
            // The radius itself, from the centre to where it would reach.
            double y = anchorY(state, anchor, level);
            into.add(Line.between(anchor.x() + 0.5, y, anchor.z() + 0.5,
                    cursor.x() + 0.5, anchorY(state, cursor, level), cursor.z() + 0.5, Line.Tone.GHOST));
        }
    }

    /**
     * An outline: the path so far, the edge that would close it, a marker on every point of a
     * polygon, and a bar from the last point to the cursor.
     *
     * <p>At normal height the path is drawn on the top and the bottom of what it spans, with an
     * edge up each corner; at full height once, at the viewer's height, with a post per corner.
     */
    private static void outlineLines(State state, @Nullable BlockPosition cursor, double level, List<Line> into) {
        List<BlockPosition> points = state.outline();
        if (points.isEmpty()) {
            return;
        }
        boolean full = state.height() == SelectionHeight.FULL;
        List<double[]> corners = new ArrayList<>(points.size());
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (BlockPosition point : points) {
            corners.add(new double[] {point.x() + 0.5, point.z() + 0.5});
            minY = Math.min(minY, point.y());
            maxY = Math.max(maxY, point.y());
        }
        double top = full ? level : maxY + 1.0;
        OutlineLines.path(corners, top, true, Line.Tone.EDGE, Line.Tone.CLOSING, into);
        if (full) {
            OutlineLines.posts(corners, level, Line.Tone.EDGE, into);
        } else {
            OutlineLines.path(corners, minY, true, Line.Tone.EDGE, Line.Tone.CLOSING, into);
            OutlineLines.uprights(corners, minY, top, Line.Tone.EDGE, into);
        }
        if (state.mode() == SelectionMode.POLYGON && points.size() <= OutlineLines.MAX_POSTS) {
            for (double[] corner : corners) {
                into.add(Line.marker(corner[0], top, corner[1], Line.Tone.ANCHOR));
            }
        }
        if (cursor != null && state.wantsCursor()) {
            double[] last = corners.get(corners.size() - 1);
            into.add(Line.between(last[0], top, last[1], cursor.x() + 0.5,
                    full ? level : cursor.y() + 1.0, cursor.z() + 0.5, Line.Tone.GHOST));
        }
    }

    /** A marker on a clicked block. */
    private static void marker(State state, @Nullable BlockPosition point, double level, List<Line> into) {
        if (point != null) {
            into.add(Line.marker(point.x() + 0.5, anchorY(state, point, level), point.z() + 0.5, Line.Tone.ANCHOR));
        }
    }

    /**
     * The height a clicked block is marked at: the viewer's at full height, the middle of a
     * sphere's centre block, and the top of the block otherwise, where a player standing on it
     * can see it.
     */
    private static double anchorY(State state, BlockPosition point, double level) {
        if (state.height() == SelectionHeight.FULL) {
            return level;
        }
        return state.mode() == SelectionMode.SPHERE ? point.y() + 0.5 : point.y() + 1.0;
    }
}
