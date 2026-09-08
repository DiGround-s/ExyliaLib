package net.exylia.lib.ragdoll.internal;

import net.exylia.lib.display.Rotation;
import net.exylia.lib.ragdoll.RagdollMotion;
import net.exylia.lib.ragdoll.RagdollPart;
import net.exylia.lib.ragdoll.RagdollPose;
import org.jetbrains.annotations.ApiStatus;

import java.util.random.RandomGenerator;

/**
 * Where one piece of a body is, at every moment a pose is sent.
 *
 * <h2>Solved once, drawn by the client</h2>
 * The whole flight is worked out the moment the body goes and handed over as a
 * couple of dozen poses with times on them. Nothing is ticked afterwards and
 * nothing is ever teleported: the client is told where a piece will be and
 * draws every frame in between at its own frame rate, so the motion is as
 * smooth as the player's monitor on a server that is not smooth at all.
 *
 * <h2>Four deaths, one solver</h2>
 * A body thrown apart is ballistics. A body held open in the air, turning, is
 * choreography. A body being knocked about is choreography plus a shove that
 * decays. A body taken upwards is a spiral. They are written apart here rather
 * than as one clever function with flags, because a pose nobody can read is a
 * pose nobody will add a fifth to.
 *
 * <h2>Deliberately not physics</h2>
 * A piece is a point with a bounce, and the only thing in the world it can hit
 * is the floor its owner was standing on. A piece that passes through a wall
 * for a fifth of a second, once, is a fair price for one that never sticks
 * inside a staircase and never needs the world read off the main thread.
 *
 * <p>Free of Bukkit on purpose, so the part of this that can be wrong quietly
 * &mdash; a piece that sinks through the floor, a spin the client takes the
 * long way round &mdash; is the part that can be asserted.
 */
@ApiStatus.Internal
public final class RagdollFlight {

    /** How often a pose is sent while a piece is moving. */
    public static final long POSE_MS = 100L;

    /** A ceiling, so a long effect does not become a packet a tick per piece. */
    private static final int MAX_POSES = 48;

    /** The step the flight is integrated at, well under the pose interval. */
    private static final double STEP_SECONDS = 0.01;

    /** How high a piece's centre rests above the floor it landed on. */
    private static final double REST_HEIGHT = 0.08;

    /** What a landing takes out of a piece's sideways speed. */
    private static final double FLOOR_FRICTION = 0.55;

    /** Below this, a bounce is a rattle nobody sees, so the piece is done. */
    private static final double SETTLE_SPEED = 1.2;

    /**
     * The fastest a piece may be told to turn.
     *
     * <p>The client takes the shortest arc between two poses, so a pose more
     * than half a turn from the last one spins backwards. At a pose every tenth
     * of a second and three axes turning at once, this is the rate that keeps
     * the worst case comfortably inside that limit.
     */
    public static final double MAX_TURNS_PER_SECOND = 2.4;

    /** Where a head is thrown compared with everything else. */
    private static final double HEAD_LIFT_SPEED = 1.35;

    /**
     * How long a blow's shove takes to die away, in seconds.
     *
     * <p>Short enough that the body is coming back before the next one lands,
     * long enough that the shove is drawn across three or four poses rather
     * than appearing and vanishing between two.
     */
    private static final double KNOCK_DECAY = 0.28;

    /** The angle between one blow and the next. The golden angle, so no two line up. */
    private static final double GOLDEN_ANGLE = 2.39996;

    /** What is left of the throw when a held body is finally let go. */
    private static final double RELEASE_SHARE = 0.45;

    private RagdollFlight() {
    }

    /**
     * The whole flight of one part: where it is and how it is turned, at every
     * moment a pose is sent.
     */
    public record Flight(long[] times, double[] x, double[] y, double[] z,
                         Rotation[] rotations) {
    }

    /**
     * Solves one part's flight, whichever death the file asked for.
     *
     * @param part   which piece
     * @param motion what happens to the body
     * @param scale  how big the body is; 1 is player-sized
     * @param facing which way the body was looking
     * @param random where the variation between pieces comes from
     * @return every pose the piece passes through
     */
    public static Flight solve(RagdollPart part, RagdollMotion motion, double scale,
                               Rotation facing, RandomGenerator random) {
        return switch (motion.pose()) {
            case BURST -> burst(part, motion, scale, facing, random);
            case SPREAD, KNOCKED -> held(part, motion, scale, facing, random);
            case VORTEX -> vortex(part, motion, scale, facing, random);
        };
    }

