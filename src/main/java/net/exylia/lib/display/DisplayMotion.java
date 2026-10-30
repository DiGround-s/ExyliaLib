package net.exylia.lib.display;

import net.exylia.lib.util.internal.Ease;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * How a display moves, turns and grows over its life.
 *
 * <pre>{@code
 * DisplayMotion thrown = DisplayMotion.builder()
 *         .life(1200)
 *         .from(0, 6, 0).to(0, 0, 0)
 *         .spin(Rotation.Axis.Z, 3)
 *         .scale(1.0, 1.4)
 *         .gravity(18)
 *         .build();
 * }</pre>
 *
 * <h2>Poses, not frames</h2>
 * A motion is a handful of poses with times on them. The client draws the frames
 * between them, so a two-second animation costs about six packets per viewer
 * however smooth it looks. Nothing here runs per tick.
 *
 * <h2>How many poses a spin needs</h2>
 * The client turns a display by the shortest arc between two rotations, so a
 * pose more than half a turn from the last one spins the wrong way. A spin is
 * therefore cut into pieces small enough that the short way round is the way
 * the file meant &mdash; six per turn, which is a sixth of a turn each and well
 * inside the limit. Nobody writing {@code spin:3} should have to know this.
 *
 * <p>Immutable; built when configuration is read and shared by every play.
 *
 * @since 1.85.0
 */
public final class DisplayMotion {

    /** Poses per turn of spin. Six is 60&deg; a pose, comfortably under the 180&deg; limit. */
    private static final int POSES_PER_TURN = 6;

    /** Poses used for a fall, which needs enough to look like a curve and not a corner. */
    private static final int POSES_PER_FALL = 8;

    /**
     * The ceiling on a short motion's poses, so a file asking for forty turns
     * does not send forty packets a viewer.
     */
    private static final int MAX_POSES = 48;

    /** The ceiling on any motion, however long: one packet a tick for twelve seconds. */
    private static final int MAX_POSES_LONG = 256;

    /** One tick, which is as close together as two poses can usefully be sent. */
    private static final long TICK_MS = 50L;

    /**
     * How many poses a motion of that life may have.
     *
     * <p>Forty-eight for anything up to about two seconds, as it always was;
     * beyond that one a tick, up to {@value #MAX_POSES_LONG}. Poses closer than a
     * tick are sent on the same tick anyway, so a longer cap would buy nothing
     * on screen and a shorter one starves a ten-second wheel of the poses its
     * deceleration needs.
     *
     * @param lifeMillis the motion's life
     * @return the most poses it may be cut into
     * @since 1.272.0
     */
    public static int maxPoses(long lifeMillis) {
        return Math.max(MAX_POSES, (int) Math.min(MAX_POSES_LONG, lifeMillis / TICK_MS + 1));
    }

    /**
     * How a movement is spread across its own life.
     *
     * <p>The difference between something moving and something striking.
     * Everything the client does between two poses is a straight line at a
     * constant rate; this is what puts the poses where they need to be for that
     * to add up to a blow.
     *
     * <p>The curves are the library's own, shared with ragdolls and cameras, so
     * {@code back} means the same overshoot wherever it is written.
     */
    public enum Easing {

        /** The same rate from start to finish. */
        LINEAR(Ease.LINEAR, 2),

        /** Slow, then very fast. A wind-up and a strike. */
        IN(Ease.IN, 12),

        /** Fast, then settling. An impact coming to rest. */
        OUT(Ease.OUT, 12),

        /** Slow, fast, slow. A whole gesture in one line. */
        IN_OUT(Ease.IN_OUT, 12),

        /**
         * Past the target and back. A spike bursting out of the ground, a
         * pulse that swells before it settles.
         *
         * @since 1.197.0
         */
        BACK(Ease.BACK, 16),

        /**
         * Lands, hops twice and settles. Something dropped that is solid.
         *
         * @since 1.197.0
         */
        BOUNCE(Ease.BOUNCE, 24),

        /**
         * Springs past the target a few times, each smaller. Something that
         * snapped into place.
         *
         * @since 1.197.0
         */
        ELASTIC(Ease.ELASTIC, 24),

        /**
         * Pulls back before it goes. A card drawn back before it is thrown.
         *
         * @since 1.272.0
         */
        ANTICIPATE(Ease.ANTICIPATE, 16),

        /**
         * Jumps to the end within the first tick and stays there.
         *
         * @since 1.272.0
         */
        SNAP(Ease.SNAP, 2),

        /**
         * Stays where it starts and jumps to the end within the last tick.
         *
         * @since 1.272.0
         */
        HOLD(Ease.HOLD, 2),

        /**
         * A straight rate on one movement; through keyframes, a curve that runs
         * through every one of them rather than stopping at each. See
         * {@link DisplayMotion#spline}.
         *
         * @since 1.272.0
         */
        SMOOTH(Ease.SMOOTH, 2),

