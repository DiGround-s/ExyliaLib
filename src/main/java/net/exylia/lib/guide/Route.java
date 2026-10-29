package net.exylia.lib.guide;

import org.bukkit.Location;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * A path through the world as a polyline, and the questions a trail asks of it.
 *
 * <pre>{@code
 * Route route = Route.of(recordedPoints).simplified(0.4);
 * Route.Nearest here = route.closest(player.getX(), player.getY(), player.getZ());
 * for (int k : route.stations(here.arc(), 18, 1.5)) {        // the next 18 blocks, a marker every 1.5
 *     Route.Station mark = route.at(k * 1.5);                // where it goes, and which way it points
 * }
 * }</pre>
 *
 * <p>Pure geometry: no world, no thread, nothing to load. A trail is drawn from
 * these answers, and a plugin can ask the same questions for its own reasons
 * (how far along a player is, how far off the path, whether a recorded route
 * needs thinning before it is saved).
 *
 * <p>Distances are measured along the line ("arc length"), from its first
 * point. Immutable and safe to share.
 *
 * @since 1.391.0
 */
public final class Route {

    private final double[] xs;
    private final double[] ys;
    private final double[] zs;
    /** Arc length at each point; {@code arcs[0] == 0}. */
    private final double[] arcs;

    private Route(List<double[]> points) {
        int size = points.size();
        xs = new double[size];
        ys = new double[size];
        zs = new double[size];
        arcs = new double[size];
        for (int index = 0; index < size; index++) {
            double[] point = points.get(index);
            xs[index] = point[0];
            ys[index] = point[1];
            zs[index] = point[2];
            if (index > 0) {
                arcs[index] = arcs[index - 1] + distance(index - 1, point[0], point[1], point[2]);
            }
        }
    }

    /**
     * A route through points, in walking order.
     *
     * <p>Consecutive duplicates are dropped: a recorder that stood still for a
     * second wrote the same point twenty times, and a zero-length segment has
     * no direction to point a marker along.
     *
     * @param points at least one point; the world is not kept
     * @return the route
     * @throws IllegalArgumentException when {@code points} is empty
     */
    public static @NotNull Route of(@NotNull List<? extends Vector> points) {
        List<double[]> raw = new ArrayList<>(points.size());
        for (Vector point : points) {
            raw.add(new double[]{point.getX(), point.getY(), point.getZ()});
        }
        return ofArrays(raw);
    }

    /**
     * A route through locations, in walking order; the worlds are not checked.
     *
     * @param points at least one location
     * @return the route
     */
    public static @NotNull Route ofLocations(@NotNull List<Location> points) {
        List<double[]> raw = new ArrayList<>(points.size());
        for (Location point : points) {
            raw.add(new double[]{point.getX(), point.getY(), point.getZ()});
        }
        return ofArrays(raw);
    }

