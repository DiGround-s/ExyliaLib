package net.exylia.lib.ragdoll;

import org.jetbrains.annotations.NotNull;

/**
 * What happens to a body, and how fast.
 *
 * <pre>{@code
 * RagdollMotion thrown = RagdollMotion.builder()
 *         .life(2.4)
 *         .intactFor(0.35)
 *         .speed(3.5).up(7.0).spread(0.45)
 *         .gravity(26).bounce(0.35)
 *         .spin(2.0)
 *         .build();
 *
 * RagdollMotion batted = RagdollMotion.builder()
 *         .pose(RagdollPose.KNOCKED)
 *         .rise(1.3).open(0.6)
 *         .hits(4).every(0.34).force(0.9)
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
 * <p>The path each piece takes is worked out once, when the body goes, and sent
 * as a handful of poses. The client draws every frame in between at its own
 * frame rate: what a player sees is smooth on a server that is not, and there
 * is no teleporting, because nothing is ever moved &mdash; it is told where it
 * will be.
 *
 * <h2>Which numbers a pose reads</h2>
 * <table>
 *   <caption>What each pose is made of</caption>
 *   <tr><th>Pose</th><th>Reads</th></tr>
 *   <tr><td>{@link RagdollPose#BURST}</td>
 *       <td>{@code speed up spread gravity bounce spin settle}</td></tr>
 *   <tr><td>{@link RagdollPose#SPREAD}</td>
 *       <td>{@code rise open lift hang turns} then the throw, gently</td></tr>
 *   <tr><td>{@link RagdollPose#KNOCKED}</td>
 *       <td>the same, plus {@code hits every force}</td></tr>
 *   <tr><td>{@link RagdollPose#VORTEX}</td>
 *       <td>{@code rise open turns}</td></tr>
 * </table>
 *
 * <p>Immutable. Built when configuration is read and shared by every death.
 *
 * @since 1.120.0
 */
public final class RagdollMotion {

    private final RagdollPose pose;
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
    private final double rise;
    private final double open;
    private final long liftMillis;
    private final long hangMillis;
    private final double turns;
    private final int hits;
    private final long everyMillis;
    private final double force;
    private final double swell;
    private final double squash;

    private RagdollMotion(Builder builder) {
        this.pose = builder.pose;
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
        this.rise = builder.rise;
        this.open = builder.open;
        this.liftMillis = builder.liftMillis;
        this.hangMillis = builder.hangMillis;
        this.turns = builder.turns;
        this.hits = builder.hits;
        this.everyMillis = builder.everyMillis;
        this.force = builder.force;
        this.swell = builder.swell;
        this.squash = builder.squash;
    }

    /** A body thrown apart on the Exylia defaults: a beat, a throw, a fall and a rest. */
    public static @NotNull RagdollMotion standard() {
        return builder().build();
    }

    /** A builder for the numbers a file writes. */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /** What happens to the body. */
    public @NotNull RagdollPose pose() {
        return pose;
    }

    /** How long the pieces last, in milliseconds. */
    public long lifeMillis() {
        return lifeMillis;
    }

    /** How long the body stands whole before anything happens, in milliseconds. */
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

    /** How far off the ground a held body hangs, in blocks. */
    public double rise() {
        return rise;
    }

    /** How far the arms and legs are opened out, in blocks. */
    public double open() {
        return open;
    }

    /** How long the body takes to be lifted and opened, in milliseconds. */
    public long liftMillis() {
        return liftMillis;
    }

    /** How long it hangs there once it is open, in milliseconds. */
    public long hangMillis() {
        return hangMillis;
    }

    /** Turns the whole body makes while it hangs, or spirals through. */
    public double turns() {
        return turns;
    }

    /** How many times a hanging body is struck. */
    public int hits() {
        return hits;
    }

    /** How long between one blow and the next, in milliseconds. */
    public long everyMillis() {
        return everyMillis;
    }

