package net.exylia.lib.util.internal;

import org.jetbrains.annotations.ApiStatus;

import java.util.Locale;

/**
 * How a keyframe is arrived at.
 *
 * <p>Shared, because the words are the same wherever they are written. A file
 * that eases a body's arm with {@code ease=back} and a file that eases a camera
 * push with {@code ease=back} mean the same curve, and two copies of these
 * numbers would drift apart the first time one of them was tuned.
 *
 * <p>{@code in_out} arrives and leaves gently. {@code in} winds up and strikes,
 * {@code out} lands, {@code linear} keeps a spin at one speed. {@code back}
 * overshoots and settles, {@code anticipate} pulls back before it goes,
 * {@code elastic} springs, {@code bounce} lands twice, {@code snap} cuts
 * straight to the pose. {@code smooth} runs a curve through its neighbours
 * instead of stopping at every frame, which is what a body swaying from side to
 * side needs and what nothing else can give it; whoever reads it is responsible
 * for the spline, since this enum only names the intent.
 */
@ApiStatus.Internal
public enum Ease {

    LINEAR(1),
    IN(3),
    OUT(3),
    IN_OUT(3),
    BACK(4.7),
    ANTICIPATE(4.7),
    ELASTIC(7),
    BOUNCE(5.5),
    SNAP(1),
    SMOOTH(1.6);

    /** How much faster than the average it runs at its fastest. */
    private final double peak;

    Ease(double peak) {
        this.peak = peak;
    }

    /**
     * How much faster than the average this curve runs at its fastest.
     *
     * <p>What a caller needs to know whether a frame asks the client to turn
     * something further in one tick than it can take the right way round.
     */
    public double peak() {
        return peak;
    }

    /**
     * The curve of that name, or {@code null} when there is none.
     *
     * @param name as written in a file
     */
    public static Ease of(String name) {
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "linear" -> LINEAR;
            case "in" -> IN;
            case "out" -> OUT;
            case "in_out", "inout", "both" -> IN_OUT;
            case "back", "overshoot" -> BACK;
            case "anticipate", "windup" -> ANTICIPATE;
            case "elastic", "spring" -> ELASTIC;
            case "bounce" -> BOUNCE;
            case "snap", "cut", "step" -> SNAP;
            case "smooth", "spline", "flow" -> SMOOTH;
            default -> null;
        };
    }

    /**
     * Where a frame is, given how far through it the clock is.
     *
     * @param progress 0 at the previous frame, 1 at this one
     * @return the eased progress, which may leave 0..1 where the curve
     *         overshoots
     */
    public double at(double progress) {
        double u = Math.clamp(progress, 0, 1);
        final double c1 = 1.70158;
        final double c3 = c1 + 1;
        return switch (this) {
            case LINEAR, SMOOTH -> u;
            case IN -> u * u * u;
            case OUT -> 1 - Math.pow(1 - u, 3);
            case IN_OUT -> u < 0.5 ? 4 * u * u * u : 1 - Math.pow(-2 * u + 2, 3) / 2;
            case BACK -> 1 + c3 * Math.pow(u - 1, 3) + c1 * Math.pow(u - 1, 2);
            case ANTICIPATE -> c3 * u * u * u - c1 * u * u;
            case ELASTIC -> u == 0 || u == 1 ? u
                    : Math.pow(2, -10 * u) * Math.sin((u * 10 - 0.75) * (2 * Math.PI / 3)) + 1;
            case BOUNCE -> bounce(u);
            case SNAP -> u > 0 ? 1 : 0;
        };
    }

    private static double bounce(double u) {
        final double n1 = 7.5625;
        final double d1 = 2.75;
        if (u < 1 / d1) {
            return n1 * u * u;
        }
        if (u < 2 / d1) {
            double v = u - 1.5 / d1;
            return n1 * v * v + 0.75;
        }
        if (u < 2.5 / d1) {
            double v = u - 2.25 / d1;
            return n1 * v * v + 0.9375;
        }
        double v = u - 2.625 / d1;
        return n1 * v * v + 0.984375;
    }
}