    private static Route ofArrays(List<double[]> raw) {
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("a route needs at least one point");
        }
        List<double[]> kept = new ArrayList<>(raw.size());
        for (double[] point : raw) {
            if (kept.isEmpty() || !same(kept.get(kept.size() - 1), point)) {
                kept.add(point);
            }
        }
        return new Route(kept);
    }

    private static boolean same(double[] a, double[] b) {
        return Math.abs(a[0] - b[0]) < 1e-9 && Math.abs(a[1] - b[1]) < 1e-9 && Math.abs(a[2] - b[2]) < 1e-9;
    }

    /** How many points it has. */
    public int size() {
        return xs.length;
    }

    /** Its whole length along the line, in blocks. */
    public double length() {
        return arcs[arcs.length - 1];
    }

    /** One of its points, as a fresh vector. */
    public @NotNull Vector point(int index) {
        return new Vector(xs[index], ys[index], zs[index]);
    }

    /** Every point, as fresh vectors, for saving. */
    public @NotNull List<Vector> points() {
        List<Vector> points = new ArrayList<>(xs.length);
        for (int index = 0; index < xs.length; index++) {
            points.add(point(index));
        }
        return points;
    }

    /**
     * The point of the route nearest to a position.
     *
     * @param arc      how far along the route it is
     * @param x        its x
     * @param y        its y
     * @param z        its z
     * @param distance how far the position is from it, in three dimensions
     */
    public record Nearest(double arc, double x, double y, double z, double distance) {
    }

    /**
     * A place on the route and the way the route runs there.
     *
     * @param x   its x
     * @param y   its y
     * @param z   its z
     * @param yaw the heading of the route at that point, in Minecraft degrees
     *            ({@code 0} south, {@code 90} west)
     */
    public record Station(double x, double y, double z, float yaw) {
    }

    /**
     * Where the route comes nearest to a position.
     *
     * <p>Every segment is tried. That is linear in the number of points, which
     * for a walked route of a few hundred points asked a few times a second is
     * nothing; it also never locks onto a stretch the walker has left, which a
     * search starting from the last answer does where a path doubles back.
     * Ties go to the stretch further along, so a route that passes the same
     * spot twice points onwards rather than back.
     *
     * @param x the position's x
     * @param y the position's y
     * @param z the position's z
     * @return the nearest point
     */
    public @NotNull Nearest closest(double x, double y, double z) {
        if (xs.length == 1) {
            return new Nearest(0, xs[0], ys[0], zs[0], distance(0, x, y, z));
        }
        // ponytail: full scan of every segment; index the segments if routes grow past thousands of points
        Nearest best = null;
        for (int index = 0; index + 1 < xs.length; index++) {
            double dx = xs[index + 1] - xs[index];
            double dy = ys[index + 1] - ys[index];
            double dz = zs[index + 1] - zs[index];
            double lengthSquared = dx * dx + dy * dy + dz * dz;
            double t = lengthSquared <= 0 ? 0
                    : ((x - xs[index]) * dx + (y - ys[index]) * dy + (z - zs[index]) * dz) / lengthSquared;
            t = Math.clamp(t, 0.0, 1.0);
            double px = xs[index] + dx * t;
            double py = ys[index] + dy * t;
            double pz = zs[index] + dz * t;
            double gap = Math.sqrt((x - px) * (x - px) + (y - py) * (y - py) + (z - pz) * (z - pz));
            if (best == null || gap <= best.distance() + 1e-9) {
                best = new Nearest(arcs[index] + (arcs[index + 1] - arcs[index]) * t, px, py, pz, gap);
            }
        }
        return best;
    }

    /**
     * The place a given distance along the route.
     *
     * @param arc how far along; clamped to the route
     * @return where it is and which way the route runs there
     */
    public @NotNull Station at(double arc) {
        if (xs.length == 1) {
            return new Station(xs[0], ys[0], zs[0], 0f);
        }
        double along = Math.clamp(arc, 0.0, length());
        int index = 0;
        while (index + 2 < arcs.length && arcs[index + 1] < along) {
            index++;
        }
        double span = arcs[index + 1] - arcs[index];
        double t = span <= 0 ? 0 : (along - arcs[index]) / span;
        double dx = xs[index + 1] - xs[index];
        double dz = zs[index + 1] - zs[index];
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        return new Station(xs[index] + dx * t, ys[index] + (ys[index + 1] - ys[index]) * t,
                zs[index] + dz * t, yaw);
    }

    /**
     * Which markers a trail draws: every multiple of {@code spacing} from
     * {@code from} to {@code from + ahead}, clamped to the route.
     *
     * <p>Markers sit on fixed multiples of the spacing rather than at fixed
     * distances from the walker. That is what makes a trail cheap to keep
     * up: as somebody walks, the markers already drawn stay exactly where they
     * are, one leaves behind them and one appears at the far end per
     * {@code spacing} walked, instead of every marker moving every step.
     *
     * @param from    where the window starts, usually the walker's nearest point
     * @param ahead   how far ahead it reaches, in blocks
     * @param spacing the distance between markers, in blocks; at least 0.25
     * @return the station indexes in order: marker {@code k} sits at
     *         {@code k * spacing}
     */
    public int @NotNull [] stations(double from, double ahead, double spacing) {
        double step = Math.max(0.25, spacing);
        double start = Math.clamp(from, 0.0, length());
        double end = Math.clamp(from + Math.max(0, ahead), 0.0, length());
        int first = (int) Math.ceil(start / step - 1e-9);
        int last = (int) Math.floor(end / step + 1e-9);
        if (last < first) {
            return new int[0];
        }
        int[] stations = new int[last - first + 1];
        for (int index = 0; index < stations.length; index++) {
            stations[index] = first + index;
        }
        return stations;
    }

    /**
     * Whether marker {@code station} would stand almost on top of the one
     * before it: less than half a spacing away across the ground.
     *
     * <p>Where a route climbs steeply (a straight line up a wall, a recorded
     * ladder) markers a spacing apart along the route are nearly the same
     * spot on the ground, and each would be snapped to its own height: a
     * column of footprints. A trail skips the stacked ones, so the climb
     * shows one footprint at the foot and the next at the top, and a stair
     * one per step.
     *
     * @param station the marker's index, as {@link #stations} gives it
     * @param spacing the spacing the indexes were made with
     * @return whether to leave it out
     * @since 1.396.0
     */
    public boolean stacked(int station, double spacing) {
        if (station <= 0) {
            return false;
        }
        double step = Math.max(0.25, spacing);
        Station here = at(station * step);
        Station before = at((station - 1) * step);
        double dx = here.x() - before.x();
        double dz = here.z() - before.z();
        return dx * dx + dz * dz < (step / 2) * (step / 2);
    }

    /**
     * A thinner copy: points that add nothing to its shape are dropped
     * (Ramer&ndash;Douglas&ndash;Peucker).
     *
     * <p>For a recorded route before it is saved. A walker who went in a
     * straight line for forty blocks recorded thirty points on it; one segment
     * draws the same trail.
     *
     * @param tolerance how far, in blocks, a dropped point may lie from the
     *                  thinned line
     * @return the thinned route; its ends are always kept
     */
    public @NotNull Route simplified(double tolerance) {
        if (xs.length < 3) {
            return this;
        }
        boolean[] keep = new boolean[xs.length];
        keep[0] = true;
        keep[xs.length - 1] = true;
        thin(0, xs.length - 1, Math.max(0, tolerance), keep);
        List<double[]> kept = new ArrayList<>();
        for (int index = 0; index < xs.length; index++) {
            if (keep[index]) {
                kept.add(new double[]{xs[index], ys[index], zs[index]});
            }
        }
        return new Route(kept);
    }

    private void thin(int first, int last, double tolerance, boolean[] keep) {
        // An explicit stack: a route of thousands of points must not recurse that deep.
        int[] stack = new int[2 * xs.length];
        int top = 0;
        stack[top++] = first;
        stack[top++] = last;
        while (top > 0) {
            int to = stack[--top];
            int from = stack[--top];
            double worst = -1;
            int at = -1;
            for (int index = from + 1; index < to; index++) {
                double gap = gapToSegment(index, from, to);
                if (gap > worst) {
                    worst = gap;
                    at = index;
                }
            }
            if (at >= 0 && worst > tolerance) {
                keep[at] = true;
                stack[top++] = from;
                stack[top++] = at;
                stack[top++] = at;
                stack[top++] = to;
            }
        }
    }

    private double gapToSegment(int point, int from, int to) {
        double dx = xs[to] - xs[from];
        double dy = ys[to] - ys[from];
        double dz = zs[to] - zs[from];
        double lengthSquared = dx * dx + dy * dy + dz * dz;
        double t = lengthSquared <= 0 ? 0 : Math.clamp(((xs[point] - xs[from]) * dx
                + (ys[point] - ys[from]) * dy + (zs[point] - zs[from]) * dz) / lengthSquared, 0.0, 1.0);
        double px = xs[from] + dx * t - xs[point];
        double py = ys[from] + dy * t - ys[point];
        double pz = zs[from] + dz * t - zs[point];
        return Math.sqrt(px * px + py * py + pz * pz);
    }

    private double distance(int index, double x, double y, double z) {
        double dx = x - xs[index];
        double dy = y - ys[index];
        double dz = z - zs[index];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * What the ground looks like, asked one block at a time.
     *
     * <p>Handed in rather than read here so the geometry stays pure: the
     * runtime answers it from the world on the thread that owns the block, a
     * test answers it from a map.
     */
    @FunctionalInterface
    public interface Ground {

        /**
         * The height of the top of whatever stands in a block, or
         * {@link Double#NaN} when nothing there can be stood on (air, flowers,
         * an unloaded chunk).
         *
         * @param x block x
         * @param y block y
         * @param z block z
         * @return the absolute y of its top surface, or {@code NaN}
         */
        double top(int x, int y, int z);
    }

    /**
     * The height a marker at {@code (x, y, z)} should sit at to lie on the
     * ground.
     *
     * <p>Searched from one block above {@code y} down to four below for the
     * first block that can be stood on with room above it. One above, not
     * more, so a tunnel's ceiling is never mistaken for its floor; four below,
     * so a point interpolated across a dip still lands. A recorded route is
     * already at foot height, so this only ever corrects a block or two.
     *
     * @param x      where the marker goes
     * @param y      the height the route says
     * @param z      where the marker goes
     * @param ground the world's answer, one block at a time
     * @return the surface height, or {@code y} unchanged when nothing nearby
     *         can be stood on
     */
    public static double snap(double x, double y, double z, @NotNull Ground ground) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int feet = (int) Math.floor(y);
        double above = ground.top(bx, feet + 2, bz);
        for (int by = feet + 1; by >= feet - 4; by--) {
            double top = ground.top(bx, by, bz);
            if (!Double.isNaN(top) && Double.isNaN(above)) {
                return top;
            }
            above = top;
        }
        return y;
    }
}