    // --------------------------------------------------------------- the throw

    /** Thrown apart: outwards, up, down, bounce, rest. */
    private static Flight burst(RagdollPart part, RagdollMotion motion, double scale,
                                Rotation facing, RandomGenerator random) {
        double[] standing = standing(part, scale, facing);

        // Outwards means away from the middle of the body, which for the head
        // and the chest is no direction at all — so they get one.
        double angle = outwards(standing, random) + random.nextDouble(-1, 1) * motion.spread();
        double vary = 1 + random.nextDouble(-1, 1) * motion.spread();
        double speed = motion.speed() * vary;
        double lift = motion.up() * (1 + random.nextDouble(-0.5, 0.5) * motion.spread())
                * (part == RagdollPart.HEAD ? HEAD_LIFT_SPEED : 1.0);

        double[] velocity = {Math.cos(angle) * speed, lift, Math.sin(angle) * speed};
        double[] axis = tumbleAxis(random);
        double turns = spinRate(motion, random);

        long[] times = times(motion, motion.intactMillis());
        Fall fall = new Fall(motion, scale, standing, velocity);
        return sample(times, motion.intactMillis(), elapsed -> {
            fall.to(elapsed);
            return new Step(fall.position(),
                    fall.resting() && motion.settle()
                            ? tumble(axis, turns, fall.restedAt())
                            : tumble(axis, turns, elapsed));
        });
    }

    // ---------------------------------------------------------------- the hold

    /**
     * Held open in the air, turning, and then let go.
     *
     * <p>Four phases on one clock: standing, lifted and opened, hanging there
     * &mdash; being hit, if the pose says so &mdash; and finally falling. The
     * beat in the middle is the whole reason the pose exists: a body hanging
     * open is a second in which something else can happen to it.
     */
    private static Flight held(RagdollPart part, RagdollMotion motion, double scale,
                               Rotation facing, RandomGenerator random) {
        double[] standing = standing(part, scale, facing);
        double[] open = opened(part, motion, scale, facing);
        Rotation opening = opening(part);
        boolean knocked = motion.pose() == RagdollPose.KNOCKED;

        long intact = motion.intactMillis();
        long lifted = intact + motion.liftMillis();
        long released = Math.min(motion.lifeMillis(), lifted + motion.hangMillis());

        // What it is doing when it is let go, so it falls from the pose it was
        // in rather than snapping back to a standing one.
        double angle = outwards(open, random) + random.nextDouble(-1, 1) * motion.spread();
        double[] velocity = {
                Math.cos(angle) * motion.speed() * RELEASE_SHARE,
                motion.up() * RELEASE_SHARE,
                Math.sin(angle) * motion.speed() * RELEASE_SHARE};
        double[] axis = tumbleAxis(random);
        double turns = spinRate(motion, random);

        double releaseYaw = motion.turns() * Math.PI * 2;
        long[] times = times(motion, intact);
        Fall fall = null;
        int poses = times.length;
        double[] x = new double[poses];
        double[] y = new double[poses];
        double[] z = new double[poses];
        Rotation[] rotations = new Rotation[poses];

        for (int index = 0; index < poses; index++) {
            long at = times[index];
            if (at <= intact) {
                x[index] = standing[0];
                y[index] = standing[1];
                z[index] = standing[2];
                rotations[index] = Rotation.NONE;
                continue;
            }
            if (at < released) {
                double opened = Math.clamp((double) (at - intact) / motion.liftMillis(), 0, 1);
                // Eased out: a body is snatched off the ground and then settles
                // into the pose, rather than sliding into it at one rate.
                double eased = 1 - Math.pow(1 - opened, 3);
                double[] here = {
                        standing[0] + (open[0] - standing[0]) * eased,
                        standing[1] + (open[1] - standing[1]) * eased,
                        standing[2] + (open[2] - standing[2]) * eased};
                double hanging = at <= lifted ? 0 : (at - lifted) / 1000.0;
                double yaw = motion.hangMillis() <= 0 ? 0
                        : motion.turns() * Math.PI * 2 * hanging / (motion.hangMillis() / 1000.0);
                double[] turned = spun(here, yaw);
                // Only once it is actually hanging there. A blow that lands
                // while the body is still being lifted is a blow that landed
                // before the body arrived.
                if (knocked && at > lifted) {
                    double[] shove = knock(motion, hanging);
                    turned[0] += shove[0];
                    turned[1] += shove[1];
                    turned[2] += shove[2];
                    yaw += shove[3];
                }
                x[index] = turned[0];
                y[index] = turned[1];
                z[index] = turned[2];
                rotations[index] = partial(opening, eased).then(Rotation.around(Rotation.Axis.Y, yaw));
                continue;
            }
            if (fall == null) {
                // Falls from wherever it was hanging when the hold ran out.
                fall = new Fall(motion, scale,
                        new double[]{x[index - 1], y[index - 1], z[index - 1]}, velocity);
            }
            double falling = (at - released) / 1000.0;
            fall.to(falling);
            x[index] = fall.position()[0];
            y[index] = fall.position()[1];
            z[index] = fall.position()[2];
            // Carries the yaw it had reached while it hung, so letting go is
            // the moment it starts falling and not the moment it snaps round.
            rotations[index] = fall.resting() && motion.settle()
                    ? rotations[index - 1]
                    : partial(opening, 1)
                            .then(Rotation.around(Rotation.Axis.Y, releaseYaw))
                            .then(tumble(axis, turns, falling));
        }
        return new Flight(times, x, y, z, rotations);
    }

