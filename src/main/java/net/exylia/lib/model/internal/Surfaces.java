package net.exylia.lib.model.internal;

import org.jetbrains.annotations.ApiStatus;

/**
 * The ground under a model's feet when its feet are not pointing down: a
 * climber standing up a wall has a wall for ground.
 *
 * <p>{@link Legs} works in a frame where up is up. {@link #frame} gives it
 * one: it is asked to pose at no yaw at the origin, and every point it asks
 * about is turned back into the world by the model's own facing, and the
 * surface is looked for along the model's up rather than the world's.
 *
 * <p>Pure: the world is asked through {@link Probe}.
 */
@ApiStatus.Internal
final class Surfaces {

    /** How far along a direction the first solid surface is, or {@code NaN} within none. */
    @FunctionalInterface
    interface Probe {
        /**
         * @param x   where to start, east
         * @param y   up
         * @param z   south
         * @param dx  which way, a unit vector
         * @param dy  which way
         * @param dz  which way
         * @param max how far to look, blocks
         * @return how far the surface is, blocks
         */
        double distance(double x, double y, double z, double dx, double dy, double dz, double max);
    }

    private Surfaces() {
    }

    /**
     * Ground for legs posed at yaw 0, at the origin, of a model drawn with this
     * facing at this place.
     *
     * @param facing the model's turn, as {@link Poser#facing} gives it
     * @param x      the carrier's feet
     * @param y      the carrier's feet
     * @param z      the carrier's feet
     * @param probe  how far the world's surfaces are
     */
    static Legs.Ground frame(double[] facing, double x, double y, double z, Probe probe) {
        double[] up = Poser.rotate(facing[0], facing[1], facing[2], facing[3], 0, 1, 0);
        return (fx, fz, fy, reach) -> {
            // Legs at yaw 0 put a point of the model at (-x, -z): undo it, then turn it as the model is turned.
            double[] at = Poser.rotate(facing[0], facing[1], facing[2], facing[3], -fx, 0, -fz);
            double sx = x + at[0] + up[0] * reach;
            double sy = y + at[1] + up[1] * reach;
            double sz = z + at[2] + up[2] * reach;
            double found = probe.distance(sx, sy, sz, -up[0], -up[1], -up[2], reach * 2);
            return Double.isNaN(found) ? Double.NaN : fy + reach - found;
        };
    }
}