        /** @since 1.272.0 */
        SINE_IN(Ease.SINE_IN, 12),
        /** @since 1.272.0 */
        SINE_OUT(Ease.SINE_OUT, 12),
        /** @since 1.272.0 */
        SINE_IN_OUT(Ease.SINE_IN_OUT, 12),
        /** @since 1.272.0 */
        QUAD_IN(Ease.QUAD_IN, 12),
        /** @since 1.272.0 */
        QUAD_OUT(Ease.QUAD_OUT, 12),
        /** @since 1.272.0 */
        QUAD_IN_OUT(Ease.QUAD_IN_OUT, 12),
        /** @since 1.272.0 */
        CUBIC_IN(Ease.CUBIC_IN, 12),
        /** @since 1.272.0 */
        CUBIC_OUT(Ease.CUBIC_OUT, 12),
        /** @since 1.272.0 */
        CUBIC_IN_OUT(Ease.CUBIC_IN_OUT, 12),
        /** @since 1.272.0 */
        QUART_IN(Ease.QUART_IN, 16),
        /** @since 1.272.0 */
        QUART_OUT(Ease.QUART_OUT, 16),
        /** @since 1.272.0 */
        QUART_IN_OUT(Ease.QUART_IN_OUT, 16),
        /** @since 1.272.0 */
        QUINT_IN(Ease.QUINT_IN, 16),
        /** @since 1.272.0 */
        QUINT_OUT(Ease.QUINT_OUT, 16),
        /** @since 1.272.0 */
        QUINT_IN_OUT(Ease.QUINT_IN_OUT, 16),
        /** @since 1.272.0 */
        EXPO_IN(Ease.EXPO_IN, 16),
        /** @since 1.272.0 */
        EXPO_OUT(Ease.EXPO_OUT, 16),
        /** @since 1.272.0 */
        EXPO_IN_OUT(Ease.EXPO_IN_OUT, 16),
        /** @since 1.272.0 */
        CIRC_IN(Ease.CIRC_IN, 16),
        /** @since 1.272.0 */
        CIRC_OUT(Ease.CIRC_OUT, 16),
        /** @since 1.272.0 */
        CIRC_IN_OUT(Ease.CIRC_IN_OUT, 16),
        /** @since 1.272.0 */
        BACK_IN(Ease.BACK_IN, 16),
        /** @since 1.272.0 */
        BACK_OUT(Ease.BACK_OUT, 16),
        /** @since 1.272.0 */
        BACK_IN_OUT(Ease.BACK_IN_OUT, 16),
        /** @since 1.272.0 */
        ELASTIC_IN(Ease.ELASTIC_IN, 24),
        /** @since 1.272.0 */
        ELASTIC_OUT(Ease.ELASTIC_OUT, 24),
        /** @since 1.272.0 */
        ELASTIC_IN_OUT(Ease.ELASTIC_IN_OUT, 24),
        /** @since 1.272.0 */
        BOUNCE_IN(Ease.BOUNCE_IN, 24),
        /** @since 1.272.0 */
        BOUNCE_OUT(Ease.BOUNCE_OUT, 24),
        /** @since 1.272.0 */
        BOUNCE_IN_OUT(Ease.BOUNCE_IN_OUT, 24);

        private final Ease curve;

        /**
         * Poses the curve needs before it reads as itself. A bounce sampled at
         * twelve points is a wobble; it needs about six per hop.
         */
        private final int poses;

        Easing(Ease curve, int poses) {
            this.curve = curve;
            this.poses = poses;
        }

        /**
         * Reads an easing from configuration, defaulting to {@link #LINEAR}.
         *
         * <p>{@code back}/{@code overshoot}, {@code bounce} and
         * {@code elastic}/{@code spring} since 1.197.0. Since 1.272.0 every
         * curve above by name, in any of the spellings Blockbench and the
         * easings.net family use: {@code quart_out}, {@code easeOutQuart},
         * {@code outquart}.
         */
        public static @NotNull Easing of(@NotNull String name) {
            switch (name.trim().toUpperCase(java.util.Locale.ROOT)) {
                case "ACCELERATE" -> {
                    return IN;
                }
                case "DECELERATE" -> {
                    return OUT;
                }
                default -> {
                }
            }
            Ease curve = Ease.of(name);
            return curve == null ? LINEAR : valueOf(curve.name());
        }

        /**
         * Where the movement has got to, at a given fraction of its life.
         *
         * <p>May leave 0..1 on the overshooting curves; that is the overshoot.
         *
         * @param progress 0 at the start, 1 at the end
         * @return the eased progress
         * @since 1.272.0
         */
        public double apply(double progress) {
            return curve.at(progress);
        }

        /**
         * Where the movement has got to, at a given fraction of its life.
         *
         * <p>May leave 0..1 on the overshooting curves; that is the overshoot.
         */
        double at(double progress) {
            return curve.at(progress);
        }

        /**
         * How much faster than the average this curve runs at its fastest.
         *
         * <p>A spin eased this way is cut into that many more poses, so the
         * fastest stretch still turns less than half a turn between two of
         * them.
         */
        double peak() {
            return curve.peak();
        }
    }

    private final List<DisplayKeyframe> poses;
    private final long lifeMillis;
    private final long loopFromMillis;
    private final long cycleMillis;
    private final double accel;
    private final double maxSpeed;
    private final double startSpeed;

    private DisplayMotion(List<DisplayKeyframe> poses, long lifeMillis) {
        this(poses, lifeMillis, 0L, 0L, 1.0, 1.0, 1.0);
    }

    private DisplayMotion(List<DisplayKeyframe> poses, long lifeMillis, long loopFromMillis,
                          long cycleMillis, double accel, double maxSpeed, double startSpeed) {
        this.poses = List.copyOf(poses);
        this.lifeMillis = lifeMillis;
        this.loopFromMillis = loopFromMillis;
        this.cycleMillis = cycleMillis;
        this.accel = accel;
        this.maxSpeed = maxSpeed;
        this.startSpeed = startSpeed;
    }

