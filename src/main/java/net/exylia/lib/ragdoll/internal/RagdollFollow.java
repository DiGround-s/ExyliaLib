package net.exylia.lib.ragdoll.internal;

import net.exylia.lib.display.Rotation;
import net.exylia.lib.ragdoll.RagdollAnimation;
import org.jetbrains.annotations.ApiStatus;

/**
 * The part of a body that nobody animated: arms that lag behind a jump, a head
 * that nods when the body lands, limbs flung out by a spin.
 *
 * <h2>Follow-through, solved as springs</h2>
 * A choreography says where the hips go. A real body does not move its arms in
 * lockstep with them: when the hips shoot upwards the arms are left behind for
 * a moment, and when the hips stop at the top the arms carry on and float. That
 * lag and overshoot is what separates an animated body from a body being moved,
 * and it is exactly what nobody wants to write by hand in forty frames.
 *
 * <p>Each loose joint is a damped spring pushed by how the hips are
 * accelerating, felt in the body's own axes so that "left" is the body's left
 * however it has turned. A spin pushes arms and legs outwards by the square of
 * how fast it is. The result is added on top of whatever the frames say, so a
 * file that raises the arms still raises them, and they wobble as they arrive.
 *
 * <p>Deterministic and free of Bukkit: the same choreography lags the same way
 * on every death.
 */
@ApiStatus.Internal
final class RagdollFollow {

    /** How stiff the springs are: a little over two swings a second. */
    private static final double OMEGA = 2 * Math.PI * 2.2;

    /** How quickly a swing dies away. Low enough to see it overshoot once. */
    private static final double DAMPING = 0.32;

    /** The step the springs are integrated at, in seconds. */
    private static final double STEP = 0.01;

    /** How far either side of a moment the hips are read to get their acceleration. */
    private static final double SPAN = 0.03;

    /** The hardest push a snap may give, in blocks a second squared. */
    private static final double MAX_ACCELERATION = 80;

    /** How far any loose joint may be thrown, in degrees. */
    private static final double MAX_DEGREES = 50;

    /** How far an arm may be pushed into the body it hangs beside, in degrees. */
    private static final double MAX_INWARDS = 14;

    private static final int ARM_RIGHT_ROLL = 0;
    private static final int ARM_LEFT_ROLL = 1;
    private static final int ARM_PITCH = 2;
    private static final int HEAD_PITCH = 3;
    private static final int HEAD_ROLL = 4;
    private static final int LEG_ROLL = 5;
    private static final int LEG_PITCH = 6;
    private static final int JOINTS = 7;

    private RagdollFollow() {
    }

    /**
     * What the loose joints add, at every moment a pose is sent.
     *
     * @param animation the choreography
     * @param times     when each pose is sent, counted from the sequence start
     * @param intact    when the choreography starts
     * @param strength  how much follow-through; 1 is a body, 2 is a cartoon
     * @return per pose, a full set of channels to add
     */
    static double[][] solve(RagdollAnimation animation, long[] times, long intact, double strength) {
        double[][] offsets = new double[times.length][RagdollRig.COUNT];
        double[] angle = new double[JOINTS];
        double[] speed = new double[JOINTS];
        double clock = 0;
        for (int frame = 0; frame < times.length; frame++) {
            double until = Math.max(0, times[frame] - intact) / 1000.0;
            while (clock < until - 1e-9) {
                double step = Math.min(STEP, until - clock);
                double[] drive = drive(animation, clock);
                for (int joint = 0; joint < JOINTS; joint++) {
                    double pull = OMEGA * OMEGA * (strength * drive[joint] - angle[joint])
                            - 2 * DAMPING * OMEGA * speed[joint];
                    speed[joint] += pull * step;
                    angle[joint] = clamp(joint, angle[joint] + speed[joint] * step);
                }
                clock += step;
            }
            double[] lag = offsets[frame];
            lag[RagdollRig.of(RagdollRig.ARM_RIGHT, RagdollRig.ROLL)] = angle[ARM_RIGHT_ROLL];
            lag[RagdollRig.of(RagdollRig.ARM_LEFT, RagdollRig.ROLL)] = angle[ARM_LEFT_ROLL];
            lag[RagdollRig.of(RagdollRig.ARM_RIGHT, RagdollRig.PITCH)] = angle[ARM_PITCH];
            lag[RagdollRig.of(RagdollRig.ARM_LEFT, RagdollRig.PITCH)] = angle[ARM_PITCH];
            lag[RagdollRig.of(RagdollRig.HEAD, RagdollRig.PITCH)] = angle[HEAD_PITCH];
            lag[RagdollRig.of(RagdollRig.HEAD, RagdollRig.ROLL)] = angle[HEAD_ROLL];
            lag[RagdollRig.of(RagdollRig.LEG_RIGHT, RagdollRig.ROLL)] = angle[LEG_ROLL];
            lag[RagdollRig.of(RagdollRig.LEG_LEFT, RagdollRig.ROLL)] = angle[LEG_ROLL];
            lag[RagdollRig.of(RagdollRig.LEG_RIGHT, RagdollRig.PITCH)] = angle[LEG_PITCH];
            lag[RagdollRig.of(RagdollRig.LEG_LEFT, RagdollRig.PITCH)] = angle[LEG_PITCH];
        }
        return offsets;
    }

