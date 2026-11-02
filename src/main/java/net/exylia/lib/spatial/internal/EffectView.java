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
    /** How far under the pedestal's top a fitted effect may reach. */
    private static final double SINK = 0.12;

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
        double best = auto ? 0 : fixed;
        if (auto) {
            double bestScore = Double.NEGATIVE_INFINITY;
            for (double turn = -90; turn <= 90; turn += STEP) {
                double[] spread = spread(samples, base, boardYaw, played, turn);
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
        if (zone[0] > 0 && zone[1] > 0) {
            double half = 0;
            double top = 0;
            double bottom = 0;
            for (double[] sample : samples) {
                double[] at = place(sample, base, boardYaw, played, best);
                half = Math.max(half, Math.abs(at[0]) + sample[3]);
                top = Math.max(top, at[1] + sample[3]);
                bottom = Math.min(bottom, at[1] - sample[3]);
            }
            if (half > 0) scale = Math.min(scale, zone[0] / (2 * half));
            // Its whole height, what falls under the pedestal's top included, inside the zone's.
            if (top - bottom > 0) scale = Math.min(scale, zone[1] / (top - bottom));
            // Little may fall under the pedestal's top: below it are the button and the words.
            if (bottom < -SINK) scale = Math.min(scale, SINK / -bottom);
            // ponytail: an effect that leaves for the sky (a body launched into orbit) is not shrunk past this; it
            // may cross the words above for a moment. A per-effect preview scale would settle it if it matters.
            scale = Math.max(0.15, scale);
        }
        return new Tuning(best, scale);
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