    /**
     * A display that appears, stays exactly as it is, and goes.
     *
     * @param lifeMillis how long it lasts
     * @return the motion
     */
    public static @NotNull DisplayMotion still(long lifeMillis) {
        return new DisplayMotion(List.of(
                new DisplayKeyframe(0L, 0f, 0f, 0f, Rotation.NONE, 1f, 1f, 1f)), lifeMillis);
    }

    /**
     * A motion built from poses worked out elsewhere.
     *
     * @param poses      the poses, in time order; the first should be at zero
     * @param lifeMillis how long the display lasts
     * @return the motion
     */
    public static @NotNull DisplayMotion of(@NotNull List<DisplayKeyframe> poses, long lifeMillis) {
        return poses.isEmpty() ? still(lifeMillis) : new DisplayMotion(poses, lifeMillis);
    }

    /**
     * Several movements played back to back, as one.
     *
     * <pre>{@code
     * DisplayMotion debris = DisplayMotion.chain(
     *         DisplayMotion.builder().life(150).to(0, 0.6, 0).ease(Easing.OUT).build(),
     *         DisplayMotion.builder().life(300).from(0, 0.6, 0).to(0, -0.2, 0)
     *                 .ease(Easing.IN).build());
     * }</pre>
     *
     * <p>A rise and then a fall, a pop and then a drift: two curves one builder
     * cannot say, because each has its own easing. Offsets stay what each
     * segment wrote, relative to where the display was spawned, so a segment
     * starts where the one before it ended by being written that way &mdash;
     * its own first pose is taken as that end and not sent again. A segment
     * whose poses stop before its life is up holds still for the rest of it,
     * and the next one only starts moving once that hold is over.
     *
     * <p>Each segment keeps its own spin cutting, so a tumbling fall is as
     * correct chained as alone. Loops are not carried over: the chain plays
     * once, and {@link #looping} can be applied to the result.
     *
     * @param segments the movements, in order; at least one
     * @return one motion lasting the sum of their lives
     * @since 1.197.0
     */
    public static @NotNull DisplayMotion chain(@NotNull DisplayMotion @NotNull ... segments) {
        if (segments.length == 0) {
            throw new IllegalArgumentException("A chain needs at least one segment.");
        }
        List<DisplayKeyframe> joined = new ArrayList<>();
        long offset = 0L;
        for (int index = 0; index < segments.length; index++) {
            List<DisplayKeyframe> own = segments[index].poses;
            if (index > 0) {
                DisplayKeyframe last = joined.get(joined.size() - 1);
                if (last.atMillis() < offset) {
                    // Held, not drifted: without a pose at the seam the client
                    // would ease towards the next segment across the whole hold.
                    joined.add(new DisplayKeyframe(offset, last.x(), last.y(), last.z(),
                            last.rotation(), last.scaleX(), last.scaleY(), last.scaleZ()));
                }
            }
            for (int pose = index == 0 ? 0 : 1; pose < own.size(); pose++) {
                DisplayKeyframe frame = own.get(pose);
                joined.add(new DisplayKeyframe(offset + frame.atMillis(), frame.x(), frame.y(),
                        frame.z(), frame.rotation(), frame.scaleX(), frame.scaleY(), frame.scaleZ()));
            }
            offset += Math.max(0L, segments[index].lifeMillis);
        }
        return new DisplayMotion(joined, offset);
    }

    /**
     * A glide from one pose to another.
     *
     * <p>What {@link DisplayHandle#moveTo} plays: offsets and sizes along the
     * curve, the rotation by the shorter arc. The poses' own times are ignored.
     *
     * @param from   where it starts
     * @param to     where it ends
     * @param millis how long it takes
     * @param easing how it is spread over that time
     * @return the motion, lasting {@code millis}
     * @since 1.272.0
     */
    public static @NotNull DisplayMotion between(@NotNull DisplayKeyframe from,
                                                 @NotNull DisplayKeyframe to, long millis,
                                                 @NotNull Easing easing) {
        return builder().life(millis)
                .from(from.x(), from.y(), from.z())
                .to(to.x(), to.y(), to.z())
                .scale(new double[]{from.scaleX(), from.scaleY(), from.scaleZ()},
                        new double[]{to.scaleX(), to.scaleY(), to.scaleZ()})
                .rotation(from.rotation())
                .turnTo(to.rotation())
                .ease(easing)
                .build();
    }

