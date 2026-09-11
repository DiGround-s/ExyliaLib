package net.exylia.lib.ragdoll.internal;

import net.exylia.lib.display.Rotation;
import net.exylia.lib.ragdoll.RagdollMotion;
import net.exylia.lib.ragdoll.RagdollPart;
import net.exylia.lib.ragdoll.RagdollPose;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayList;
import java.util.List;
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
    static final double REST_HEIGHT = 0.08;

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

    /** How high the middle of a body is, in blocks. What a rigid throw turns about. */
    private static final double BODY_MIDDLE = 1.0;

    /** Where a head is thrown compared with everything else. */
    static final double HEAD_LIFT_SPEED = 1.35;

    /** How often a pose is sent for a rotor, which cannot be drawn on the usual beat. */
    private static final long ROTOR_POSE_MS = 50L;

    /** The fastest a rotor may turn on that beat, in turns a second. */
    private static final double MAX_ROTOR_TURNS = 0.4 / (ROTOR_POSE_MS / 1000.0);

    /** How high above the feet a rotor's hub sits, in blocks. */
    private static final double ROTOR_HEIGHT = 2.25;

    /** How far a flying body pitches its nose up, in radians. */
    private static final double PLANE_PITCH = 0.28;

    /** How far it rolls into its turns, in radians. */
    private static final double PLANE_BANK = 0.45;

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
     * How a pose is arrived at: slowly, then quickly, then slowly.
     *
     * <p>The module used to ease every deploy out, which starts at full speed.
     * With a pose every tenth of a second and a lift of under half of one, that
     * put the limbs halfway open in the first frame &mdash; the pose did not
     * open, it appeared. Everything that is being taken from one shape to
     * another eases both ends instead, and the first frame moves almost
     * nothing.
     *
     * @param progress how far through, from 0 to 1
     * @return how far the movement has got
     */
    private static double eased(double progress) {
        double at = Math.clamp(progress, 0, 1);
        return at < 0.5
                ? 4 * at * at * at
                : 1 - Math.pow(-2 * at + 2, 3) / 2;
    }

    /**
     * The whole flight of one part: where it is and how it is turned, at every
     * moment a pose is sent.
     */
    public record Flight(long[] times, double[] x, double[] y, double[] z,
                         Rotation[] rotations, double[][] scales) {
    }

    /** A piece at its own size, which is what most poses leave it at. */
    private static final double[] SAME = {1, 1, 1};

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
            case BALLOON -> balloon(part, motion, scale, facing, random);
            case HELICOPTER -> helicopter(part, motion, scale, facing, random);
            case PLANE -> plane(part, motion, scale, facing, random);
            case FLATTEN -> flatten(part, motion, scale, facing, random);
            case MELT -> melt(part, motion, scale, facing, random);
            // Every piece but the head is placed by the builder, which is the
            // only thing that knows which piece of the word it is. The head
            // watches from above.
            case SIGN -> signHead(part, motion, scale, facing);
            case THROWN -> thrown(part, motion, scale, facing);
            // Only as far as the last frame. What happens after that is worked
            // out per piece, by the pieces, from how each one was moving.
            case ANIMATE -> animated(part, motion, scale, facing);
        };
    }

    // --------------------------------------------------------- the choreography

    /**
     * How often a choreographed body is sampled: every tick.
     *
     * <p>As fine as the client is ever told anything, and the only beat on
     * which a curve with an overshoot in it still has its overshoot. The poses
     * a limb holds still or moves in a straight line through are thinned out
     * again before anything is sent, so the beat costs packets only where the
     * movement needs them.
     */
    public static final long FRAME_MS = 50L;

    /** A ceiling, so a thirty-second dance is still a finite number of poses. */
    private static final int MAX_FRAMES = 600;

    /** Stands for {@code intact}, and then follows the frames. */
    private static Flight animated(RagdollPart part, RagdollMotion motion, double scale,
                                   Rotation facing) {
        net.exylia.lib.ragdoll.RagdollAnimation animation = motion.animation();
        long intact = motion.intactMillis();
        long end = Math.min(motion.lifeMillis(), motion.finishAt());
        return sample(frames(intact, end), intact, elapsed -> {
            RagdollRig.Placed placed = RagdollRig.place(part,
                    animation.at(Math.round(elapsed * 1000)), scale, facing, elapsed);
            double grown = placed.size() / scale;
            return new Step(new double[]{placed.x(), placed.y(), placed.z()},
                    placed.rotation(), new double[]{grown, grown, grown});
        });
    }

    /** Zero, and then every beat from {@code from} to {@code end}, both included. */
    static long[] frames(long from, long end) {
        long moving = Math.max(0, end - from);
        long beat = FRAME_MS;
        if (moving / beat + 2 > MAX_FRAMES) {
            beat = (long) Math.ceil((double) moving / (MAX_FRAMES - 2) / FRAME_MS) * FRAME_MS;
        }
        List<Long> times = new ArrayList<>();
        times.add(0L);
        for (long at = from; at < end; at += beat) {
            if (at > 0) {
                times.add(at);
            }
        }
        times.add(Math.max(end, from));
        long[] packed = new long[times.size()];
        for (int index = 0; index < packed.length; index++) {
            packed[index] = times.get(index);
        }
        return packed;
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
        double swung = swing(part, motion.open());
        double[] open = opened(part, motion, scale, facing, swung);
        Rotation opening = Rotation.around(Rotation.Axis.Z, swung);
        boolean knocked = motion.pose() == RagdollPose.KNOCKED;
        double phase = part.ordinal() * 1.1;

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
                // Eased at both ends: a body is taken off the ground, opened,
                // and settles into the pose. Eased out alone starts at full
                // speed, which at this frame rate is a pose that appears.
                double eased = eased((double) (at - intact) / motion.liftMillis());
                double[] here = {
                        standing[0] + (open[0] - standing[0]) * eased,
                        standing[1] + (open[1] - standing[1]) * eased,
                        standing[2] + (open[2] - standing[2]) * eased};
                double hanging = at <= lifted ? 0 : (at - lifted) / 1000.0;
                // A body held perfectly still reads as frozen, not as held.
                // This is the whole difference, and it is one sine wave.
                double breath = Math.sin(hanging * 2.3 + phase) * 0.05 * scale * eased;
                double sway = Math.sin(hanging * 1.6 + phase) * 0.07 * eased;
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
                y[index] = turned[1] + breath;
                z[index] = turned[2];
                rotations[index] = Rotation.around(Rotation.Axis.Z, swung * eased + sway)
                        .then(Rotation.around(Rotation.Axis.Y, yaw));
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
                    : opening
                            .then(Rotation.around(Rotation.Axis.Y, releaseYaw))
                            .then(tumble(axis, turns, falling));
        }
        double[][] scales = new double[poses][];
        java.util.Arrays.fill(scales, SAME);
        return new Flight(times, x, y, z, rotations, scales);
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
        double held = Math.hypot(standing[0], standing[2]);
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
            // Opens out from where the piece was standing rather than starting
            // wide: a ring that exists from the first frame is six pieces
            // teleporting, which is what this module is here not to do.
            double opened = held + motion.open() * Math.min(1, progress * 2.5);
            // Squared, so it hangs wide for most of the climb and closes on the
            // point at the end. A radius closing at one rate reads as a drain.
            double drawn = opened * (1 - progress) * (1 - progress);
            return new Step(new double[]{
                    Math.cos(angle) * drawn,
                    standing[1] + (motion.rise() + 2.0) * progress,
                    Math.sin(angle) * drawn},
                    tumble(axis, turns, elapsed)
                            .then(Rotation.around(Rotation.Axis.Y, angle)));
        });
    }

    // --------------------------------------------------------------- the swell

    /**
     * The head swells until it is far too big, and bursts.
     *
     * <p>Everything else stands there and waits for it, which is the whole
     * joke: the body has not been told yet. When the head goes, so does the
     * rest, all at once and outwards.
     */
    private static Flight balloon(RagdollPart part, RagdollMotion motion, double scale,
                                  Rotation facing, RandomGenerator random) {
        double[] standing = standing(part, scale, facing);
        boolean head = part == RagdollPart.HEAD;

        double angle = outwards(standing, random) + random.nextDouble(-1, 1) * motion.spread();
        double vary = 1 + random.nextDouble(-1, 1) * motion.spread();
        double[] velocity = {
                Math.cos(angle) * motion.speed() * vary,
                motion.up() * vary,
                Math.sin(angle) * motion.speed() * vary};
        double[] axis = tumbleAxis(random);
        double turns = spinRate(motion, random);
        Fall fall = new Fall(motion, scale, standing, velocity);

        double swelling = motion.liftMillis() / 1000.0;
        double popAt = swelling + motion.hangMillis() / 1000.0;
        double sway = random.nextDouble(-1, 1);

        return sample(times(motion, motion.intactMillis()), motion.intactMillis(), elapsed -> {
            if (elapsed >= popAt) {
                fall.to(elapsed - popAt);
                // The head is not thrown anywhere. It stopped existing.
                return head
                        ? new Step(new double[]{standing[0], standing[1], standing[2]},
                                Rotation.NONE, new double[]{0.02, 0.02, 0.02})
                        : new Step(fall.position(),
                                fall.resting() && motion.settle()
                                        ? tumble(axis, turns, fall.restedAt())
                                        : tumble(axis, turns, elapsed - popAt));
            }
            if (!head) {
                // A body that knows something is wrong: it shifts its weight
                // and does nothing else.
                double unease = Math.sin(elapsed * 7) * 0.02 * scale;
                return new Step(new double[]{standing[0] + unease, standing[1], standing[2]},
                        Rotation.around(Rotation.Axis.Y, unease * 2));
            }
            double eased = eased(elapsed / Math.max(0.05, swelling));
            double size = 1 + (motion.swell() - 1) * eased;
            if (elapsed > swelling) {
                // Barely moving, and the reason the pose works. A head held
                // perfectly still at four times its size reads as a bug; the
                // same head breathing reads as a head about to go.
                size *= 1 + 0.05 * Math.sin((elapsed - swelling) * 22);
            }
            return new Step(new double[]{
                    standing[0],
                    // Lifted by exactly half its own growth, so the bottom of
                    // it stays on the neck however large it gets.
                    standing[1] + (size - 1) * RagdollPart.HEAD.blockHeight() / 2 * scale,
                    standing[2]},
                    Rotation.around(Rotation.Axis.Z, sway * 0.12 * eased),
                    new double[]{size, size, size});
        });
    }

    // --------------------------------------------------------------- the rotor

    /**
     * The arms go out and the whole of them becomes the rotor.
     *
     * <p>The first version of this took the arms off the shoulders and flew
     * them round a hub above the head. It was correct and it looked like two
     * planks orbiting a corpse, because a limb that leaves the body it belongs
     * to stops reading as a limb. So the body <em>is</em> the rotor: arms out
     * at the shoulders, stretched into blades, and the whole thing turning fast
     * enough to lift itself. Everything stays joined to everything.
     *
     * <p>Drawn on a finer beat than the rest of the module, because the client
     * turns a display the short way round and half a turn per pose is the
     * ceiling: at a tenth of a second that caps a rotor at the speed of a desk
     * fan.
     */
    private static Flight helicopter(RagdollPart part, RagdollMotion motion, double scale,
                                     Rotation facing, RandomGenerator random) {
        double[] standing = standing(part, scale, facing);
        double[] ahead = heading(motion, facing);
        boolean blade = part == RagdollPart.ARM_LEFT || part == RagdollPart.ARM_RIGHT;
        boolean leg = part == RagdollPart.LEG_LEFT || part == RagdollPart.LEG_RIGHT;

        // An arm is three quarters of a block long. Left at that, a rotor is a
        // pair of stumps; stretched, it is a blade with some span to it.
        double span = 1.5 + motion.open() * 1.6;
        double stretch = span / RagdollPart.ARM_LEFT.blockHeight();
        // Arms straight out at the shoulder, legs turned inwards underneath.
        double swung = blade ? swing(part, 1.0) : leg ? -swing(part, 0.3) : 0;
        double hang = part.fromJoint() * scale;

        double rotor = Math.min(Math.abs(motion.spin()) < 0.1 ? 4.0 : Math.abs(motion.spin()),
                MAX_ROTOR_TURNS);
        double lift = motion.liftMillis() / 1000.0;

        return sample(times(motion, motion.intactMillis(), ROTOR_POSE_MS), motion.intactMillis(),
                elapsed -> {
            double eased = eased(elapsed / Math.max(0.05, lift));
            double flying = Math.max(0, elapsed - lift);
            // It winds up rather than starting at speed: a rotor already at
            // full speed when the body is still standing has no take-off in it.
            double spun = rotor * Math.PI * 2 * (elapsed - lift * (1 - eased) * 0.5) * eased;
            double climbed = motion.rise() * scale * eased + motion.up() * flying;
            double gone = motion.speed() * Math.pow(flying, 1.15);

            double angle = swung * eased;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            // The blade grows out of the shoulder as it deploys, so its middle
            // moves out with it rather than starting a metre away from the arm.
            double reach = hang * (blade ? 1 + (stretch - 1) * eased : 1);
            double[] local = {
                    part.jointX() * scale - reach * sin,
                    part.jointY() * scale + reach * cos,
                    0};
            float[] faced = facing.apply(new float[]{
                    (float) local[0], (float) local[1], (float) local[2]});
            double[] carried = spun(new double[]{faced[0], faced[1], faced[2]}, spun);

            return new Step(new double[]{
                    carried[0] + ahead[0] * gone,
                    Math.max(REST_HEIGHT * scale, carried[1] + climbed),
                    carried[2] + ahead[2] * gone},
                    Rotation.around(Rotation.Axis.Z, angle)
                            .then(Rotation.around(Rotation.Axis.Y, spun)),
                    blade ? new double[]{1, 1 + (stretch - 1) * eased, 1} : SAME);
        });
    }

    // ---------------------------------------------------------------- the wing

    /**
     * Arms out as wings, nose up, and away.
     *
     * <p>The bank is the whole effect. A body sliding through the air on a
     * straight line is a body being moved; the same body rolling into its turn
     * is a body flying, and it is one sine wave.
     */
    private static Flight plane(RagdollPart part, RagdollMotion motion, double scale,
                                Rotation facing, RandomGenerator random) {
        double[] standing = standing(part, scale, facing);
        double wingLength = 1.2 + motion.open() * 1.6;
        boolean wing = part == RagdollPart.ARM_LEFT || part == RagdollPart.ARM_RIGHT;
        double[] out = wing(part, motion, scale, wingLength);
        double[] ahead = heading(motion, facing);
        double swung = swing(part, part == RagdollPart.ARM_LEFT
                || part == RagdollPart.ARM_RIGHT ? 1.0 : 0.0);
        Rotation opening = Rotation.around(Rotation.Axis.Z, swung);
        double lift = motion.liftMillis() / 1000.0;
        double bank = motion.turns() == 0 ? 0.4 : motion.turns();

        return sample(times(motion, motion.intactMillis()), motion.intactMillis(), elapsed -> {
            double eased = eased(elapsed / Math.max(0.05, lift));
            double flying = Math.max(0, elapsed - lift);
            double roll = Math.sin(elapsed * bank * Math.PI * 2) * PLANE_BANK * eased;
            // The nose comes up as the wings come out, so the pose arrives all
            // at once instead of as two separate decisions.
            Rotation attitude = Rotation.around(Rotation.Axis.X, PLANE_PITCH * eased)
                    .then(Rotation.around(Rotation.Axis.Z, roll));
            float[] local = attitude.apply(new float[]{
                    (float) (standing[0] + (out[0] - standing[0]) * eased),
                    (float) (standing[1] + (out[1] - standing[1]) * eased),
                    (float) (standing[2] + (out[2] - standing[2]) * eased)});
            double span = wingLength / RagdollPart.ARM_LEFT.blockHeight();
            double[] grown = wing ? new double[]{1, 1 + (span - 1) * eased, 1} : SAME;
            double climbed = motion.rise() * scale * eased + motion.up() * flying;
            double gone = motion.speed() * Math.pow(flying, 1.1);
            return new Step(new double[]{
                    local[0] + ahead[0] * gone,
                    // A dive is a negative climb, and a dive that is not
                    // stopped at the floor is a body inside it.
                    Math.max(REST_HEIGHT * scale, local[1] + climbed),
                    local[2] + ahead[2] * gone},
                    partial(opening, eased).then(attitude), grown);
        });
    }

    /** Where a part sits on a body holding itself like an aeroplane. */
    private static double[] wing(RagdollPart part, RagdollMotion motion, double scale,
                                 double wingLength) {
        // The wing root meets the chest and the rest of it is span, so a longer
        // wing grows outwards instead of sliding off the shoulder.
        double open = (0.28 + wingLength / 2) * scale;
        return switch (part) {
            case HEAD -> new double[]{0, part.blockCentreY() * scale, 0.1 * scale};
            case TORSO -> new double[]{0, part.blockCentreY() * scale, 0};
            case ARM_RIGHT -> new double[]{-open,
                    part.blockCentreY() * scale + 0.1 * scale, 0};
            case ARM_LEFT -> new double[]{open,
                    part.blockCentreY() * scale + 0.1 * scale, 0};
            // Legs together and trailing: a tail, not a stance.
            case LEG_RIGHT -> new double[]{-0.06 * scale,
                    part.blockCentreY() * scale + 0.12 * scale, -0.18 * scale};
            case LEG_LEFT -> new double[]{0.06 * scale,
                    part.blockCentreY() * scale + 0.12 * scale, -0.18 * scale};
        };
    }

    // ---------------------------------------------------------------- the floor

    /** Driven straight down and left flat, in its own colours. */
    private static Flight flatten(RagdollPart part, RagdollMotion motion, double scale,
                                  Rotation facing, RandomGenerator random) {
        double[] standing = standing(part, scale, facing);
        double rest = REST_HEIGHT * scale;
        double slam = motion.liftMillis() / 1000.0;
        double spread = 1 + motion.open();
        double wobble = random.nextDouble(-1, 1);

        return sample(times(motion, motion.intactMillis()), motion.intactMillis(), elapsed -> {
            double driven = Math.clamp(elapsed / Math.max(0.05, slam), 0, 1);
            // Eased in: it is not falling, it is being hit.
            double eased = driven * driven * driven;
            double flat = 1 - (1 - motion.squash()) * eased;
            double wide = 1 + (spread - 1) * eased;
            return new Step(new double[]{
                    standing[0] * (1 + 0.45 * eased),
                    standing[1] + (rest - standing[1]) * eased,
                    standing[2] * (1 + 0.45 * eased) + wobble * 0.05 * eased},
                    Rotation.around(Rotation.Axis.Y, wobble * 0.35 * eased),
                    new double[]{wide, flat, wide});
        });
    }

    /** Sinks where it stands, and is gone. */
    private static Flight melt(RagdollPart part, RagdollMotion motion, double scale,
                               Rotation facing, RandomGenerator random) {
        double[] standing = standing(part, scale, facing);
        double rest = REST_HEIGHT * scale;
        double over = Math.max(0.2, motion.hangMillis() / 1000.0);
        // Legs go first and the head goes last, so it sinks rather than
        // deflating: a body that loses all six pieces at once is a puddle,
        // and one that loses them from the feet up is a person going under.
        double late = switch (part) {
            case LEG_LEFT, LEG_RIGHT -> 0.0;
            case ARM_LEFT, ARM_RIGHT -> 0.12;
            case TORSO -> 0.2;
            case HEAD -> 0.35;
        };
        double lean = random.nextDouble(-1, 1);

        return sample(times(motion, motion.intactMillis()), motion.intactMillis(), elapsed -> {
            double sunk = Math.clamp((elapsed / over - late) / (1 - late), 0, 1);
            double flat = 1 - (1 - motion.squash()) * Math.pow(sunk, 1.2);
            double wide = 1 + motion.open() * sunk;
            return new Step(new double[]{
                    standing[0] * (1 + 0.2 * sunk),
                    standing[1] + (rest - standing[1]) * Math.pow(sunk, 1.5),
                    standing[2] * (1 + 0.2 * sunk)},
                    Rotation.around(Rotation.Axis.Y, lean * 0.5 * sunk),
                    new double[]{wide, flat, wide});
        });
    }

    /**
     * Which way a body travels.
     *
     * <p>Where the file said, if it said; otherwise away from whoever did it.
     * The two are not interchangeable: everything a sequence draws is written
     * on the world's own axes, and a body that leaves on a bearing taken from
     * the kill instead leaves in a direction nothing else in the effect knows
     * about.
     */
    private static double[] heading(RagdollMotion motion, Rotation facing) {
        if (!motion.aimed()) {
            return forward(facing);
        }
        double radians = Math.toRadians(motion.heading());
        return new double[]{Math.cos(radians), 0, Math.sin(radians)};
    }

    /** Which way a body is looking, as a unit vector pointing away from it. */
    private static double[] forward(Rotation facing) {
        float[] ahead = facing.apply(new float[]{0f, 0f, -1f});
        return new double[]{ahead[0], ahead[1], ahead[2]};
    }

    // --------------------------------------------------------------- the throw

    /**
     * Sent somewhere, in one piece.
     *
     * <p>Everything else in this module takes a body apart. This carries it:
     * every piece keeps its place in the body and the whole of it turns about
     * its own middle, so what leaves is a person tumbling away rather than six
     * boxes leaving in six directions.
     *
     * <p>The tumble axis is worked out from the direction of travel rather than
     * drawn at random, and that is not tidiness &mdash; a rigid body needs
     * <em>every</em> piece turning about the same axis, and a random one per
     * piece is exactly the cloud this pose exists to avoid.
     */
    private static Flight thrown(RagdollPart part, RagdollMotion motion, double scale,
                                 Rotation facing) {
        double[] standing = standing(part, scale, facing);
        double[] ahead = heading(motion, facing);
        double[] middle = {0, BODY_MIDDLE * scale, 0};
        // End over end about the axis across its own path, with a little yaw on
        // top so it is not a wheel.
        double[] axis = {-ahead[2], 0.25, ahead[0]};
        double length = Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
        for (int index = 0; index < 3; index++) {
            axis[index] /= length;
        }
        double turns = Math.clamp(motion.spin(), -MAX_TURNS_PER_SECOND, MAX_TURNS_PER_SECOND);
        double lift = motion.liftMillis() / 1000.0;

        return sample(times(motion, motion.intactMillis()), motion.intactMillis(), elapsed -> {
            // Eased in over the throw, so it is launched rather than already
            // moving at full speed the instant the blow lands.
            double thrown = elapsed < lift
                    ? elapsed * elapsed / Math.max(1e-4, 2 * lift)
                    : elapsed - lift / 2;
            Rotation turned = tumble(axis, turns, elapsed);
            float[] carried = turned.apply(new float[]{
                    (float) (standing[0] - middle[0]),
                    (float) (standing[1] - middle[1]),
                    (float) (standing[2] - middle[2])});
            double drop = 0.5 * motion.gravity() * elapsed * elapsed;
            return new Step(new double[]{
                    middle[0] + carried[0] + ahead[0] * motion.speed() * thrown,
                    // No rise: a thrown body leaves from where it was
                    // standing. Lifting it first is a teleport with a throw
                    // after it.
                    Math.max(REST_HEIGHT * scale,
                            middle[1] + carried[1] + motion.up() * thrown - drop),
                    middle[2] + carried[2] + ahead[2] * motion.speed() * thrown},
                    turned);
        });
    }

    // ---------------------------------------------------------------- the sign

    /**
     * The head, while the rest of them is being read.
     *
     * <p>It goes up above the word and turns there. Somebody has to be looking
     * at it, and the only face in the effect belongs to the person the word is
     * about.
     */
    private static Flight signHead(RagdollPart part, RagdollMotion motion, double scale,
                                   Rotation facing) {
        double[] standing = standing(part, scale, facing);
        double top = (motion.rise() + motion.letters() + 0.55) * scale;
        double lift = motion.liftMillis() / 1000.0;
        long released = motion.intactMillis() + motion.liftMillis() + motion.hangMillis();
        double falls = Math.max(0, motion.lifeMillis() - released) / 1000.0;

        return sample(times(motion, motion.intactMillis()), motion.intactMillis(), elapsed -> {
            double eased = eased(elapsed / Math.max(0.05, lift));
            double after = Math.max(0, elapsed - (motion.lifeMillis() - released < 0 ? elapsed
                    : (released - motion.intactMillis()) / 1000.0));
            double drop = falls <= 0 ? 0 : 0.5 * motion.gravity() * after * after;
            return new Step(new double[]{
                    standing[0],
                    Math.max(REST_HEIGHT * scale, standing[1] + (top - standing[1]) * eased - drop),
                    standing[2]},
                    Rotation.around(Rotation.Axis.Y, elapsed * 0.7));
        });
    }

    /**
     * Where one piece of a body goes to be part of a letter.
     *
     * <p>Called once per piece by the builder, because the builder is what
     * knows how many pieces there are and which one this is. The piece is
     * carried to its stroke, stretched along it, held while the word is read,
     * and dropped.
     *
     * @param motion   what the file asked for
     * @param scale    how big the body is
     * @param facing   which way the sign faces
     * @param standing where this piece started
     * @param to       the stroke it belongs to
     * @param cellSize the piece's own size, in blocks
     * @return every pose it passes through
     */
    public static Flight signCell(RagdollMotion motion, double scale, Rotation facing,
                                  double[] standing, RagdollSign.Placement to, float[] cellSize) {
        double lift = motion.liftMillis() / 1000.0;
        long released = Math.min(motion.lifeMillis(),
                motion.intactMillis() + motion.liftMillis() + motion.hangMillis());

        float[] target = facing.apply(new float[]{
                (float) (to.x() * scale),
                (float) ((motion.rise() + to.y()) * scale),
                0f});
        double yaw = 2 * Math.atan2(facing.y(), facing.w());
        // The piece is a box standing on its own end, so a stroke running east
        // is that box rolled a quarter turn back from upright.
        double roll = to.angle() - Math.PI / 2;
        double[] grown = {
                Math.max(0.05, to.thickness() * scale / Math.max(1e-4, cellSize[0])),
                Math.max(0.05, to.length() * scale / Math.max(1e-4, cellSize[1])),
                Math.max(0.05, to.thickness() * scale / Math.max(1e-4, cellSize[2]))};

        long[] times = times(motion, motion.intactMillis());
        int poses = times.length;
        double[] x = new double[poses];
        double[] y = new double[poses];
        double[] z = new double[poses];
        Rotation[] rotations = new Rotation[poses];
        double[][] scales = new double[poses][];
        Fall fall = null;

        for (int index = 0; index < poses; index++) {
            long at = times[index];
            if (at <= motion.intactMillis()) {
                x[index] = standing[0];
                y[index] = standing[1];
                z[index] = standing[2];
                rotations[index] = Rotation.NONE;
                scales[index] = SAME;
                continue;
            }
            if (at < released) {
                double eased = eased((at - motion.intactMillis()) / 1000.0
                        / Math.max(0.05, lift));
                x[index] = standing[0] + (target[0] - standing[0]) * eased;
                y[index] = standing[1] + (target[1] - standing[1]) * eased;
                z[index] = standing[2] + (target[2] - standing[2]) * eased;
                rotations[index] = Rotation.around(Rotation.Axis.Z, roll * eased)
                        .then(Rotation.around(Rotation.Axis.Y, yaw * eased));
                scales[index] = new double[]{
                        1 + (grown[0] - 1) * eased,
                        1 + (grown[1] - 1) * eased,
                        1 + (grown[2] - 1) * eased};
                continue;
            }
            if (fall == null) {
                fall = new Fall(motion, scale,
                        new double[]{x[index - 1], y[index - 1], z[index - 1]},
                        new double[]{0, 0, 0});
            }
            fall.to((at - released) / 1000.0);
            x[index] = fall.position()[0];
            y[index] = fall.position()[1];
            z[index] = fall.position()[2];
            rotations[index] = rotations[index - 1];
            scales[index] = grown;
        }
        return new Flight(times, x, y, z, rotations, scales);
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
     * How far a limb is swung open, in radians about its own joint.
     *
     * <p>{@code open} is an angle and not a distance, and that is the whole
     * fix: a limb moved half a block out to the side comes away from the
     * shoulder it belongs to, and the gap is the first thing anybody sees. A
     * limb turned about its shoulder cannot come away from it.
     */
    private static double swing(RagdollPart part, double open) {
        double quarter = Math.PI / 2 * Math.clamp(open, 0, 1.2);
        return switch (part) {
            case ARM_RIGHT -> -quarter;
            case ARM_LEFT -> quarter;
            // Legs go about half as far. Any more is the splits, not a stance.
            case LEG_RIGHT -> -quarter * 0.45;
            case LEG_LEFT -> quarter * 0.45;
            case HEAD, TORSO -> 0;
        };
    }

    /**
     * Where a part hangs on a body that has been lifted and opened out.
     *
     * <p>Worked out from the joint every time, so the pose and the picture
     * cannot disagree: whatever angle the limb is drawn at is the angle its
     * position was taken from.
     */
    private static double[] opened(RagdollPart part, RagdollMotion motion, double scale,
                                   Rotation facing, double angle) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double reach = part.fromJoint() * scale;
        double[] local = {
                part.jointX() * scale - reach * sin,
                part.jointY() * scale + reach * cos,
                0};
        float[] turned = facing.apply(new float[]{
                (float) local[0], (float) local[1], (float) local[2]});
        return new double[]{turned[0], turned[1] + motion.rise() * scale, turned[2]};
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
    private record Step(double[] position, Rotation rotation, double[] scale) {

        Step(double[] position, Rotation rotation) {
            this(position, rotation, SAME);
        }
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
        double[][] scales = new double[poses][];
        for (int index = 0; index < poses; index++) {
            long at = times[index];
            Step step = path.at(Math.max(0, at - from) / 1000.0);
            x[index] = step.position()[0];
            y[index] = step.position()[1];
            z[index] = step.position()[2];
            rotations[index] = step.rotation();
            scales[index] = step.scale();
        }
        return new Flight(times, x, y, z, rotations, scales);
    }

    /**
     * When poses are sent: one at zero, and then a steady beat from the moment
     * anything starts happening.
     */
    private static long[] times(RagdollMotion motion, long from) {
        return times(motion, from, POSE_MS);
    }

    /**
     * The same, on a finer beat.
     *
     * <p>A rotor is the one thing in the module that cannot be drawn on a
     * tenth of a second: the client turns a display the short way round, so a
     * blade never gets to travel more than half a turn between two poses, and
     * half a turn every tenth of a second is a ceiling of five turns a second
     * that reads as a desk fan. Halving the beat doubles the ceiling, and it is
     * two extra packets a second on one effect.
     */
    private static long[] times(RagdollMotion motion, long from, long beat) {
        long life = motion.lifeMillis();
        long moving = Math.max(0, life - from);
        int poses = Math.min(MAX_POSES, (int) (moving / beat) + 2);
        long step = Math.max(beat, poses <= 2 ? moving : moving / (poses - 2));
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
    static double[] tumbleAxis(RandomGenerator random) {
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
    static double spinRate(RagdollMotion motion, RandomGenerator random) {
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
    static Rotation tumble(double[] axis, double turns, double seconds) {
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
    static final class Fall {

        private final RagdollMotion motion;
        private final double restHeight;
        private final double[] position;
        private final double[] velocity;
        private double flown;
        private double restedAt = -1;

        Fall(RagdollMotion motion, double scale, double[] from, double[] velocity) {
            this(motion, from, velocity, REST_HEIGHT * scale);
        }

        /** The same, resting its centre at a height of its own: a head is thicker than a cell. */
        Fall(RagdollMotion motion, double[] from, double[] velocity, double restHeight) {
            this.motion = motion;
            this.restHeight = restHeight;
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