    /**
     * Where a blow has shoved the body, and how far it has spun it.
     *
     * <p>Each blow is a push that dies away, so the body is already drifting
     * back when the next one lands. Their directions walk round by the golden
     * angle, which is the cheapest way to be sure that four blows do not come
     * from two places.
     *
     * @return east, up, south and the yaw it added
     */
    private static double[] knock(RagdollMotion motion, double hanging) {
        double[] shove = new double[4];
        double every = motion.everyMillis() / 1000.0;
        for (int hit = 0; hit < motion.hits(); hit++) {
            double since = hanging - hit * every;
            if (since < 0) {
                break;
            }
            double left = Math.exp(-since / KNOCK_DECAY);
            double angle = hit * GOLDEN_ANGLE;
            shove[0] += Math.cos(angle) * motion.force() * left;
            shove[1] += Math.sin(angle * 1.7) * motion.force() * 0.35 * left;
            shove[2] += Math.sin(angle) * motion.force() * left;
            shove[3] += (hit % 2 == 0 ? 1 : -1) * motion.force() * 0.6 * left;
        }
        return shove;
    }

    // -------------------------------------------------------------- the spiral

    /** Taken: out, round, up, and gone at the top. */
    private static Flight vortex(RagdollPart part, RagdollMotion motion, double scale,
                                 Rotation facing, RandomGenerator random) {
        double[] standing = standing(part, scale, facing);
        double radius = Math.hypot(standing[0], standing[2]) + motion.open();
        double start = outwards(standing, random);
        double axisTurns = motion.turns() == 0 ? 1.5 : motion.turns();
        // A little apart in time, so six pieces do not arrive at the point
        // together and read as one object.
        double stagger = random.nextDouble(0, 0.25);
        double[] axis = tumbleAxis(random);
        double turns = spinRate(motion, random);

        long[] times = times(motion, motion.intactMillis());
        double flight = Math.max(1, motion.lifeMillis() - motion.intactMillis()) / 1000.0;
        return sample(times, motion.intactMillis(), elapsed -> {
            double progress = Math.clamp(elapsed / flight - stagger, 0, 1);
            double angle = start + axisTurns * Math.PI * 2 * progress;
            // Squared, so it hangs wide for most of the climb and closes on the
            // point at the end. A radius closing at one rate reads as a drain.
            double drawn = radius * (1 - progress) * (1 - progress);
            return new Step(new double[]{
                    Math.cos(angle) * drawn,
                    standing[1] + (motion.rise() + 2.0) * progress,
                    Math.sin(angle) * drawn},
                    tumble(axis, turns, elapsed)
                            .then(Rotation.around(Rotation.Axis.Y, angle)));
        });
    }

    // ---------------------------------------------------------------- the body

