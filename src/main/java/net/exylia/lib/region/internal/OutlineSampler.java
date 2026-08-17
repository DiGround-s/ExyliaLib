package net.exylia.lib.region.internal;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.exylia.lib.region.Cuboid;
import net.exylia.lib.region.HorizontalCylinder;
import net.exylia.lib.region.RegionShape;
import net.exylia.lib.region.Sphere;
import net.exylia.lib.region.UnboundedYRectangle;

import java.time.Duration;

/**
 * Turns a region's geometry into the points that draw its outline.
 *
 * <p>Sampling is arithmetic over a shape, so it is cached rather than repeated
 * for every frame of every viewer. The key is the shape value and the spacing,
 * which is what the answer depends on; a shape that is replaced every tick —
 * a shrinking safe zone — simply misses the cache, which is why the cache is
 * bounded by size and by idle time rather than growing with the world.
 *
 * <h2>Why the budget is spent before the points are made</h2>
 * Each edge is given its share of {@link #MAX_POINTS_PER_FRAME} up front and
 * never produces more than that. Generating the whole outline and then thinning
 * it looks equivalent and is not: a claim a hundred thousand blocks wide would
 * generate eight hundred thousand points to keep five hundred, and with a
 * duplicate check over a list that is a hundred billion comparisons. The cost
 * of drawing an outline must depend on the budget, not on how big the region is.
 *
 * <p>Points are produced along each edge without ever revisiting the list, so
 * there is no membership test at all. Corners are emitted once, by their own
 * pass, and the edges then skip both endpoints.
 */
final class OutlineSampler {

    /**
     * How many points one frame may draw.
     *
     * <p>A particle per point per viewer per frame is the real cost, so this is
     * a server-protection limit rather than a taste setting. An outline that
     * needs more is drawn at a coarser spacing, which still shows the shape.
     */
    static final int MAX_POINTS_PER_FRAME = 512;

    /** The eight corners of a cuboid, which are drawn whatever the budget. */
    private static final int CUBOID_CORNERS = 8;

    /** The twelve edges of a cuboid share whatever budget the corners leave. */
    private static final int CUBOID_EDGES = 12;

    /** A sphere is drawn as three great circles. */
    private static final int SPHERE_CIRCLES = 3;

    /** The four corners of a rectangle. */
    private static final int RECTANGLE_CORNERS = 4;

    /** The four edges of a rectangle. */
    private static final int RECTANGLE_EDGES = 4;

    /** The fewest points that still read as a circle rather than a triangle. */
    private static final int MIN_CIRCLE_POINTS = 8;

    private static final Cache<SampleKey, Outline> CACHE = Caffeine.newBuilder()
            .maximumSize(256)
            .expireAfterAccess(Duration.ofMinutes(10))
            .build();

    private OutlineSampler() {
    }

    /**
     * The points that draw a shape's outline, worked out once per shape and
     * spacing.
     *
     * @param shape   the geometry
     * @param spacing how far apart the points should be, in blocks
     * @return the outline
     */
    static Outline sample(RegionShape shape, double spacing) {
        return CACHE.get(new SampleKey(shape, spacing), OutlineSampler::create);
    }

    /** The points a shape would draw, ignoring the cache. For tests. */
    static Outline sampleUncached(RegionShape shape, double spacing) {
        return create(new SampleKey(shape, spacing));
    }

    private static Outline create(SampleKey key) {
        return switch (key.shape()) {
            case Cuboid cuboid -> cuboid(cuboid, key.spacing());
            case UnboundedYRectangle rectangle -> rectangle(rectangle, key.spacing());
            case Sphere sphere -> sphere(sphere, key.spacing());
            // Unbounded in y, so the ring is drawn at the viewer's own height.
            case HorizontalCylinder cylinder -> new Points(circlePoints(cylinder.radius(), key.spacing(),
                    MAX_POINTS_PER_FRAME))
                    .circle(cylinder.centerX(), 0.0, cylinder.centerZ(), cylinder.radius(),
                            Plane.HORIZONTAL)
                    .outline(true);
        };
    }

    /**
     * The twelve edges of a cuboid, with each corner drawn exactly once.
     *
     * <p>Corners come first and edges skip their endpoints, so no point is ever
     * produced twice and nothing has to be checked for duplicates.
     */
    private static Outline cuboid(Cuboid cuboid, double spacing) {
        double x0 = cuboid.minX();
        double x1 = cuboid.maxX();
        double y0 = cuboid.minY();
        double y1 = cuboid.maxY();
        double z0 = cuboid.minZ();
        double z1 = cuboid.maxZ();

        int perEdge = shareOf(MAX_POINTS_PER_FRAME - CUBOID_CORNERS, CUBOID_EDGES);
        Points points = new Points(MAX_POINTS_PER_FRAME);

        for (double x : new double[] {x0, x1}) {
            for (double y : new double[] {y0, y1}) {
                for (double z : new double[] {z0, z1}) {
                    points.add(x, y, z);
                }
            }
        }

        for (double y : new double[] {y0, y1}) {
            for (double z : new double[] {z0, z1}) {
                points.between(x0, y, z, x1, y, z, spacing, perEdge);
            }
        }
        for (double x : new double[] {x0, x1}) {
            for (double z : new double[] {z0, z1}) {
                points.between(x, y0, z, x, y1, z, spacing, perEdge);
            }
        }
        for (double x : new double[] {x0, x1}) {
            for (double y : new double[] {y0, y1}) {
                points.between(x, y, z0, x, y, z1, spacing, perEdge);
            }
        }
        return points.outline(false);
    }