    /**
     * A path that runs through every keyframe instead of stopping at each.
     *
     * <pre>{@code
     * DisplayMotion swoop = DisplayMotion.spline(List.of(
     *         new DisplayKeyframe(0, 0, 0, 0, Rotation.NONE, 1, 1, 1),
     *         new DisplayKeyframe(600, 1.5f, 1, 0, Rotation.NONE, 1, 1, 1),
     *         new DisplayKeyframe(1200, 0, 2, 1.5f, Rotation.NONE, 1.4f, 1.4f, 1.4f),
     *         new DisplayKeyframe(1800, 0, 1, 0, Rotation.NONE, 1, 1, 1)), 2000);
     * }</pre>
     *
     * <p>Offsets and sizes follow a Catmull-Rom curve, which passes exactly
     * through every keyframe with no corner at any of them; the rotation turns
     * between neighbouring keyframes by the shorter arc. The curve is cut into
     * one pose about every 100 ms, so a path through four keyframes over two
     * seconds is about twenty packets a viewer, within {@link #maxPoses}.
     *
     * @param keys       the keyframes, in time order; the first should be at zero
     * @param lifeMillis how long the display lasts
     * @return the motion; the keyframes themselves when there are fewer than three
     * @since 1.272.0
     */
    public static @NotNull DisplayMotion spline(@NotNull List<DisplayKeyframe> keys, long lifeMillis) {
        if (keys.size() < 3) {
            return of(keys, lifeMillis);
        }
        int segments = keys.size() - 1;
        int[] cuts = new int[segments];
        int total = 1;
        for (int index = 0; index < segments; index++) {
            long span = keys.get(index + 1).atMillis() - keys.get(index).atMillis();
            cuts[index] = (int) Math.clamp((span + SPLINE_STEP_MS - 1) / SPLINE_STEP_MS, 1L, 8L);
            total += cuts[index];
        }
        int cap = maxPoses(lifeMillis);
        if (total > cap) {
            // Fewer cuts on every segment alike, so no stretch of the path is
            // starved to keep another one smooth.
            double keep = (double) (cap - 1) / (total - 1);
            for (int index = 0; index < segments; index++) {
                cuts[index] = Math.max(1, (int) Math.floor(cuts[index] * keep));
            }
        }
        List<DisplayKeyframe> poses = new ArrayList<>();
        poses.add(keys.get(0));
        for (int index = 0; index < segments; index++) {
            DisplayKeyframe before = keys.get(Math.max(0, index - 1));
            DisplayKeyframe from = keys.get(index);
            DisplayKeyframe to = keys.get(index + 1);
            DisplayKeyframe after = keys.get(Math.min(keys.size() - 1, index + 2));
            for (int cut = 1; cut <= cuts[index]; cut++) {
                double t = (double) cut / cuts[index];
                poses.add(cut == cuts[index] ? to : new DisplayKeyframe(
                        from.atMillis() + Math.round((to.atMillis() - from.atMillis()) * t),
                        (float) catmullRom(before.x(), from.x(), to.x(), after.x(), t),
                        (float) catmullRom(before.y(), from.y(), to.y(), after.y(), t),
                        (float) catmullRom(before.z(), from.z(), to.z(), after.z(), t),
                        from.rotation().slerp(to.rotation(), t),
                        (float) catmullRom(before.scaleX(), from.scaleX(), to.scaleX(), after.scaleX(), t),
                        (float) catmullRom(before.scaleY(), from.scaleY(), to.scaleY(), after.scaleY(), t),
                        (float) catmullRom(before.scaleZ(), from.scaleZ(), to.scaleZ(), after.scaleZ(), t)));
            }
        }
        return new DisplayMotion(poses, lifeMillis);
    }

    /** How far apart in time the poses of a spline are cut. */
    private static final long SPLINE_STEP_MS = 100L;

