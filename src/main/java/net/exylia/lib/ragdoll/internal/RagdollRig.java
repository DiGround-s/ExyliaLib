package net.exylia.lib.ragdoll.internal;

import net.exylia.lib.display.Rotation;
import net.exylia.lib.ragdoll.RagdollPart;
import org.jetbrains.annotations.ApiStatus;

/**
 * A body as a skeleton: where every part is once its joints have been turned.
 *
 * <h2>Why a skeleton</h2>
 * Every other pose in this module moves each part on its own curve and trusts
 * the curves to agree. That is fine for a body coming apart and hopeless for a
 * body that dances: an arm that is moved rather than carried drifts off the
 * shoulder the moment the chest turns, and the gap is the first thing anybody
 * sees. Here nothing is moved. The hips are placed, the chest hangs off the
 * hips, the head and the arms hang off the chest and the legs off the hips, so
 * a turn anywhere carries everything below it and no joint can open.
 *
 * <h2>The body's own axes</h2>
 * Worked out facing the way the body faces, and turned into the world last:
 * {@code X} is the body's left, {@code Y} is up and {@code Z} is forward, towards
 * whoever it is facing. Every number a file writes is in those terms, so the
 * same choreography reads the same from wherever the killer was standing.
 *
 * <h2>Channels</h2>
 * A pose is a flat array of doubles, one per channel, because a timeline
 * interpolates numbers and nothing else. Angles are in degrees and stay
 * unwrapped: 720 is two turns, which is how a spin is written.
 *
 * <p>Free of Bukkit, so where a hand ends up can be asserted.
 */
@ApiStatus.Internal
public final class RagdollRig {

    /** Where the hips are moved to: to the body's right, up and forward, in blocks. */
    public static final int RIGHT = 0;
    public static final int UP = 1;
    public static final int FORWARD = 2;

    /** The whole body turned about the hips: forwards, to its left, to its right. */
    public static final int FLIP = 3;
    public static final int TURN = 4;
    public static final int LEAN = 5;

    /** How big the whole body is, as a multiple of its own size. */
    public static final int SIZE = 6;

    /** How hard the whole body trembles, in blocks. */
    public static final int SHAKE = 7;

    /** The joints, in the order their channels are laid out. */
    public static final int HEAD = 0;
    public static final int BODY = 1;
    public static final int ARM_RIGHT = 2;
    public static final int ARM_LEFT = 3;
    public static final int LEG_RIGHT = 4;
    public static final int LEG_LEFT = 5;

    /** What each joint has. */
    public static final int PITCH = 0;
    public static final int YAW = 1;
    public static final int ROLL = 2;
    public static final int OUT = 3;
    public static final int RAISE = 4;
    public static final int AHEAD = 5;
    public static final int SCALE = 6;

    private static final int FIRST_JOINT = 8;
    private static final int PER_JOINT = 7;

    /** How many channels a pose has. */
    public static final int COUNT = FIRST_JOINT + 6 * PER_JOINT;

    private static final float P = RagdollPart.PIXEL;

    private RagdollRig() {
    }

    /** The channel one joint keeps one of its numbers in. */
    public static int of(int joint, int field) {
        return FIRST_JOINT + joint * PER_JOINT + field;
    }

    /** Whether a channel is an angle, which is what a spin can go wrong on. */
    public static boolean isAngle(int channel) {
        if (channel == FLIP || channel == TURN || channel == LEAN) {
            return true;
        }
        if (channel < FIRST_JOINT) {
            return false;
        }
        int field = (channel - FIRST_JOINT) % PER_JOINT;
        return field == PITCH || field == YAW || field == ROLL;
    }

    /** A body standing still, at its own size. */
    public static double[] standing() {
        double[] pose = new double[COUNT];
        pose[SIZE] = 1;
        for (int joint = HEAD; joint <= LEG_LEFT; joint++) {
            pose[of(joint, SCALE)] = 1;
        }
        return pose;
    }

