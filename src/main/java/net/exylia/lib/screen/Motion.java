package net.exylia.lib.screen;

import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * How boxes on a {@link Screen} move: a {@link Transform} (a move and a scale of a box and everything in it), a
 * {@link Transition} (how a change of it is eased), a {@link Tween} (how a box arrives or leaves) and
 * {@link Keyframes} (a looping or one-shot animation).
 *
 * <p>The client glides a display from one transform to the next in a straight line over a number of ticks, with no
 * extra packets on the way: that is all the motion Minecraft draws. An {@link Ease} other than {@link Ease#LINEAR}
 * is drawn as two to four such glides that follow the curve, so it costs that many packets per display. Colours
 * never glide; a transition that names them changes them in steps.
 *
 * <pre>{@code
 * Node.text("{letters}Play").padding(5, 12).background("{primary}")
 *         .hoverTransform(Motion.Transform.translate(0, -2))      // lifts while hovered
 *         .transition(4, Motion.Ease.OUT)                         // eased over 4 ticks
 *         .enter(Motion.Tween.of(Motion.Transform.scale(0.9), 5, Motion.Ease.BACK));
 * }</pre>
 *
 * @since 1.496.0
 */
public final class Motion {

    private Motion() {
    }

    /** A timing curve, from 0 to 1 over the length of a glide. */
    public enum Ease {
        /** Even speed: one packet. */
        LINEAR,
        /** Starts slow, as CSS {@code ease-in}. */
        IN,
        /** Ends slow, as CSS {@code ease-out} (and {@code ease}). */
        OUT,
        /** Slow at both ends, as CSS {@code ease-in-out}. */
        IN_OUT,
        /** Goes a little past the end and settles back: a pop. */
        BACK,
        /** Lands and bounces twice before it settles, as a dropped ball. @since 1.565.0 */
        BOUNCE,
        /** Springs past the end and wobbles back and forth into place. @since 1.565.0 */
        ELASTIC;

        /** Whether the curve turns back on itself, so a glide along it needs more steps to show its shape. */
        public boolean springy() {
            return this == BOUNCE || this == ELASTIC;
        }

        /** Where the curve is at {@code t} (0 to 1). */
        public double at(double t) {
            t = Math.max(0, Math.min(1, t));
            return switch (this) {
                case LINEAR -> t;
                case IN -> t * t * t;
                case OUT -> 1 - Math.pow(1 - t, 3);
                case IN_OUT -> t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
                case BACK -> {
                    double c = 1.70158;
                    yield 1 + (c + 1) * Math.pow(t - 1, 3) + c * Math.pow(t - 1, 2);
                }
                case BOUNCE -> {
                    double n = 7.5625, d = 2.75;
                    if (t < 1 / d) yield n * t * t;
                    if (t < 2 / d) yield n * (t -= 1.5 / d) * t + 0.75;
                    if (t < 2.5 / d) yield n * (t -= 2.25 / d) * t + 0.9375;
                    yield n * (t -= 2.625 / d) * t + 0.984375;
                }
                case ELASTIC -> t == 0 || t == 1 ? t
                        : Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * (2 * Math.PI / 3)) + 1;
            };
        }

        /**
         * {@code linear}, {@code ease-in}, {@code ease-out} or {@code ease}, {@code ease-in-out}, {@code back},
         * {@code bounce}, {@code elastic}; {@code null} when none.
         */
        public static Ease parse(String css) {
            return switch (css.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "linear" -> LINEAR;
                case "ease-in" -> IN;
                case "ease-out", "ease" -> OUT;
                case "ease-in-out" -> IN_OUT;
                case "back", "ease-out-back" -> BACK;
                case "bounce", "ease-out-bounce" -> BOUNCE;
                case "elastic", "ease-out-elastic" -> ELASTIC;
                default -> null;
            };
        }
    }

    /**
     * A move of {@code x}, {@code y} screen pixels (y down) and a scale about the box's centre, applied to the box and
     * everything inside it, as CSS {@code transform: translate(x, y) scale(s)}. {@code squeeze} (since 1.531.0) narrows
     * it across on top of that, as CSS {@code scaleX}: 0 is its edge, which is how a tile or a card flips.
     */
    public record Transform(double x, double y, double scale, double squeeze) {

        /** No move, no scale. */
        public static final Transform NONE = new Transform(0, 0, 1, 1);

        /** A move and a scale, not narrowed. */
        public Transform(double x, double y, double scale) {
            this(x, y, scale, 1);
        }

        public static @NotNull Transform translate(double x, double y) {
            return new Transform(x, y, 1);
        }

        public static @NotNull Transform scale(double scale) {
            return new Transform(0, 0, scale);
        }

        /** Narrowed across to {@code squeeze} of its width, about its centre. @since 1.531.0 */
        public static @NotNull Transform squeeze(double squeeze) {
            return new Transform(0, 0, 1, squeeze);
        }

        /** Whether it changes nothing. */
        public boolean none() {
            return x == 0 && y == 0 && scale == 1 && squeeze == 1;
        }

        /** This one and {@code other} together, as if written one after the other. */
        public @NotNull Transform with(@NotNull Transform other) {
            return new Transform(x + other.x, y + other.y, scale * other.scale, squeeze * other.squeeze);
        }

        /** Part of the way from this one to {@code to}: 0 is this, 1 is {@code to}. */
        public @NotNull Transform toward(@NotNull Transform to, double f) {
            return new Transform(x + (to.x - x) * f, y + (to.y - y) * f, scale + (to.scale - scale) * f,
                    squeeze + (to.squeeze - squeeze) * f);
        }
    }

    /**
     * How a change of transform is drawn: over {@code ticks}, along {@code ease}; with {@code colours}, colour
     * changes are drawn in a step at every glide rather than at once.
     */
    public record Transition(int ticks, @NotNull Ease ease, boolean colours) {
    }

    /** How a box arrives (from {@code transform} to its place) or leaves (from its place to {@code transform}). */
    public record Tween(@NotNull Transform transform, int ticks, @NotNull Ease ease) {

        /** No motion: the box appears or goes at once. */
        public static final Tween NONE = new Tween(Transform.NONE, 0, Ease.LINEAR);

        public static @NotNull Tween of(@NotNull Transform transform, int ticks, @NotNull Ease ease) {
            return new Tween(transform, ticks, ease);
        }
    }

    /** One step of {@link Keyframes}: where the box is at {@code at} (0 to 1) of the animation. */
    public record Frame(double at, @NotNull Transform transform) {
    }

    /**
     * An animation, as CSS {@code @keyframes} with {@code animation: name <ticks> <ease> [delay] [count|infinite]
     * [alternate]}: the frames in order, from {@code at} 0 to 1, each eased into along {@code ease}.
     *
     * @param delay      ticks it waits before it starts, holding its first frame (since 1.565.0)
     * @param iterations how many times it plays when not {@code infinite}, at least 1 (since 1.565.0)
     * @param alternate  every second play runs backwards, so a loop swings to and fro (since 1.565.0)
     */
    public record Keyframes(@NotNull List<Frame> frames, int ticks, @NotNull Ease ease, boolean infinite, int delay,
                            int iterations, boolean alternate) {

        public Keyframes {
            frames = List.copyOf(frames);
            delay = Math.max(0, delay);
            iterations = Math.max(1, iterations);
        }

        /** Played once, or for ever, with no delay. */
        public Keyframes(@NotNull List<Frame> frames, int ticks, @NotNull Ease ease, boolean infinite) {
            this(frames, ticks, ease, infinite, 0, 1, false);
        }

        /** The same animation started {@code more} ticks later. @since 1.565.0 */
        public @NotNull Keyframes delayed(int more) {
            return more <= 0 ? this : new Keyframes(frames, ticks, ease, infinite, delay + more, iterations, alternate);
        }

        /** Whether it has played to its end {@code tick} ticks after it started; never for an endless one. @since 1.565.0 */
        public boolean finished(long tick) {
            return !infinite && tick >= delay + (long) ticks * iterations;
        }

        /** Where the animation is {@code tick} ticks after it started. */
        public @NotNull Transform at(long tick) {
            if (frames.isEmpty() || ticks <= 0) return Transform.NONE;
            long played = tick - delay;
            if (played < 0) return frames.get(0).transform();
            long cycle = played / ticks;
            double t;
            if (!infinite && cycle >= iterations) {
                // Ended: the last play's end, which for an even count of alternating plays is the start.
                t = alternate && iterations % 2 == 0 ? 0 : 1;
            } else {
                t = (played % ticks) / (double) ticks;
                if (alternate && cycle % 2 == 1) t = 1 - t;
            }
            Frame before = frames.get(0);
            if (t <= before.at()) return before.transform();
            for (Frame next : frames) {
                if (next.at() >= t) {
                    double span = next.at() - before.at();
                    double f = span <= 0 ? 1 : (t - before.at()) / span;
                    return before.transform().toward(next.transform(), ease.at(f));
                }
                before = next;
            }
            return before.transform();
        }
    }
}