    /**
     * A uniform Catmull-Rom curve between {@code p1} and {@code p2}, steered by
     * their neighbours: {@code p1} at 0, {@code p2} at 1.
     */
    static double catmullRom(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return 0.5 * ((2 * p1) + (-p0 + p2) * t
                + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2
                + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }

    /** A builder for the movements configuration can describe. */
    public static @NotNull Builder builder() {
        return new Builder();
    }

    /**
     * The same poses, played over and over until the display is taken away.
     *
     * <p>For anything whose last pose is its first one: a dance, an idle, a
     * turning sign. The runtime walks the poses again from the top rather than
     * being handed the same list twenty times over, so a loop costs exactly
     * what one cycle costs however long somebody stands there watching it.
     *
     * <p>Each cycle can be quicker than the one before it, which is a dance
     * that winds up: {@code accel} multiplies the speed every cycle and
     * {@code maxSpeed} is where it stops climbing. Both {@code 1} is a loop
     * that keeps its tempo.
     *
     * @param cycleMillis how long one cycle lasts; the pose list must cover it
     * @param accel       what the speed is multiplied by each cycle, at least 1
     * @param maxSpeed    the fastest it may get, at least 1
     * @return the looping motion
     * @since 1.174.0
     */
    public @NotNull DisplayMotion looping(long cycleMillis, double accel, double maxSpeed) {
        return looping(0L, cycleMillis, accel, maxSpeed);
    }

    /**
     * The same, with a lead-in that is played once.
     *
     * <p>A dance is two things: getting into it, and doing it. Looping the
     * whole list would stand the body up and start it over every cycle, so the
     * cycle is only the part from {@code fromMillis} onwards and everything
     * before it is the entry, played once.
     *
     * @param fromMillis  where the cycle begins; everything before is the entry
     * @param toMillis    where it ends and goes back, which is the last pose
     * @param accel       what the speed is multiplied by each cycle, at least 1
     * @param maxSpeed    the fastest it may get, at least 1
     * @return the looping motion
     * @since 1.174.0
     */
    public @NotNull DisplayMotion looping(long fromMillis, long toMillis, double accel,
                                          double maxSpeed) {
        return looping(fromMillis, toMillis, accel, maxSpeed, 1.0);
    }

    /**
     * The same, starting at a tempo of its own rather than at the one written.
     *
     * <p>What this is for is a loop that must not feel identical every time it
     * is played: the caller rolls a tempo and hands it in, and the same frames
     * come out quicker or slower without a second pose list. {@code 1} is the
     * tempo the frames were written at, {@code 1.5} is half again as fast.
     *
     * <p>{@code maxSpeed} is raised to the starting tempo when it is below it,
     * because a ceiling under the floor would slow the motion down on its first
     * wrap instead of holding it where it started.
     *
     * @param fromMillis  where the cycle begins; everything before is the entry
     * @param toMillis    where it ends and goes back, which is the last pose
     * @param accel       what the speed is multiplied by each cycle, at least 1
     * @param maxSpeed    the fastest it may get, at least 1
     * @param startSpeed  the tempo the first cycle is played at, at least 0.1
     * @return the looping motion
     * @since 1.176.0
     */
    public @NotNull DisplayMotion looping(long fromMillis, long toMillis, double accel,
                                          double maxSpeed, double startSpeed) {
        if (toMillis <= 0L || toMillis <= fromMillis) {
            return this;
        }
        double start = Math.max(0.1, startSpeed);
        return new DisplayMotion(poses, lifeMillis, Math.max(0L, fromMillis), toMillis,
                Math.max(1.0, accel), Math.max(Math.max(1.0, maxSpeed), start), start);
    }

    /**
     * Where a looping motion goes back to, which is the end of its entry.
     *
     * @since 1.174.0
     */
    public long loopFromMillis() {
        return loopFromMillis;
    }

    /** The poses, in time order. */
    public @NotNull List<DisplayKeyframe> poses() {
        return poses;
    }

    /**
     * Where a looping motion's poses end and it goes round again, or {@code 0}
     * when this plays once.
     *
     * @since 1.174.0
     */
    public long cycleMillis() {
        return cycleMillis;
    }

    /**
     * What the speed is multiplied by at the end of every cycle.
     *
     * @since 1.174.0
     */
    public double accel() {
        return accel;
    }

    /**
     * The fastest a looping motion is allowed to get.
     *
     * @since 1.174.0
     */
    public double maxSpeed() {
        return maxSpeed;
    }

    /**
     * The tempo its first cycle is played at, where {@code 1} is as written.
     *
     * @since 1.176.0
     */
    public double startSpeed() {
        return startSpeed;
    }

    /** How long the display lasts, in milliseconds. */
    public long lifeMillis() {
        return lifeMillis;
    }

    /**
     * The same motion with every pose turned first.
     *
     * <p>One ring of blades, each facing outwards: the animation is built once
     * and turned twelve times, rather than built twelve times. Applied after
     * each pose's own rotation &mdash; see {@link DisplayKeyframe#turnedBy}.
     *
     * @param last the rotation applied after each pose's own
     * @return the turned motion
     */
    public @NotNull DisplayMotion turnedBy(@NotNull Rotation last) {
        if (last.isNone()) {
            return this;
        }
        List<DisplayKeyframe> turned = new ArrayList<>(poses.size());
        for (DisplayKeyframe pose : poses) {
            turned.add(pose.turnedBy(last));
        }
        return new DisplayMotion(turned, lifeMillis, loopFromMillis, cycleMillis, accel,
                maxSpeed, startSpeed);
    }

    /**
     * The same motion, drifting somewhere over its life.
     *
     * <p>Added on top of everything else and spread across the poses, so it
     * composes with a spin and a fall rather than replacing them. What it is
     * for is the movement a shape's points cannot share: twelve blades that
     * each converge on the same spot are twelve different directions and one
     * animation.
     *
     * @param dx how far east by the end
     * @param dy how far up by the end
     * @param dz how far south by the end
     * @return the drifting motion
     */
    public @NotNull DisplayMotion drifting(double dx, double dy, double dz) {
        if ((dx == 0.0 && dy == 0.0 && dz == 0.0) || lifeMillis <= 0L) {
            return this;
        }
        List<DisplayKeyframe> drifted = new ArrayList<>(poses.size());
        for (DisplayKeyframe pose : poses) {
            double progress = (double) pose.atMillis() / lifeMillis;
            drifted.add(pose.movedBy(dx * progress, dy * progress, dz * progress));
        }
        return new DisplayMotion(drifted, lifeMillis, loopFromMillis, cycleMillis, accel,
                maxSpeed, startSpeed);
    }

    /**
     * The same motion, carried round the anchor as it goes.
     *
     * <p>The one movement a straight line cannot express and the one every
     * effect eventually wants: a ring that turns, a swarm that circles, debris
     * that curves away instead of leaving on a rail. It is worked out per point
     * because each point starts somewhere different, and it is added to
     * whatever the point was already doing.
     *
     * @param x       where the point sits, east of the anchor
     * @param z       where the point sits, south of the anchor
     * @param turns   turns about the anchor over the whole life
     * @param facing  whether the model turns with the orbit, so a blade stays
     *                tangent to the circle rather than sliding round sideways
     * @return the orbiting motion
     */
    public @NotNull DisplayMotion orbiting(double x, double z, double turns, boolean facing) {
        if (turns == 0.0 || (x == 0.0 && z == 0.0) || lifeMillis <= 0L) {
            return this;
        }
        List<DisplayKeyframe> orbited = new ArrayList<>(poses.size());
        for (DisplayKeyframe pose : poses) {
            double progress = (double) pose.atMillis() / lifeMillis;
            double angle = turns * Math.PI * 2 * progress;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            DisplayKeyframe moved = pose.movedBy(
                    x * cos + z * sin - x, 0.0, -x * sin + z * cos - z);
            orbited.add(facing ? moved.turnedBy(Rotation.around(Rotation.Axis.Y, angle)) : moved);
        }
        return new DisplayMotion(orbited, lifeMillis, loopFromMillis, cycleMillis, accel,
                maxSpeed, startSpeed);
    }

    /**
     * The same motion at a different size throughout.
     *
     * <p>For giving the pieces of one shape sizes that differ. A dozen
     * fragments cut to exactly the same size read as a pattern; the same dozen
     * varying by a fifth read as rubble.
     *
     * @param factor what to multiply every pose's size by
     * @return the resized motion
     */
    public @NotNull DisplayMotion scaledBy(double factor) {
        if (factor == 1.0) {
            return this;
        }
        List<DisplayKeyframe> resized = new ArrayList<>(poses.size());
        for (DisplayKeyframe pose : poses) {
            resized.add(new DisplayKeyframe(pose.atMillis(), pose.x(), pose.y(), pose.z(),
                    pose.rotation(),
                    (float) (pose.scaleX() * factor),
                    (float) (pose.scaleY() * factor),
                    (float) (pose.scaleZ() * factor)));
        }
        return new DisplayMotion(resized, lifeMillis, loopFromMillis, cycleMillis, accel,
                maxSpeed, startSpeed);
    }

    /**
     * The same motion through a magnifier: every pose's offsets and sizes {@code factor} times larger
     * (see {@link DisplayKeyframe#magnified}), its timing and any loop kept.
     *
     * @param factor how many times larger
     * @return the magnified motion
     * @since 1.377.0
     */
    public @NotNull DisplayMotion magnified(double factor) {
        if (factor == 1.0) {
            return this;
        }
        List<DisplayKeyframe> larger = new ArrayList<>(poses.size());
        for (DisplayKeyframe pose : poses) {
            larger.add(pose.magnified(factor));
        }
        return new DisplayMotion(larger, lifeMillis, loopFromMillis, cycleMillis, accel,
                maxSpeed, startSpeed);
    }

    /**
     * The same motion with no pose lower than {@code lowest}: what would sink below that height stops on
     * it, its timing, rotation and size kept. For something drawn over a floor that is not really there,
     * such as a miniature on a pedestal, whose debris has to land instead of falling through.
     *
     * @param lowest the lowest offset up a pose may have, in blocks
     * @return the motion, itself when no pose goes that low
     * @since 1.413.0
     */
    public @NotNull DisplayMotion above(double lowest) {
        boolean below = false;
        for (DisplayKeyframe pose : poses) {
            if (pose.y() < lowest) {
                below = true;
                break;
            }
        }
        if (!below) {
            return this;
        }
        List<DisplayKeyframe> held = new ArrayList<>(poses.size());
        for (DisplayKeyframe pose : poses) {
            held.add(pose.y() >= lowest ? pose : pose.movedBy(0.0, lowest - pose.y(), 0.0));
        }
        return new DisplayMotion(held, lifeMillis, loopFromMillis, cycleMillis, accel,
                maxSpeed, startSpeed);
    }

    /**
     * The same motion starting somewhere else.
     *
     * @param dx east
     * @param dy up
     * @param dz south
     * @return the moved motion
     */
    public @NotNull DisplayMotion movedBy(double dx, double dy, double dz) {
        if (dx == 0.0 && dy == 0.0 && dz == 0.0) {
            return this;
        }
        List<DisplayKeyframe> moved = new ArrayList<>(poses.size());
        for (DisplayKeyframe pose : poses) {
            moved.add(pose.movedBy(dx, dy, dz));
        }
        return new DisplayMotion(moved, lifeMillis, loopFromMillis, cycleMillis, accel,
                maxSpeed, startSpeed);
    }

    /**
     * Describes a movement in the terms configuration is written in.
     *
     * <p>Straight line from one offset to another, an optional fall on top of
     * it, an optional spin, and a size that grows or shrinks. Between them they
     * cover every effect anybody has actually asked for, and each one is a
     * number a server owner can picture.
     */
    public static final class Builder {

        private long lifeMillis = 1000L;
        private double fromX;
        private double fromY;
        private double fromZ;
        private double toX;
        private double toY;
        private double toZ;
        private double[] startScale = {1.0, 1.0, 1.0};
        private double[] endScale = {1.0, 1.0, 1.0};
        private Rotation base = Rotation.NONE;
        private double spinX;
        private double spinY;
        private double spinZ;
        private double gravity;
        private Easing positionEase = Easing.LINEAR;
        private Easing rotationEase = Easing.LINEAR;
        private Easing scaleEase = Easing.LINEAR;
        private Rotation turnTo;

        private Builder() {
        }

        /** How long the display lasts, in milliseconds. */
        public @NotNull Builder life(long millis) {
            this.lifeMillis = Math.max(50L, millis);
            return this;
        }

        /** Where it starts, relative to where it was spawned. */
        public @NotNull Builder from(double x, double y, double z) {
            this.fromX = x;
            this.fromY = y;
            this.fromZ = z;
            return this;
        }

        /** Where it ends up, relative to where it was spawned. */
        public @NotNull Builder to(double x, double y, double z) {
            this.toX = x;
            this.toY = y;
            this.toZ = z;
            return this;
        }

        /** The size it starts and ends at, as a multiple of the model's own. */
        public @NotNull Builder scale(double start, double end) {
            return scale(new double[]{start, start, start}, new double[]{end, end, end});
        }

        /**
         * The same, per axis.
         *
         * <p>What a cube cannot say on its own. A block flattened to a tenth of
         * its height is a plate, and a plate growing outwards is a shockwave; a
         * block stretched along one axis is a pillar, a beam or the blade of
         * something far too large to be an item. The models are the twenty
         * blocks a server already has, and the shape comes from here.
         *
         * @param start width, height and depth it starts at
         * @param end   width, height and depth it ends at
         * @return this builder
         */
        public @NotNull Builder scale(double @NotNull [] start, double @NotNull [] end) {
            this.startScale = new double[]{start[0], start[1], start[2]};
            this.endScale = new double[]{end[0], end[1], end[2]};
            return this;
        }

        /** A fixed rotation the model keeps for its whole life. */
        public @NotNull Builder rotation(@NotNull Rotation rotation) {
            this.base = rotation;
            return this;
        }

        /** Turns around one axis over the display's whole life. */
        public @NotNull Builder spin(@NotNull Rotation.Axis axis, double turns) {
            return switch (axis) {
                case X -> spin(turns, 0, 0);
                case Y -> spin(0, turns, 0);
                case Z -> spin(0, 0, turns);
            };
        }

        /**
         * Turns around all three axes at once.
         *
         * <p>One axis is a wheel and reads as machinery. Two or three at
         * different rates is a tumble, and a tumble is what a thrown thing
         * actually does: nothing in the world spins about exactly one axis, and
         * the eye knows it even when it cannot say why.
         *
         * @param x turns about the pitch axis
         * @param y turns about the vertical
         * @param z turns about the roll axis
         * @return this builder
         */
        public @NotNull Builder spin(double x, double y, double z) {
            this.spinX = x;
            this.spinY = y;
            this.spinZ = z;
            return this;
        }

        /**
         * Downward acceleration in blocks per second squared, on top of the line.
         *
         * <p>What turns a throw into an arc. Vanilla gravity is about 32; the
         * number is left open because an effect is choreography, not physics,
         * and a slower fall reads better on a short life.
         */
        public @NotNull Builder gravity(double blocksPerSecondSquared) {
            this.gravity = blocksPerSecondSquared;
            return this;
        }

        /**
         * How the movement is spread across its life.
         *
         * <p>A straight line at a constant rate is a thing sliding. The same
         * line eased in is the same thing arriving. Sets the curve of the
         * offsets, the turn and the size together.
         */
        public @NotNull Builder ease(@NotNull Easing easing) {
            this.positionEase = easing;
            this.rotationEase = easing;
            this.scaleEase = easing;
            return this;
        }

        /**
         * The curve of the offsets alone.
         *
         * <p>A card that flies in with {@code BACK_OUT} while it flips at a
         * steady rate, or a reel that slides into place while still turning.
         *
         * @param easing how the offsets are spread across the life
         * @return this builder
         * @since 1.272.0
         */
        public @NotNull Builder easePosition(@NotNull Easing easing) {
            this.positionEase = easing;
            return this;
        }

        /**
         * The curve of the turn alone: the spin and {@link #turnTo}.
         *
         * @param easing how the turning is spread across the life
         * @return this builder
         * @since 1.272.0
         */
        public @NotNull Builder easeRotation(@NotNull Easing easing) {
            this.rotationEase = easing;
            return this;
        }

        /**
         * The curve of the size alone.
         *
         * @param easing how the growth is spread across the life
         * @return this builder
         * @since 1.272.0
         */
        public @NotNull Builder easeScale(@NotNull Easing easing) {
            this.scaleEase = easing;
            return this;
        }

        /**
         * Turns from {@link #rotation} to this one over the life, by the shorter
         * arc, with any spin on top.
         *
         * @param end the rotation it ends in, before the spin
         * @return this builder
         * @since 1.272.0
         */
        public @NotNull Builder turnTo(@NotNull Rotation end) {
            this.turnTo = end;
            return this;
        }

        /** Works out the poses. */
        public @NotNull DisplayMotion build() {
            double[] fractions = fractions();
            List<DisplayKeyframe> poses = new ArrayList<>(fractions.length);
            double seconds = lifeMillis / 1000.0;
            for (double elapsedFraction : fractions) {
                // The poses are spaced in time and unevenly spaced along the
                // movement. That is what easing is: the client still draws a
                // straight line between two poses, and the poses are where the
                // acceleration lives.
                double moved = positionEase.at(elapsedFraction);
                double turned = rotationEase.at(elapsedFraction);
                double grown = scaleEase.at(elapsedFraction);
                double elapsed = elapsedFraction * seconds;
                // The fall is added to the straight line rather than replacing
                // it, so "throw it four blocks east and let it drop" is two
                // independent numbers instead of one solved trajectory.
                double drop = gravity == 0.0 ? 0.0 : 0.5 * gravity * elapsed * elapsed;
                poses.add(new DisplayKeyframe(
                        (long) (elapsedFraction * lifeMillis),
                        (float) (fromX + (toX - fromX) * moved),
                        (float) (fromY + (toY - fromY) * moved - drop),
                        (float) (fromZ + (toZ - fromZ) * moved),
                        spinning(turnTo == null ? base : base.slerp(turnTo, turned), turned),
                        (float) (startScale[0] + (endScale[0] - startScale[0]) * grown),
                        (float) (startScale[1] + (endScale[1] - startScale[1]) * grown),
                        (float) (startScale[2] + (endScale[2] - startScale[2]) * grown)));
            }
            return new DisplayMotion(poses, lifeMillis);
        }

        /** The base rotation with this moment's share of every spin on top. */
        private Rotation spinning(Rotation from, double progress) {
            Rotation turned = from;
            if (spinX != 0.0) {
                turned = turned.then(Rotation.around(Rotation.Axis.X,
                        progress * spinX * Math.PI * 2));
            }
            if (spinY != 0.0) {
                turned = turned.then(Rotation.around(Rotation.Axis.Y,
                        progress * spinY * Math.PI * 2));
            }
            if (spinZ != 0.0) {
                turned = turned.then(Rotation.around(Rotation.Axis.Z,
                        progress * spinZ * Math.PI * 2));
            }
            return turned;
        }

        /** The three curves, so the most demanding one decides. */
        private Easing[] curves() {
            return new Easing[]{positionEase, rotationEase, scaleEase};
        }

        /**
         * When, as fractions of the life, a pose is struck.
         *
         * <p>Evenly spaced while that fits under the cap, which is every motion
         * that ever fitted. A long, fast, eased spin does not: a wheel that
         * decelerates over ten turns needs its poses close together while it is
         * fast and hardly any once it crawls, so past the cap the poses are
         * placed where the turn needs them rather than every so often.
         */
        double[] fractions() {
            int needed = poseCount();
            int cap = maxPoses(lifeMillis);
            double[] even = null;
            if (needed <= cap) {
                even = new double[needed];
                for (int index = 0; index < needed; index++) {
                    even[index] = needed == 1 ? 0.0 : (double) index / (needed - 1);
                }
            }
            double[] placed = even != null ? even : adaptive(cap);
            return withSteps(placed);
        }

        /**
         * Poses placed one per sixth of a turn of the eased spin, plus enough
         * evenly spaced for the curves of the offsets and the size.
         */
        private double[] adaptive(int cap) {
            double turns = Math.abs(spinX) + Math.abs(spinY) + Math.abs(spinZ);
            int shape = 2;
            for (Easing curve : curves()) {
                shape = Math.max(shape, curve.poses);
            }
            if (gravity != 0.0) {
                shape = Math.max(shape, POSES_PER_FALL);
            }
            double evenStep = 1.0 / (Math.min(shape, cap) - 1);
            double turnStep = turns == 0.0 ? Double.MAX_VALUE : 1.0 / (turns * POSES_PER_TURN);
            int grid = (int) Math.max(400, Math.min(20_000, lifeMillis / 2));
            List<Double> out = new ArrayList<>();
            out.add(0.0);
            double lastAt = 0.0;
            double lastTurn = rotationEase.at(0.0);
            for (int index = 1; index < grid; index++) {
                double at = (double) index / grid;
                double turn = rotationEase.at(at);
                if (at - lastAt >= evenStep - 1e-9 || Math.abs(turn - lastTurn) >= turnStep) {
                    out.add(at);
                    lastAt = at;
                    lastTurn = turn;
                }
            }
            out.add(1.0);
            if (out.size() > cap) {
                // Over the cap even placed where needed: keep an even selection
                // of them, ends included. A spin this fast for this long turns
                // more than a sixth of a turn between poses somewhere, which
                // the file asked for and the cap refuses to pay for.
                List<Double> thinned = new ArrayList<>(cap);
                for (int index = 0; index < cap; index++) {
                    thinned.add(out.get((int) Math.round((double) index * (out.size() - 1) / (cap - 1))));
                }
                out = thinned;
            }
            double[] fractions = new double[out.size()];
            for (int index = 0; index < fractions.length; index++) {
                fractions[index] = out.get(index);
            }
            return fractions;
        }

        /**
         * Adds the instants a stepped curve jumps at.
         *
         * <p>{@code SNAP} and {@code HOLD} change all at once, and sampled
         * evenly they would be drawn as a slow slide across the gap between two
         * poses. A pose a tick after the start, or a tick before the end, is
         * where the jump really is.
         */
        private double[] withSteps(double[] fractions) {
            double tick = Math.min(0.5, (double) TICK_MS / lifeMillis);
            List<Double> extra = new ArrayList<>(2);
            for (Easing curve : curves()) {
                if (curve == Easing.SNAP) {
                    extra.add(tick);
                } else if (curve == Easing.HOLD) {
                    extra.add(1.0 - tick);
                }
            }
            if (extra.isEmpty() || fractions.length < 2) {
                return fractions;
            }
            java.util.TreeSet<Double> all = new java.util.TreeSet<>();
            for (double fraction : fractions) {
                all.add(fraction);
            }
            all.addAll(extra);
            double[] merged = new double[all.size()];
            int index = 0;
            for (double fraction : all) {
                merged[index++] = fraction;
            }
            return merged;
        }

        /**
         * How many evenly spaced poses this movement needs.
         *
         * <p>A straight line needs two. A spin needs enough that the client
         * never has to guess which way round; a fall needs enough to look like
         * a curve. The most demanding of the three wins.
         */
        private int poseCount() {
            int needed = 2;
            double turns = Math.abs(spinX) + Math.abs(spinY) + Math.abs(spinZ);
            if (turns != 0.0) {
                // The sum, not the largest: two axes turning at once can put
                // more angle between two poses than either does alone.
                needed = Math.max(needed,
                        (int) Math.ceil(turns * POSES_PER_TURN * rotationEase.peak()) + 1);
            }
            if (turnTo != null) {
                needed = Math.max(needed, rotationEase.poses);
            }
            if (gravity != 0.0) {
                needed = Math.max(needed, POSES_PER_FALL);
            }
            for (Easing curve : curves()) {
                needed = Math.max(needed, curve.poses);
            }
            return needed;
        }
    }
}
