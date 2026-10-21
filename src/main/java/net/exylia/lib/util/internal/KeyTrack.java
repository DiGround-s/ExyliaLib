package net.exylia.lib.util.internal;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * One number keyed over time, and read at any moment.
 *
 * <p>Shared by everything that plays keyframes &mdash; a ragdoll's channels and
 * a model's bones &mdash; because how a key is arrived at has to be the same
 * curve wherever it was written. A {@link Ease#SMOOTH} key is a spline through
 * its neighbours, {@link Ease#HOLD} jumps when it is reached, and every other
 * curve eases from the key before.
 *
 * <p>Two keys may share a moment: that is a jump, the first arrived at and the
 * second left from.
 *
 * @param times   when each key is reached, ascending
 * @param values  the value at each key
 * @param eases   how each key is arrived at
 * @param args    each curve's argument, {@code NaN} for the usual
 * @param handles Blockbench's bezier handles, four per key (left time, left value, right time, right value,
 *                times in milliseconds relative to the key), or {@code null}; read only for a segment
 *                whose {@code bezier} flag is set
 * @param bezier  per key, whether the segment arriving at it is a bezier, or {@code null} for none
 */
@ApiStatus.Internal
public record KeyTrack(long[] times, double[] values, Ease[] eases, double[] args,
                       double @Nullable [] handles, boolean @Nullable [] bezier) {

    /** A track with no bezier segments: every one an importer that is not Blockbench's own file builds. */
    public KeyTrack(long[] times, double[] values, Ease[] eases, double[] args) {
        this(times, values, eases, args, null, null);
    }

    /** A track that holds one value forever. */
    public static KeyTrack constant(double value) {
        return new KeyTrack(new long[]{0L}, new double[]{value}, new Ease[]{Ease.LINEAR}, new double[]{Double.NaN});
    }

    /** Its value at one moment; before the first key it is the first, after the last the last. */
    public double value(long millis) {
        int last = times.length - 1;
        if (millis >= times[last]) {
            return values[last];
        }
        if (millis < times[0]) {
            return values[0];
        }
        // The first key after now, so that of several keys sharing a moment the
        // one left from is the last of them.
        int to = 1;
        while (times[to] <= millis) {
            to++;
        }
        int from = to - 1;
        double span = times[to] - times[from];
        double progress = (millis - times[from]) / span;
        if (bezier != null && bezier[to] && handles != null) {
            return bezier(from, to, millis);
        }
        Ease ease = eases[to];
        if (ease == Ease.SMOOTH) {
            double squared = progress * progress;
            double cubed = squared * progress;
            double h00 = 2 * cubed - 3 * squared + 1;
            double h10 = cubed - 2 * squared + progress;
            double h01 = -2 * cubed + 3 * squared;
            double h11 = cubed - squared;
            return h00 * values[from]
                    + h10 * span * slope(from)
                    + h01 * values[to]
                    + h11 * span * slope(to);
        }
        return values[from] + (values[to] - values[from]) * ease.at(progress, args[to]);
    }

    /**
     * A cubic bezier between two keys, as Blockbench draws it: the curve runs
     * from one key through its right handle and the next key's left handle,
     * and is read at the moment asked by solving for the time along it.
     */
    private double bezier(int from, int to, long millis) {
        double t0 = times[from];
        double t1 = times[to];
        double v0 = values[from];
        double v1 = values[to];
        double c0t = t0 + handles[from * 4 + 2];
        double c0v = v0 + handles[from * 4 + 3];
        double c1t = t1 + handles[to * 4];
        double c1v = v1 + handles[to * 4 + 1];
        // Handles may not cross the keys in time, or the curve folds back on itself.
        c0t = Math.clamp(c0t, t0, t1);
        c1t = Math.clamp(c1t, t0, t1);
        double low = 0;
        double high = 1;
        double s = 0.5;
        for (int step = 0; step < 32; step++) {
            s = (low + high) / 2;
            double t = cubic(t0, c0t, c1t, t1, s);
            if (t < millis) {
                low = s;
            } else {
                high = s;
            }
        }
        return cubic(v0, c0v, c1v, v1, s);
    }

    private static double cubic(double a, double b, double c, double d, double s) {
        double r = 1 - s;
        return r * r * r * a + 3 * r * r * s * b + 3 * r * s * s * c + s * s * s * d;
    }

    /**
     * How fast it is moving through a key, per millisecond.
     *
     * <p>Only a key that is flowed through has a speed. One reached with any
     * other ease is arrived at, which is a stop, and a curve that sails past it
     * would never reach the value the file wrote. A linear neighbour lends its
     * own speed, so a spin that eases into a sway does not hitch where the two
     * meet.
     */
    private double slope(int key) {
        if (key <= 0 || key >= times.length - 1) {
            return 0;
        }
        Ease into = eases[key];
        Ease out = eases[key + 1];
        boolean flowsIn = into == Ease.SMOOTH || into == Ease.LINEAR;
        boolean flowsOut = out == Ease.SMOOTH || out == Ease.LINEAR;
        if (!flowsIn || !flowsOut) {
            return 0;
        }
        long before = times[key] - times[key - 1];
        long after = times[key + 1] - times[key];
        if (into == Ease.LINEAR) {
            return before <= 0 ? 0 : (values[key] - values[key - 1]) / before;
        }
        if (out == Ease.LINEAR) {
            return after <= 0 ? 0 : (values[key + 1] - values[key]) / after;
        }
        long across = times[key + 1] - times[key - 1];
        return across <= 0 ? 0 : (values[key + 1] - values[key - 1]) / across;
    }
}