    /** Where a part stands on a living body, turned the way the body faces. */
    private static double[] standing(RagdollPart part, double scale, Rotation facing) {
        float[] turned = facing.apply(new float[]{
                part.blockOffsetX() * (float) scale,
                part.blockCentreY() * (float) scale,
                0f});
        return new double[]{turned[0], turned[1], turned[2]};
    }

    /**
     * Where a part hangs on a body that has been lifted and opened out.
     *
     * <p>Arms straight out to the sides, legs apart, head and chest carried up
     * with them. The pose a person is held in when something else is about to
     * happen to them, and the one the eye reads as helpless rather than dead.
     */
    private static double[] opened(RagdollPart part, RagdollMotion motion, double scale,
                                   Rotation facing) {
        double open = motion.open() * scale;
        double[] local = switch (part) {
            case HEAD -> new double[]{0, part.blockCentreY() * scale + open * 0.35, 0};
            case TORSO -> new double[]{0, part.blockCentreY() * scale, 0};
            case ARM_RIGHT -> new double[]{
                    part.blockOffsetX() * scale - open,
                    part.blockCentreY() * scale + open * 0.5, 0};
            case ARM_LEFT -> new double[]{
                    part.blockOffsetX() * scale + open,
                    part.blockCentreY() * scale + open * 0.5, 0};
            case LEG_RIGHT -> new double[]{
                    part.blockOffsetX() * scale - open * 0.8,
                    part.blockCentreY() * scale - open * 0.15, 0};
            case LEG_LEFT -> new double[]{
                    part.blockOffsetX() * scale + open * 0.8,
                    part.blockCentreY() * scale - open * 0.15, 0};
        };
        float[] turned = facing.apply(new float[]{
                (float) local[0], (float) local[1], (float) local[2]});
        return new double[]{turned[0], turned[1] + motion.rise() * scale, turned[2]};
    }

    /**
     * How a part is turned once the body is open.
     *
     * <p>An arm's long axis is upright on a standing body, so an arm held out
     * sideways is a quarter turn about the roll axis. Legs go half as far,
     * which is a stance rather than the splits.
     */
    private static Rotation opening(RagdollPart part) {
        return switch (part) {
            case ARM_RIGHT -> Rotation.around(Rotation.Axis.Z, Math.PI / 2);
            case ARM_LEFT -> Rotation.around(Rotation.Axis.Z, -Math.PI / 2);
            case LEG_RIGHT -> Rotation.around(Rotation.Axis.Z, Math.PI / 5);
            case LEG_LEFT -> Rotation.around(Rotation.Axis.Z, -Math.PI / 5);
            case HEAD, TORSO -> Rotation.NONE;
        };
    }

    /** Part of the way into a turn about one axis. */
    private static Rotation partial(Rotation whole, double progress) {
        if (whole.isNone() || progress >= 1) {
            return progress >= 1 ? whole : Rotation.NONE;
        }
        // Every opening rotation is a roll, so the fraction is the angle rather
        // than a slerp nobody would be able to read here.
        double angle = 2 * Math.atan2(whole.z(), whole.w());
        return Rotation.around(Rotation.Axis.Z, angle * progress);
    }

    /** A point carried round the body's own vertical axis. */
    private static double[] spun(double[] point, double yaw) {
        double cos = Math.cos(yaw);
        double sin = Math.sin(yaw);
        return new double[]{
                point[0] * cos + point[2] * sin,
                point[1],
                -point[0] * sin + point[2] * cos};
    }

    // ------------------------------------------------------------- the plumbing

    /** One moment of a flight, as a solver hands it back. */
    private record Step(double[] position, Rotation rotation) {
    }

    /** What a solver does at each moment a pose is due. */
    @FunctionalInterface
    private interface Path {

        Step at(double elapsedSeconds);
    }

    /** Walks the pose times and collects what a path says at each of them. */
    private static Flight sample(long[] times, long from, Path path) {
        int poses = times.length;
        double[] x = new double[poses];
        double[] y = new double[poses];
        double[] z = new double[poses];
        Rotation[] rotations = new Rotation[poses];
        for (int index = 0; index < poses; index++) {
            long at = times[index];
            Step step = path.at(Math.max(0, at - from) / 1000.0);
            x[index] = step.position()[0];
            y[index] = step.position()[1];
            z[index] = step.position()[2];
            rotations[index] = step.rotation();
        }
        return new Flight(times, x, y, z, rotations);
    }