    /** A rectangle with no vertical limit, drawn at the viewer's height. */
    private static Outline rectangle(UnboundedYRectangle rectangle, double spacing) {
        double x0 = rectangle.minX();
        double x1 = rectangle.maxX();
        double z0 = rectangle.minZ();
        double z1 = rectangle.maxZ();

        int perEdge = shareOf(MAX_POINTS_PER_FRAME - RECTANGLE_CORNERS, RECTANGLE_EDGES);
        Points points = new Points(MAX_POINTS_PER_FRAME);
        points.add(x0, 0.0, z0);
        points.add(x1, 0.0, z0);
        points.add(x1, 0.0, z1);
        points.add(x0, 0.0, z1);
        points.between(x0, 0.0, z0, x1, 0.0, z0, spacing, perEdge);
        points.between(x1, 0.0, z0, x1, 0.0, z1, spacing, perEdge);
        points.between(x1, 0.0, z1, x0, 0.0, z1, spacing, perEdge);
        points.between(x0, 0.0, z1, x0, 0.0, z0, spacing, perEdge);
        return points.outline(true);
    }

    /**
     * Three great circles rather than a filled ball.
     *
     * <p>Drawing the surface would be thousands of particles for a shape whose
     * extent three rings already show.
     */
    private static Outline sphere(Sphere sphere, double spacing) {
        int perCircle = circlePoints(sphere.radius(), spacing,
                shareOf(MAX_POINTS_PER_FRAME, SPHERE_CIRCLES));
        return new Points(MAX_POINTS_PER_FRAME)
                .circle(sphere.centerX(), sphere.centerY(), sphere.centerZ(), sphere.radius(),
                        Plane.HORIZONTAL, perCircle)
                .circle(sphere.centerX(), sphere.centerY(), sphere.centerZ(), sphere.radius(),
                        Plane.XY, perCircle)
                .circle(sphere.centerX(), sphere.centerY(), sphere.centerZ(), sphere.radius(),
                        Plane.YZ, perCircle)
                .outline(false);
    }

    /** How many points a circle of this size wants, never more than its budget. */
    private static int circlePoints(double radius, double spacing, int budget) {
        long wanted = (long) Math.ceil((Math.PI * 2.0 * radius) / spacing);
        return (int) Math.max(MIN_CIRCLE_POINTS, Math.min(budget, wanted));
    }

    private static int shareOf(int budget, int parts) {
        return Math.max(1, budget / parts);
    }

    /**
     * A growing list of coordinates, held as a flat array.
     *
     * <p>Flat rather than a list of point objects because this is read once per
     * frame per viewer: three doubles and no object per point.
     */
    private static final class Points {

        private final double[] coordinates;
        private int size;

        private Points(int capacity) {
            this.coordinates = new double[capacity * 3];
        }

        private void add(double x, double y, double z) {
            if (size * 3 + 3 > coordinates.length) {
                return;
            }
            coordinates[size * 3] = x;
            coordinates[size * 3 + 1] = y;
            coordinates[size * 3 + 2] = z;
            size++;
        }

        /**
         * Points along an edge, excluding both ends.
         *
         * <p>The ends are the corners, which were added already. Excluding them
         * is what keeps a corner from being drawn three times, once per edge
         * that meets there.
         */
        private void between(double x0, double y0, double z0,
                             double x1, double y1, double z1,
                             double spacing, int budget) {
            double dx = x1 - x0;
            double dy = y1 - y0;
            double dz = z1 - z0;
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            long wanted = (long) Math.ceil(length / spacing);
            int divisions = (int) Math.max(1, Math.min(budget + 1L, wanted));
            for (int step = 1; step < divisions; step++) {
                double fraction = (double) step / divisions;
                add(x0 + dx * fraction, y0 + dy * fraction, z0 + dz * fraction);
            }
        }

        private Points circle(double centerX, double centerY, double centerZ,
                              double radius, Plane plane) {
            return circle(centerX, centerY, centerZ, radius, plane, size == 0
                    ? coordinates.length / 3
                    : coordinates.length / 3 - size);
        }

        private Points circle(double centerX, double centerY, double centerZ,
                              double radius, Plane plane, int count) {
            for (int index = 0; index < count; index++) {
                double angle = Math.PI * 2.0 * index / count;
                double first = Math.cos(angle) * radius;
                double second = Math.sin(angle) * radius;
                switch (plane) {
                    case HORIZONTAL -> add(centerX + first, centerY, centerZ + second);
                    case XY -> add(centerX + first, centerY + second, centerZ);
                    case YZ -> add(centerX, centerY + first, centerZ + second);
                }
            }
            return this;
        }

        private Outline outline(boolean dynamicY) {
            double[] exact = new double[size * 3];
            System.arraycopy(coordinates, 0, exact, 0, exact.length);
            return new Outline(exact, dynamicY);
        }
    }

    /**
     * The points of an outline.
     *
     * @param coordinates x, y and z of each point, one after another
     * @param dynamicY    whether y is meaningless and should be replaced by the
     *                    viewer's own height, which is the case for the shapes
     *                    that have no vertical limit
     */
    record Outline(double[] coordinates, boolean dynamicY) {
        int size() {
            return coordinates.length / 3;
        }
    }

    private record SampleKey(RegionShape shape, double spacing) {
    }

    private enum Plane {
        HORIZONTAL,
        XY,
        YZ
    }
}
