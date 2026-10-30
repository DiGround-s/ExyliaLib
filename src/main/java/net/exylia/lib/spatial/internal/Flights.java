package net.exylia.lib.spatial.internal;

/**
 * The arc a flight preview's projectile draws over the pedestal, in the preview's own plane (across to
 * the viewer's right, up from the pedestal's top), kept apart from Bukkit so it can be tested.
 *
 * <p>It sets off low over the left of the stage, rises a little and comes down into the left face of a
 * small target standing on the pedestal, so the whole flight stays between the side panels and under
 * the focus text at the layout's preview scale, and is seen side on.
 *
 * <p>Internal: free to change without notice.
 */
public final class Flights {

    /** How long the projectile is in the air: slower than a real shot, which is over before the eye catches it. */
    public static final long FLIGHT_MILLIS = 900L;
    /** From one launch to the next, at the least. */
    public static final long CYCLE_MILLIS = 2600L;
    /** The target's edge, at preview scale 1; it stands on the pedestal's top, centred. */
    public static final double TARGET = 0.36;

    private static final double START_ACROSS = -1.4, START_UP = 0.9;
    private static final double LIFT = 0.32;

    private Flights() {
    }

    /** Where the projectile is at {@code progress} (0 to 1) of its flight, {across, up}, at {@code scale}. */
    public static double[] at(double progress, double scale) {
        double t = Math.max(0, Math.min(1, progress));
        double endAcross = -TARGET / 2, endUp = TARGET / 2;
        double across = START_ACROSS + (endAcross - START_ACROSS) * t;
        double up = START_UP + (endUp - START_UP) * t + LIFT * 4 * t * (1 - t);
        return new double[]{across * scale, up * scale};
    }

    /** Which way it flies at {@code progress}, {across, up} per unit of progress, at {@code scale}. */
    public static double[] heading(double progress, double scale) {
        double t = Math.max(0, Math.min(1, progress));
        double across = -TARGET / 2 - START_ACROSS;
        double up = TARGET / 2 - START_UP + LIFT * 4 * (1 - 2 * t);
        return new double[]{across * scale, up * scale};
    }
}