    /**
     * Where each loose joint is being pushed towards, in degrees, at one moment.
     *
     * <p>Every sign is a body being left behind: hips accelerating upwards drop
     * the arms and nod the head, hips accelerating forwards throw the arms and
     * the head back, hips accelerating to the body's left swing everything to
     * its right. A spin flings arms and legs outwards.
     */
    private static double[] drive(RagdollAnimation animation, double seconds) {
        double[] before = animation.at(Math.round((seconds - SPAN) * 1000));
        double[] now = animation.at(Math.round(seconds * 1000));
        double[] after = animation.at(Math.round((seconds + SPAN) * 1000));
        double squared = SPAN * SPAN;
        // The body's own axes: X is its left, Y up, Z forward.
        float[] felt = {
                (float) (-(after[RagdollRig.RIGHT] - 2 * now[RagdollRig.RIGHT] + before[RagdollRig.RIGHT]) / squared),
                (float) ((after[RagdollRig.UP] - 2 * now[RagdollRig.UP] + before[RagdollRig.UP]) / squared),
                (float) ((after[RagdollRig.FORWARD] - 2 * now[RagdollRig.FORWARD] + before[RagdollRig.FORWARD]) / squared)};
        Rotation hips = RagdollRig.centred(now[RagdollRig.FLIP], now[RagdollRig.TURN], now[RagdollRig.LEAN]);
        float[] body = new Rotation(-hips.x(), -hips.y(), -hips.z(), hips.w()).apply(felt);
        double left = Math.clamp(body[0], -MAX_ACCELERATION, MAX_ACCELERATION);
        double up = Math.clamp(body[1], -MAX_ACCELERATION, MAX_ACCELERATION);
        double forward = Math.clamp(body[2], -MAX_ACCELERATION, MAX_ACCELERATION);
        double spin = Math.toRadians(after[RagdollRig.TURN] - before[RagdollRig.TURN]) / (2 * SPAN);
        double flung = Math.min(MAX_DEGREES, spin * spin * 0.9);

        double[] drive = new double[JOINTS];
        drive[ARM_RIGHT_ROLL] = -0.45 * up + 0.6 * left + flung;
        drive[ARM_LEFT_ROLL] = -0.45 * up - 0.6 * left + flung;
        drive[ARM_PITCH] = -0.6 * forward;
        drive[HEAD_PITCH] = -0.35 * forward + 0.3 * up;
        drive[HEAD_ROLL] = 0.4 * left;
        drive[LEG_ROLL] = flung * 0.45;
        drive[LEG_PITCH] = -0.25 * forward;
        return drive;
    }

    private static double clamp(int joint, double degrees) {
        double inwards = joint == ARM_RIGHT_ROLL || joint == ARM_LEFT_ROLL || joint == LEG_ROLL
                ? MAX_INWARDS : MAX_DEGREES;
        return Math.clamp(degrees, -inwards, MAX_DEGREES);
    }
}
