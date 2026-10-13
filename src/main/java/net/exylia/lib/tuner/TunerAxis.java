package net.exylia.lib.tuner;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.DoubleUnaryOperator;

/**
 * One value a {@link Tuner} moves with the mouse wheel.
 *
 * <pre>{@code
 * TunerAxis.blocks("X", () -> spot.x(), x -> spot = spot.withX(x));
 * TunerAxis.degrees("Yaw", () -> spot.yaw(), yaw -> spot = spot.withYaw(yaw));
 * TunerAxis.of("Scale", () -> scale, value -> scale = value)
 *         .steps(0.05, 0.1, 0.25, 0.5).range(0.1, 10).snap(v -> Math.round(v * 4) / 4.0);
 * TunerAxis.relative("Forward", distance -> moveAlongLook(distance));
 * }</pre>
 *
 * <p>The axis holds no value of its own: it reads and writes through the getter
 * and setter it was given, so whatever the consumer keeps (a working copy, a
 * live entity) stays the one truth. A {@linkplain #relative relative} axis is
 * the exception: it has nothing to read, so it counts how far it moved since the
 * session began and hands each change over as a distance.
 *
 * <p>Every method returns this axis, so it is built in one expression. Axes
 * belong to one session; do not share an instance between two.
 *
 * @since 1.298.0
 */
public final class TunerAxis {

    /** Block distances: from a hair to a whole block. */
    public static final double[] BLOCK_STEPS = {0.01, 0.05, 0.1, 0.5, 1};
    /** Angles: fine, a little, a snap's worth and an eighth of a turn. */
    public static final double[] DEGREE_STEPS = {1, 5, 15, 45};

    private final String label;
    private final DoubleSupplier getter;
    private final DoubleConsumer setter;
    private double[] steps = BLOCK_STEPS;
    private int step = 2;
    private String unit = "";
    private double min = Double.NEGATIVE_INFINITY;
    private double max = Double.POSITIVE_INFINITY;
    private DoubleUnaryOperator snap;

    private TunerAxis(String label, DoubleSupplier getter, DoubleConsumer setter) {
        this.label = Objects.requireNonNull(label, "label");
        this.getter = Objects.requireNonNull(getter, "getter");
        this.setter = Objects.requireNonNull(setter, "setter");
    }

    /**
     * A value read and written through the consumer.
     *
     * @param label  what the HUD calls it, colours allowed
     * @param getter its current value
     * @param setter takes the new value; called on the player's thread
     * @return the axis, with block steps and no range or snap
     */
    public static @NotNull TunerAxis of(@NotNull String label, @NotNull DoubleSupplier getter,
                                        @NotNull DoubleConsumer setter) {
        return new TunerAxis(label, getter, setter);
    }

    /**
     * A coordinate: block steps, snapping to the centre of its block.
     *
     * <p>For a height, where the centre of a block is rarely what is wanted,
     * replace the snap: {@code .snap(y -> Math.round(y * 2) / 2.0)}.
     *
     * @param label  what the HUD calls it
     * @param getter its current value
     * @param setter takes the new value
     * @return the axis
     */
    public static @NotNull TunerAxis blocks(@NotNull String label, @NotNull DoubleSupplier getter,
                                            @NotNull DoubleConsumer setter) {
        return of(label, getter, setter).snap(value -> Math.floor(value) + 0.5);
    }

    /**
     * An angle: degree steps, the {@code °} unit, snapping to the nearest 15
     * degrees and folded into -180..180 so it never grows past a turn.
     *
     * @param label  what the HUD calls it
     * @param getter its current value, in degrees
     * @param setter takes the new value, already folded
     * @return the axis
     */
    public static @NotNull TunerAxis degrees(@NotNull String label, @NotNull DoubleSupplier getter,
                                             @NotNull DoubleConsumer setter) {
        return of(label, getter, value -> setter.accept(fold(value)))
                .steps(DEGREE_STEPS).step(1).unit("°")
                .snap(value -> Math.round(value / 15.0) * 15.0);
    }