    /**
     * Where one part is, how it is turned and how big it is.
     *
     * @param part    which part
     * @param pose    every channel
     * @param scale   how big the body is; 1 is player-sized
     * @param facing  which way the body faces in the world
     * @param seconds how far into the choreography, for the tremble
     * @return the part's centre, relative to the feet, in world axes
     */
    public static Placed place(RagdollPart part, double[] pose, double scale, Rotation facing,
                               double seconds) {
        double size = scale * pose[SIZE];
        double legs = Math.max(pose[of(LEG_RIGHT, SCALE)], pose[of(LEG_LEFT, SCALE)]);

        double[] root = {-pose[RIGHT], 12 * P * size * legs + pose[UP], pose[FORWARD]};
        double shakeYaw = 0;
        double shake = pose[SHAKE];
        if (shake != 0) {
            // Several sines at frequencies that never line up, so it trembles
            // rather than oscillates. The same for every part, because a body
            // that shakes apart is a different effect.
            double t = seconds;
            root[0] += shake * (0.6 * Math.sin(37.1 * t) + 0.4 * Math.sin(23.3 * t + 1.3));
            root[1] += shake * 0.5 * (0.6 * Math.sin(41.7 * t + 0.7) + 0.4 * Math.sin(19.9 * t + 2.2));
            root[2] += shake * (0.6 * Math.sin(29.9 * t + 2.1) + 0.4 * Math.sin(45.2 * t + 0.4));
            shakeYaw = shake * 60 * Math.sin(33.3 * t + 1.9);
        }
        Rotation hips = centred(pose[FLIP], pose[TURN] + shakeYaw, pose[LEAN]);

        double[] centre;
        Rotation turned;
        double grown;
        if (part == RagdollPart.LEG_RIGHT || part == RagdollPart.LEG_LEFT) {
            int joint = part == RagdollPart.LEG_LEFT ? LEG_LEFT : LEG_RIGHT;
            double side = part == RagdollPart.LEG_LEFT ? 1 : -1;
            grown = pose[of(joint, SCALE)];
            double[] hip = plus(root, turn(hips, plus(
                    new double[]{side * 2 * P * size * grown, 0, 0}, moved(pose, joint, side))));
            turned = limb(pose, joint, side).then(hips);
            centre = plus(hip, turn(turned, new double[]{0, -6 * P * size * grown, 0}));
        } else {
            double chest = pose[of(BODY, SCALE)];
            Rotation body = centred(pose, BODY).then(hips);
            double[] waist = plus(root, turn(hips, moved(pose, BODY, -1)));
            switch (part) {
                case TORSO -> {
                    grown = chest;
                    turned = body;
                    centre = plus(waist, turn(body, new double[]{0, 6 * P * size * chest, 0}));
                }
                case HEAD -> {
                    grown = pose[of(HEAD, SCALE)];
                    double[] neck = plus(waist, turn(body, plus(
                            new double[]{0, 12 * P * size * chest, 0}, moved(pose, HEAD, -1))));
                    turned = centred(pose, HEAD).then(body);
                    centre = plus(neck, turn(turned, new double[]{0, 4 * P * size * grown, 0}));
                }
                default -> {
                    int joint = part == RagdollPart.ARM_LEFT ? ARM_LEFT : ARM_RIGHT;
                    double side = part == RagdollPart.ARM_LEFT ? 1 : -1;
                    grown = pose[of(joint, SCALE)];
                    // The shoulder sits on the edge of the chest and two pixels
                    // down from its top, where the vanilla model turns an arm.
                    double[] shoulder = plus(waist, turn(body, plus(new double[]{
                            side * (4 * chest + 2 * grown) * P * size,
                            (12 * chest - 2 * grown) * P * size,
                            0}, moved(pose, joint, side))));
                    turned = limb(pose, joint, side).then(body);
                    centre = plus(shoulder, turn(turned, new double[]{0, -4 * P * size * grown, 0}));
                }
            }
        }
        double[] world = turn(facing, centre);
        return new Placed(world[0], world[1], world[2], turned.then(facing), size * grown);
    }

    /** One part, placed. */
    public record Placed(double x, double y, double z, Rotation rotation, double size) {
    }

    /**
     * A turn of the hips, the chest or the head.
     *
     * <p>Pitched first, rolled second and turned last, so a body that is
     * flipped over and turned is a body lying down and pointing somewhere else,
     * which is what anybody writing both means.
     */
    private static Rotation centred(double pitch, double yaw, double roll) {
        return Rotation.around(Rotation.Axis.X, Math.toRadians(pitch))
                .then(Rotation.around(Rotation.Axis.Z, Math.toRadians(roll)))
                .then(Rotation.around(Rotation.Axis.Y, Math.toRadians(yaw)));
    }

    private static Rotation centred(double[] pose, int joint) {
        return centred(pose[of(joint, PITCH)], pose[of(joint, YAW)], pose[of(joint, ROLL)]);
    }

    /**
     * A turn of an arm or a leg, written the same way on either side.
     *
     * <p>Pitch swings it forwards and up, roll raises it away from the body and
     * yaw swings a raised limb outwards. Mirrored here rather than in the file,
     * so {@code arms=0,0,90} is both arms out and not one arm out and the other
     * through the chest.
     */
    private static Rotation limb(double[] pose, int joint, double side) {
        return Rotation.around(Rotation.Axis.X, -Math.toRadians(pose[of(joint, PITCH)]))
                .then(Rotation.around(Rotation.Axis.Z, side * Math.toRadians(pose[of(joint, ROLL)])))
                .then(Rotation.around(Rotation.Axis.Y, side * Math.toRadians(pose[of(joint, YAW)])));
    }

    /** How far a joint has been pulled away from where it belongs, in its parent's axes. */
    private static double[] moved(double[] pose, int joint, double side) {
        return new double[]{side * pose[of(joint, OUT)], pose[of(joint, RAISE)], pose[of(joint, AHEAD)]};
    }

    private static double[] plus(double[] one, double[] other) {
        return new double[]{one[0] + other[0], one[1] + other[1], one[2] + other[2]};
    }

    private static double[] turn(Rotation rotation, double[] vector) {
        float[] turned = rotation.apply(new float[]{(float) vector[0], (float) vector[1], (float) vector[2]});
        return new double[]{turned[0], turned[1], turned[2]};
    }
}