    /** How far a blow shoves the body, in blocks. */
    public double force() {
        return force;
    }

    /**
     * When one blow lands, counting from the moment the sequence started.
     *
     * <p>Read by whoever is writing the thing that does the hitting: a snowball
     * that arrives at the same millisecond as the shove is a snowball that hit
     * the body, and one that arrives near it is two effects happening at once.
     *
     * @param index which blow, from zero
     * @return the moment it lands, in milliseconds
     */
    public long hitAt(int index) {
        return intactMillis + liftMillis + (long) index * everyMillis;
    }

    /** How many times its own size a swelling head reaches. */
    public double swell() {
        return swell;
    }

    /** What is left of a flattened piece's height, as a fraction. */
    public double squash() {
        return squash;
    }

    /** Describes what happens to a body in the terms configuration is written in. */
    public static final class Builder {

        private RagdollPose pose = RagdollPose.BURST;
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
        private double rise = 1.1;
        private double open = 0.55;
        private long liftMillis = 450L;
        private long hangMillis = 900L;
        private double turns = 0.35;
        private int hits = 3;
        private long everyMillis = 320L;
        private double force = 0.85;
        private double swell = 3.0;
        private double squash = 0.14;

        private Builder() {
        }

        /** What happens to the body. */
        public @NotNull Builder pose(@NotNull RagdollPose pose) {
            this.pose = pose;
            return this;
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

        /**
         * How far off the ground a held body hangs, in blocks.
         *
         * <p>Read by every pose but {@link RagdollPose#BURST}. A body lifted
         * about a block clears the floor and still reads as a person; much more
         * than two and whoever killed them has to look up to see it.
         */
        public @NotNull Builder rise(double blocks) {
            this.rise = blocks;
            return this;
        }

        /**
         * How far the arms and legs are opened out, in blocks.
         *
         * <p>Zero leaves the body in its own shape, hanging. Half a block is
         * the pose the effect is named for.
         */
        public @NotNull Builder open(double blocks) {
            this.open = Math.max(0.0, blocks);
            return this;
        }

        /** How long the lift takes, in seconds. */
        public @NotNull Builder lift(double seconds) {
            this.liftMillis = Math.max(50L, (long) (seconds * 1000));
            return this;
        }

        /** How long the body hangs there once it is open, in seconds. */
        public @NotNull Builder hang(double seconds) {
            this.hangMillis = Math.max(0L, (long) (seconds * 1000));
            return this;
        }

        /** Turns the whole body makes while it hangs, or spirals through. */
        public @NotNull Builder turns(double turns) {
            this.turns = turns;
            return this;
        }

        /** How many times a hanging body is struck. */
        public @NotNull Builder hits(int count) {
            this.hits = Math.clamp(count, 0, 12);
            return this;
        }

        /** How long between one blow and the next, in seconds. */
        public @NotNull Builder every(double seconds) {
            this.everyMillis = Math.max(60L, (long) (seconds * 1000));
            return this;
        }

        /** How far a blow shoves the body, in blocks. */
        public @NotNull Builder force(double blocks) {
            this.force = Math.max(0.0, blocks);
            return this;
        }

        /**
         * How many times its own size a swelling head reaches.
         *
         * <p>Two is a caricature. Four is a head that has stopped being a head
         * and become the thing everyone in the arena is looking at, which is
         * the point.
         */
        public @NotNull Builder swell(double times) {
            this.swell = Math.max(1.0, times);
            return this;
        }

        /**
         * What is left of a flattened piece's height, as a fraction of it.
         *
         * <p>Not zero: a piece with no height at all disappears into the floor
         * and takes its colour with it. A seventh of it still reads as a body
         * from above.
         */
        public @NotNull Builder squash(double fraction) {
            this.squash = Math.clamp(fraction, 0.01, 1.0);
            return this;
        }

        /** The motion. */
        public @NotNull RagdollMotion build() {
            return new RagdollMotion(this);
        }
    }
}