    /**
     * A move with no absolute value, such as forward along the player's look.
     *
     * <p>The HUD shows how far it moved since the session began, and each change
     * reaches the consumer as the distance to move by.
     *
     * @param label  what the HUD calls it
     * @param moveBy takes a signed distance; called on the player's thread
     * @return the axis, with block steps
     */
    public static @NotNull TunerAxis relative(@NotNull String label, @NotNull DoubleConsumer moveBy) {
        Objects.requireNonNull(moveBy, "moveBy");
        double[] offset = {0};
        return of(label, () -> offset[0], value -> {
            double delta = value - offset[0];
            offset[0] = value;
            moveBy.accept(delta);
        });
    }

    /**
     * The steps one notch can be, smallest first. Sneak + wheel walks them.
     *
     * @param steps positive values; at least one
     * @return this axis
     */
    public @NotNull TunerAxis steps(double @NotNull ... steps) {
        if (steps.length == 0 || Arrays.stream(steps).anyMatch(step -> !(step > 0))) {
            throw new IllegalArgumentException("Steps must be positive and at least one");
        }
        this.steps = steps.clone();
        this.step = Math.min(step, steps.length - 1);
        return this;
    }

    /**
     * Which step it starts on.
     *
     * @param index into {@link #steps(double...)}, clamped
     * @return this axis
     */
    public @NotNull TunerAxis step(int index) {
        this.step = Math.clamp(index, 0, steps.length - 1);
        return this;
    }

    /**
     * Written after the value and the step, such as {@code °} or {@code x}.
     *
     * @param unit the suffix
     * @return this axis
     */
    public @NotNull TunerAxis unit(@NotNull String unit) {
        this.unit = Objects.requireNonNull(unit, "unit");
        return this;
    }

    /**
     * Keeps the value between two bounds. A bounded axis also fills the HUD bar
     * to show where in the range it is.
     *
     * @param min the lowest value
     * @param max the highest value
     * @return this axis
     */
    public @NotNull TunerAxis range(double min, double max) {
        if (min > max) {
            throw new IllegalArgumentException("min > max");
        }
        this.min = min;
        this.max = max;
        return this;
    }

    /**
     * What the snap gesture turns the value into.
     *
     * @param snap the rounding, or {@code null} for an axis that does not snap
     * @return this axis
     */
    public @NotNull TunerAxis snap(@Nullable DoubleUnaryOperator snap) {
        this.snap = snap;
        return this;
    }

    // ------------------------------------------------------------------
    // Read by the session.
    // ------------------------------------------------------------------

    public @NotNull String label() {
        return label;
    }

    public @NotNull String unit() {
        return unit;
    }

    /** The value now. */
    public double value() {
        return getter.getAsDouble();
    }

    /** The step one notch moves by now. */
    public double currentStep() {
        return steps[step];
    }

    int stepIndex() {
        return step;
    }

    int stepCount() {
        return steps.length;
    }

    boolean snaps() {
        return snap != null;
    }

    /** Where in the range it is, 0 to 1; 1 for an unbounded axis. */
    float fill() {
        if (Double.isInfinite(min) || Double.isInfinite(max) || max == min) {
            return 1f;
        }
        return (float) Math.clamp((value() - min) / (max - min), 0, 1);
    }

    /**
     * Moves by a number of notches.
     *
     * @return whether the value changed; at a bound it does not
     */
    boolean nudge(int notches) {
        return set(value() + notches * currentStep());
    }

    /** Walks the steps; returns whether the step changed. */
    boolean shiftStep(int by) {
        int next = Math.clamp(step + by, 0, steps.length - 1);
        boolean changed = next != step;
        step = next;
        return changed;
    }

    boolean snapNow() {
        return snap != null && set(snap.applyAsDouble(value()));
    }

    private boolean set(double value) {
        double next = Math.clamp(clean(value), min, max);
        if (next == value()) {
            return false;
        }
        setter.accept(next);
        return true;
    }

    /** {@code 0.1 + 0.2} is {@code 0.30000000000000004}; nobody tunes below a ten-thousandth. */
    static double clean(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    static double fold(double degrees) {
        return clean(((degrees % 360) + 540) % 360 - 180);
    }

    /** A number as the HUD writes it: no trailing zeros, no exponent. */
    static @NotNull String format(double value) {
        return BigDecimal.valueOf(clean(value)).setScale(4, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }
}
