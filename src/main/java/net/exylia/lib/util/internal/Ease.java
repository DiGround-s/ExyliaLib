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
 *
 * <p>Beside those, the whole family from easings.net: {@code sine_in},
 * {@code quad_out}, {@code cubic_in_out} and so on through {@code quart},
 * {@code quint}, {@code expo}, {@code circ}, {@code back}, {@code elastic} and
 * {@code bounce}. They are the curves Blockbench and Emotecraft write, so an
 * animation made in either is read with the curve its author chose, and they
 * accept the names those tools spell them with ({@code easeInOutSine},
 * {@code EASEINOUTSINE}). {@code hold} keeps the previous value until the
 * frame is reached and then jumps, which is what a stepped keyframe is.
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
    SMOOTH(1.6),
    /** @since 1.262.0 */
    HOLD(1),
    /** @since 1.262.0 */
    SINE_IN, SINE_OUT, SINE_IN_OUT,
    /** @since 1.262.0 */
    QUAD_IN, QUAD_OUT, QUAD_IN_OUT,
    /** @since 1.262.0 */
    CUBIC_IN, CUBIC_OUT, CUBIC_IN_OUT,
    /** @since 1.262.0 */
    QUART_IN, QUART_OUT, QUART_IN_OUT,
    /** @since 1.262.0 */
    QUINT_IN, QUINT_OUT, QUINT_IN_OUT,
    /** @since 1.262.0 */
    EXPO_IN, EXPO_OUT, EXPO_IN_OUT,
    /** @since 1.262.0 */
    CIRC_IN, CIRC_OUT, CIRC_IN_OUT,
    /** @since 1.262.0 */
    BACK_IN, BACK_OUT, BACK_IN_OUT,
    /** @since 1.262.0 */
    ELASTIC_IN, ELASTIC_OUT, ELASTIC_IN_OUT,
    /** @since 1.262.0 */
    BOUNCE_IN, BOUNCE_OUT, BOUNCE_IN_OUT;

    /** The families, by the word that names each one. */
    private static final String[] FAMILIES =
            {"sine", "quad", "cubic", "quart", "quint", "expo", "circ", "back", "elastic", "bounce"};

    /** How much faster than the average it runs at its fastest; measured for the families. */
    private double peak;

    Ease(double peak) {
        this.peak = peak;
    }

    Ease() {
        this.peak = 0;
    }

    static {
        for (Ease ease : values()) {
            if (ease.peak == 0) {
                // Measured rather than written down: thirty curves of hand-worked
                // maxima is thirty chances to be wrong about one of them.
                double steepest = 0;
                int samples = 2000;
                for (int index = 0; index < samples; index++) {
                    double from = ease.at((double) index / samples);
                    double to = ease.at((double) (index + 1) / samples);
                    steepest = Math.max(steepest, Math.abs(to - from) * samples);
                }
                ease.peak = Math.max(1, steepest);
            }
        }
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
     * <p>Case, underscores, hyphens and a leading {@code ease} are ignored, so
     * {@code sine_in_out}, {@code easeInOutSine} and {@code EASEINOUTSINE} are
     * one curve.
     *
     * @param name as written in a file
     */
    public static Ease of(String name) {
        String word = name.trim().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
        if (word.startsWith("ease") && word.length() > 4) {
            word = word.substring(4);
        }
        Ease plain = switch (word) {
            case "linear" -> LINEAR;
            case "in" -> IN;
            case "out" -> OUT;
            case "inout", "both" -> IN_OUT;
            case "back", "overshoot" -> BACK;
            case "anticipate", "windup" -> ANTICIPATE;
            case "elastic", "spring" -> ELASTIC;
            case "bounce" -> BOUNCE;
            case "snap", "cut", "step" -> SNAP;
            case "smooth", "spline", "flow", "catmullrom" -> SMOOTH;
            case "hold", "constant" -> HOLD;
            default -> null;
        };
        if (plain != null) {
            return plain;
        }
        for (int family = 0; family < FAMILIES.length; family++) {
            String named = FAMILIES[family];
            int first = SINE_IN.ordinal() + family * 3;
            if (word.equals("inout" + named) || word.equals(named + "inout")) {
                return values()[first + 2];
            }
            if (word.equals("in" + named) || word.equals(named + "in")) {
                return values()[first];
            }
            if (word.equals("out" + named) || word.equals(named + "out")) {
                return values()[first + 1];
            }
        }
        return null;
    }

    /**
     * Where a frame is, given how far through it the clock is.
     *
     * @param progress 0 at the previous frame, 1 at this one
     * @return the eased progress, which may leave 0..1 where the curve
     *         overshoots
     */
    public double at(double progress) {
        return at(progress, Double.NaN);
    }

    /**
     * The same, with the argument some curves take.
     *
     * <p>How far a {@code back} curve overshoots (1 is the usual), how many
     * times an {@code elastic} one swings, and how high a {@code bounce} comes
     * back (0.5 is the usual). {@code NaN} is the usual for each; the curves
     * that take no argument ignore it. These are the arguments Blockbench and
     * Emotecraft write next to the curve's name.
     *
     * @param progress 0 at the previous frame, 1 at this one
     * @param argument the curve's argument, or {@code NaN}
     * @return the eased progress
     * @since 1.262.0
     */
    public double at(double progress, double argument) {
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
            case HOLD -> u >= 1 ? 1 : 0;
            default -> family(u, argument);
        };
    }

    /** One of the easings.net curves: its rising form, run forwards, backwards or both ways. */
    private double family(double u, double argument) {
        int index = ordinal() - SINE_IN.ordinal();
        int family = index / 3;
        return switch (index % 3) {
            case 0 -> rising(family, u, argument);
            case 1 -> 1 - rising(family, 1 - u, argument);
            default -> u < 0.5
                    ? rising(family, u * 2, argument) / 2
                    : 1 - rising(family, (1 - u) * 2, argument) / 2;
        };
    }

    /**
     * A family's curve as it leaves: slowly, then faster.
     *
     * <p>{@code back}, {@code elastic} and {@code bounce} are the forms
     * Emotecraft plays, arguments included, so a dance made for it lands the
     * same way here.
     */
    private static double rising(int family, double t, double argument) {
        return switch (family) {
            case 0 -> 1 - Math.cos(t * Math.PI / 2);
            case 1 -> t * t;
            case 2 -> t * t * t;
            case 3 -> t * t * t * t;
            case 4 -> t * t * t * t * t;
            case 5 -> t <= 0 ? 0 : Math.pow(2, 10 * (t - 1));
            case 6 -> 1 - Math.sqrt(Math.max(0, 1 - t * t));
            case 7 -> {
                double overshoot = Double.isNaN(argument) ? 1.70158 : argument * 1.70158;
                yield t * t * ((overshoot + 1) * t - overshoot);
            }
            case 8 -> {
                double swings = Double.isNaN(argument) ? 1 : argument;
                yield 1 - Math.pow(Math.cos(t * Math.PI / 2), 3) * Math.cos(t * swings * Math.PI);
            }
            default -> {
                double height = Double.isNaN(argument) ? 0.5 : argument;
                double one = 121.0 / 16 * t * t;
                double two = 121.0 / 4 * height * Math.pow(t - 6.0 / 11, 2) + 1 - height;
                double three = 121 * height * height * Math.pow(t - 9.0 / 11, 2) + 1 - height * height;
                double four = 484 * height * height * height * Math.pow(t - 10.5 / 11, 2)
                        + 1 - height * height * height;
                yield Math.min(Math.min(one, two), Math.min(three, four));
            }
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
