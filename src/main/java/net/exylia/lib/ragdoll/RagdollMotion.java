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
    private final String sign;
    private final double letters;
    private final double heading;
    private final boolean aimed;
    private final RagdollAnimation animation;
    private final RagdollFinish finish;
    private final double follow;
    private final double holdSize;
    private final double hatSize;
    private final double hatRaise;
    private final double strings;
    private final double chains;
    private final long snipMillis;

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
        this.sign = builder.sign;
        this.letters = builder.letters;
        this.heading = builder.heading;
        this.aimed = builder.aimed;
        this.animation = builder.animation;
        this.finish = builder.finish;
        this.follow = builder.follow;
        this.holdSize = builder.holdSize;
        this.hatSize = builder.hatSize;
        this.hatRaise = builder.hatRaise;
        this.strings = builder.strings;
        this.chains = builder.chains;
        this.snipMillis = builder.snipMillis;
    }

    /** How high puppet strings run from the hands and head, in blocks, or 0 for none. */
    public double strings() {
        return strings;
    }

    /** How far out to each side the wrists are chained to the floor, in blocks, or 0 for none. */
    public double chains() {
        return chains;
    }

    /**
     * When the strings are cut, counting from the moment the sequence started.
     *
     * @return the moment in milliseconds; the last frame when the file does not say
     */
    public long snipMillis() {
        return snipMillis >= 0 ? snipMillis : finishAt();
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

    /** What a {@link RagdollPose#SIGN} body spells. */
    public @NotNull String sign() {
        return sign;
    }

    /** How tall one letter of it is, in blocks. */
    public double letters() {
        return letters;
    }

    /**
     * Which way a body travels, in degrees, or a meaningless number when
     * {@link #aimed()} is false.
     *
     * <p>The same bearing the shapes are written in: zero is east, ninety is
     * south. That is the whole reason it exists &mdash; an effect draws its
     * airlock, its wave or its battering ram at a fixed offset, and a body that
     * leaves in some other direction is a body leaving somebody else's scene.
     */
    public double heading() {
        return heading;
    }

    /** Whether a direction was written down, rather than taken from the kill. */
    public boolean aimed() {
        return aimed;
    }

    /** What a {@link RagdollPose#ANIMATE} body does, frame by frame. */
    public @NotNull RagdollAnimation animation() {
        return animation;
    }

    /** What a {@link RagdollPose#ANIMATE} body does once its last frame is reached. */
    public @NotNull RagdollFinish finish() {
        return finish;
    }

    /**
     * When a choreographed body reaches its last frame, counting from the
     * moment the sequence started.
     *
     * <p>The number the {@code [DELAY]} lines of whatever happens at the end
     * add up to: the flash that is meant to go off as the body bursts goes off
     * as it bursts.
     *
     * @return the moment, in milliseconds
     */
    public long finishAt() {
        return intactMillis + animation.durationMillis();
    }

    /** How much a choreographed body's loose joints lag and overshoot; 0 is none. */
    public double follow() {
        return follow;
    }

    /** How big an item held in a hand is, in blocks for a player-sized body. */
    public double holdSize() {
        return holdSize;
    }

    /** How big an item worn on the head is, in blocks for a player-sized body. */
    public double hatSize() {
        return hatSize;
    }

    /** How far above the middle of the head a worn item sits, in blocks. */
    public double hatRaise() {
        return hatRaise;
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
        private String sign = "EZ";
        private double letters = 2.4;
        private double heading;
        private boolean aimed;
        private RagdollAnimation animation = RagdollAnimation.none();
        private RagdollFinish finish = RagdollFinish.HOLD;
        private double follow;
        private double holdSize = 0.7;
        private double hatSize = 0.6;
        private double hatRaise;
        private double strings;
        private double chains;
        private long snipMillis = -1;

        private Builder() {
        }

        /**
         * Chains from both wrists down to the floor, this far out to each side
         * of the body, in blocks.
         *
         * <p>Pinned where the body's own facing puts its sides and re-measured
         * at every pose, so a struggling arm drags its chain with it. They
         * break at {@link #snip(double)}, like strings.
         *
         * @since 1.139.0
         */
        public @NotNull Builder chains(double spread) {
            this.chains = Math.max(0.0, spread);
            return this;
        }

        /**
         * Puppet strings from both hands and the head straight up to this
         * height above the floor, in blocks.
         *
         * <p>Solved from the hands themselves, so a string shortens when its
         * arm is jerked up and follows the hand wherever the choreography takes
         * it. A string that stays still while the arm under it moves says the
         * arm is moving itself, which is the opposite of a marionette.
         *
         * @since 1.136.0
         */
        public @NotNull Builder strings(double height) {
            this.strings = Math.max(0.0, height);
            return this;
        }

        /**
         * When the strings are cut, in seconds from the start of the sequence.
         * Left unset, they are cut at the last frame.
         *
         * @since 1.136.0
         */
        public @NotNull Builder snip(double seconds) {
            this.snipMillis = Math.max(0L, (long) (seconds * 1000));
            return this;
        }

        /**
         * How much the loose joints of a choreographed body lag and overshoot.
         *
         * <p>Zero is exactly what the frames say. One is a body: arms left
         * behind by a jump and floating at the top of it, a head that nods on
         * landing, limbs flung out by a spin. Two is a cartoon.
         *
         * @since 1.134.0
         */
        public @NotNull Builder follow(double amount) {
            this.follow = Math.clamp(amount, 0.0, 3.0);
            return this;
        }

        /**
         * How big an item held in a hand is, in blocks for a player-sized body.
         *
         * @since 1.134.0
         */
        public @NotNull Builder holdSize(double blocks) {
            this.holdSize = Math.max(0.05, blocks);
            return this;
        }

        /**
         * How big an item worn on the head is. A little over half a block
         * covers a head entirely, which is what a pumpkin or a helmet wants.
         *
         * @since 1.134.0
         */
        public @NotNull Builder hatSize(double blocks) {
            this.hatSize = Math.max(0.05, blocks);
            return this;
        }

        /**
         * How far above the middle of the head a worn item sits, in blocks. A
         * little over a quarter puts it on top rather than around.
         *
         * @since 1.134.0
         */
        public @NotNull Builder hatRaise(double blocks) {
            this.hatRaise = blocks;
            return this;
        }

        /**
         * What a {@link RagdollPose#ANIMATE} body does, frame by frame.
         *
         * <p>It starts once the body has stood for {@code intact}.
         */
        public @NotNull Builder animation(@NotNull RagdollAnimation animation) {
            this.animation = animation;
            return this;
        }

        /** What a {@link RagdollPose#ANIMATE} body does once its last frame is reached. */
        public @NotNull Builder finish(@NotNull RagdollFinish finish) {
            this.finish = finish;
            return this;
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

        /**
         * What a {@link RagdollPose#SIGN} body spells.
         *
         * <p>Letters and the two marks worth having. Anything else in the
         * string is skipped rather than refused: a sign that quietly loses a
         * comma is better than a kill effect that does not play.
         */
        public @NotNull Builder sign(@NotNull String text) {
            this.sign = text;
            return this;
        }

        /** How tall one letter is, in blocks. */
        public @NotNull Builder letters(double blocks) {
            this.letters = Math.max(0.5, blocks);
            return this;
        }

        /**
         * Which way it travels, in degrees: zero is east, ninety is south.
         *
         * <p>Left unset, a body leaves away from whoever killed it, which is
         * right for an effect that draws nothing of its own. Set, it leaves the
         * way the effect's own scenery says it should.
         */
        public @NotNull Builder heading(double degrees) {
            this.heading = degrees;
            this.aimed = true;
            return this;
        }

        /** The motion. */
        public @NotNull RagdollMotion build() {
            return new RagdollMotion(this);
        }
    }
}
