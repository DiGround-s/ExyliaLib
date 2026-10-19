package net.exylia.lib.region.internal;

import net.exylia.lib.region.Cuboid;
import net.exylia.lib.region.Cylinder;
import net.exylia.lib.region.HorizontalCylinder;
import net.exylia.lib.region.Polygon;
import net.exylia.lib.region.RegionShape;
import net.exylia.lib.region.Sphere;
import net.exylia.lib.region.UnboundedYRectangle;
import net.exylia.lib.region.VerticalBounds;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a region's geometry into the bars that draw it.
 *
 * <p>The line counterpart of {@link OutlineSampler}: where that one spreads points along every
 * edge, this one answers each edge with a single bar, so a box is twelve of them however big it
 * is. Curves are cut into straight pieces about {@link #RING_STEP} long.
 *
 * <h2>Exact, or at the viewer's height</h2>
 * A shape with a top and a bottom is drawn where it is: every face, at its own height. A shape
 * that runs through the whole column has no top or bottom worth drawing, so its outline is drawn
 * at the {@code level} the caller passes — the viewer's own height — with a short post at each
 * corner to show it goes on up and down.
 */
final class OutlineLines {

    /** About how long each straight piece of a circle is, in blocks. */
    static final double RING_STEP = 2.0;

    /** The fewest pieces a circle is cut into, so a small one is still round. */
    private static final int MIN_RING_PIECES = 16;

    /** The most pieces a circle is cut into, whatever its size. */
    private static final int MAX_RING_PIECES = 720;

    /** How far a corner post reaches below the outline drawn at the viewer's height. */
    static final double POST_BELOW = 1.0;

    /** How far a corner post reaches above it. */
    static final double POST_ABOVE = 2.0;

    /**
     * The most corners that are given a post.
     *
     * <p>A post says "this goes on up and down"; four of them say it as well as forty, and a
     * freehand outline with a post at every point is a fence.
     */
    static final int MAX_POSTS = 24;

    /** A circle's posts: north, east, south and west. */
    private static final int CIRCLE_POSTS = 4;

    private OutlineLines() {
    }

    /**
     * The bars that draw a shape.
     *
     * @param shape the geometry
     * @param level the height a shape with no top or bottom is drawn at
     * @param tone  what the bars mean
     * @return the bars, in a stable order
     */
    static @NotNull List<Line> of(@NotNull RegionShape shape, double level, @NotNull Line.Tone tone) {
        List<Line> lines = new ArrayList<>();
        switch (shape) {
            case Cuboid box -> box(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ(),
                    tone, lines);
            case UnboundedYRectangle rectangle -> {
                List<double[]> corners = List.of(
                        new double[] {rectangle.minX(), rectangle.minZ()},
                        new double[] {rectangle.maxX(), rectangle.minZ()},
                        new double[] {rectangle.maxX(), rectangle.maxZ()},
                        new double[] {rectangle.minX(), rectangle.maxZ()});
                path(corners, level, true, tone, tone, lines);
                posts(corners, level, tone, lines);
            }
            case Sphere sphere -> {
                ring(sphere.centerX(), sphere.centerY(), sphere.centerZ(), sphere.radius(), Plane.HORIZONTAL,
                        tone, lines);
                ring(sphere.centerX(), sphere.centerY(), sphere.centerZ(), sphere.radius(), Plane.XY, tone, lines);
                ring(sphere.centerX(), sphere.centerY(), sphere.centerZ(), sphere.radius(), Plane.YZ, tone, lines);
            }
            case HorizontalCylinder cylinder -> {
                ring(cylinder.centerX(), level, cylinder.centerZ(), cylinder.radius(), Plane.HORIZONTAL,
                        tone, lines);
                posts(compass(cylinder.centerX(), cylinder.centerZ(), cylinder.radius()), level, tone, lines);
            }
            case Cylinder cylinder -> {
                ring(cylinder.centerX(), cylinder.minY(), cylinder.centerZ(), cylinder.radius(),
                        Plane.HORIZONTAL, tone, lines);
                ring(cylinder.centerX(), cylinder.maxY(), cylinder.centerZ(), cylinder.radius(),
                        Plane.HORIZONTAL, tone, lines);
                for (double[] side : compass(cylinder.centerX(), cylinder.centerZ(), cylinder.radius())) {
                    lines.add(Line.between(side[0], cylinder.minY(), side[1], side[0], cylinder.maxY(), side[1],
                            tone));
                }
            }
            case Polygon polygon -> polygon(polygon, level, tone, lines);
        }
        return lines;
    }

    /** The twelve edges of a box. */
    static void box(double x0, double y0, double z0, double x1, double y1, double z1,
                    Line.Tone tone, List<Line> into) {
        for (double y : new double[] {y0, y1}) {
            for (double z : new double[] {z0, z1}) {
                into.add(Line.between(x0, y, z, x1, y, z, tone));
            }
        }
        for (double x : new double[] {x0, x1}) {
            for (double z : new double[] {z0, z1}) {
                into.add(Line.between(x, y0, z, x, y1, z, tone));
            }
        }
        for (double x : new double[] {x0, x1}) {
            for (double y : new double[] {y0, y1}) {
                into.add(Line.between(x, y, z0, x, y, z1, tone));
            }
        }
    }

    /**
     * A polygon: both faces and an edge up every corner when it has heights, the outline and a
     * post at every corner when it has none.
     */
    private static void polygon(Polygon polygon, double level, Line.Tone tone, List<Line> into) {
        List<double[]> corners = new ArrayList<>(polygon.vertices().size());
        for (Polygon.Vertex vertex : polygon.vertices()) {
            corners.add(new double[] {vertex.x(), vertex.z()});
        }
        VerticalBounds vertical = polygon.vertical();
        if (vertical == null) {
            path(corners, level, true, tone, tone, into);
            posts(corners, level, tone, into);
            return;
        }
        path(corners, vertical.minY(), true, tone, tone, into);
        path(corners, vertical.maxY(), true, tone, tone, into);
        uprights(corners, vertical.minY(), vertical.maxY(), tone, into);
    }

    /**
     * Joins points in order at one height.
     *
     * @param closed  whether the last point is joined back to the first
     * @param closing the tone of that last bar
     */
    static void path(List<double[]> points, double y, boolean closed, Line.Tone tone, Line.Tone closing,
                     List<Line> into) {
        for (int index = 1; index < points.size(); index++) {
            double[] from = points.get(index - 1);
            double[] to = points.get(index);
            into.add(Line.between(from[0], y, from[1], to[0], y, to[1], tone));
        }
        if (closed && points.size() > 2) {
            double[] last = points.get(points.size() - 1);
            double[] first = points.get(0);
            into.add(Line.between(last[0], y, last[1], first[0], y, first[1], closing));
        }
    }

    /** An edge up every corner, between two heights, when there are few enough corners. */
    static void uprights(List<double[]> corners, double bottom, double top, Line.Tone tone, List<Line> into) {
        if (corners.size() > MAX_POSTS) {
            return;
        }
        for (double[] corner : corners) {
            into.add(Line.between(corner[0], bottom, corner[1], corner[0], top, corner[1], tone));
        }
    }

    /** A short post at every corner, around the viewer's height. */
    static void posts(List<double[]> corners, double level, Line.Tone tone, List<Line> into) {
        uprights(corners, level - POST_BELOW, level + POST_ABOVE, tone, into);
    }

    /**
     * A circle cut into straight pieces.
     *
     * <p>Pieces about {@link #RING_STEP} long: at that length the gap between a piece and the
     * true curve is under a tenth of a block for any circle bigger than a room, which nobody can
     * see, and a circle a hundred blocks across is still under two hundred bars.
     */
    static void ring(double centerX, double centerY, double centerZ, double radius, Plane plane,
                     Line.Tone tone, List<Line> into) {
        int pieces = (int) Math.clamp((long) Math.ceil(2.0 * Math.PI * radius / RING_STEP),
                MIN_RING_PIECES, MAX_RING_PIECES);
        double[] previous = plane.at(centerX, centerY, centerZ, radius, 0.0);
        for (int index = 1; index <= pieces; index++) {
            double[] next = plane.at(centerX, centerY, centerZ, radius, Math.PI * 2.0 * index / pieces);
            into.add(Line.between(previous[0], previous[1], previous[2], next[0], next[1], next[2], tone));
            previous = next;
        }
    }

    /** The four points of a circle that face the compass, for its posts. */
    private static List<double[]> compass(double centerX, double centerZ, double radius) {
        List<double[]> points = new ArrayList<>(CIRCLE_POSTS);
        points.add(new double[] {centerX, centerZ - radius});
        points.add(new double[] {centerX + radius, centerZ});
        points.add(new double[] {centerX, centerZ + radius});
        points.add(new double[] {centerX - radius, centerZ});
        return points;
    }

    /** The plane a circle is drawn in. */
    enum Plane {

        /** Flat on the ground. */
        HORIZONTAL {
            @Override
            double[] at(double cx, double cy, double cz, double radius, double angle) {
                return new double[] {cx + Math.cos(angle) * radius, cy, cz + Math.sin(angle) * radius};
            }
        },

        /** Upright, facing north and south. */
        XY {
            @Override
            double[] at(double cx, double cy, double cz, double radius, double angle) {
                return new double[] {cx + Math.cos(angle) * radius, cy + Math.sin(angle) * radius, cz};
            }
        },

        /** Upright, facing east and west. */
        YZ {
            @Override
            double[] at(double cx, double cy, double cz, double radius, double angle) {
                return new double[] {cx, cy + Math.cos(angle) * radius, cz + Math.sin(angle) * radius};
            }
        };

        /** The point of the circle at an angle. */
        abstract double[] at(double cx, double cy, double cz, double radius, double angle);
    }
}
