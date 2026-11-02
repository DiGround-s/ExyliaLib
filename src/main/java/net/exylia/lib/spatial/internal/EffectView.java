package net.exylia.lib.spatial.internal;

import java.util.List;

/**
 * How to show an effect on a spatial menu's pedestal, learned from one play of it: the turn that lays its motion
 * across the view (never showing the victim's back) and how much smaller it must play to stay inside its zone.
 * Pure maths over what was watched; the session does the watching.
 */
public final class EffectView {

    private EffectView() {
    }

    /**
     * @param turn  degrees the effect is turned from facing the viewer: within ±90, so the victim shows its front
     *              or its side, never its back
     * @param scale how many times its played size it should play at to stay in its zone (at most 1)
     */
    public record Tuning(double turn, double scale) {
    }

    /** The turns tried, in degrees from facing the viewer. */
    private static final double STEP = 15;
    /** Below this horizontal spread an effect has no direction worth turning for. */
    private static final double STILL = 0.08;
    /** The least an effect is shrunk to fit: below this a body is too small to read. */
    public static final double MIN_SCALE = 0.6;
    /** How many body samples measure an effect by its bodies alone. */
    private static final int MIN_BODY_SAMPLES = 20;

    /**
     * Chooses the view from {@code samples} ({x, y, z, half size} in the world) watched while the effect played
     * at {@code played} degrees from facing the viewer, its base at {@code base}, on a board turned {@code boardYaw}.
     *
     * @param auto  whether to choose the turn; otherwise {@code fixed} is kept
     * @param zone  {width, height} above the base it must fit, 0 for none
     */
    public static Tuning choose(List<double[]> samples, double[] base, float boardYaw, double played, boolean auto,
                                double fixed, double[] zone) {
        if (samples.isEmpty()) return new Tuning(auto ? 0 : fixed, 1);
        // Measured by its bodies when it has them (an effect is about who dies), the rest only when it has none,
        // and with the far tenth left out: a crowd, a flying part or a floating word does not set its size.
        List<double[]> bodies = new java.util.ArrayList<>();
        for (double[] sample : samples) if (sample.length > 4 && sample[4] > 0) bodies.add(sample);
        List<double[]> measured = trimmed(bodies.size() >= MIN_BODY_SAMPLES ? bodies : samples, base);
        double best = auto ? 0 : fixed;
        if (auto) {
            double bestScore = Double.NEGATIVE_INFINITY;
            for (double turn = -90; turn <= 90; turn += STEP) {
                double[] spread = spread(measured, base, boardYaw, played, turn);
                if (Math.sqrt(spread[0] + spread[1]) < STILL) {
                    best = 0;
                    break;
                }
                // Motion across the view, little towards or away from it; the front a little preferred.
                double score = spread[0] - spread[1] - Math.abs(turn) * 1e-4;
                if (score > bestScore) {
                    bestScore = score;
                    best = turn;
                }
            }
        }
        double scale = 1;
        if (zone[1] > 0) {
            // Only its height fits it to the zone; wider than the zone, it spills out sideways rather than shrink.
            List<Double> tops = new java.util.ArrayList<>();
            for (double[] sample : measured) tops.add(place(sample, base, boardYaw, played, best)[1] + sample[3]);
            java.util.Collections.sort(tops);
            double top = tops.get((int) Math.floor((tops.size() - 1) * 0.9));
            if (top > 0) scale = Math.clamp(zone[1] / top, MIN_SCALE, 1);
        }
        return new Tuning(best, scale);
    }

    /** The samples less the tenth that lie furthest from the base: the outliers. */
    private static List<double[]> trimmed(List<double[]> samples, double[] base) {
        if (samples.size() < 10) return samples;
        List<double[]> sorted = new java.util.ArrayList<>(samples);
        sorted.sort(java.util.Comparator.comparingDouble(s -> Math.hypot(Math.hypot(s[0] - base[0], s[2] - base[2]), s[1] - base[1])));
        return sorted.subList(0, (int) Math.ceil(sorted.size() * 0.9));
    }

    /** {variance across the view, variance towards it} of the samples, the effect turned {@code turn}. */
    private static double[] spread(List<double[]> samples, double[] base, float boardYaw, double played, double turn) {
        double sx = 0, sz = 0, sxx = 0, szz = 0;
        for (double[] sample : samples) {
            double[] at = place(sample, base, boardYaw, played, turn);
            sx += at[0];
            sz += at[2];
            sxx += at[0] * at[0];
            szz += at[2] * at[2];
        }
        int n = samples.size();
        return new double[]{sxx / n - (sx / n) * (sx / n), szz / n - (sz / n) * (sz / n)};
    }

    /**
     * Where a sample watched with the effect turned {@code played} would stand with it turned {@code turn}, as
     * {across, up, towards the viewer} from the base on the board's axes.
     */
    public static double[] place(double[] sample, double[] base, float boardYaw, double played, double turn) {
        double dx = sample[0] - base[0];
        double dz = sample[2] - base[2];
        // Into the effect's own frame (yaw it played at), then out again at the new yaw.
        double was = Math.toRadians(boardYaw + 180 + played);
        double now = Math.toRadians(boardYaw + 180 + turn);
        double right = dx * -Math.cos(was) + dz * -Math.sin(was);
        double ahead = dx * -Math.sin(was) + dz * Math.cos(was);
        double x = right * -Math.cos(now) + ahead * -Math.sin(now);
        double z = right * -Math.sin(now) + ahead * Math.cos(now);
        double b = Math.toRadians(boardYaw);
        double across = x * -Math.cos(b) + z * -Math.sin(b);
        double depth = x * -Math.sin(b) + z * Math.cos(b);
        return new double[]{across, sample[1] - base[1], -depth};
    }
}
