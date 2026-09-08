package net.exylia.lib.ragdoll;

import org.jetbrains.annotations.NotNull;

/**
 * How a body comes apart.
 *
 * <pre>{@code
 * RagdollBurst burst = RagdollBurst.builder()
 *         .life(2.4)
 *         .intactFor(0.35)
 *         .speed(3.5).up(7.0).spread(0.45)
 *         .gravity(26).bounce(0.35)
 *         .spin(2.0)
 *         .build();
 * }</pre>
 *
 * <h2>Choreography, not physics</h2>
 * Every number here is open, including gravity, because an effect is a shot in
 * a film rather than a simulation. Vanilla gravity is about 32 blocks a second
 * squared; a body thrown up at seven and pulled down at twenty-four hangs long
 * enough to be looked at, which is the whole point of the effect and not
 * something reality was ever going to give us.
 *
 * <p>The path each piece takes is worked out once, when the body bursts, and
 * sent as a handful of poses. The client draws every frame in between at its
 * own frame rate: what a player sees is smooth on a server that is not, and
 * there is no teleporting, because nothing is ever moved &mdash; it is told
 * where it will be.
 *
 * <p>Immutable. Built when configuration is read and shared by every death.
 *
 * @since 1.120.0
 */
public final class RagdollBurst {

    private final long lifeMillis;
    private final long intactMillis;
    private final double speed;
    private final double up;
    private final double spread;
    private final double gravity;
    private final double bounce;
    private final double spin;
    private final boolean fade;
    private final boolean settle;

    private RagdollBurst(Builder builder) {
        this.lifeMillis = builder.lifeMillis;
        this.intactMillis = Math.min(builder.intactMillis, builder.lifeMillis);
        this.speed = builder.speed;
        this.up = builder.up;
        this.spread = builder.spread;
        this.gravity = builder.gravity;
        this.bounce = builder.bounce;
        this.spin = builder.spin;
        this.fade = builder.fade;
        this.settle = builder.settle;
    }

    /** A burst with the Exylia defaults: a beat, a throw, a fall and a rest. */
    public static @NotNull RagdollBurst standard() {
        return builder().build();
    }

    /** A builder for the numbers a file writes. */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /** How long the pieces last, in milliseconds. */
    public long lifeMillis() {
        return lifeMillis;
    }

    /** How long the body stands whole before it bursts, in milliseconds. */
    public long intactMillis() {
        return intactMillis;
    }

    /** How fast the pieces leave, outwards, in blocks a second. */
    public double speed() {
        return speed;
    }

    /** How fast they leave upwards, in blocks a second. */
    public double up() {
        return up;
    }

    /** How much the pieces differ from each other, from 0 to 1. */
    public double spread() {
        return spread;
    }

    /** Downward acceleration, in blocks a second squared. */
    public double gravity() {
        return gravity;
    }

    /** How much of its speed a piece keeps when it hits the ground, from 0 to 1. */
    public double bounce() {
        return bounce;
    }

    /** Turns a second, before each piece's own variation. */
    public double spin() {
        return spin;
    }

    /** Whether the pieces shrink away at the end rather than vanishing. */
    public boolean fade() {
        return fade;
    }

    /** Whether a piece stops turning once it has come to rest. */
    public boolean settle() {
        return settle;
    }

    /** Describes a burst in the terms configuration is written in. */
    public static final class Builder {

        private long lifeMillis = 2200L;
        private long intactMillis = 300L;
        private double speed = 3.2;
        private double up = 6.5;
        private double spread = 0.45;
        private double gravity = 26.0;
        private double bounce = 0.32;
        private double spin = 1.8;
        private boolean fade = true;
        private boolean settle = true;

        private Builder() {
        }

        /** How long the pieces last, in seconds. */
        public @NotNull Builder life(double seconds) {
            this.lifeMillis = Math.max(200L, (long) (seconds * 1000));
            return this;
        }

        /**
         * How long the body stands whole first, in seconds.
         *
         * <p>The beat that makes it read as a death rather than as a firework.
         * A body that is already in pieces when the sword lands was never a
         * body; a third of a second standing there is what tells the eye it
         * was.
         */
        public @NotNull Builder intactFor(double seconds) {
            this.intactMillis = Math.max(0L, (long) (seconds * 1000));
            return this;
        }

        /** How fast the pieces leave, outwards, in blocks a second. */
        public @NotNull Builder speed(double blocksPerSecond) {
            this.speed = blocksPerSecond;
            return this;
        }

        /** How fast they leave upwards, in blocks a second. */
        public @NotNull Builder up(double blocksPerSecond) {
            this.up = blocksPerSecond;
            return this;
        }

        /**
         * How much the pieces differ from each other, from 0 to 1.
         *
         * <p>Zero is six pieces leaving on the same curve, which reads as a
         * pattern and not as an accident. Half is what a body does.
         */
        public @NotNull Builder spread(double amount) {
            this.spread = Math.clamp(amount, 0.0, 1.0);
            return this;
        }

        /** Downward acceleration, in blocks a second squared. */
        public @NotNull Builder gravity(double blocksPerSecondSquared) {
            this.gravity = Math.max(0.0, blocksPerSecondSquared);
            return this;
        }

        /** How much speed a piece keeps when it lands, from 0 to 1. */
        public @NotNull Builder bounce(double restitution) {
            this.bounce = Math.clamp(restitution, 0.0, 0.95);
            return this;
        }

        /** Turns a second, before each piece's own variation. */
        public @NotNull Builder spin(double turnsPerSecond) {
            this.spin = turnsPerSecond;
            return this;
        }

        /** Whether the pieces shrink away at the end. */
        public @NotNull Builder fade(boolean fade) {
            this.fade = fade;
            return this;
        }

        /** Whether a piece stops turning once it has come to rest. */
        public @NotNull Builder settle(boolean settle) {
            this.settle = settle;
            return this;
        }

        /** The burst. */
        public @NotNull RagdollBurst build() {
            return new RagdollBurst(this);
        }
    }
}