    /**
     * When poses are sent: one at zero, and then a steady beat from the moment
     * anything starts happening.
     */
    private static long[] times(RagdollMotion motion, long from) {
        long life = motion.lifeMillis();
        long moving = Math.max(0, life - from);
        int poses = Math.min(MAX_POSES, (int) (moving / POSE_MS) + 2);
        long step = Math.max(POSE_MS, poses <= 2 ? moving : moving / (poses - 2));
        long[] times = new long[poses];
        times[0] = 0L;
        for (int index = 1; index < poses; index++) {
            times[index] = Math.min(life, from + (long) (index - 1) * step);
        }
        return times;
    }

    /** Which way a piece leaves, given where on the body it was. */
    private static double outwards(double[] from, RandomGenerator random) {
        return from[0] == 0 && from[2] == 0
                ? random.nextDouble(Math.PI * 2)
                : Math.atan2(from[2], from[0]);
    }

    /** A unit axis for a piece to tumble about. */
    private static double[] tumbleAxis(RandomGenerator random) {
        double[] axis = {random.nextDouble(-1, 1), random.nextDouble(-1, 1),
                random.nextDouble(-1, 1)};
        double length = Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
        if (length < 1e-4) {
            return new double[]{0, 1, 0};
        }
        for (int index = 0; index < 3; index++) {
            axis[index] /= length;
        }
        return axis;
    }

    /** How fast this piece turns, kept inside what the client can interpolate. */
    private static double spinRate(RagdollMotion motion, RandomGenerator random) {
        return Math.clamp(motion.spin() * (1 + random.nextDouble(-1, 1) * motion.spread()),
                -MAX_TURNS_PER_SECOND, MAX_TURNS_PER_SECOND);
    }

    /**
     * How far a piece has turned by now.
     *
     * <p>Three axes at once rather than one, because a thing thrown never turns
     * about a single axis and the eye knows it. Built by composing the three
     * the module already has, so no keyframe is ever more than half a turn from
     * the last one and the client never takes the long way round.
     */
    private static Rotation tumble(double[] axis, double turns, double seconds) {
        double angle = turns * Math.PI * 2 * seconds;
        return Rotation.around(Rotation.Axis.X, angle * axis[0])
                .then(Rotation.around(Rotation.Axis.Y, angle * axis[1]))
                .then(Rotation.around(Rotation.Axis.Z, angle * axis[2]));
    }

    /**
     * A piece falling, integrated forward.
     *
     * <p>Stateful on purpose: a bounce depends on everything that happened
     * before it, so the flight is walked once rather than solved again at every
     * pose.
     */
    private static final class Fall {

        private final RagdollMotion motion;
        private final double restHeight;
        private final double[] position;
        private final double[] velocity;
        private double flown;
        private double restedAt = -1;

        Fall(RagdollMotion motion, double scale, double[] from, double[] velocity) {
            this.motion = motion;
            this.restHeight = REST_HEIGHT * scale;
            this.position = new double[]{from[0], from[1], from[2]};
            this.velocity = new double[]{velocity[0], velocity[1], velocity[2]};
        }

        /** Moves it forward to a moment, in seconds since it was let go. */
        void to(double seconds) {
            while (flown < seconds - 1e-9) {
                double step = Math.min(STEP_SECONDS, seconds - flown);
                velocity[1] -= motion.gravity() * step;
                position[0] += velocity[0] * step;
                position[1] += velocity[1] * step;
                position[2] += velocity[2] * step;
                if (position[1] <= restHeight && velocity[1] < 0) {
                    land();
                }
                flown += step;
            }
        }

        private void land() {
            position[1] = restHeight;
            if (-velocity[1] < SETTLE_SPEED) {
                velocity[0] = 0;
                velocity[1] = 0;
                velocity[2] = 0;
                if (restedAt < 0) {
                    restedAt = flown;
                }
                return;
            }
            velocity[1] = -velocity[1] * motion.bounce();
            velocity[0] *= FLOOR_FRICTION;
            velocity[2] *= FLOOR_FRICTION;
        }

        double[] position() {
            return position;
        }

        boolean resting() {
            return restedAt >= 0;
        }

        double restedAt() {
            return restedAt;
        }
    }
}
